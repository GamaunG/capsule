package capsule.profile.services

import android.app.Activity
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import capsule.profile.CapsuleApplication
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.ui.DummyActivity
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.CapsuleLog
import capsule.profile.util.FileProviderProxy
import capsule.profile.util.UriForwardProxy
import capsule.profile.util.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Core service running in both the main profile and the work profile.
 * Performs app listing, cloning, freezing and profile configuration on
 * behalf of the other side over AIDL.
 */
class CapsuleService : Service() {
    private lateinit var policyManager: DevicePolicyManager
    private lateinit var packageManagerRef: PackageManager
    private lateinit var adminComponent: ComponentName
    private var isProfileOwner = false
    private var startActivityProxy: IStartActivityProxy? = null

    // All binder calls run off the main thread through one supervised scope.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder =
        object : ICapsuleService.Stub() {
            override fun ping() {
                // Let the other side know we are alive.
            }

            override fun stopCapsuleService(kill: Boolean) {
                // Delay slightly so the binder call can return before we die.
                serviceScope.launch {
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        // Stopping anyway.
                    }

                    (application as CapsuleApplication).unbindCapsuleService()

                    val keepProcess =
                        isProfileOwner &&
                            (FreezeService.hasPendingAppToFreeze() || FreezeService.isRunning())
                    if (kill && !keepProcess) {
                        // Nothing left to do: kill the whole process.
                        kotlin.system.exitProcess(0)
                    }
                }
            }

            override fun getApps(callback: IGetAppsCallback, showAll: Boolean) {
                serviceScope.launch {
                    val pmFlags =
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    val list =
                        getInstalledApplications(pmFlags)
                            .filter { it.packageName != packageName }
                            .filter {
                                val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                val isHidden = isHidden(it.packageName)
                                val isInstalled = (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                                val canLaunch = packageManagerRef.getLaunchIntentForPackage(it.packageName) != null
                                showAll || (!isSystem && isInstalled) || isHidden || canLaunch
                            }.map { ApplicationInfoWrapper(it).loadLabel(packageManagerRef).setHidden(isHidden(it.packageName)) }
                            .sortedWith(
                                compareBy<ApplicationInfoWrapper> { if (it.isHidden()) 1 else 0 }
                                    .thenBy { it.labelOrDefault.lowercase() },
                            )

                    try {
                        callback.callback(list)
                    } catch (e: android.os.RemoteException) {
                        // The caller is gone.
                    }
                }
            }

            override fun loadIcon(info: ApplicationInfoWrapper, callback: ILoadIconCallback) {
                serviceScope.launch {
                    val icon =
                        Utility.drawableToBitmap(
                            info.infoValue.loadUnbadgedIcon(packageManagerRef),
                        )
                    try {
                        callback.callback(icon)
                    } catch (e: android.os.RemoteException) {
                        // The caller is gone.
                    }
                }
            }

            override fun installApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
                serviceScope.launch {
                    when (installAppBlocking(app, callback)) {
                        InstallDecision.HANDLED -> {}
                        InstallDecision.PROCEED_INSTALLER -> {
                            proceedWithInstaller(app, callback)
                        }

                        InstallDecision.PROCEED_SYSTEM -> {
                            try {
                                policyManager.enableSystemApp(adminComponent, app.packageName)
                                policyManager.setApplicationHidden(adminComponent, app.packageName, false)
                                if (isPackageVisible(app.packageName)) {
                                    CapsuleLog.i("System app ${app.packageName} enabled in the profile")
                                    callback.callback(Activity.RESULT_OK)
                                } else {
                                    // Some vendor builds silently refuse to enable
                                    // certain system apps inside a managed profile.
                                    CapsuleLog.w("System app ${app.packageName} did not become visible after enabling")
                                    callback.callback(RESULT_CANNOT_ENABLE_SYSTEM_APP)
                                }
                            } catch (e: Exception) {
                                CapsuleLog.w("Failed to enable system app ${app.packageName}", e)
                                callback.callback(RESULT_CANNOT_ENABLE_SYSTEM_APP)
                            }
                        }
                    }
                }
            }

            private fun proceedWithInstaller(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
                // Non-system apps must go through the system PackageInstaller,
                // which only an activity can drive with a result.
                val intent = Intent(DummyActivity.INSTALL_PACKAGE)
                intent.component = ComponentName(this@CapsuleService, DummyActivity::class.java)
                intent.putExtra("package", app.packageName)
                intent.putExtra("apk", app.sourceDir)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.putExtra("split_apks", app.splitApks)
                }
                putCallback(intent, callback)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchActivityForResult(intent, callback)
            }

            override fun installApk(uri: UriForwardProxy, callback: IAppInstallCallback) {
                CapsuleLog.i("installApk: direct APK install requested")
                val intent = Intent(DummyActivity.INSTALL_PACKAGE)
                intent.component = ComponentName(this@CapsuleService, DummyActivity::class.java)
                val proxyUri: Uri = FileProviderProxy.setUriForwardProxy(uri, "apk")
                intent.putExtra("direct_install_apk", proxyUri)
                putCallback(intent, callback)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launchActivityForResult(intent, callback)
            }

            override fun uninstallApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
                serviceScope.launch {
                    CapsuleLog.i("uninstallApp: ${app.packageName} (system=${app.isSystem()})")
                    if (!app.isSystem()) {
                        val intent = Intent(DummyActivity.UNINSTALL_PACKAGE)
                        intent.component = ComponentName(this@CapsuleService, DummyActivity::class.java)
                        intent.putExtra("package", app.packageName)
                        putCallback(intent, callback)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launchActivityForResult(intent, callback)
                    } else {
                        if (isProfileOwner) {
                            // "Uninstalling" a system app equals hiding it.
                            policyManager.setApplicationHidden(adminComponent, app.packageName, true)
                            callback.callback(Activity.RESULT_OK)
                        } else {
                            callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                        }
                    }
                }
            }

            override fun freezeApp(app: ApplicationInfoWrapper) {
                check(isProfileOwner) { "Cannot freeze app without being profile owner" }
                CapsuleLog.i("freezeApp: ${app.packageName}")
                policyManager.setApplicationHidden(adminComponent, app.packageName, true)
            }

            override fun unfreezeApp(app: ApplicationInfoWrapper) {
                check(isProfileOwner) { "Cannot unfreeze app without being profile owner" }
                CapsuleLog.i("unfreezeApp: ${app.packageName}")
                policyManager.setApplicationHidden(adminComponent, app.packageName, false)
            }

            override fun hasUsageStatsPermission(): Boolean =
                Utility.checkUsageStatsPermission(this@CapsuleService)

            override fun hasSystemAlertPermission(): Boolean =
                Utility.checkSystemAlertPermission(this@CapsuleService)

            override fun hasAllFileAccessPermission(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
                return Utility.checkAllFileAccessPermission()
            }

            override fun getCrossProfileWidgetProviders(): List<String> {
                check(isProfileOwner) { "Cannot access cross-profile widget providers without being profile owner" }
                return policyManager.getCrossProfileWidgetProviders(adminComponent)
            }

            override fun hasWidgetProviders(packageName: String): Boolean {
                val intent =
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(packageName)
                return packageManagerRef.queryBroadcastReceivers(intent, 0).isNotEmpty()
            }

            @Suppress("DEPRECATION")
            override fun setCrossProfileWidgetProviderEnabled(pkgName: String, enabled: Boolean): Boolean {
                check(isProfileOwner) { "Cannot access cross-profile widget providers without being profile owner" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    // Android 17 replaced the incremental add/remove API with a set-based one.
                    val packages = HashSet(policyManager.getCrossProfileWidgetProviders(adminComponent))
                    val changed = if (enabled) packages.add(pkgName) else packages.remove(pkgName)
                    if (changed) {
                        policyManager.setCrossProfileWidgetProviders(packages)
                    }
                    return changed
                }
                return if (enabled) {
                    policyManager.addCrossProfileWidgetProvider(adminComponent, pkgName)
                } else {
                    policyManager.removeCrossProfileWidgetProvider(adminComponent, pkgName)
                }
            }

            override fun setStartActivityProxy(proxy: IStartActivityProxy) {
                startActivityProxy = proxy
            }

            override fun getCrossProfilePackages(): List<String> {
                check(isProfileOwner) { "Cannot access cross-profile packages without being profile owner" }
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Cross-profile packages support is only available on Android 11 and later"
                }
                return ArrayList(policyManager.getCrossProfilePackages(adminComponent))
            }

            override fun setCrossProfilePackages(packages: List<String>) {
                check(isProfileOwner) { "Cannot access cross-profile packages without being profile owner" }
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Cross-profile packages support is only available on Android 11 and later"
                }
                policyManager.setCrossProfilePackages(adminComponent, HashSet(packages))
            }

            override fun getManagedApplicationRestrictions(packageName: String): Bundle {
                check(isProfileOwner) { "Application restrictions require profile owner access" }

                val result = Bundle()
                val configured = policyManager.getApplicationRestrictions(adminComponent, packageName)
                result.putBundle("values", configured)

                val schema = ArrayList<Bundle>()
                val entries =
                    getSystemService(RestrictionsManager::class.java)
                        .getManifestRestrictions(packageName)
                entries?.forEach { entry ->
                    schema.add(
                        Bundle().apply {
                            putString("key", entry.key)
                            putString("title", entry.title)
                            putString("description", entry.description)
                            putInt("type", entry.type)
                            putBoolean("defaultBoolean", entry.selectedState)
                            putInt("defaultInteger", entry.intValue)
                            putString("defaultString", entry.selectedString)
                            putStringArray("defaultStrings", entry.allSelectedStrings)
                            putStringArray("choiceEntries", entry.choiceEntries)
                            putStringArray("choiceValues", entry.choiceValues)
                        },
                    )
                }
                result.putParcelableArrayList("schema", schema)
                return result
            }

            override fun setManagedApplicationRestrictions(packageName: String, restrictions: Bundle) {
                check(isProfileOwner) { "Application restrictions require profile owner access" }
                policyManager.setApplicationRestrictions(adminComponent, packageName, restrictions)
            }
        }

    override fun onCreate() {
        super.onCreate()
        policyManager = getSystemService(DevicePolicyManager::class.java)
        packageManagerRef = packageManager
        isProfileOwner = policyManager.isProfileOwnerApp(packageName)
        adminComponent = ComponentName(applicationContext, CapsuleDeviceAdminReceiver::class.java)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /** Outcome of the pre-install package presence check. */
    private enum class InstallDecision {
        HANDLED,
        PROCEED_INSTALLER,
        PROCEED_SYSTEM,
    }

    /**
     * Checks whether the package already exists in this profile before any
     * install attempt. Already-visible packages report a dedicated failure
     * instead of a silent no-op success; hidden ones are simply unhidden.
     */
    private fun installAppBlocking(app: ApplicationInfoWrapper, callback: IAppInstallCallback): InstallDecision {
        CapsuleLog.i("installApp: ${app.packageName} (system=${app.isSystem()})")
        if (!isProfileOwner) {
            // Without profile owner control only regular package installs work.
            return if (app.isSystem()) {
                callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                InstallDecision.HANDLED
            } else {
                InstallDecision.PROCEED_INSTALLER
            }
        }

        val visible = isPackageVisible(app.packageName)
        val presentButHidden = !visible && isPackagePresent(app.packageName)
        return when {
            visible -> {
                // Nothing would change: report instead of faking a success.
                CapsuleLog.w("Package ${app.packageName} is already installed in this profile")
                callback.callback(RESULT_ALREADY_EXISTS)
                InstallDecision.HANDLED
            }

            presentButHidden && app.isSystem() -> {
                InstallDecision.PROCEED_SYSTEM
            }

            presentButHidden -> {
                // A frozen app from a previous clone: make it available again.
                policyManager.setApplicationHidden(adminComponent, app.packageName, false)
                CapsuleLog.i("Unhid the existing package ${app.packageName}")
                callback.callback(Activity.RESULT_OK)
                InstallDecision.HANDLED
            }

            app.isSystem() -> {
                InstallDecision.PROCEED_SYSTEM
            }

            else -> {
                InstallDecision.PROCEED_INSTALLER
            }
        }
    }

    /** True when the package resolves for a normal (no uninstalled flag) query. */
    private fun isPackageVisible(packageName: String): Boolean =
        try {
            getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** True when the package exists but is hidden from this profile. */
    private fun isPackagePresent(packageName: String): Boolean =
        try {
            getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    @Suppress("DEPRECATION")
    private fun getPackageInfo(packageName: String, flags: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManagerRef.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManagerRef.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplications(flags: Int): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManagerRef.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            packageManagerRef.getInstalledApplications(flags)
        }

    private fun putCallback(intent: Intent, callback: IAppInstallCallback) {
        val callbackExtra = Bundle()
        callbackExtra.putBinder("callback", callback.asBinder())
        intent.putExtra("callback", callbackExtra)
    }

    private fun launchActivityForResult(intent: Intent, callback: IAppInstallCallback) {
        val proxy = startActivityProxy
        if (proxy == null) {
            CapsuleLog.w("No start activity proxy registered; reporting cancellation")
            FileProviderProxy.clearForwardProxy()
            reportCancelled(callback)
            return
        }
        try {
            DummyActivity.registerSameProcessRequest(intent)
            proxy.startActivity(intent)
        } catch (e: Exception) {
            // The proxy may be gone or the cross-profile resolution may have
            // failed. Report a cancellation instead of crashing the caller.
            CapsuleLog.w("Failed to start activity through proxy", e)
            FileProviderProxy.clearForwardProxy()
            reportCancelled(callback)
        }
    }

    private fun reportCancelled(callback: IAppInstallCallback) {
        try {
            callback.callback(Activity.RESULT_CANCELED)
        } catch (e: android.os.RemoteException) {
            // The caller has already disconnected.
        }
    }

    override fun onBind(intent: Intent): IBinder {
        if (intent.getBooleanExtra("foreground", false)) {
            setForeground()
        }
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // Drop the foreground notification once all clients disconnect.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        return false
    }

    private fun isHidden(packageName: String): Boolean =
        isProfileOwner && policyManager.isApplicationHidden(adminComponent, packageName)

    private fun setForeground() {
        startForeground(
            NOTIFICATION_ID,
            Utility.buildNotification(
                this,
                important = false,
                ticker = getString(R.string.app_name),
                title = getString(R.string.service_title),
                desc = getString(R.string.service_desc),
                icon = R.drawable.ic_notification,
            ),
        )
    }

    companion object {
        const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001
        const val RESULT_ALREADY_EXISTS = 100002
        const val RESULT_CANNOT_ENABLE_SYSTEM_APP = 100003
        private const val NOTIFICATION_ID = 0xC9A11
    }
}
