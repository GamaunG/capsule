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
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.ui.DummyActivity
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.CapsuleLog
import capsule.profile.util.FileProviderProxy
import capsule.profile.util.LocalStorageManager
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
                    val installed = getInstalledApplications(pmFlags)

                    // One resolver pass replaces a per-app launch-intent query,
                    // which dominated the listing time on large app sets.
                    val launchablePackages = mutableSetOf<String>()
                    val launcherIntent =
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    for (info in packageManagerRef.queryIntentActivities(launcherIntent, 0)) {
                        launchablePackages.add(info.activityInfo.packageName)
                    }

                    val list = ArrayList<ApplicationInfoWrapper>(installed.size)
                    for (info in installed) {
                        if (info.packageName == packageName) continue
                        val wrapper = ApplicationInfoWrapper(info).loadLabel(packageManagerRef)
                        // A per-app isApplicationHidden binder call dominated the
                        // listing time on the work side. The package list already
                        // knows the answer: present in this MATCH_UNINSTALLED
                        // query but not flagged installed means hidden for this
                        // user, which is exactly what the freeze state is.
                        val hidden =
                            isProfileOwner &&
                                (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0
                        wrapper.setHidden(hidden)

                        // A hidden system app counts as "not installed": it
                        // only returns once enabled (cloned) again. Frozen
                        // user apps stay listed so they can be unfrozen.
                        if (hidden && wrapper.isSystem() && !showAll) continue

                        val isSystem = wrapper.isSystem()
                        val isInstalled = (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                        val canLaunch = info.packageName in launchablePackages
                        if (!showAll && !(!isSystem && isInstalled) && !hidden && !canLaunch) continue
                        list.add(wrapper)
                    }

                    // User apps first, then preinstalled; frozen apps last in
                    // each group; alphabetical as the final tiebreaker.
                    list.sortWith(
                        compareBy<ApplicationInfoWrapper> { if (it.isSystem()) 1 else 0 }
                            .thenBy { if (it.isHidden()) 1 else 0 }
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

            override fun hasInstallPermission(): Boolean =
                // The unknown-sources consent only exists as an app op since O.
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    packageManager.canRequestPackageInstalls()

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

            override fun installCaCert(cert: ByteArray): Boolean {
                // The system parses PEM and DER inputs; only the profile owner
                // may add CAs to the profile trust store.
                check(isProfileOwner) { "Certificate management requires profile owner access" }
                CapsuleLog.i("Installing a CA certificate into the profile trust store")
                return policyManager.installCaCert(adminComponent, cert)
            }

            override fun getInstalledCaCerts(): List<Bundle> {
                check(isProfileOwner) { "Certificate management requires profile owner access" }
                // AIDL cannot carry lists of arrays: one Bundle per certificate.
                return policyManager.getInstalledCaCerts(adminComponent).map { cert ->
                    Bundle().apply { putByteArray("cert", cert) }
                }
            }

            override fun uninstallCaCert(cert: ByteArray): Boolean {
                check(isProfileOwner) { "Certificate management requires profile owner access" }
                CapsuleLog.i("Removing a CA certificate from the profile trust store")
                policyManager.uninstallCaCert(adminComponent, cert)
                return true
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

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001
        const val RESULT_ALREADY_EXISTS = 100002
        const val RESULT_CANNOT_ENABLE_SYSTEM_APP = 100003
    }
}
