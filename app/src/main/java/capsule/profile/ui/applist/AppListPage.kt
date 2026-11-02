package capsule.profile.ui.applist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.ui.DummyActivity
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.components.HtmlText
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.apps
import capsule.profile.ui.icons.bolt
import capsule.profile.ui.icons.delete
import capsule.profile.ui.icons.done
import capsule.profile.ui.icons.lock_open
import capsule.profile.ui.icons.play_arrow
import capsule.profile.ui.icons.shield_person
import capsule.profile.ui.icons.sync_alt
import capsule.profile.ui.icons.tune
import capsule.profile.ui.icons.widgets
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.CapsuleLog
import capsule.profile.util.Utility

/** One profile's app list with pull-to-refresh, search and multi-select. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListPage(
    state: AppListState,
    otherPackages: () -> Set<String>,
    modifier: Modifier = Modifier,
    cloneAvailable: Boolean = true,
) {
    val context = LocalContext.current
    var sheetApp by remember { mutableStateOf<ApplicationInfoWrapper?>(null) }
    var miuiCloneApp by remember { mutableStateOf<ApplicationInfoWrapper?>(null) }
    var restrictionsApp by remember { mutableStateOf<ApplicationInfoWrapper?>(null) }
    var batchShortcutSheet by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        if (state.selectionMode) {
            SelectionBar(
                count = state.selected.size,
                onClose = { state.exitSelectionMode() },
                onConfirm = { batchShortcutSheet = true },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { state.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(0.5f))
                    }
                }

                state.displayedApps.isEmpty() -> {
                    EmptyListState()
                }

                else -> {
                    val searching = state.searchQuery.isNotBlank()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (searching) {
                            // Search results keep the score order, no sections.
                            items(state.displayedApps, key = { it.packageName }) { app ->
                                AppListItem(
                                    app = app,
                                    state = state,
                                    inOtherProfile = !state.isRemote && app.packageName in otherPackages(),
                                    dimmed = state.fromCache,
                                    onClick = { onListClick(state, app) { sheetApp = it } },
                                    onLongClick = {
                                        if (state.isRemote && !state.selectionMode) {
                                            state.enterSelectionMode(app)
                                        }
                                    },
                                )
                            }
                        } else {
                            // The service sorts user apps before preinstalled
                            // ones; the sections below keep that order.
                            val userApps = state.displayedApps.filter { !it.isSystem() }
                            val systemApps = state.displayedApps.filter { it.isSystem() }
                            if (userApps.isNotEmpty()) {
                                item(key = "header_user") {
                                    ListSectionHeader(stringResource(R.string.list_section_user_apps))
                                }
                            }
                            items(userApps, key = { it.packageName }) { app ->
                                AppListItem(
                                    app = app,
                                    state = state,
                                    inOtherProfile = !state.isRemote && app.packageName in otherPackages(),
                                    dimmed = state.fromCache,
                                    onClick = { onListClick(state, app) { sheetApp = it } },
                                    onLongClick = {
                                        if (state.isRemote && !state.selectionMode) {
                                            state.enterSelectionMode(app)
                                        }
                                    },
                                )
                            }
                            if (systemApps.isNotEmpty()) {
                                item(key = "header_system") {
                                    Column {
                                        Spacer(Modifier.height(8.dp))
                                        ListSectionHeader(stringResource(R.string.list_section_system_apps))
                                    }
                                }
                            }
                            items(systemApps, key = { it.packageName }) { app ->
                                AppListItem(
                                    app = app,
                                    state = state,
                                    inOtherProfile = !state.isRemote && app.packageName in otherPackages(),
                                    dimmed = state.fromCache,
                                    onClick = { onListClick(state, app) { sheetApp = it } },
                                    onLongClick = {
                                        if (state.isRemote && !state.selectionMode) {
                                            state.enterSelectionMode(app)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetApp?.let { app ->
        AppActionsSheet(
            app = app,
            state = state,
            otherPackages = otherPackages,
            cloneAvailable = cloneAvailable,
            onDismiss = { sheetApp = null },
            onClone = {
                sheetApp = null
                if (Utility.isMIUI() && !app.isSystem() && !state.isRemote) {
                    miuiCloneApp = app
                } else {
                    state.installOrUninstall(app, isInstall = true)
                }
            },
            onUninstall = {
                sheetApp = null
                state.installOrUninstall(app, isInstall = false)
            },
            onFreeze = {
                sheetApp = null
                state.freezeApp(app)
            },
            onUnfreeze = {
                sheetApp = null
                state.unfreezeApp(app)
            },
            onLaunch = {
                sheetApp = null
                launchApp(context, app)
            },
            onAutoFreeze = { state.toggleAutoFreeze(app) },
            onShortcutAutoFreeze = { state.toggleShortcutAutoFreeze(app) },
            onWidgets = { state.setCrossProfileWidgetProvider(app, it) },
            onCrossProfile = { state.setCrossProfileInteraction(app, it) },
            onRestrictions = {
                sheetApp = null
                restrictionsApp = app
            },
            onShortcut = {
                sheetApp = null
                addUnfreezeShortcut(context, state, app, emptyList())
            },
        )
    }

    miuiCloneApp?.let { app ->
        AppDialog(
            onDismiss = { miuiCloneApp = null },
            title = stringResource(R.string.app_name),
            content = {
                HtmlText(stringResource(R.string.miui_cannot_clone))
            },
            dismiss =
                DialogButton(
                    text = stringResource(R.string.ok),
                    onClick = { miuiCloneApp = null },
                ),
            confirm =
                DialogButton(
                    text = stringResource(R.string.continue_anyway),
                    onClick = {
                        miuiCloneApp = null
                        state.installOrUninstall(app, isInstall = true)
                    },
                ),
        )
    }

    restrictionsApp?.let { app ->
        RestrictionsEditor(
            state = state,
            app = app,
            onDismiss = { restrictionsApp = null },
        )
    }

    if (batchShortcutSheet) {
        AppDialog(
            onDismiss = { batchShortcutSheet = false },
            title = stringResource(R.string.batch_operation),
            message = stringResource(R.string.create_unfreeze_shortcut),
            dismiss = DialogButton(text = stringResource(R.string.cancel)),
            confirm =
                DialogButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        val list = state.selectedApps()
                        if (list.isNotEmpty()) {
                            // The shortcut launches the first app after unfreezing all others.
                            addUnfreezeShortcut(context, state, list.first(), list)
                        }
                        state.exitSelectionMode()
                        batchShortcutSheet = false
                    },
                ),
        )
    }
}

@Composable
private fun EmptyListState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.list_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared click handler for list items: selection mode toggles, taps open the sheet. */
private fun onListClick(
    state: AppListState,
    app: ApplicationInfoWrapper,
    openSheet: (ApplicationInfoWrapper) -> Unit,
) {
    if (state.selectionMode) state.toggleSelected(app) else openSheet(app)
}

@Composable
private fun ListSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 10.dp, bottom = 6.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppListItem(
    app: ApplicationInfoWrapper,
    state: AppListState,
    inOtherProfile: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    LaunchedEffect(app.packageName) { state.loadIcon(app) }

    val selected = app.packageName in state.selected
    val container =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(10.dp)
                // Stale snapshots from the disk cache stay visible but dimmed
                // until the fresh list arrives.
                .let { if (dimmed) it.alpha(0.5f) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val bitmap = state.iconCache[app.packageName]
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(44.dp),
            ) {}
        }
        Column(Modifier.weight(1f)) {
            Text(
                text =
                    if (app.isHidden()) {
                        stringResource(R.string.list_item_disabled, app.labelOrDefault)
                    } else {
                        app.labelOrDefault
                    },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (app.isHidden()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.isHidden()) {
            // Frozen apps share the snowflake with the other freeze actions.
            Icon(
                imageVector = ac_unit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        } else if (inOtherProfile) {
            // Marks apps that already exist in the work profile.
            Icon(
                imageVector = done,
                contentDescription = stringResource(R.string.list_item_installed_badge),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Context actions for a single app, in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppActionsSheet(
    app: ApplicationInfoWrapper,
    state: AppListState,
    otherPackages: () -> Set<String>,
    cloneAvailable: Boolean,
    onDismiss: () -> Unit,
    onClone: () -> Unit,
    onUninstall: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onLaunch: () -> Unit,
    onAutoFreeze: () -> Unit,
    onShortcutAutoFreeze: () -> Unit,
    onWidgets: (Boolean) -> Unit,
    onCrossProfile: (Boolean) -> Unit,
    onRestrictions: () -> Unit,
    onShortcut: () -> Unit,
) {
    // The widget option only applies to apps that actually ship widgets.
    var hasWidgets by remember { mutableStateOf(false) }
    LaunchedEffect(app.packageName) { hasWidgets = state.hasWidgets(app) }
    // Restrictions are only editable from the work profile side, where the
    // app is under Capsule's control; querying them on the main side throws.
    var hasRestrictions by remember { mutableStateOf(false) }
    LaunchedEffect(app.packageName) {
        hasRestrictions = state.isRemote && state.hasEditableRestrictions(app)
    }
    val inOtherProfile = app.packageName in otherPackages()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            SheetHeader(app)
            if (state.isRemote) {
                if (!app.isSystem() && !inOtherProfile) {
                    SheetAction(
                        icon = shield_person,
                        label = stringResource(R.string.clone_to_main_profile),
                        onClick = onClone,
                    )
                } else if (!app.isSystem() && inOtherProfile) {
                    // Keep the row visible but inert, with the reason as the
                    // label, so the sheet layout stays predictable.
                    SheetAction(
                        icon = shield_person,
                        label = stringResource(R.string.clone_unavailable_already_installed),
                        enabled = false,
                        onClick = {},
                    )
                }
                if (app.isHidden()) {
                    SheetAction(icon = lock_open, label = stringResource(R.string.unfreeze_app), onClick = onUnfreeze)
                    SheetAction(icon = play_arrow, label = stringResource(R.string.unfreeze_and_launch), onClick = onLaunch)
                } else {
                    SheetAction(icon = ac_unit, label = stringResource(R.string.freeze_app), onClick = onFreeze)
                    SheetAction(icon = play_arrow, label = stringResource(R.string.launch), onClick = onLaunch)
                }
                if (hasWidgets) {
                    SheetAction(
                        icon = widgets,
                        label = stringResource(R.string.allow_cross_profile_widgets),
                        checked = app.packageName in state.crossProfileWidgetProviders,
                        onClick = { onWidgets(app.packageName !in state.crossProfileWidgetProviders) },
                    )
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    SheetAction(
                        icon = sync_alt,
                        label = stringResource(R.string.allow_cross_profile_interaction),
                        checked = app.packageName in state.crossProfilePackages,
                        onClick = { onCrossProfile(app.packageName !in state.crossProfilePackages) },
                    )
                }
                if (!app.isHidden() && hasRestrictions) {
                    SheetAction(icon = tune, label = stringResource(R.string.manage_app_restrictions), onClick = onRestrictions)
                }
                SheetAction(
                    icon = ac_unit,
                    label = stringResource(R.string.auto_freeze),
                    checked = state.isAutoFreeze(app),
                    onClick = onAutoFreeze,
                )
                SheetAction(
                    icon = ac_unit,
                    label = stringResource(R.string.auto_freeze_shortcut),
                    checked = state.isShortcutAutoFreeze(app),
                    onClick = onShortcutAutoFreeze,
                )
                SheetAction(icon = bolt, label = stringResource(R.string.create_unfreeze_shortcut), onClick = onShortcut)
            } else {
                when {
                    !cloneAvailable -> {
                        SheetAction(
                            icon = shield_person,
                            label = stringResource(R.string.clone_unavailable_paused),
                            enabled = false,
                            onClick = {},
                        )
                    }

                    inOtherProfile -> {
                        SheetAction(
                            icon = shield_person,
                            label = stringResource(R.string.clone_unavailable_already_installed),
                            enabled = false,
                            onClick = {},
                        )
                    }

                    else -> {
                        SheetAction(icon = shield_person, label = stringResource(R.string.clone_to_work_profile), onClick = onClone)
                    }
                }
            }
            if (!app.isSystem()) {
                SheetAction(icon = delete, label = stringResource(R.string.uninstall_app), onClick = onUninstall, destructive = true)
            } else if (state.isRemote) {
                // System apps cannot be uninstalled from the profile; hide them
                // instead, which also drops them from the list.
                SheetAction(
                    icon = delete,
                    label = stringResource(R.string.remove_system_app_from_profile),
                    onClick = onUninstall,
                    destructive = true,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetHeader(app: ApplicationInfoWrapper) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = app.labelOrDefault,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    checked: Boolean? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { base ->
                    if (checked != null) {
                        // Announce the whole row as one checkbox control.
                        base.toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            enabled = enabled,
                            onValueChange = { onClick() },
                        )
                    } else {
                        base.clickable(enabled = enabled) { onClick() }
                    }
                }.clip(RoundedCornerShape(16.dp))
                .let { if (enabled) it else it.alpha(0.45f) }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
                if (enabled) {
                    if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (checked != null) {
            // A visible checkbox separates toggles from one-shot buttons.
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

/** Multi-select bar shown while selecting apps in the work profile. */
@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.batch_selected_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            )
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                onClick = onClose,
            ) {
                Text(
                    stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = onConfirm,
            ) {
                Text(
                    stringResource(R.string.create_unfreeze_shortcut),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun launchApp(context: Context, app: ApplicationInfoWrapper) {
    CapsuleLog.i("Launching ${app.packageName} through the unfreeze and launch bridge")
    val intent = Intent(DummyActivity.UNFREEZE_AND_LAUNCH)
    intent.component = ComponentName(context, DummyActivity::class.java)
    intent.putExtra("packageName", app.packageName)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    DummyActivity.registerSameProcessRequest(intent)
    context.startActivity(intent)
}

/** Creates an unfreeze shortcut, optionally unfreezing linked apps first. */
fun addUnfreezeShortcut(
    context: Context,
    state: AppListState,
    app: ApplicationInfoWrapper,
    linkedApps: List<ApplicationInfoWrapper>,
) {
    CapsuleLog.i("Creating unfreeze shortcut for ${app.packageName} with ${linkedApps.size} linked apps")
    val iconBitmap = state.iconCache[app.packageName]
    var id = "capsule-" + app.packageName
    val launchIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH)
    launchIntent.component = ComponentName(context, DummyActivity::class.java)
    launchIntent.putExtra("packageName", app.packageName)
    if (linkedApps.isNotEmpty()) {
        val appListStr = linkedApps.joinToString(",") { it.packageName }
        id += appListStr.hashCode()
        // PersistableBundle has no String array lists; pass a joined string.
        launchIntent.putExtra("linkedPackages", appListStr)
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val icon =
        if (iconBitmap != null) {
            android.graphics.drawable.Icon
                .createWithBitmap(iconBitmap)
        } else {
            android.graphics.drawable.Icon
                .createWithResource(context, R.mipmap.ic_launcher)
        }
    if (!Utility.createLauncherShortcut(context, launchIntent, icon, id, app.labelOrDefault)) {
        state.showMessage(R.string.unsupported_launcher, null)
    }
}
