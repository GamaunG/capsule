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
import capsule.profile.services.FreezeService
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.IFileShuttleService
import capsule.profile.services.IFileShuttleServiceCallback
import capsule.profile.ui.components.InstallationProgressDialog
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.AuthenticationUtility
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
            appInstallFinishedIfInstalling()
            init()
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
            // Keep our policies in sync with the app version on every contact.
            Utility.enforceWorkProfilePolicies(this)
            Utility.enforceUserRestrictions(this)
            SettingsManager.applyAll()

            synchronized(DummyActivity::class.java) {
                // Never ask during finalization; it conflicts with the
                // provisioning UI. Ask once per process, inside the profile.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasRequestedPermission &&
                    FINALIZE_PROVISION != intent?.action
                ) {
                    hasRequestedPermission = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return
                    }
                }
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
        setIntent(intent)

        if (PACKAGEINSTALLER_CALLBACK == intent.action) {
            when (
                intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
            ) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val pendingUserAction =
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (pendingUserAction != null) {
                        startActivity(pendingUserAction)
                    } else {
                        appInstallFinished(Activity.RESULT_CANCELED)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    appInstallFinished(Activity.RESULT_OK)
                }
                else -> {
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
        // Foreground bind: this activity cannot hold the connection itself.
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
            true,
        )
    }

    @Suppress("DEPRECATION")
    private fun actionInstallPackage() {
        var uri: Uri? = null
        if (intent.hasExtra("package")) {
            uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        }
        val policy = StrictMode.getVmPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || intent.hasExtra("direct_install_apk")) {
            if (intent.hasExtra("apk")) {
                // The "package:" scheme stopped working on Oreo; use the apk path.
                intent.getStringExtra("apk")?.let { path ->
                    uri = Uri.fromFile(File(path))
                }
            } else if (intent.hasExtra("direct_install_apk")) {
                // Install an APK streamed from the other profile via our
                // FileProviderProxy. The Fd is released when we finish.
                uri = IntentCompat.getParcelableExtra(intent, "direct_install_apk", Uri::class.java)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pi = packageManager.packageInstaller
            val intent = Intent(this, DummyActivity::class.java)
            intent.action = PACKAGEINSTALLER_CALLBACK
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE,
                )
            val pkg =
                intent.getStringExtra("package") ?: run {
                    finish()
                    return
                }
            pi.uninstall(pkg, pendingIntent.intentSender)
            return
        }

        val uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri)
        uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        packageActivityLauncher.launch(uninstallIntent)
    }

    private fun appInstallFinishedIfInstalling() {
        if (!installProgress.isNaN()) installProgress = Float.NaN
    }

    private fun appInstallFinished(resultCode: Int) {
        // Always drop any forwarded Fd we may have been streaming from.
        FileProviderProxy.clearForwardProxy()
        installProgress = Float.NaN

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
            val intent = Intent(UNFREEZE_AND_LAUNCH)
            Utility.transferIntentToProfile(this, intent)
            val packageName =
                intent.getStringExtra("packageName") ?: run {
                    finish()
                    return
                }
            intent.putExtra(
                "shouldFreeze",
                SettingsManager.getAutoFreezeServiceEnabled() &&
                    LocalStorageManager.stringListContains(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName,
                    ),
            )
            if (intent.hasExtra("linkedPackages")) {
                val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
                val packagesShouldFreeze = BooleanArray(packages.size)
                for (i in packages.indices) {
                    packagesShouldFreeze[i] =
                        SettingsManager.getAutoFreezeServiceEnabled() &&
                        LocalStorageManager.stringListContains(
                            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                            packages[i],
                        )
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
            Toast.makeText(this, getString(R.string.launch_app_fail, packageName), Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun registerAppToFreeze(packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        startService(Intent(this, FreezeService::class.java))
    }

    private fun actionPublicFreezeAll() {
        if (!isProfileOwner) {
            // Load the full auto-freeze list and forward the batch request.
            val intent = Intent(FREEZE_ALL_IN_LIST)
            Utility.transferIntentToProfile(this, intent)
            val list =
                LocalStorageManager
                    .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
            intent.putExtra("list", list.toTypedArray())
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
            Toast.makeText(this, R.string.freeze_all_success, Toast.LENGTH_SHORT).show()
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

    private fun actionSynchronizePreference() {
        val name =
            intent.getStringExtra("name") ?: run {
                finish()
                return
            }
        if (intent.hasExtra("boolean")) {
            LocalStorageManager.setBoolean(name, intent.getBooleanExtra("boolean", false))
        } else if (intent.hasExtra("int")) {
            LocalStorageManager.setInt(name, intent.getIntExtra("int", Int.MIN_VALUE))
        }
        SettingsManager.applyAll()
        if (isProfileOwner) {
            // Settings may have changed: refresh the profile policies.
            Utility.enforceWorkProfilePolicies(this)
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

        private var hasRequestedPermission = false

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
