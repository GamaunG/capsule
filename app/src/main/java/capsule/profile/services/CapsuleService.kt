package capsule.profile.services

import android.app.Activity
import android.app.Service
import android.app.admin.DevicePolicyManager
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
import capsule.profile.util.FileProviderProxy
import capsule.profile.util.UriForwardProxy
import capsule.profile.util.Utility

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

    private val binder =
        object : ICapsuleService.Stub() {
            override fun ping() {
                // Let the other side know we are alive.
            }

            override fun stopCapsuleService(kill: Boolean) {
                // Delay slightly so the binder call can return before we die.
                Thread {
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        // Stopping anyway.
                    }

                    (application as CapsuleApplication).unbindCapsuleService()

                    if (kill && !(isProfileOwner && FreezeService.hasPendingAppToFreeze())) {
                        // Nothing left to do: kill the whole process.
                        kotlin.system.exitProcess(0)
                    }
                }.start()
            }

            override fun getApps(callback: IGetAppsCallback, showAll: Boolean) {
                Thread {
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
                }.start()
            }

            override fun loadIcon(info: ApplicationInfoWrapper, callback: ILoadIconCallback) {
                Thread {
                    val icon =
                        Utility.drawableToBitmap(
                            info.infoValue.loadUnbadgedIcon(packageManagerRef),
                        )
                    try {
                        callback.callback(icon)
                    } catch (e: android.os.RemoteException) {
                        // The caller is gone.
                    }
                }.start()
            }

            override fun installApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
                if (!app.isSystem()) {
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
                } else {
                    if (isProfileOwner) {
                        // System apps can only be enabled in our own profile.
                        policyManager.enableSystemApp(adminComponent, app.packageName)
                        policyManager.setApplicationHidden(adminComponent, app.packageName, false)
                        callback.callback(Activity.RESULT_OK)
                    } else {
                        callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                    }
                }
            }

            override fun installApk(uri: UriForwardProxy, callback: IAppInstallCallback) {
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

            override fun freezeApp(app: ApplicationInfoWrapper) {
                check(isProfileOwner) { "Cannot freeze app without being profile owner" }
                policyManager.setApplicationHidden(adminComponent, app.packageName, true)
            }

            override fun unfreezeApp(app: ApplicationInfoWrapper) {
                check(isProfileOwner) { "Cannot unfreeze app without being profile owner" }
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
            android.util.Log.w("CapsuleService", "Failed to start activity through proxy", e)
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
        private const val NOTIFICATION_ID = 0xC9A11
    }
}
