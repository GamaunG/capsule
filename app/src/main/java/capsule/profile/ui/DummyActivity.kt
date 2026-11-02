package capsule.profile.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import capsule.profile.CapsuleApplication
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.services.CapsuleService
import capsule.profile.services.FreezeService
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.IFileShuttleService
import capsule.profile.services.IFileShuttleServiceCallback
import capsule.profile.ui.components.InstallationProgressDialog
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.AuthenticationUtility
import capsule.profile.util.CapsuleLog
import capsule.profile.util.FileProviderProxy
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Headless wrapper for operations that must cross the profile boundary: only
 * activity intents can traverse crossProfileIntentFilters, so every
 * cross-profile action is dispatched through here.
 */
class DummyActivity : ComponentActivity() {
    private var isProfileOwner = false
    private lateinit var policyManager: DevicePolicyManager

    // Install progress state for the Compose dialog (null = not installing).
    private var installProgress by mutableFloatStateOf(Float.NaN)

    private val packageActivityLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> appInstallFinished(result.resultCode) }

    private val legacyStoragePermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) doStartFileShuttle() else finish() }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            // Requested on demand when the Auto Freeze service is enabled;
            // the grant state does not affect anything else in this activity.
            finish()
        }

    // Set while the unknown-sources settings page is up, so returning from it
    // without the permission does not loop back into the same request.
    private var awaitingInstallPermission = false

    private val installPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            awaitingInstallPermission = false
            // The user is back in the app now, so an install prompt can only
            // ever appear on top of Capsule, never on top of Settings.
            if (packageManager.canRequestPackageInstalls()) {
                actionInstallPackage()
            } else {
                CapsuleLog.w("Install-unknown-sources was not granted; aborting the install")
                appInstallFinished(Activity.RESULT_CANCELED)
            }
        }

    // Opens a permission screen for THIS profile's instance and reports back.
    // Used from the main profile settings screen so the work-profile instance
    // can be granted special-access permissions without the user digging for
    // the work-profile settings manually.
    private val profileSettingsLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            setResult(Activity.RESULT_OK)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CapsuleTheme {
                if (!installProgress.isNaN()) {
                    InstallationProgressDialog(progress = installProgress)
                }
            }
        }

        policyManager = getSystemService(DevicePolicyManager::class.java)
        isProfileOwner = policyManager.isProfileOwnerApp(packageName)
        if (isProfileOwner) {
            // Profile policies persist in the system; re-asserting them on
            // every bridge contact costs a burst of DPM writes and settings
            // broadcasts. Once per process is enough — real preference
            // changes re-enforce explicitly.
            if (!profilePoliciesApplied) {
                profilePoliciesApplied = true
                Utility.enforceWorkProfilePolicies(this)
                Utility.enforceUserRestrictions(this)
                SettingsManager.applyAll()
            }
        }

        init()
    }

    private fun init() {
        val intent =
            intent ?: run {
                finish()
                return
            }

        if (!checkSameProcessRequest(intent)) {
            // checkIntent() must run first: it accepts the bootstrap auth key.
            if (!AuthenticationUtility.checkIntent(intent)) {
                if (intent.action !in ACTIONS_ALLOWED_WITHOUT_SIGNATURE) {
                    // Unauthenticated: bail out immediately.
                    CapsuleLog.w("Rejecting unauthenticated action ${intent.action}")
                    finish()
                    return
                }
            }
        }

        when (intent.action) {
            START_SERVICE -> {
                actionStartService()
            }
            TRY_START_SERVICE -> {
                // Used to probe whether the work profile is enabled.
                setResult(Activity.RESULT_OK)
                finish()
            }
            INSTALL_PACKAGE -> {
                actionInstallPackage()
            }
            UNINSTALL_PACKAGE -> {
                actionUninstallPackage()
            }
            FINALIZE_PROVISION -> {
                actionFinalizeProvision()
            }
            UNFREEZE_AND_LAUNCH, PUBLIC_UNFREEZE_AND_LAUNCH -> {
                actionUnfreezeAndLaunch()
            }
            PUBLIC_FREEZE_ALL -> {
                actionPublicFreezeAll()
            }
            FREEZE_ALL_IN_LIST -> {
                actionFreezeAllInList()
            }
            START_FILE_SHUTTLE, START_FILE_SHUTTLE_2 -> {
                actionStartFileShuttle()
            }
            OPEN_SETTINGS_IN_PROFILE -> {
                actionOpenSettingsInProfile()
            }
            OPEN_APP_DETAILS_IN_PROFILE -> {
                actionOpenAppDetailsInProfile()
            }
            REQUEST_NOTIFICATION_PERMISSION -> {
                actionRequestNotificationPermission()
            }
            SYNCHRONIZE_PREFERENCE -> {
                actionSynchronizePreference()
            }
            else -> {
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The original intent still carries the result callback binder, so it
        // must stay intact; the delivery intent is only inspected here.

        if (PACKAGEINSTALLER_CALLBACK == intent.action) {
            when (
                intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
            ) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    CapsuleLog.i("PackageInstaller: user action required")
                    val pendingUserAction =
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (pendingUserAction != null) {
                        startActivity(pendingUserAction)
                    } else {
                        CapsuleLog.w("PackageInstaller: pending user action without an intent")
                        appInstallFinished(Activity.RESULT_CANCELED)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    CapsuleLog.i("PackageInstaller: session finished successfully")
                    appInstallFinished(Activity.RESULT_OK)
                }
                else -> {
                    val message =
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"
                    CapsuleLog.w("PackageInstaller: session failed: $message")
                    appInstallFinished(Activity.RESULT_CANCELED)
                }
            }
        }
    }

    private fun actionFinalizeProvision() {
        if (isProfileOwner) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // Inform the main profile about the finished provisioning.
                val intent = Intent(FINALIZE_PROVISION)
                Utility.transferIntentToProfileUnsigned(this, intent)
                startActivity(intent)
            }
            finish()

            // Component state changes can kill the process completing
            // provisioning; delay hiding the launcher entry until Android
            // has returned control from the provisioning flow.
            val appContext = applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { Utility.hideMainActivityInManagedProfile(appContext) },
                5000,
            )
        } else {
            // Tell the main profile the setup is now complete.
            LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            val intent = Intent(this, SetupWizardActivity::class.java)
            intent.action = SetupWizardActivity.ACTION_PROFILE_PROVISIONED
            startActivity(intent)
            Toast.makeText(this, R.string.provision_finished, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun actionStartService() {
        // A snapshot of the synced preferences travels with every bind so the
        // two sides converge even when a single change event was missed.
        if (isProfileOwner) {
            @Suppress("DEPRECATION")
            val bundle = intent.getBundleExtra("settings_sync")
            if (bundle != null && SettingsManager.applySyncBundle(bundle)) {
                // Only meaningful when the snapshot actually diverged.
                Utility.enforceWorkProfilePolicies(this)
            }
            // Keep the auto-freeze listener alive while there is work for it.
            FreezeService.armIfNeeded(this)
            // Arm the session keep-alive before handing the binder over: a
            // raw cross-profile proxy keeps the bridge process in the cached
            // pool, where the freezer would recycle it within seconds.
            val bridgeIntent = Intent(this, CapsuleService::class.java)
            bridgeIntent.putExtra(CapsuleService.EXTRA_BRIDGE_SESSION, true)
            try {
                ContextCompat.startForegroundService(this, bridgeIntent)
            } catch (e: Exception) {
                // The bridge still works without the keep-alive; the freezer
                // churn is recovered by the reconnect logic.
                CapsuleLog.w("Could not arm the bridge keep-alive", e)
            }
        }

        // Foreground bind: this activity cannot hold the connection itself,
        // so the connection is kept by the shared application object; the
        // session keep-alive armed above keeps the process out of the cached
        // pool, and the main side's death recipient rebinds if the process
        // is reclaimed anyway.
        (application as CapsuleApplication).bindCapsuleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val data = Intent()
                    val bundle = Bundle()
                    bundle.putBinder("service", service)
                    data.putExtra("extra", bundle)
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Nothing to do.
                }
            },
            false,
        )
    }

    @Suppress("DEPRECATION")
    private fun actionInstallPackage() {
        CapsuleLog.i(
            "Installing package: package=${intent.getStringExtra("package")} " +
                "apk=${intent.getStringExtra("apk")} direct=${intent.hasExtra("direct_install_apk")}",
        )

        // The unknown-sources consent must exist before the installer session
        // commits, so its prompt never pops up over the settings screen: ask
        // first, and drive the install only after the user returns to Capsule.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            if (awaitingInstallPermission) {
                appInstallFinished(Activity.RESULT_CANCELED)
                return
            }
            awaitingInstallPermission = true
            CapsuleLog.i("Requesting the install-unknown-sources consent before installing")
            val settings =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null),
                )
            installPermissionLauncher.launch(settings)
            return
        }

        startInstallSession()
    }

    /** Drives the installer session from the picked APK or a package source. */
    @Suppress("DEPRECATION")
    private fun startInstallSession() {
        var uri: Uri? = null
        if (intent.hasExtra("package")) {
            uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        }

        val policy = StrictMode.getVmPolicy()
        if (intent.hasExtra("direct_install_apk")) {
            // Install an APK streamed from the other profile through our
            // FileProviderProxy. The unknown-sources consent was granted
            // before the file picker was even opened, so no app op changes
            // mid-install and the forwarded Fd survives the whole session.
            uri = IntentCompat.getParcelableExtra(intent, "direct_install_apk", Uri::class.java)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || intent.hasExtra("apk")) {
            if (intent.hasExtra("apk")) {
                // The "package:" scheme stopped working on Oreo; use the apk path.
                intent.getStringExtra("apk")?.let { path ->
                    uri = Uri.fromFile(File(path))
                }
            }

            // Permissive VmPolicy to work around cross-application Uri checks.
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Q+ ACTION_INSTALL_PACKAGE is deprecated; use PackageInstaller
            // and stream split APKs ("Configuration APKs") alongside the base.
            actionInstallPackageQ(uri, intent.getStringArrayExtra("split_apks"))
        } else {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
            installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            packageActivityLauncher.launch(installIntent)
        }

        StrictMode.setVmPolicy(policy)
    }

    private fun actionInstallPackageQ(uri: Uri?, splitApks: Array<String>?) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)

        val thisSessionId = sessionId
        installProgress = 0f
        pi.registerSessionCallback(
            object : PackageInstaller.SessionCallback() {
                override fun onCreated(sessionId: Int) {}

                override fun onBadgingChanged(sessionId: Int) {}

                override fun onActiveChanged(sessionId: Int, active: Boolean) {}

                override fun onProgressChanged(sessionId: Int, progress: Float) {
                    if (sessionId == thisSessionId) {
                        installProgress = progress
                    }
                }

                override fun onFinished(sessionId: Int, success: Boolean) {
                    if (sessionId == thisSessionId) {
                        installProgress = Float.NaN
                        pi.unregisterSessionCallback(this)
                    }
                }
            },
        )

        val session = pi.openSession(sessionId)
        doInstallPackageQ(uri, splitApks, session) {
            session.setStagingProgress(0.1f)
            val intent = Intent(this, DummyActivity::class.java)
            intent.action = PACKAGEINSTALLER_CALLBACK
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE,
                )
            session.commit(pendingIntent.intentSender)
        }
    }

    // The heavy I/O runs on a worker thread to keep the main thread free.
    private fun doInstallPackageQ(
        baseUri: Uri?,
        splitApks: Array<String>?,
        session: PackageInstaller.Session,
        callback: Runnable,
    ) {
        val uris = ArrayList<Uri>()
        baseUri?.let { uris.add(it) }
        splitApks?.forEach { apk -> uris.add(Uri.fromFile(File(apk))) }

        Thread {
            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri).use { inputStream ->
                        session
                            .openWrite(
                                UUID.randomUUID().toString(),
                                0,
                                inputStream!!.available().toLong(),
                            ).use { os: OutputStream ->
                                pipe(inputStream, os)
                                session.fsync(os)
                            }
                    }
                } catch (e: java.io.IOException) {
                    // Continue with the remaining streams.
                }
            }

            runOnUiThread(callback)
        }.start()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536)
        var n = input.read(buffer)
        while (n > -1) {
            output.write(buffer, 0, n)
            n = input.read(buffer)
        }
    }

    @Suppress("DEPRECATION")
    private fun actionUninstallPackage() {
        val pkg =
            intent.getStringExtra("package") ?: run {
                CapsuleLog.w("Uninstall requested without a package name")
                finish()
                return
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CapsuleLog.i("Uninstalling $pkg through PackageInstaller")
            val pi = packageManager.packageInstaller
            val callbackIntent = Intent(this, DummyActivity::class.java)
            callbackIntent.action = PACKAGEINSTALLER_CALLBACK
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    callbackIntent,
                    PendingIntent.FLAG_MUTABLE,
                )
            pi.uninstall(pkg, pendingIntent.intentSender)
            return
        }

        val uri = Uri.fromParts("package", pkg, null)
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri)
        uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        packageActivityLauncher.launch(uninstallIntent)
    }

    private fun appInstallFinished(resultCode: Int) {
        // Always drop any forwarded Fd we may have been streaming from.
        FileProviderProxy.clearForwardProxy()
        installProgress = Float.NaN
        CapsuleLog.i("Package operation finished with result $resultCode")

        if (!intent.hasExtra("callback")) {
            finish()
            return
        }

        val callbackExtra = intent.getBundleExtra("callback")
        val callback =
            IAppInstallCallback.Stub.asInterface(
                callbackExtra?.getBinder("callback"),
            )
        try {
            callback.callback(resultCode)
        } catch (e: android.os.RemoteException) {
            // The caller is gone.
        }

        finish()
    }

    private fun actionUnfreezeAndLaunch() {
        if (!isProfileOwner) {
            // Forward to the work profile with auto-freeze decisions attached.
            val srcIntent = intent
            val packageName =
                srcIntent.getStringExtra("packageName") ?: run {
                    CapsuleLog.w("Unfreeze and launch requested without a package name")
                    finish()
                    return
                }
            CapsuleLog.i("Forwarding unfreeze and launch for $packageName to the work profile")
            val intent = Intent(UNFREEZE_AND_LAUNCH)
            Utility.transferIntentToProfile(this, intent)
            intent.putExtra("packageName", packageName)
            // Shortcut launches freeze on lock by default, independent of the
            // per-app Auto Freeze list (which covers normal launches only);
            // the exemption list is the per-app opt-out.
            intent.putExtra(
                "shouldFreeze",
                shouldFreezeAfterShortcut(packageName),
            )
            if (srcIntent.hasExtra("linkedPackages")) {
                val packages = srcIntent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
                val packagesShouldFreeze = BooleanArray(packages.size)
                for (i in packages.indices) {
                    packagesShouldFreeze[i] = shouldFreezeAfterShortcut(packages[i])
                }
                intent.putExtra("linkedPackages", packages)
                intent.putExtra("linkedPackagesShouldFreeze", packagesShouldFreeze)
            }
            startActivity(intent)
            finish()
            return
        }

        // Unfreeze linked apps first, each with its own auto-freeze flag.
        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringArrayExtra("linkedPackages") ?: emptyArray()
            val packagesShouldFreeze =
                intent.getBooleanArrayExtra("linkedPackagesShouldFreeze") ?: BooleanArray(0)
            for (i in packages.indices) {
                policyManager.setApplicationHidden(
                    ComponentName(this, CapsuleDeviceAdminReceiver::class.java),
                    packages[i],
                    false,
                )
                if (i < packagesShouldFreeze.size && packagesShouldFreeze[i]) {
                    registerAppToFreeze(packages[i])
                }
            }
        }

        val packageName =
            intent.getStringExtra("packageName") ?: run {
                finish()
                return
            }

        CapsuleLog.i("Unfreezing and launching $packageName inside the profile")
        policyManager.setApplicationHidden(
            ComponentName(this, CapsuleDeviceAdminReceiver::class.java),
            packageName,
            false,
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            if (intent.getBooleanExtra("shouldFreeze", false)) {
                registerAppToFreeze(packageName)
            }
            startActivity(launchIntent)
        } else {
            CapsuleLog.w("No launch intent found for $packageName")
            Toast.makeText(this, getString(R.string.launch_app_fail, packageName), Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun registerAppToFreeze(packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        startService(Intent(this, FreezeService::class.java))
    }

    /** Freezes on lock after a shortcut launch unless exempted per app. */
    private fun shouldFreezeAfterShortcut(packageName: String): Boolean =
        SettingsManager.getAutoFreezeServiceEnabled() &&
            !LocalStorageManager.stringListContains(
                LocalStorageManager.PREF_SHORTCUT_FREEZE_EXEMPT,
                packageName,
            )

    private fun actionPublicFreezeAll() {
        if (!isProfileOwner) {
            // Load the full auto-freeze list and forward the batch request.
            // Launcher shortcuts run outside the app UI and keep the toast;
            // requests made from inside the app report back through its
            // snackbar instead, so the work side stays silent for them.
            val fromApp = intent.getBooleanExtra("from_app", false)
            val intent = Intent(FREEZE_ALL_IN_LIST)
            Utility.transferIntentToProfile(this, intent)
            val list =
                LocalStorageManager
                    .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
            intent.putExtra("list", list.toTypedArray())
            intent.putExtra("notify", !fromApp)
            startActivity(intent)
            finish()
        } else {
            // Only meaningful from the main profile.
            finish()
        }
    }

    private fun actionFreezeAllInList() {
        if (isProfileOwner) {
            val list = intent.getStringArrayExtra("list") ?: emptyArray()
            for (pkg in list) {
                policyManager.setApplicationHidden(
                    ComponentName(this, CapsuleDeviceAdminReceiver::class.java),
                    pkg,
                    true,
                )
            }
            stopService(Intent(this, FreezeService::class.java))
            if (intent.getBooleanExtra("notify", true)) {
                Toast.makeText(this, R.string.freeze_all_success, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun actionStartFileShuttle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                doStartFileShuttle()
            } else {
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            // All-files access and overlay must have been granted when the
            // user enabled File Shuttle.
            if (Utility.checkAllFileAccessPermission() && Utility.checkSystemAlertPermission(this)) {
                doStartFileShuttle()
            } else {
                finish()
            }
        }
    }

    private fun doStartFileShuttle() {
        (application as CapsuleApplication).bindFileShuttleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val shuttle = IFileShuttleService.Stub.asInterface(service)
                    val callback =
                        IFileShuttleServiceCallback.Stub.asInterface(
                            intent.getBundleExtra("extra")?.getBinder("callback"),
                        )
                    try {
                        callback.callback(shuttle)
                    } catch (e: android.os.RemoteException) {
                        // The requester is gone.
                    }
                    finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Nothing to do.
                }
            },
        )
    }

    /**
     * Opens a system settings screen for this profile's own instance. Called
     * from the other profile's settings UI for special-access permissions
     * that must be granted separately per profile.
     */
    private fun actionOpenSettingsInProfile() {
        val action =
            intent.getStringExtra("settings_action") ?: run {
                finish()
                return
            }
        val settings = Intent(action, Uri.fromParts("package", packageName, null))
        try {
            profileSettingsLauncher.launch(settings)
        } catch (e: android.content.ActivityNotFoundException) {
            // The screen does not exist on this ROM: nothing to grant.
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    /**
     * Opens the system App Info screen of one app in this profile. Called
     * from the other profile's app action sheet, whose header doubles as a
     * shortcut into the system settings.
     */
    private fun actionOpenAppDetailsInProfile() {
        val packageName =
            intent.getStringExtra("package") ?: run {
                finish()
                return
            }
        val details =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        try {
            startActivity(details)
        } catch (e: android.content.ActivityNotFoundException) {
            CapsuleLog.w("The app details screen could not be opened", e)
        }
        finish()
    }

    /**
     * Asks for the notification permission in this profile, so the Auto
     * Freeze service's status notification can be shown. Requested when the
     * user enables that service in the other profile's settings.
     */
    private fun actionRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finish()
        }
    }

    private fun actionSynchronizePreference() {
        val name =
            intent.getStringExtra("name") ?: run {
                finish()
                return
            }
        when {
            intent.hasExtra("boolean") -> {
                LocalStorageManager.setBoolean(name, intent.getBooleanExtra("boolean", false))
            }

            intent.hasExtra("int") -> {
                LocalStorageManager.setInt(name, intent.getIntExtra("int", Int.MIN_VALUE))
            }

            intent.hasExtra("list") -> {
                LocalStorageManager.setStringList(name, intent.getStringArrayExtra("list")?.toList() ?: emptyList())
            }
        }
        SettingsManager.applyAll()
        if (isProfileOwner) {
            // Settings may have changed: refresh the profile policies.
            Utility.enforceWorkProfilePolicies(this)
            if (name == LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE ||
                name == LocalStorageManager.PREF_AUTO_FREEZE_SERVICE
            ) {
                FreezeService.armIfNeeded(this)
            }
        }
        finish()
    }

    companion object {
        const val FINALIZE_PROVISION = "capsule.profile.action.FINALIZE_PROVISION"
        const val START_SERVICE = "capsule.profile.action.START_SERVICE"
        const val TRY_START_SERVICE = "capsule.profile.action.TRY_START_SERVICE"
        const val INSTALL_PACKAGE = "capsule.profile.action.INSTALL_PACKAGE"
        const val UNINSTALL_PACKAGE = "capsule.profile.action.UNINSTALL_PACKAGE"
        const val UNFREEZE_AND_LAUNCH = "capsule.profile.action.UNFREEZE_AND_LAUNCH"
        const val PUBLIC_UNFREEZE_AND_LAUNCH = "capsule.profile.action.PUBLIC_UNFREEZE_AND_LAUNCH"
        const val PUBLIC_FREEZE_ALL = "capsule.profile.action.PUBLIC_FREEZE_ALL"
        const val FREEZE_ALL_IN_LIST = "capsule.profile.action.FREEZE_ALL_IN_LIST"

        // Two actions for one purpose: reusing a single action for both
        // directions makes the system show a one-entry action chooser.
        const val START_FILE_SHUTTLE = "capsule.profile.action.START_FILE_SHUTTLE"
        const val START_FILE_SHUTTLE_2 = "capsule.profile.action.START_FILE_SHUTTLE_2"
        const val SYNCHRONIZE_PREFERENCE = "capsule.profile.action.SYNCHRONIZE_PREFERENCE"
        const val PACKAGEINSTALLER_CALLBACK = "capsule.profile.action.PACKAGEINSTALLER_CALLBACK"

        // Opens a settings screen for this profile's own instance; the action
        // to open travels in the "settings_action" string extra.
        const val OPEN_SETTINGS_IN_PROFILE = "capsule.profile.action.OPEN_SETTINGS_IN_PROFILE"

        // Opens the system App Info screen of the app named in the "package"
        // extra, in this profile.
        const val OPEN_APP_DETAILS_IN_PROFILE = "capsule.profile.action.OPEN_APP_DETAILS_IN_PROFILE"

        // Requests the notification permission in this profile; used when the
        // Auto Freeze service is enabled from the other profile's settings.
        const val REQUEST_NOTIFICATION_PERMISSION = "capsule.profile.action.REQUEST_NOTIFICATION_PERMISSION"

        // Callable from the other side without a signature (user-visible).
        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE =
            listOf(
                FINALIZE_PROVISION,
                PUBLIC_FREEZE_ALL,
                PUBLIC_UNFREEZE_AND_LAUNCH,
            )

        // Callable signature-less only from this process, pre-registered.
        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS =
            listOf(
                INSTALL_PACKAGE,
                UNINSTALL_PACKAGE,
                UNFREEZE_AND_LAUNCH,
            )

        // Set once the profile-side policies have been asserted in this
        // process; DPM state persists, so re-asserting per bridge contact
        // only burns binder calls and settings broadcasts.
        @Volatile
        private var profilePoliciesApplied = false

        // Last time a same-process request was registered. Same-process means
        // sharing this memory is safe.
        @Volatile
        private var lastSameProcessRequest = -1L

        /** Allows a same-process caller to invoke an action unsigned for 5s. */
        @Synchronized
        fun registerSameProcessRequest(intent: Intent) {
            lastSameProcessRequest = System.currentTimeMillis()
            intent.putExtra("is_same_process", true)
        }

        @Synchronized
        private fun checkSameProcessRequest(intent: Intent): Boolean {
            if (!intent.getBooleanExtra("is_same_process", false)) return false
            if (lastSameProcessRequest == -1L) return false

            val ok =
                System.currentTimeMillis() - lastSameProcessRequest <= 5000 &&
                    intent.action in ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS
            if (ok) {
                lastSameProcessRequest = -1L
            }
            return ok
        }
    }
}
