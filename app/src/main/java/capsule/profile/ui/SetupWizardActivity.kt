package capsule.profile.ui

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.AuthenticationUtility
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.Utility

/**
 * Guided setup: welcome, permissions, compatibility notes, then system
 * provisioning of the managed profile.
 */
class SetupWizardActivity : ComponentActivity() {
    private lateinit var policyManager: DevicePolicyManager

    private val provisionProfile: ActivityResultLauncher<Void?> =
        registerForActivityResult(ProfileProvisionContract()) { setupProfileCb(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyManager = getSystemService(DevicePolicyManager::class.java)

        // The user may tap the finish-provisioning notification after this
        // activity was removed from recents: the notification then starts a
        // fresh instance, which should bounce straight back to MainActivity.
        if (ACTION_PROFILE_PROVISIONED == intent?.action && Utility.isWorkProfileAvailable(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val resume = ACTION_RESUME_SETUP == intent?.action

        setContent {
            CapsuleTheme {
                WizardScreen(resume)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // DummyActivity starts us with this action once provisioning is done.
        if (ACTION_PROFILE_PROVISIONED == intent.action && Utility.isWorkProfileAvailable(this)) {
            finishWithResult(true)
        }
    }

    private fun finishWithResult(succeeded: Boolean) {
        setResult(if (succeeded) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun setupProfile() {
        if (!policyManager.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            wizardState = WizardStep.FAILED
            return
        }

        // Aborted setups may leave stale keys behind, making the auth layer
        // think a shared key already exists.
        AuthenticationUtility.reset()

        try {
            provisionProfile.launch(null)
        } catch (e: ActivityNotFoundException) {
            wizardState = WizardStep.FAILED
        }
    }

    private fun setupProfileCb(result: Boolean) {
        if (result) {
            if (Utility.isWorkProfileAvailable(this)) {
                // On Oreo+ the activity-based ACTION_PROVISIONING_SUCCESSFUL
                // flow keeps the provisioning UI up until finalization.
                finishWithResult(true)
                return
            }

            // Pre-Oreo: the user must tap a notification to bring the profile
            // side up before the setup is complete.
            LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, true)
            wizardState = WizardStep.ACTION_REQUIRED
        } else {
            wizardState = WizardStep.FAILED
        }
    }

    /** Collects ROM and provisioning-handler details for bug reports. */
    private fun getProvisioningDiagnostics(): String {
        val pm = packageManager
        val report = StringBuilder()
        report
            .append("Capsule provisioning diagnostics\n")
            .append("Device: ")
            .append(Build.MANUFACTURER)
            .append(' ')
            .append(Build.MODEL)
            .append(" ( ")
            .append(Build.DEVICE)
            .append(" )\n")
            .append("Android: ")
            .append(Build.VERSION.RELEASE)
            .append(" / API ")
            .append(Build.VERSION.SDK_INT)
            .append("\n")
            .append("Build: ")
            .append(Build.DISPLAY)
            .append("\n")
            .append("Managed users feature: ")
            .append(pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS))
            .append("\n")
            .append("Managed-profile provisioning allowed: ")
            .append(
                policyManager.isProvisioningAllowed(
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
                ),
            ).append("\n")

        val handler: ResolveInfo? =
            pm.resolveActivity(
                Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        report.append("Provisioning handler: ")
        if (handler?.activityInfo == null) {
            report.append("not found\n")
        } else {
            report
                .append(handler.activityInfo.packageName)
                .append('/')
                .append(handler.activityInfo.name)
                .append('\n')
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val roleHolder = policyManager.devicePolicyManagementRoleHolderPackage
            report
                .append("Device policy role holder: ")
                .append(roleHolder ?: "not configured")
            if (roleHolder != null) {
                report.append(if (isPackageInstalled(roleHolder)) " (installed)" else " (not installed)")
            }
            report.append('\n')
        }

        val partnerProvider: ProviderInfo? =
            pm.resolveContentProvider(
                "com.google.android.setupwizard.partner",
                0,
            )
        report
            .append("Google SetupWizard partner provider: ")
            .append(partnerProvider?.packageName ?: "not present")
            .append(" (ROM dependent)\n")
        return report.toString()
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    // ==== Compose UI ====

    private enum class WizardStep {
        WELCOME,
        PERMISSIONS,
        COMPATIBILITY,
        READY,
        PLEASE_WAIT,
        ACTION_REQUIRED,
        FAILED,
    }

    private var wizardState by mutableStateOf(WizardStep.WELCOME)

    @Composable
    private fun WizardScreen(resume: Boolean) {
        LaunchedEffect(resume) {
            if (resume) wizardState = WizardStep.ACTION_REQUIRED
        }
        var showDiagnostics by remember { mutableStateOf(false) }
        val step = wizardState

        WizardScaffold(
            step = wizardState,
            showBack = step in listOf(WizardStep.PERMISSIONS, WizardStep.COMPATIBILITY, WizardStep.READY),
            showNext =
                step in
                    listOf(
                        WizardStep.WELCOME,
                        WizardStep.PERMISSIONS,
                        WizardStep.COMPATIBILITY,
                        WizardStep.READY,
                        WizardStep.FAILED,
                    ),
            nextLabel =
                if (step == WizardStep.FAILED) {
                    stringResource(R.string.ok)
                } else {
                    stringResource(R.string.setup_wizard_next)
                },
            onBack = {
                wizardState =
                    when (wizardState) {
                        WizardStep.PERMISSIONS -> WizardStep.WELCOME
                        WizardStep.COMPATIBILITY -> WizardStep.PERMISSIONS
                        WizardStep.READY -> WizardStep.COMPATIBILITY
                        else -> wizardState
                    }
            },
            onNext = {
                when (wizardState) {
                    WizardStep.WELCOME -> {
                        wizardState = WizardStep.PERMISSIONS
                    }
                    WizardStep.PERMISSIONS -> {
                        wizardState = WizardStep.COMPATIBILITY
                    }
                    WizardStep.COMPATIBILITY -> {
                        wizardState = WizardStep.READY
                    }
                    WizardStep.READY -> {
                        wizardState = WizardStep.PLEASE_WAIT
                        setupProfile()
                    }
                    WizardStep.FAILED -> {
                        finishWithResult(false)
                    }
                    else -> {}
                }
            },
        ) {
            when (wizardState) {
                WizardStep.WELCOME -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_welcome,
                        textRes = R.string.setup_wizard_welcome_text,
                    )
                }
                WizardStep.PERMISSIONS -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_permissions,
                        textRes = R.string.setup_wizard_permissions_text,
                    )
                }
                WizardStep.COMPATIBILITY -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_compatibility,
                        textRes = R.string.setup_wizard_compatibility_text,
                    )
                }
                WizardStep.READY -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_ready,
                        textRes = R.string.setup_wizard_ready_text,
                    )
                }
                WizardStep.PLEASE_WAIT -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_please_wait,
                        textRes = R.string.setup_wizard_please_wait_text,
                        progress = true,
                    )
                }
                WizardStep.ACTION_REQUIRED -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_action_required,
                        textRes = R.string.setup_wizard_action_required_text,
                        progress = true,
                    )
                }
                WizardStep.FAILED -> {
                    WizardTextPage(
                        titleRes = R.string.setup_wizard_failed,
                        textRes = R.string.setup_wizard_failed_text,
                        onCopyDiagnostics = { showDiagnostics = true },
                    )
                }
            }
        }

        if (showDiagnostics) {
            AppDialog(
                onDismiss = { showDiagnostics = false },
                title = stringResource(R.string.setup_wizard_failed),
                message = getProvisioningDiagnostics(),
                dismiss = DialogButton(text = stringResource(R.string.cancel)),
                confirm =
                    DialogButton(
                        text = stringResource(R.string.setup_copy_diagnostics),
                        onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Capsule provisioning diagnostics",
                                    getProvisioningDiagnostics(),
                                ),
                            )
                            Toast.makeText(this, R.string.setup_diagnostics_copied, Toast.LENGTH_SHORT).show()
                            showDiagnostics = false
                        },
                    ),
            )
        }
    }

    @Composable
    private fun WizardScaffold(
        step: WizardStep,
        showBack: Boolean,
        showNext: Boolean,
        nextLabel: String,
        onBack: () -> Unit,
        onNext: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
        ) {
            AnimatedContent(
                targetState = wizardState,
                transitionSpec = {
                    if (targetState.ordinal >= initialState.ordinal) {
                        (slideInHorizontally(tween()) { it / 4 } + fadeIn(tween())) togetherWith
                            (slideOutHorizontally(tween()) { -it / 4 } + fadeOut(tween()))
                    } else {
                        (slideInHorizontally(tween()) { -it / 4 } + fadeIn(tween())) togetherWith
                            (slideOutHorizontally(tween()) { it / 4 } + fadeOut(tween()))
                    }
                },
                label = "wizard-step",
                modifier = Modifier.weight(1f),
            ) {
                content()
            }

            if (wizardState == WizardStep.PLEASE_WAIT || wizardState == WizardStep.ACTION_REQUIRED) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    WizardButton(
                        text = stringResource(R.string.setup_wizard_back),
                        onClick = onBack,
                        filled = false,
                    )
                    Spacer(Modifier.padding(4.dp))
                }
                if (showNext) {
                    WizardButton(text = nextLabel, onClick = onNext, filled = true)
                }
            }
        }
    }

    @Composable
    private fun WizardButton(text: String, onClick: () -> Unit, filled: Boolean) {
        Surface(
            shape = RoundedCornerShape(100.dp),
            color =
                if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            contentColor =
                if (filled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            onClick = onClick,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }

    @Composable
    private fun WizardTextPage(
        titleRes: Int,
        textRes: Int,
        progress: Boolean = false,
        onCopyDiagnostics: (() -> Unit)? = null,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onCopyDiagnostics != null) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onCopyDiagnostics,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_copy_diagnostics),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Used when provisioning already finished but the profile-side app was
         * never brought up (pre-Oreo notification flow).
         */
        const val ACTION_RESUME_SETUP = "capsule.profile.RESUME_SETUP"
        const val ACTION_PROFILE_PROVISIONED = "capsule.profile.PROFILE_PROVISIONED"
    }
}

class SetupWizardContract : ActivityResultContract<Void?, Boolean>() {
    override fun createIntent(context: Context, input: Void?): Intent =
        Intent(context, SetupWizardActivity::class.java)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Boolean = resultCode == android.app.Activity.RESULT_OK
}

class ResumeSetupContract : ActivityResultContract<Void?, Boolean>() {
    override fun createIntent(context: Context, input: Void?): Intent =
        Intent(context, SetupWizardActivity::class.java).apply {
            action = SetupWizardActivity.ACTION_RESUME_SETUP
        }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Boolean = resultCode == android.app.Activity.RESULT_OK
}

private class ProfileProvisionContract : ActivityResultContract<Void?, Boolean>() {
    override fun createIntent(context: Context, input: Void?): Intent {
        val admin =
            ComponentName(
                context.applicationContext,
                CapsuleDeviceAdminReceiver::class.java,
            )
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
        }
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Boolean = resultCode == android.app.Activity.RESULT_OK
}
