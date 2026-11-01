package capsule.profile.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.services.ICapsuleService
import capsule.profile.services.IStartActivityProxy
import capsule.profile.services.KillerService
import capsule.profile.ui.applist.AppListPage
import capsule.profile.ui.applist.AppListState
import capsule.profile.ui.components.AppSearchField
import capsule.profile.ui.components.AppSnackbarHost
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.add
import capsule.profile.ui.icons.bolt
import capsule.profile.ui.icons.done
import capsule.profile.ui.icons.folder
import capsule.profile.ui.icons.home
import capsule.profile.ui.icons.more_vert
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.settings
import capsule.profile.ui.icons.shield_person
import capsule.profile.ui.icons.visibility
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.CapsuleLog
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager
import capsule.profile.util.UriForwardProxy
import capsule.profile.util.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val startSetup: ActivityResultLauncher<Void?> =
        registerForActivityResult(SetupWizardContract()) { setupWizardCb(it) }
    private val resumeSetup: ActivityResultLauncher<Void?> =
        registerForActivityResult(ResumeSetupContract()) { setupWizardCb(it) }
    private val selectApk: ActivityResultLauncher<Void?> =
        registerForActivityResult(
            OpenApkContract(),
        ) { uri: Uri? -> onApkSelected(uri) }
    private val tryStartWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { tryStartWorkServiceCb(it) }
    private val bindWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { bindWorkServiceCb(it) }

    private lateinit var storage: LocalStorageManager

    // Flag to avoid double-killing our services while restarting.
    private var restarting = false

    private var serviceMain: ICapsuleService? = null
    private var serviceWork: ICapsuleService? = null

    // List states, published to the Compose UI once the services are bound.
    private val mainListState = mutableStateOf<AppListState?>(null)
    private val workListState = mutableStateOf<AppListState?>(null)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var showAll = false
        private set

    // Compose-visible service state.
    private val servicesReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = LocalStorageManager

        if (getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)) {
            // Never run the main UI inside our own managed profile.
            finish()
            return
        }

        setContent {
            CapsuleTheme {
                MainScreen()
            }
        }

        init()
    }

    private fun init() {
        if (storage.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) &&
            !Utility.isWorkProfileAvailable(this)
        ) {
            // Provisioning finished, but the app inside the profile has never
            // been brought up (pre-Oreo notification flow).
            resumeSetup.launch(null)
        } else if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            if (Utility.isWorkProfileAvailable(this)) {
                // Reinstalling the app must not trigger a new provisioning when
                // our own work profile is still alive; just adopt it again.
                CapsuleLog.i("Existing work profile found; skipping the setup wizard")
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                SettingsManager.applyAll()
                bindServices()
            } else {
                startSetup.launch(null)
            }
        } else {
            SettingsManager.applyAll()
            bindServices()
        }
    }

    private fun setupWizardCb(result: Boolean) {
        if (result) {
            init()
        } else {
            finish()
        }
    }

    private fun bindServices() {
        // The main-profile service needs no foreground notification: this
        // activity holds the connection.
        CapsuleLog.i("Binding the main profile service")
        (application as capsule.profile.CapsuleApplication).bindCapsuleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    serviceMain = ICapsuleService.Stub.asInterface(service)
                    tryStartWorkService()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Handled by the restart check in onResume.
                }
            },
            false,
        )
    }

    private fun tryStartWorkService() {
        // Probe the work profile: RESULT_CANCELED means Work Mode is off.
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (e: IllegalStateException) {
            // No work profile exists at all; re-provision on next start.
            storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
            Toast.makeText(this, R.string.work_profile_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tryStartWorkService.launch(intent)
    }

    private fun tryStartWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            CapsuleLog.i("Work profile probe succeeded; binding work service")
            bindWorkService()
        } else {
            CapsuleLog.w("Work profile probe returned ${result.resultCode}; work mode disabled?")
            // The user was prompted to enable Work Mode; we cannot tell ok
            // from cancel, so ask them to start the app again.
            Toast.makeText(this, R.string.work_mode_disabled, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun bindWorkService() {
        val intent = Intent(DummyActivity.START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // Full settings snapshot: heals any preference the work profile missed.
        intent.putExtra("settings_sync", SettingsManager.buildSyncBundle())
        Utility.transferIntentToProfile(this, intent)
        bindWorkService.launch(intent)
    }

    private fun bindWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == RESULT_OK && result.data != null) {
            val extra = result.data!!.getBundleExtra("extra")
            val binder = extra!!.getBinder("service")
            serviceWork = ICapsuleService.Stub.asInterface(binder)
            CapsuleLog.i("Work profile service bound")
            registerStartActivityProxies()
            startKiller()
            servicesReady.value = true
        } else {
            CapsuleLog.w("Failed to bind the work profile service (result=${result.resultCode})")
        }
    }

    private fun startKiller() {
        // Sticky service that kills both CapsuleServices when we are removed
        // from recents: activities receive no callback for that.
        val intent = Intent(this, KillerService::class.java)
        val bundle = Bundle()
        bundle.putBinder("main", serviceMain!!.asBinder())
        bundle.putBinder("work", serviceWork!!.asBinder())
        intent.putExtra("extra", bundle)
        startService(intent)
    }

    fun getOtherService(isRemote: Boolean): ICapsuleService? =
        if (isRemote) serviceMain else serviceWork

    private fun servicesAlive(): Boolean =
        try {
            serviceMain!!.ping()
            serviceWork!!.ping()
            true
        } catch (e: Exception) {
            false
        }

    private fun registerStartActivityProxies() {
        try {
            serviceMain!!.setStartActivityProxy(
                object : IStartActivityProxy.Stub() {
                    override fun startActivity(intent: Intent) {
                        this@MainActivity.startActivity(intent)
                    }
                },
            )

            serviceWork!!.setStartActivityProxy(
                object : IStartActivityProxy.Stub() {
                    override fun startActivity(intent: Intent) {
                        // Resolve the component in the other profile explicitly;
                        // a full intent may fail PackageManager resolution.
                        val dummyIntent = Intent(intent.action)
                        Utility.transferIntentToProfileUnsigned(this@MainActivity, dummyIntent)
                        intent.component = dummyIntent.component
                        this@MainActivity.startActivity(intent)
                    }
                },
            )
        } catch (e: android.os.RemoteException) {
            throw RuntimeException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (restarting) return

        if (!servicesReady.value) {
            // The bind chain was interrupted, e.g. a configuration change in
            // the background blocked an activity launch. Pick it up from where
            // it stopped now that we are foreground again.
            if (serviceMain == null) {
                bindServices()
            } else {
                tryStartWorkService()
            }
            return
        }

        if (!servicesAlive()) {
            // The services died (e.g. KillerService was destroyed). Restart
            // cleanly instead of reusing dead binders.
            doOnDestroy()
            restarting = true
            // Never replay an external intent that reached this exported
            // activity; restart with a fresh launcher intent.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // States change while we are away (shortcuts, batch freeze, installs):
        // reload both lists on every resume.
        mainListState.value?.refresh()
        workListState.value?.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        // DO NOT kill anything while restarting or recreating for a
        // configuration change: the new instance may already have started its
        // own services again.
        if (!restarting && !isChangingConfigurations) {
            doOnDestroy()
        }
    }

    private fun doOnDestroy() {
        refreshScope.cancel()
        stopService(Intent(this, KillerService::class.java))
        Utility.killCapsuleServices(serviceMain, serviceWork)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && serviceMain != null) {
            // We do not need to live in the background; dying takes the
            // work-profile foreground service down with us.
            finish()
        }
    }

    // ==== Menu actions ====

    fun freezeAll() {
        val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        intent.component = ComponentName(this, DummyActivity::class.java)
        startActivity(intent)
        // The freeze pass runs asynchronously in the work profile; refresh
        // again once it has had time to finish.
        mainListState.value?.refresh()
        workListState.value?.refresh()
        refreshScope.launch {
            kotlinx.coroutines.delay(2000)
            mainListState.value?.refresh()
            workListState.value?.refresh()
        }
    }

    fun createFreezeAllShortcut() {
        val launchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        launchIntent.component = ComponentName(this, DummyActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Utility.createLauncherShortcut(
            this,
            launchIntent,
            Icon.createWithResource(this, R.mipmap.ic_freeze),
            "capsule-freeze-all",
            getString(R.string.freeze_all_shortcut),
        )
    }

    fun installApkToProfile() {
        selectApk.launch(null)
    }

    private fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val work = serviceWork ?: return
        CapsuleLog.i("Selected APK $uri for installation into the work profile")
        val proxy = UriForwardProxy(applicationContext, uri)
        try {
            work.installApk(
                proxy,
                object : capsule.profile.services.IAppInstallCallback.Stub() {
                    override fun callback(result: Int) {
                        runOnUiThread {
                            // The other side closes the Fd for us.
                            if (result == RESULT_OK) {
                                Toast
                                    .makeText(
                                        this@MainActivity,
                                        R.string.install_app_to_profile_success,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
            )
        } catch (e: android.os.RemoteException) {
            CapsuleLog.w("Failed to forward the selected APK to the work profile", e)
        }
    }

    fun openDocumentsUi() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(null, "vnd.android.document/root")
        startActivity(intent)
    }

    fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        val extras = Bundle()
        extras.putBinder("profile_service", serviceWork?.asBinder())
        intent.putExtra("extras", extras)
        startActivity(intent)
    }

    // ==== Compose UI ====

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        if (!servicesReady.value) {
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
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        val searchFocus =
            remember {
                androidx.compose.ui.focus
                    .FocusRequester()
            }

        val mainState =
            remember {
                AppListState(
                    service = serviceMain!!,
                    isRemote = false,
                    scope = scope,
                    onMessage = { res, arg ->
                        val message = if (arg != null) getString(res, arg) else getString(res)
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onOtherService = { serviceWork },
                    showAllProvider = { showAll },
                    refreshPeer = { workListState.value?.refresh() },
                ).also { mainListState.value = it }
            }
        val workState =
            remember {
                AppListState(
                    service = serviceWork!!,
                    isRemote = true,
                    scope = scope,
                    onMessage = { res, arg ->
                        val message = if (arg != null) getString(res, arg) else getString(res)
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onOtherService = { serviceMain },
                    showAllProvider = { showAll },
                    refreshPeer = { mainListState.value?.refresh() },
                ).also { workListState.value = it }
            }

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
                                Icon(more_vert, contentDescription = null)
                            }
                            MainDropdownMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                onFreezeAll = { freezeAll() },
                                onFreezeAllShortcut = { createFreezeAllShortcut() },
                                onInstallApk = { installApkToProfile() },
                                onShowAll = {
                                    if (!showAll) {
                                        showAllConfirm = true
                                    } else {
                                        showAll = false
                                        mainState.refresh()
                                        workState.refresh()
                                    }
                                },
                                onDocumentsUi = { openDocumentsUi() },
                                onSettings = { openSettings() },
                                showAllChecked = showAll,
                            )
                        },
                    )
                    if (searchActive) {
                        AppSearchField(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                mainState.searchQuery = it
                                workState.searchQuery = it
                            },
                            placeholder = stringResource(R.string.search_hint),
                            focusRequester = searchFocus,
                            modifier =
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
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
                            otherPackages = { workState.apps.map { it.packageName }.toSet() },
                        )
                    }
                    else -> {
                        AppListPage(
                            workState,
                            otherPackages = { mainState.apps.map { it.packageName }.toSet() },
                        )
                    }
                }
            }
        }

        if (showAllConfirm) {
            capsule.profile.ui.components.AppDialog(
                onDismiss = { showAllConfirm = false },
                title = stringResource(R.string.show_all),
                message = stringResource(R.string.show_all_warning),
                dismiss =
                    capsule.profile.ui.components.DialogButton(
                        text = stringResource(R.string.cancel),
                    ),
                confirm =
                    capsule.profile.ui.components.DialogButton(
                        text = stringResource(R.string.ok),
                        onClick = {
                            showAll = true
                            showAllConfirm = false
                            mainState.refresh()
                            workState.refresh()
                        },
                    ),
            )
        }
    }

    @Composable
    private fun MainDropdownMenu(
        expanded: Boolean,
        onDismiss: () -> Unit,
        onFreezeAll: () -> Unit,
        onFreezeAllShortcut: () -> Unit,
        onInstallApk: () -> Unit,
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
}

/** Opens the system document picker restricted to APK files. */
private class OpenApkContract : ActivityResultContract<Void?, Uri?>() {
    private val inner = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Void?): Intent =
        inner.createIntent(context, arrayOf("application/vnd.android.package-archive"))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        inner.parseResult(resultCode, intent)
}
