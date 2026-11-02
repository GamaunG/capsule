package capsule.profile.ui

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import capsule.profile.BuildConfig
import capsule.profile.R
import capsule.profile.services.ICapsuleService
import capsule.profile.ui.DummyActivity
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.AppSnackbarHost
import capsule.profile.ui.components.AppTextField
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.components.GroupedCard
import capsule.profile.ui.components.HtmlText
import capsule.profile.ui.components.SectionHeader
import capsule.profile.ui.components.SettingActionRow
import capsule.profile.ui.components.SettingToggleRow
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.arrow_back
import capsule.profile.ui.icons.bug_report
import capsule.profile.ui.icons.code
import capsule.profile.ui.icons.contactless
import capsule.profile.ui.icons.folder
import capsule.profile.ui.icons.info
import capsule.profile.ui.icons.lock_clock
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.sync_alt
import capsule.profile.ui.icons.verified
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.CapsuleLog
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility
import capsule.profile.util.WorkServiceHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat

class SettingsActivity : ComponentActivity() {
    // Read live from the holder: a binder snapshot handed over through the
    // intent would silently go stale whenever the work-profile bridge dies
    // or rebinds while this screen is open.
    private val serviceWork: ICapsuleService?
        get() = WorkServiceHolder.binder

    // Messages from non-composable callbacks land here.
    private val snackbarHostState =
        androidx.compose.material3.SnackbarHostState()

    /**
     * One step of a special-access permission chain. Each step is requested
     * in order; returning from its settings screen advances to the next
     * missing one, so enabling a feature never needs re-toggling.
     */
    private enum class PermissionGate(
        val explainRes: Int,
        val settingsAction: String,
        val workSide: Boolean,
    ) {
        MAIN_ALL_FILES(R.string.request_storage_manager_main, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, false),
        WORK_ALL_FILES(R.string.request_storage_manager_work, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, true),
        MAIN_OVERLAY(R.string.request_system_alert_main, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, false),
        WORK_OVERLAY(R.string.request_system_alert_work, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true),
        MAIN_USAGE(R.string.request_usage_stats_main, Settings.ACTION_USAGE_ACCESS_SETTINGS, false),
        WORK_USAGE(R.string.request_usage_stats_work, Settings.ACTION_USAGE_ACCESS_SETTINGS, true),
    }

    /** The chain currently being walked; empty when idle. */
    private var permissionChain: List<PermissionGate> = emptyList()

    /** What to enable once the chain completes. */
    private var chainTarget: (() -> Unit)? = null

    /** The gate whose settings screen or dialog is currently up. */
    private var pendingGate by mutableStateOf<PermissionGate?>(null)

    /** One installed CA certificate with display metadata. */
    private data class InstalledCert(
        val encoded: ByteArray,
        val label: String,
        val validUntil: String,
    )

    // Certificate state lives on the activity: the install launcher callback
    // runs outside composition.
    private var certs by mutableStateOf<List<InstalledCert>?>(null)
    private var certBusy by mutableStateOf(false)
    private var certToRemove by mutableStateOf<InstalledCert?>(null)

    // Cancels a superseded certificate load (re-entry, resume).
    private var certLoadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CapsuleTheme {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Entering the screen and returning from other apps both refresh the
        // list: the profile may have been paused, toggled or re-enabled while
        // we were away.
        reloadCerts()
    }

    private val mainPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            advancePermissionChain()
        }

    private val workPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            advancePermissionChain()
        }

    // The Auto Freeze notification permission is asked once, when the service
    // is enabled; its result does not change any flow in this screen.
    private val workNotificationPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Nothing to do: enabling the service does not depend on it.
        }

    private val selectCert: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) installCertificate(uri)
        }

    /** True when the gate's permission currently holds. Binder-backed. */
    private fun isGateSatisfied(gate: PermissionGate): Boolean =
        try {
            if (gate.workSide) {
                val work = serviceWork ?: return false
                when (gate) {
                    PermissionGate.WORK_ALL_FILES -> work.hasAllFileAccessPermission()
                    PermissionGate.WORK_OVERLAY -> work.hasSystemAlertPermission()
                    PermissionGate.WORK_USAGE -> work.hasUsageStatsPermission()
                    else -> true
                }
            } else {
                when (gate) {
                    PermissionGate.MAIN_ALL_FILES -> {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            Utility.checkAllFileAccessPermission()
                    }
                    PermissionGate.MAIN_OVERLAY -> {
                        Utility.checkSystemAlertPermission(this)
                    }
                    PermissionGate.MAIN_USAGE -> {
                        Utility.checkUsageStatsPermission(this)
                    }
                    else -> {
                        true
                    }
                }
            }
        } catch (e: Exception) {
            false
        }

    /**
     * Walks the chain: requests the first still-missing permission, or
     * finishes the chain when everything is granted. A permission that is
     * still missing right after its settings screen was shown means the user
     * declined it: the chain stops there.
     */
    private fun advancePermissionChain() {
        val chain = permissionChain
        if (chain.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val missing = chain.firstOrNull { !isGateSatisfied(it) }
            withContext(Dispatchers.Main) {
                when {
                    missing == null -> {
                        CapsuleLog.i("Permission chain completed")
                        pendingGate = null
                        permissionChain = emptyList()
                        chainTarget?.invoke()
                        chainTarget = null
                    }

                    missing == pendingGate -> {
                        // Back from this very permission's screen without it
                        // being granted: the user declined; stop asking.
                        CapsuleLog.i("Permission ${missing.name} was declined; stopping the chain")
                        pendingGate = null
                        permissionChain = emptyList()
                        chainTarget = null
                    }

                    else -> {
                        pendingGate = missing
                    }
                }
            }
        }
    }

    /** Starts a sequential permission chain for [gates] and enables on success. */
    private fun startPermissionChain(
        gates: List<PermissionGate>,
        onGranted: () -> Unit,
    ) {
        permissionChain = gates
        chainTarget = onGranted
        pendingGate = null
        advancePermissionChain()
    }

    /**
     * Asks the work profile instance for the notification permission, so the
     * Auto Freeze service's status notification (the thing that keeps its
     * watcher alive) can actually be seen. No other feature needs it.
     */
    private fun requestWorkNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val intent = Intent(DummyActivity.REQUEST_NOTIFICATION_PERMISSION)
        try {
            Utility.transferIntentToProfile(this, intent)
            workNotificationPermissionLauncher.launch(intent)
        } catch (e: IllegalStateException) {
            // The work profile is unreachable; the service still works, only
            // its notification stays hidden.
            CapsuleLog.w("Could not reach the work profile for the notification permission", e)
        }
    }

    /** Launches the settings screen backing [gate], on the right profile. */
    private fun launchGate(gate: PermissionGate) {
        if (gate.workSide) {
            val intent = Intent(DummyActivity.OPEN_SETTINGS_IN_PROFILE)
            intent.putExtra("settings_action", gate.settingsAction)
            try {
                Utility.transferIntentToProfile(this, intent)
                workPermissionLauncher.launch(intent)
            } catch (e: IllegalStateException) {
                CapsuleLog.w("The work profile is unreachable; stopping the chain", e)
                permissionChain = emptyList()
                chainTarget = null
            }
        } else {
            mainPermissionLauncher.launch(Intent(gate.settingsAction))
        }
    }

    /**
     * Reloads the installed certificate list from the work profile. The
     * bridge may still be reconnecting (e.g. right after the profile was
     * toggled) and the profile user may not have finished unlocking — its
     * trust store then reads empty — so both conditions are retried briefly
     * instead of publishing a misleading "none installed".
     */
    private fun reloadCerts() {
        certLoadJob?.cancel()
        certLoadJob =
            lifecycleScope.launch {
                var loaded: List<InstalledCert>? = null
                repeat(CERT_LOAD_ATTEMPTS) { attempt ->
                    val work = serviceWork
                    if (work != null) {
                        val result =
                            withContext(Dispatchers.IO) {
                                try {
                                    val list =
                                        work.installedCaCerts.mapNotNull { bundle ->
                                            parseCert(bundle.getByteArray("cert") ?: return@mapNotNull null)
                                        }
                                    // An empty list from a profile user that is
                                    // still unlocking is a lie: retry it.
                                    if (list.isEmpty() && isWorkUserLocked()) null else list
                                } catch (e: Exception) {
                                    CapsuleLog.w("Failed to list the installed certificates", e)
                                    null
                                }
                            }
                        if (result != null) {
                            certs = result
                            return@launch
                        }
                        loaded = result
                    }
                    if (attempt < CERT_LOAD_ATTEMPTS - 1) {
                        delay(CERT_LOAD_RETRY_MS)
                    }
                }
                certs = loaded
            }
    }

    private companion object {
        /** Attempts and spacing for the initial certificate list load. */
        const val CERT_LOAD_ATTEMPTS = 4
        const val CERT_LOAD_RETRY_MS = 1_500L
    }

    /** True while the work profile user exists but is still unlocking. */
    private fun isWorkUserLocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val manager = getSystemService(UserManager::class.java)
        val user = Utility.getWorkProfileUserHandle(this) ?: return false
        return !manager.isUserUnlocked(user)
    }

    /** Installs the certificate file behind [uri] into the profile store. */
    private fun installCertificate(uri: Uri) {
        val work = serviceWork ?: return
        certBusy = true
        lifecycleScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    try {
                        val bytes =
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: return@withContext false
                        work.installCaCert(bytes)
                    } catch (e: Exception) {
                        CapsuleLog.w("Failed to install the certificate", e)
                        false
                    }
                }
            certBusy = false
            snackbarHostState.showSnackbar(
                getString(if (ok) R.string.cert_install_success else R.string.cert_install_failed),
            )
            if (ok) reloadCerts()
        }
    }

    /** Removes [cert] from the profile certificate store. */
    private fun removeCertificate(cert: InstalledCert) {
        val work = serviceWork ?: return
        certBusy = true
        lifecycleScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    try {
                        work.uninstallCaCert(cert.encoded)
                    } catch (e: Exception) {
                        CapsuleLog.w("Failed to remove the certificate", e)
                        false
                    }
                }
            certBusy = false
            snackbarHostState.showSnackbar(
                getString(if (ok) R.string.cert_remove_success else R.string.cert_remove_failed),
            )
            if (ok) reloadCerts()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val manager = SettingsManager
        var fileChooser by remember { mutableStateOf(manager.getCrossProfileFileChooserEnabled()) }
        var pickerMode by remember { mutableStateOf(manager.getCrossProfilePickerMode()) }
        var blockContacts by remember { mutableStateOf(manager.getBlockContactsSearchingEnabled()) }
        var paymentStub by remember { mutableStateOf(manager.getPaymentStubEnabled()) }
        var autoFreeze by remember { mutableStateOf(manager.getAutoFreezeServiceEnabled()) }
        var skipForeground by remember { mutableStateOf(manager.getSkipForegroundEnabled()) }
        var delay by remember { mutableStateOf(manager.getAutoFreezeDelay()) }
        var delayDialog by remember { mutableStateOf(false) }

        val fileChooserSupported =
            remember {
                // All-files access landed in R; low-RAM (Android Go) devices forbid
                // the overlay permission File Shuttle depends on.
                Build.VERSION.SDK_INT != Build.VERSION_CODES.Q &&
                    !(getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
            }

        fun requestFileChooser(enable: Boolean) {
            if (!enable) {
                manager.setCrossProfileFileChooserEnabled(false)
                fileChooser = false
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Every missing permission is requested one after another:
                // both profiles need all-files access, and the overlay
                // permission lets File Shuttle start in the background.
                startPermissionChain(
                    listOf(
                        PermissionGate.MAIN_ALL_FILES,
                        PermissionGate.WORK_ALL_FILES,
                        PermissionGate.MAIN_OVERLAY,
                        PermissionGate.WORK_OVERLAY,
                    ),
                ) {
                    manager.setCrossProfileFileChooserEnabled(true)
                    fileChooser = true
                }
            } else {
                manager.setCrossProfileFileChooserEnabled(true)
                fileChooser = true
            }
        }

        fun requestSkipForeground(enable: Boolean) {
            if (!enable) {
                manager.setSkipForegroundEnabled(false)
                skipForeground = false
                return
            }
            startPermissionChain(
                listOf(PermissionGate.MAIN_USAGE, PermissionGate.WORK_USAGE),
            ) {
                manager.setSkipForegroundEnabled(true)
                skipForeground = true
            }
        }

        // The certificate list is loaded from onResume: it covers both
        // entering the screen and returning to it after permission detours
        // or profile toggles.

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                arrow_back,
                                contentDescription = stringResource(R.string.settings_back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader(stringResource(R.string.settings_interaction), icon = sync_alt)
                GroupedCard {
                    SettingToggleRow(
                        title = stringResource(R.string.settings_cross_profile_file_chooser),
                        desc = stringResource(R.string.settings_cross_profile_file_chooser_desc),
                        icon = folder,
                        checked = fileChooser,
                        enabled = fileChooserSupported,
                    ) { requestFileChooser(it) }
                    SettingToggleRow(
                        title = stringResource(R.string.settings_cross_profile_picker_main_to_work),
                        desc = stringResource(R.string.settings_cross_profile_picker_desc),
                        icon = search,
                        checked =
                            pickerMode == SettingsManager.PICKER_MAIN_TO_WORK ||
                                pickerMode == SettingsManager.PICKER_BOTH,
                    ) {
                        val mode = if (it) SettingsManager.PICKER_MAIN_TO_WORK else SettingsManager.PICKER_DISABLED
                        manager.setCrossProfilePickerMode(mode)
                        pickerMode = mode
                    }
                    SettingToggleRow(
                        title = stringResource(R.string.settings_block_contacts_searching),
                        desc = stringResource(R.string.settings_block_contacts_searching_desc),
                        icon = contactless,
                        checked = blockContacts,
                    ) {
                        manager.setBlockContactsSearchingEnabled(it)
                        blockContacts = it
                    }
                    SettingToggleRow(
                        title = stringResource(R.string.settings_payment_stub),
                        desc = stringResource(R.string.settings_payment_stub_desc),
                        icon = contactless,
                        checked = paymentStub,
                    ) {
                        manager.setPaymentStubEnabled(it)
                        paymentStub = it
                    }
                }

                SectionHeader(stringResource(R.string.settings_services), icon = ac_unit)
                GroupedCard {
                    SettingToggleRow(
                        title = stringResource(R.string.settings_auto_freeze_service),
                        desc = stringResource(R.string.settings_auto_freeze_service_desc),
                        icon = ac_unit,
                        checked = autoFreeze,
                    ) {
                        manager.setAutoFreezeServiceEnabled(it)
                        autoFreeze = it
                        if (it) requestWorkNotificationPermission()
                    }
                    SettingActionRow(
                        title = stringResource(R.string.settings_auto_freeze_delay),
                        desc = stringResource(R.string.format_minutes, delay / 60),
                        icon = lock_clock,
                        onClick = { delayDialog = true },
                    )
                    SettingToggleRow(
                        title = stringResource(R.string.settings_dont_freeze_foreground),
                        desc = stringResource(R.string.settings_dont_freeze_foreground_desc),
                        icon = lock_clock,
                        checked = skipForeground,
                    ) { requestSkipForeground(it) }
                }

                SectionHeader(stringResource(R.string.settings_certificates), icon = verified)
                GroupedCard {
                    SettingActionRow(
                        title = stringResource(R.string.cert_install),
                        desc = stringResource(R.string.cert_install_desc),
                        icon = verified,
                        enabled = WorkServiceHolder.available && !certBusy,
                        onClick = { selectCert.launch(arrayOf("*/*")) },
                    )
                    val certList = certs
                    if (WorkServiceHolder.available && certList != null) {
                        if (certList.isEmpty()) {
                            Text(
                                text = stringResource(R.string.cert_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        } else {
                            certList.forEach { cert ->
                                SettingActionRow(
                                    title = cert.label,
                                    desc = cert.validUntil,
                                    icon = verified,
                                    enabled = !certBusy,
                                    onClick = { certToRemove = cert },
                                )
                            }
                        }
                    }
                }

                SectionHeader(stringResource(R.string.settings_about), icon = info)
                GroupedCard {
                    SettingActionRow(
                        title = stringResource(R.string.settings_version),
                        desc = versionDisplay,
                        icon = info,
                        onClick = {},
                    )
                    SettingActionRow(
                        title = stringResource(R.string.settings_source_code),
                        desc = stringResource(R.string.settings_source_code_url),
                        icon = code,
                        onClick = { openUrl(R.string.settings_source_code_url) },
                    )
                    SettingActionRow(
                        title = stringResource(R.string.settings_bug_report),
                        desc = stringResource(R.string.settings_bug_report_url),
                        icon = bug_report,
                        onClick = { openUrl(R.string.settings_bug_report_url) },
                    )
                }
            }
        }

        if (delayDialog) {
            DelayInputDialog(
                currentMinutes = delay / 60,
                onDismiss = { delayDialog = false },
                onPick = {
                    manager.setAutoFreezeDelay(it * 60)
                    delay = it * 60
                    delayDialog = false
                },
            )
        }

        pendingGate?.let { gate ->
            fun cancelChain() {
                pendingGate = null
                permissionChain = emptyList()
                chainTarget = null
            }
            AppDialog(
                onDismiss = { cancelChain() },
                title = stringResource(R.string.app_name),
                content = {
                    HtmlText(stringResource(gate.explainRes))
                },
                dismiss =
                    DialogButton(
                        text = stringResource(R.string.cancel),
                        onClick = { cancelChain() },
                    ),
                confirm =
                    DialogButton(
                        text = stringResource(R.string.ok),
                        autoDismiss = false,
                        onClick = {
                            // pendingGate stays set: the return trip checks
                            // whether this very permission was granted. The
                            // dialog remains visible under the settings screen
                            // and is replaced or cleared by the next step.
                            launchGate(gate)
                        },
                    ),
            )
        }

        certToRemove?.let { cert ->
            AppDialog(
                onDismiss = { certToRemove = null },
                title = stringResource(R.string.cert_remove_title),
                content = {
                    Column {
                        Text(
                            text = cert.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        HtmlText(stringResource(R.string.cert_remove_text))
                    }
                },
                dismiss =
                    DialogButton(
                        text = stringResource(R.string.cancel),
                        onClick = { certToRemove = null },
                    ),
                confirm =
                    DialogButton(
                        text = stringResource(R.string.remove),
                        destructive = true,
                        enabled = !certBusy,
                        onClick = {
                            certToRemove = null
                            removeCertificate(cert)
                        },
                    ),
            )
        }

        if (certBusy) {
            AppDialog(
                onDismiss = {},
                title = stringResource(R.string.app_name),
                dismissible = false,
                message = stringResource(R.string.cert_busy),
            )
        }
    }

    /** Parses encoded certificate bytes into a displayable row. */
    private fun parseCert(encoded: ByteArray): InstalledCert? =
        try {
            val cert =
                CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
            val dn = cert.subjectX500Principal.name
            val label =
                Regex("CN=([^,]+)")
                    .find(dn)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: dn.ifEmpty { getString(R.string.cert_unnamed) }
            InstalledCert(
                encoded = encoded,
                label = label,
                validUntil = getString(R.string.cert_valid_until, DateFormat.getDateInstance().format(cert.notAfter)),
            )
        } catch (e: Exception) {
            CapsuleLog.w("Failed to parse an installed certificate", e)
            null
        }

    /** Free-form minutes input for the auto-freeze delay. */
    @Composable
    private fun DelayInputDialog(
        currentMinutes: Int,
        onDismiss: () -> Unit,
        onPick: (Int) -> Unit,
    ) {
        var text by remember { mutableStateOf(currentMinutes.toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        val invalidText = stringResource(R.string.settings_auto_freeze_delay_invalid)

        fun submit() {
            val minutes = text.trim().toIntOrNull()
            if (minutes == null || minutes < 0 || minutes > 24 * 60) {
                error = invalidText
            } else {
                onPick(minutes)
            }
        }

        AppDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.settings_auto_freeze_delay),
            containerColor = MaterialTheme.colorScheme.surface,
            dismiss = DialogButton(text = stringResource(R.string.cancel)),
            confirm =
                DialogButton(
                    text = stringResource(R.string.ok),
                    onClick = { submit() },
                ),
            content = {
                Column {
                    AppTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            if (error != null) error = null
                        },
                        label = stringResource(R.string.settings_auto_freeze_delay),
                        error = error,
                        singleLine = true,
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(
                                onDone = { submit() },
                            ),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            },
        )
    }

    private val versionName: String
        get() =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager
                        .getPackageInfo(
                            packageName,
                            android.content.pm.PackageManager.PackageInfoFlags
                                .of(0),
                        ).versionName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionName
                } ?: ""
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                ""
            }

    private val versionDisplay: String
        get() =
            buildString {
                append(versionName)
                append(" (").append(BuildConfig.GIT_COMMIT_HASH).append(")")
                if (BuildConfig.DEBUG) append(" · Debug")
            }

    private fun openUrl(res: Int) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(res)))
        startActivity(intent)
    }
}
