package capsule.profile.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.ui.applist.AppListPage
import capsule.profile.ui.applist.AppListState
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.AppSearchField
import capsule.profile.ui.components.AppSnackbarHost
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.icons.home
import capsule.profile.ui.icons.more_vert
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.shield_person
import capsule.profile.ui.settings.SettingsController
import capsule.profile.ui.settings.SettingsPane
import capsule.profile.util.AppListCache
import kotlinx.coroutines.launch

/**
 * Root screen: hosts the settings pane and the two-profile app list pager,
 * wiring every action into the controllers. Reads their Compose state in
 * place, so recomposition behaves exactly like it did when the state lived
 * on the activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    controller: MainController,
    settings: SettingsController,
) {
    BackHandler(enabled = controller.showSettings.value) { controller.showSettings.value = false }

    if (controller.showSettings.value) {
        SettingsPane(settings, onBack = { controller.showSettings.value = false })
        return
    }

    if (!controller.mainServiceReady.value) {
        // Blank while binding services or running the setup wizard; the
        // wizard activities render on top of this window.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showAllConfirm by remember { mutableStateOf(false) }
    val searchFocus =
        remember {
            FocusRequester()
        }

    // The "already installed in the other profile" badges come from a
    // persisted snapshot so they render instantly; the fresh lists then
    // replace the sets silently.
    var workPackages by remember { mutableStateOf(controller.loadCachedPackages(isWork = true)) }
    var mainPackages by remember { mutableStateOf(controller.loadCachedPackages(isWork = false)) }

    val mainState =
        remember {
            AppListState(
                service = controller.serviceMain!!,
                isRemote = false,
                scope = scope,
                onMessage = controller::showListMessage,
                onOtherService = { controller.serviceWork },
                showAllProvider = { controller.showAll },
                refreshPeer = { controller.workListState.value?.refresh() },
                cache = AppListCache(controller.appContext, isRemote = false),
            ).also { controller.mainListState.value = it }
        }
    // The work state is created by the controller when the bridge binds
    // and survives rebinds, so a brief reconnect never drops the sheet.
    val workState = controller.workListState.value

    LaunchedEffect(mainState) { mainState.refresh() }

    // Closing the search must also drop the filter, or the list keeps
    // hiding matches with no visible way to clear the query.
    BackHandler(enabled = searchActive) { searchActive = false }
    LaunchedEffect(searchActive) {
        if (!searchActive) {
            searchQuery = ""
            mainState.searchQuery = ""
            workState?.searchQuery = ""
        }
    }

    LaunchedEffect(mainState.apps) {
        // A list that is still loading is transiently empty; publishing
        // that as the badge set would blank every "installed in the other
        // profile" checkmark until the fresh data lands.
        if (mainState.loading) return@LaunchedEffect
        val set = mainState.apps.map { it.packageName }.toSet()
        if (set != mainPackages) {
            mainPackages = set
            controller.persistCachedPackages(isWork = false, set)
        }
    }
    LaunchedEffect(workState?.apps) {
        val state = workState ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        val set = state.apps.map { it.packageName }.toSet()
        if (set != workPackages) {
            workPackages = set
            controller.persistCachedPackages(isWork = true, set)
        }
    }

    val paused = controller.workPaused.value
    val unreachable = controller.workUnreachable.value
    val absent = controller.workAbsent.value

    // Focus the field as soon as the search bar appears.
    LaunchedEffect(searchActive) {
        if (searchActive) {
            runCatching { searchFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchActive = !searchActive }) {
                            Icon(search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(more_vert, contentDescription = stringResource(R.string.more_options))
                        }
                        MainDropdownMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            onFreezeAll = { controller.freezeAll() },
                            onFreezeAllShortcut = { controller.createFreezeAllShortcut() },
                            onInstallApk = { controller.installApkToProfile() },
                            installApkEnabled = workState != null,
                            onShowAll = {
                                if (!controller.showAll) {
                                    showAllConfirm = true
                                } else {
                                    controller.showAll = false
                                    mainState.refresh()
                                    workState?.refresh()
                                }
                            },
                            onDocumentsUi = { controller.openDocumentsUi() },
                            onSettings = { settings.open() },
                            showAllChecked = controller.showAll,
                        )
                    },
                )
                if (searchActive) {
                    AppSearchField(
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            mainState.searchQuery = it
                            workState?.searchQuery = it
                        },
                        placeholder = stringResource(R.string.search_hint),
                        focusRequester = searchFocus,
                        modifier =
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (paused || unreachable) {
                    WorkProfileBanner(
                        paused = paused,
                        onResume = { controller.resumeWorkProfile() },
                        onOpenSettings = { controller.openSystemSettings() },
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(home, contentDescription = null) },
                    label = { Text(stringResource(R.string.fragment_profile_main)) },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(shield_person, contentDescription = null) },
                    label = { Text(stringResource(R.string.fragment_profile_work)) },
                )
            }
        },
        snackbarHost = { AppSnackbarHost(controller.snackbarHostState) },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) { page ->
            when (page) {
                0 -> {
                    AppListPage(
                        mainState,
                        otherPackages = { workPackages },
                        cloneAvailable = workState != null,
                        onOpenAppInfo = { app -> controller.openAppDetails(app, isRemote = false) },
                    )
                }

                else -> {
                    val state = workState
                    if (state != null) {
                        AppListPage(
                            state,
                            otherPackages = { mainPackages },
                            cloneAvailable = true,
                            onOpenAppInfo = { app -> controller.openAppDetails(app, isRemote = true) },
                        )
                    } else {
                        WorkProfilePlaceholder(
                            paused = paused,
                            unreachable = unreachable,
                            absent = absent,
                            onResume = { controller.resumeWorkProfile() },
                            onOpenSettings = { controller.openSystemSettings() },
                            onStartSetup = { controller.startSetupWizard() },
                        )
                    }
                }
            }
        }
    }

    if (showAllConfirm) {
        AppDialog(
            title = stringResource(R.string.show_all),
            onDismiss = { showAllConfirm = false },
            message = stringResource(R.string.show_all_warning),
            dismiss =
                DialogButton(
                    text = stringResource(R.string.cancel),
                ),
            confirm =
                DialogButton(
                    text = stringResource(R.string.ok),
                    onClick = {
                        controller.showAll = true
                        showAllConfirm = false
                        mainState.refresh()
                        workState?.refresh()
                    },
                ),
        )
    }
}
