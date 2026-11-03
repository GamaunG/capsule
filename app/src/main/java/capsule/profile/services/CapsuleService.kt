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
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import capsule.profile.CapsuleApplication
import capsule.profile.R
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

    // The work-profile bridge binder is handed over as a raw proxy, which the
    // system cannot see: without a foreground service the bridge process sits
    // in the cached pool, the cached-app freezer freezes it after a few
    // seconds and every synchronous call then kills it. The keep-alive below
    // runs as a foreground service for exactly as long as the client keeps
    // talking to the bridge, and releases itself once the traffic stops.
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private var bridgeLeaseCheck: Runnable? = null

    /** Written from binder threads as well; read on the main thread. */
    @Volatile
    private var bridgeForeground = false

    /** Written from binder threads; read on the main thread only. */
    @Volatile
    private var lastBridgeTraffic = 0L

    /** True while a re-arm post is still pending; coalesces binder bursts. */
    @Volatile
    private var bridgeRearmPosted = false

    /** Renews the bridge session lease; safe from any thread. */
    private fun touchBridgeLease() {
        lastBridgeTraffic = SystemClock.elapsedRealtime()
    }

    /**
     * Re-arms the keep-alive after binder traffic resumed. The posted flag
     * keeps a burst of binder calls from queueing one post each; every
     * failure (including the platform refusing a foreground promotion from a
     * cached process) degrades to the old reconnect-on-death behavior.
     */
    private fun rearmBridgeKeepAlive() {
        if (bridgeForeground || bridgeRearmPosted) return
        bridgeRearmPosted = true
        bridgeHandler.post {
            bridgeRearmPosted = false
            startBridgeForeground()
        }
    }

    private val binder =
        object : ICapsuleService.Stub() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Any incoming traffic renews the bridge session lease.
                touchBridgeLease()
                // Traffic after the lease expired means the client came back:
                // the process is cached by now and the cached-app freezer will
                // kill it on the next synchronous call unless the keep-alive
                // is re-armed first (observed in the V14 system log: a
                // DeadObjectException ~10 s after "lease expired").
                if (isProfileOwner) {
                    rearmBridgeKeepAlive()
                }
                return super.onTransact(code, data, reply, flags)
            }

            override fun probeAlive(callback: IAliveCallback) {
                // The request itself is the answer: reaching this code proves
                // the process is running, and the reply is oneway too.
                try {
                    callback.alive()
                } catch (e: android.os.RemoteException) {
                    // The caller is gone.
                }
            }

            override fun stopCapsuleService(kill: Boolean) {
                serviceScope.launch {
                    try {
                        // Delay slightly so the binder call can return before we die.
                        kotlinx.coroutines.delay(1)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Stopping anyway.
                    }

                    stopBridgeForeground()
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
                        // The frozen state must come from the profile owner
                        // API. A hidden app still reports FLAG_INSTALLED on
                        // current Android (hiding and installed became two
                        // separate per-user states), so deriving it from that
                        // flag made every frozen app look unfrozen in the
                        // list. The per-app binder call is the only public
                        // source that reflects the hide state.
                        val hidden =
                            isProfileOwner &&
                                try {
                                    policyManager.isApplicationHidden(adminComponent, info.packageName)
                                } catch (e: Exception) {
                                    // Not an owner here (or the admin is gone):
                                    // this side cannot freeze apps anyway.
                                    false
                                }
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
                                    armAutoFreeze()
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
                intent.putExtra("split_apks", app.splitApks)
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
                // An unfrozen app must be watched again: the freeze service
                // stops itself once nothing is left to freeze, so unfreezing
                // from the UI has to re-arm it or the next screen lock would
                // silently skip this app.
                armAutoFreeze()
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
                // A frozen (hidden) app reads as uninstalled for the manifest
                // query, which would throw "No such package" across the
                // binder (seen in the V14 system log). The editor then simply
                // sees no schema; the configured values are still returned.
                val entries =
                    try {
                        getSystemService(RestrictionsManager::class.java)
                            .getManifestRestrictions(packageName)
                    } catch (e: Exception) {
                        CapsuleLog.w("Could not read the restrictions schema of $packageName", e)
                        null
                    }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BRIDGE_SESSION, false) == true && isProfileOwner) {
            startBridgeForeground()
        }
        // The bridge is driven by its binding and binder traffic, so there is
        // nothing worth restoring after a process death.
        return START_NOT_STICKY
    }

    /** Arms the session-scoped foreground keep-alive for the work bridge. */
    private fun startBridgeForeground() {
        touchBridgeLease()
        if (bridgeForeground) return
        val notification =
            Utility.buildNotification(
                this,
                ticker = getString(R.string.service_bridge_title),
                title = getString(R.string.service_bridge_title),
                desc = getString(R.string.service_bridge_desc),
                icon = R.drawable.ic_notification,
            )
        try {
            // An explicit type keeps system_server from logging a typeless
            // foreground-service complaint on API 34+.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    BRIDGE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(BRIDGE_NOTIFICATION_ID, notification)
            }
            bridgeForeground = true
            CapsuleLog.i("The bridge session keep-alive started")
            scheduleBridgeLeaseCheck()
        } catch (e: Exception) {
            // Without the keep-alive the bridge still works, but the freezer
            // may recycle it; the main side recovers as before.
            CapsuleLog.w("Could not start the bridge session keep-alive", e)
        }
    }

    private fun scheduleBridgeLeaseCheck() {
        bridgeLeaseCheck?.let { bridgeHandler.removeCallbacks(it) }
        val check =
            Runnable {
                bridgeLeaseCheck = null
                if (!bridgeForeground) return@Runnable
                if (SystemClock.elapsedRealtime() - lastBridgeTraffic > BRIDGE_LEASE_MS) {
                    CapsuleLog.i("The bridge session lease expired; releasing the keep-alive")
                    stopBridgeForeground()
                } else {
                    scheduleBridgeLeaseCheck()
                }
            }
        bridgeLeaseCheck = check
        bridgeHandler.postDelayed(check, BRIDGE_LEASE_CHECK_MS)
    }

    /** Drops the keep-alive; the process returns to the cached pool. */
    private fun stopBridgeForeground() {
        bridgeLeaseCheck?.let { bridgeHandler.removeCallbacks(it) }
        bridgeLeaseCheck = null
        if (!bridgeForeground) return
        bridgeForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Drop the started half only; the binding keeps the service object.
        stopSelf()
        CapsuleLog.i("The bridge session keep-alive stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        bridgeLeaseCheck?.let { bridgeHandler.removeCallbacks(it) }
        bridgeLeaseCheck = null
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
                armAutoFreeze()
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

    /** True when the package resolves for a normal (no uninstalled flag)
     * query and is not frozen. A hidden package also resolves on current
     * Android, so the owner side must consult the hide state separately;
     * otherwise cloning a frozen app would report "already installed"
     * instead of unfreezing it. */
    private fun isPackageVisible(packageName: String): Boolean {
        if (isProfileOwner) {
            try {
                if (policyManager.isApplicationHidden(adminComponent, packageName)) return false
            } catch (e: Exception) {
                // Fall through to the plain resolution check.
            }
        }
        return try {
            getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** True when the package exists but is hidden from this profile. */
    private fun isPackagePresent(packageName: String): Boolean =
        try {
            getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Re-arms the auto-freeze watcher after this side unfroze an app. */
    private fun armAutoFreeze() {
        try {
            FreezeService.armIfNeeded(this)
        } catch (e: Exception) {
            // Arming again is opportunistic; the next bridge bind retries it.
            CapsuleLog.w("Could not re-arm the auto-freeze service", e)
        }
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

        /** Marks the service start that arms the bridge keep-alive. */
        const val EXTRA_BRIDGE_SESSION = "bridge_session"

        /** How long the bridge survives without any binder traffic. */
        private const val BRIDGE_LEASE_MS = 30_000L

        /** Granularity of the lease expiry check. */
        private const val BRIDGE_LEASE_CHECK_MS = 5_000L

        /** Distinct from the auto-freeze notification on purpose. */
        private const val BRIDGE_NOTIFICATION_ID = 0xE49C1
    }
}
