package capsule.profile.ui.main

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import capsule.profile.R
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.add
import capsule.profile.ui.icons.bolt
import capsule.profile.ui.icons.done
import capsule.profile.ui.icons.folder
import capsule.profile.ui.icons.settings
import capsule.profile.ui.icons.visibility

/** Overflow menu of the main app bar. */
@Composable
fun MainDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFreezeAll: () -> Unit,
    onFreezeAllShortcut: () -> Unit,
    onInstallApk: () -> Unit,
    installApkEnabled: Boolean,
    onShowAll: () -> Unit,
    onDocumentsUi: () -> Unit,
    onSettings: () -> Unit,
    showAllChecked: Boolean,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.freeze_all)) },
            leadingIcon = { Icon(ac_unit, contentDescription = null) },
            onClick = {
                onDismiss()
                onFreezeAll()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.create_freeze_all_shortcut)) },
            leadingIcon = { Icon(bolt, contentDescription = null) },
            onClick = {
                onDismiss()
                onFreezeAllShortcut()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.install_app_to_profile)) },
            leadingIcon = { Icon(add, contentDescription = null) },
            enabled = installApkEnabled,
            onClick = {
                onDismiss()
                onInstallApk()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.show_all)) },
            leadingIcon = { Icon(visibility, contentDescription = null) },
            trailingIcon = {
                if (showAllChecked) {
                    Icon(done, contentDescription = null)
                }
            },
            onClick = {
                onDismiss()
                onShowAll()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.documents_ui)) },
            leadingIcon = { Icon(folder, contentDescription = null) },
            onClick = {
                onDismiss()
                onDocumentsUi()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings)) },
            leadingIcon = { Icon(settings, contentDescription = null) },
            onClick = {
                onDismiss()
                onSettings()
            },
        )
    }
}
