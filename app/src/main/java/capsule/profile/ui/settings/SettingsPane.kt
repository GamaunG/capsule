package capsule.profile.ui.settings

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
import capsule.profile.R
import capsule.profile.ui.ResumeMethod
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.AppSnackbarHost
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
import capsule.profile.ui.icons.lock_open
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.sync_alt
import capsule.profile.ui.icons.verified
import capsule.profile.util.SettingsManager
import capsule.profile.util.WorkServiceHolder

/** The in-app settings pane rendered over the app lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    settings: SettingsController,
    onBack: () -> Unit,
) {
    val manager = SettingsManager
    var fileChooser by remember { mutableStateOf(manager.getCrossProfileFileChooserEnabled()) }
    var pickerMode by remember { mutableStateOf(manager.getCrossProfilePickerMode()) }
    var blockContacts by remember { mutableStateOf(manager.getBlockContactsSearchingEnabled()) }
    var paymentStub by remember { mutableStateOf(manager.getPaymentStubEnabled()) }
    var autoFreeze by remember { mutableStateOf(manager.getAutoFreezeServiceEnabled()) }
    var skipForeground by remember { mutableStateOf(manager.getSkipForegroundEnabled()) }
    var delay by remember { mutableStateOf(manager.getAutoFreezeDelay()) }
    var delayDialog by remember { mutableStateOf(false) }
    var resumeMethod by remember {
        mutableStateOf(ResumeMethod.fromId(SettingsManager.getResumeMethodId()))
    }

    fun requestFileChooser(enable: Boolean) {
        if (!enable) {
            manager.setCrossProfileFileChooserEnabled(false)
            fileChooser = false
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Every missing permission is requested one after another:
            // both profiles need all-files access, and the overlay
            // permission lets File Shuttle start in the background.
            settings.startPermissionChain(
                listOf(
                    SettingsController.PermissionGate.MAIN_ALL_FILES,
                    SettingsController.PermissionGate.WORK_ALL_FILES,
                    SettingsController.PermissionGate.MAIN_OVERLAY,
                    SettingsController.PermissionGate.WORK_OVERLAY,
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
        settings.startPermissionChain(
            listOf(
                SettingsController.PermissionGate.MAIN_USAGE,
                SettingsController.PermissionGate.WORK_USAGE,
            ),
        ) {
            manager.setSkipForegroundEnabled(true)
            skipForeground = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            arrow_back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(settings.snackbarHostState) },
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
                    enabled = settings.fileChooserSupported,
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
                SettingActionRow(
                    title = stringResource(R.string.settings_unfreeze_method),
                    desc = stringResource(resumeMethod.labelRes),
                    icon = lock_open,
                    onClick = { settings.resumeMethodDialogOpen = true },
                )
                SettingToggleRow(
                    title = stringResource(R.string.settings_auto_freeze_service),
                    desc = stringResource(R.string.settings_auto_freeze_service_desc),
                    icon = ac_unit,
                    checked = autoFreeze,
                ) {
                    manager.setAutoFreezeServiceEnabled(it)
                    autoFreeze = it
                    if (it) settings.requestWorkNotificationPermission()
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
                    enabled = WorkServiceHolder.available && !settings.certBusy,
                    onClick = { settings.pickCertificates() },
                )
                val certList = settings.certs
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
                                enabled = !settings.certBusy,
                                onClick = { settings.certToRemove = cert },
                            )
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_about), icon = info)
            GroupedCard {
                SettingActionRow(
                    title = stringResource(R.string.settings_version),
                    desc = settings.versionDisplay,
                    icon = info,
                    onClick = {},
                )
                SettingActionRow(
                    title = stringResource(R.string.settings_source_code),
                    desc = stringResource(R.string.settings_source_code_url),
                    icon = code,
                    onClick = { settings.openUrl(R.string.settings_source_code_url) },
                )
                SettingActionRow(
                    title = stringResource(R.string.settings_bug_report),
                    desc = stringResource(R.string.settings_bug_report_url),
                    icon = bug_report,
                    onClick = { settings.openUrl(R.string.settings_bug_report_url) },
                )
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

        if (settings.resumeMethodDialogOpen) {
            ResumeMethodPickerDialog(
                selected = resumeMethod,
                onSelect = {
                    SettingsManager.setResumeMethodId(it.id)
                    resumeMethod = it
                },
                onDismiss = { settings.resumeMethodDialogOpen = false },
            )
        }

        settings.pendingGate?.let { gate ->
            AppDialog(
                onDismiss = { settings.cancelPermissionChain() },
                title = stringResource(R.string.app_name),
                content = {
                    HtmlText(stringResource(gate.explainRes))
                },
                dismiss =
                    DialogButton(
                        text = stringResource(R.string.cancel),
                        onClick = { settings.cancelPermissionChain() },
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
                            settings.launchGate(gate)
                        },
                    ),
            )
        }

        settings.certToRemove?.let { cert ->
            AppDialog(
                onDismiss = { settings.certToRemove = null },
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
                        onClick = { settings.certToRemove = null },
                    ),
                confirm =
                    DialogButton(
                        text = stringResource(R.string.remove),
                        destructive = true,
                        enabled = !settings.certBusy,
                        onClick = {
                            settings.certToRemove = null
                            settings.removeCertificate(cert)
                        },
                    ),
            )
        }

        if (settings.certBusy) {
            AppDialog(
                onDismiss = {},
                title = stringResource(R.string.app_name),
                dismissible = false,
                message = stringResource(R.string.cert_busy),
            )
        }
    }
}
