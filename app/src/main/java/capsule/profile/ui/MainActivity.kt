package capsule.profile.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import capsule.profile.ui.main.MainController
import capsule.profile.ui.main.MainScreen
import capsule.profile.ui.settings.SettingsController
import capsule.profile.ui.theme.CapsuleTheme

/**
 * Thin shell of the main UI: owns the activity-result launchers and the
 * lifecycle, and forwards everything into the controllers. The screens live
 * in [capsule.profile.ui.main] and [capsule.profile.ui.settings]; the bind
 * chain, recovery and menu actions in [MainController]; the permission
 * chains, certificates and About rows in [SettingsController].
 */
class MainActivity : ComponentActivity() {
    private val startSetup: ActivityResultLauncher<Void?> =
        registerForActivityResult(SetupWizardContract()) { controller.setupWizardCb(it) }
    private val selectApk: ActivityResultLauncher<Void?> =
        registerForActivityResult(
            OpenApkContract(),
        ) { uri: Uri? -> controller.onApkSelected(uri) }
    private val tryStartWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { controller.tryStartWorkServiceCb(it) }
    private val bindWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { controller.bindWorkServiceCb(it) }

    // Returning from the work profile's unknown-sources settings screen; the
    // outcome logic lives in MainController.onInstallPermissionResult.
    private val installPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { controller.onInstallPermissionResult() }

    // ==== Settings pane state (merged from the former SettingsActivity) ====

    private val mainPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            settings.advancePermissionChain()
        }

    private val workPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            settings.advancePermissionChain()
        }

    // The Auto Freeze notification permission is asked once, when the service
    // is enabled; its result does not change any flow in this screen.
    private val workNotificationPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Nothing to do: enabling the service does not depend on it.
        }

    private val selectCert: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) settings.installCertificates(uris)
        }

    // ==== Controllers (constructed once per activity instance) ====

    private val controller = MainController(this)
    private val settings = SettingsController(this, controller)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)) {
            // Never run the main UI inside our own managed profile.
            finish()
            return
        }

        setContent {
            CapsuleTheme {
                MainScreen(controller, settings)
            }
        }

        controller.init()
    }

    override fun onResume() {
        super.onResume()
        // The certificate list reload is a coroutine launch; it observes the
        // same state whether it is enqueued before or after the bind chain,
        // which also only launches coroutines until the thread is released.
        if (controller.showSettings.value) settings.reloadCerts()
        controller.onResumed()
    }

    override fun onPause() {
        controller.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // DO NOT kill anything while recreating for a configuration change:
        // the new instance may already have started its own services again.
        if (!isChangingConfigurations) {
            controller.onDestroyed()
        }
    }

    // ==== Launcher forwarding for the controllers ====

    /** Opens the setup wizard (first run, foreign profile, placeholder). */
    fun launchSetupWizard() {
        startSetup.launch(null)
    }

    /** Opens the system document picker restricted to APK files. */
    fun launchApkPicker() {
        selectApk.launch(null)
    }

    /** Probes the work profile with a TRY_START_SERVICE bridge start. */
    fun launchWorkProbe(intent: Intent) {
        tryStartWorkService.launch(intent)
    }

    /** Starts the work profile service through the bridge activity. */
    fun launchWorkBind(intent: Intent) {
        bindWorkService.launch(intent)
    }

    /** Opens the work profile's unknown-sources settings screen. */
    fun launchInstallPermissionSettings(intent: Intent) {
        installPermissionLauncher.launch(intent)
    }

    /** Opens a main-profile special-access settings screen. */
    fun launchMainPermissionSettings(intent: Intent) {
        mainPermissionLauncher.launch(intent)
    }

    /** Opens a work-profile special-access settings screen. */
    fun launchWorkPermissionSettings(intent: Intent) {
        workPermissionLauncher.launch(intent)
    }

    /** Asks the work profile for its notification permission. */
    fun launchWorkNotificationPermission(intent: Intent) {
        workNotificationPermissionLauncher.launch(intent)
    }

    /** Opens the multi-file certificate picker. */
    fun launchCertPicker(mimeTypes: Array<String>) {
        selectCert.launch(mimeTypes)
    }
}

/** Opens the system document picker restricted to APK files. */
private class OpenApkContract : ActivityResultContract<Void?, Uri?>() {
    private val inner = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Void?): Intent =
        inner.createIntent(context, arrayOf("application/vnd.android.package-archive"))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        inner.parseResult(resultCode, intent)
}
