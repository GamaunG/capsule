package capsule.profile.ui

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.services.ICapsuleService
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.components.GroupedCard
import capsule.profile.ui.components.SectionHeader
import capsule.profile.ui.components.SettingActionRow
import capsule.profile.ui.components.SettingToggleRow
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.bug_report
import capsule.profile.ui.icons.code
import capsule.profile.ui.icons.contactless
import capsule.profile.ui.icons.done
import capsule.profile.ui.icons.folder
import capsule.profile.ui.icons.info
import capsule.profile.ui.icons.lock_clock
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.sync_alt
import capsule.profile.ui.icons.translate
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility

class SettingsActivity : ComponentActivity() {
    private var serviceWork: ICapsuleService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras: Bundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("extras", Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getBundleExtra("extras")
            }
        serviceWork =
            extras
                ?.getBinder("profile_service")
                ?.let { ICapsuleService.Stub.asInterface(it) }

        setContent {
            CapsuleTheme {
                SettingsScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        val manager = SettingsManager
        var fileChooser by remember { mutableStateOf(manager.getCrossProfileFileChooserEnabled()) }
        var picker by remember { mutableStateOf(manager.getCrossProfilePickerEnabled()) }
        var blockContacts by remember { mutableStateOf(manager.getBlockContactsSearchingEnabled()) }
        var paymentStub by remember { mutableStateOf(manager.getPaymentStubEnabled()) }
        var autoFreeze by remember { mutableStateOf(manager.getAutoFreezeServiceEnabled()) }
        var skipForeground by remember { mutableStateOf(manager.getSkipForegroundEnabled()) }
        var delay by remember { mutableStateOf(manager.getAutoFreezeDelay()) }
        var delayDialog by remember { mutableStateOf(false) }
        var permissionDialog by remember { mutableStateOf<Int?>(null) }

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
                val work = serviceWork
                val hasAllFiles =
                    work != null &&
                        runCatching { work.hasAllFileAccessPermission() }.getOrDefault(false) &&
                        Utility.checkAllFileAccessPermission()
                if (!hasAllFiles) {
                    permissionDialog = R.string.request_storage_manager
                    return
                }
                // File Shuttle starts activities in the background; the
                // overlay permission is the only way to do that reliably.
                val hasOverlay =
                    runCatching { work.hasSystemAlertPermission() }.getOrDefault(false) &&
                        Utility.checkSystemAlertPermission(this)
                if (!hasOverlay) {
                    permissionDialog = R.string.request_system_alert
                    return
                }
            }
            manager.setCrossProfileFileChooserEnabled(true)
            fileChooser = true
        }

        fun requestSkipForeground(enable: Boolean) {
            if (!enable) {
                manager.setSkipForegroundEnabled(false)
                skipForeground = false
                return
            }
            val work = serviceWork
            val hasPermission =
                work != null &&
                    runCatching { work.hasUsageStatsPermission() }.getOrDefault(false) &&
                    Utility.checkUsageStatsPermission(this)
            if (!hasPermission) {
                permissionDialog = R.string.request_usage_stats
                return
            }
            manager.setSkipForegroundEnabled(true)
            skipForeground = true
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.settings)) })
            },
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
                        title = stringResource(R.string.settings_cross_profile_picker),
                        desc = stringResource(R.string.settings_cross_profile_picker_desc),
                        icon = search,
                        checked = picker,
                    ) {
                        manager.setCrossProfilePickerEnabled(it)
                        picker = it
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

                SectionHeader(stringResource(R.string.settings_about), icon = info)
                GroupedCard {
                    SettingActionRow(
                        title = stringResource(R.string.settings_version),
                        desc = versionName,
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
                        title = stringResource(R.string.settings_translate),
                        desc = stringResource(R.string.settings_translate_url),
                        icon = translate,
                        onClick = { openUrl(R.string.settings_translate_url) },
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
            DelayPickerDialog(
                current = delay,
                onDismiss = { delayDialog = false },
                onPick = {
                    manager.setAutoFreezeDelay(it)
                    delay = it
                    delayDialog = false
                },
            )
        }

        permissionDialog?.let { res ->
            val action =
                when (res) {
                    R.string.request_storage_manager -> {
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    }
                    R.string.request_system_alert -> {
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                    }
                    else -> {
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    }
                }
            AppDialog(
                onDismiss = { permissionDialog = null },
                title = stringResource(R.string.app_name),
                content = {
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                dismiss =
                    DialogButton(
                        text = stringResource(R.string.cancel),
                        onClick = { permissionDialog = null },
                    ),
                confirm =
                    DialogButton(
                        text = stringResource(R.string.ok),
                        onClick = {
                            permissionDialog = null
                            startActivity(Intent(action))
                        },
                    ),
            )
        }
    }

    @Composable
    private fun DelayPickerDialog(
        current: Int,
        onDismiss: () -> Unit,
        onPick: (Int) -> Unit,
    ) {
        AppDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.settings_auto_freeze_delay),
            dismiss = DialogButton(text = stringResource(R.string.cancel)),
            content = {
                Column {
                    AUTO_FREEZE_DELAY_SECONDS.forEach { seconds ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(seconds) }
                                    .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.format_minutes, seconds / 60),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (seconds == current) {
                                Icon(
                                    imageVector = done,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
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

    private fun openUrl(res: Int) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(res)))
        startActivity(intent)
    }

    companion object {
        private val AUTO_FREEZE_DELAY_SECONDS = intArrayOf(0, 60, 2 * 60, 5 * 60)
    }
}
