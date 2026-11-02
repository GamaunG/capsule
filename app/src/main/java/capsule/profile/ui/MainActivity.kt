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
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
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
import capsule.profile.ui.icons.lock
import capsule.profile.ui.icons.more_vert
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.settings
import capsule.profile.ui.icons.shield_person
import capsule.profile.ui.icons.visibility
import capsule.profile.ui.theme.CapsuleTheme
import capsule.profile.util.AppListCache
import capsule.profile.util.AuthenticationUtility
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
import kotlinx.coroutines.withContext

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

    private var probingWorkService = false

    private var serviceMain: ICapsuleService? = null
    private var serviceWork: ICapsuleService? = null

    // List states, published to the Compose UI once the services are bound.
    private val mainListState = mutableStateOf<AppListState?>(null)
    private val workListState = mutableStateOf<AppListState?>(null)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared with the Compose scaffold so non-composable code (install
    // callbacks, freeze results) can show in-app messages.
    private val snackbarHostState = SnackbarHostState()

    var showAll = false
        private set

    // Compose-visible service state.
    private val servicesReady = mutableStateOf(false)

    // Set once the main-profile service binder is connected; the UI can run
    // with the work profile still binding or paused.
    private val mainServiceReady = mutableStateOf(false)

    // Work-profile status for the in-app banner: paused (Work Mode off),
    // present but unreachable (managed by another app / Capsule removed), or
    // absent entirely (nothing provisioned; the work tab offers setup).
    private val workPaused = mutableStateOf(false)
    private val workUnreachable = mutableStateOf(false)
    private val workAbsent = mutableStateOf(false)

    // Notified when the work-profile service process dies, e.g. when the user
    // switches Work Mode off from quick settings while the app is open.
    private var workDeathRecipient: IBinder.DeathRecipient? = null

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
        val settingUp = storage.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP)
        val hasSetup = storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)
        val profileExists = Utility.getWorkProfileUserHandle(this) != null

        if (settingUp && !profileExists) {
            // Provisioning finished, but the app inside the profile has never
            // been brought up (pre-Oreo notification flow).
            resumeSetup.launch(null)
            return
        }

        // Resolution-based checks are unreliable while a profile is paused,
        // so "does a profile exist" is answered from the user list only.
        workAbsent.value = !hasSetup && !profileExists
        if (!hasSetup && !profileExists) {
            // First run with no profile at all.
            startSetup.launch(null)
        }

        // Bind the main service in every case so the app stays usable behind
        // the wizard; the work side is decided once the bind completes.
        SettingsManager.applyAll()
        bindServices()
    }

    private fun setupWizardCb(result: Boolean) {
        if (result) {
            init()
        } else {
            // Canceled: stay in the app. The work tab offers the setup again
            // instead of kicking the user out to the launcher.
            workAbsent.value = Utility.getWorkProfileUserHandle(this) == null
            if (serviceMain == null && !mainServiceReady.value) {
                bindServices()
            } else if (!workAbsent.value) {
                connectWorkService()
            }
        }
    }

    private fun bindServices() {
        // The main-profile service needs no foreground notification: this
        // activity holds the connection.
        CapsuleLog.i("Binding the main profile service")
        lastMainBindAt = SystemClock.elapsedRealtime()
        (application as capsule.profile.CapsuleApplication).bindCapsuleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    serviceMain = ICapsuleService.Stub.asInterface(service)
                    mainServiceReady.value = true
                    connectWorkService()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The main service lives in this process; if it is gone,
                    // the resume path rebinds.
                }
            },
            false,
        )
    }

    /**
     * Decides between probing the work profile and showing a banner. Called
     * when the main service binds, on resume and after Resume was pressed.
     */
    private fun connectWorkService() {
        if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP) &&
            Utility.getWorkProfileUserHandle(this) == null
        ) {
            // Nothing was ever provisioned: the work tab offers the setup.
            workAbsent.value = true
            workPaused.value = false
            workUnreachable.value = false
            serviceWork = null
            servicesReady.value = false
            return
        }
        workAbsent.value = false
        if (Utility.isWorkProfilePaused(this)) {
            // Quiet mode: nothing can cross the boundary, and launching the
            // bridge would only bounce straight back.
            CapsuleLog.i("Work profile is paused; showing the paused banner")
            showWorkBanner(paused = true)
            return
        }
        workPaused.value = false
        workUnreachable.value = false
        tryStartWorkService()
    }

    private fun showWorkBanner(paused: Boolean) {
        serviceWork = null
        servicesReady.value = false
        workListState.value = null
        workPaused.value = paused
        workUnreachable.value = !paused
    }

    private fun tryStartWorkService() {
        // Probe the work profile: RESULT_CANCELED means Work Mode is off.
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (e: IllegalStateException) {
            // Resolution failed. Resolution is unreliable while a profile is
            // paused or still booting, so classify by the user list instead.
            onWorkServiceUnreachable()
            return
        }
        // onResume may fire while a probe launched from connectWorkService is
        // still in flight, or after the system silently blocked a background
        // launch: rate-limit so each session binds the work service exactly once.
        val now = SystemClock.elapsedRealtime()
        if (now - lastWorkProbeAt < WORK_PROBE_RATE_LIMIT_MS) return
        lastWorkProbeAt = now
        tryStartWorkService.launch(intent)
    }

    /** The bridge could not be resolved or the probe was refused: classify. */
    private fun onWorkServiceUnreachable() {
        when {
            Utility.isWorkProfilePaused(this) -> {
                CapsuleLog.i("Work profile is paused; showing the paused banner")
                showWorkBanner(paused = true)
            }

            Utility.getWorkProfileUserHandle(this) != null -> {
                // The profile resolves but our own instance inside it does not:
                // another app manages it, or Capsule was removed from it.
                CapsuleLog.w("Work profile exists but cannot be reached; showing the banner")
                showWorkBanner(paused = false)
            }

            else -> {
                // No other profile in this group at all, e.g. it was deleted
                // from the system settings: offer the setup instead of exiting.
                CapsuleLog.w("No work profile found; offering the setup")
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                serviceWork = null
                servicesReady.value = false
                workListState.value = null
                workPaused.value = false
                workUnreachable.value = false
                workAbsent.value = true
            }
        }
    }

    private fun tryStartWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            probingWorkService = false
            CapsuleLog.i("Work profile probe succeeded; binding work service")
            bindWorkService()
            return
        }

        // The profile was not accepted: either Work Mode is off, the user
        // dismissed the system prompt, or the two sides lost their HMAC
        // pairing (e.g. the profile-side data was cleared).
        if (Utility.isWorkProfilePaused(this)) {
            probingWorkService = false
            CapsuleLog.i("Work profile is paused; switching to the paused banner")
            showWorkBanner(paused = true)
            return
        }

        // One retry with a fresh pairing heals the lost-key case.
        if (!probingWorkService && Utility.resolvesInOtherProfile(this)) {
            CapsuleLog.w("Probe rejected; retrying once with a fresh auth pairing")
            AuthenticationUtility.reset()
            probingWorkService = true
            lastWorkProbeAt = 0
            tryStartWorkService()
            return
        }

        probingWorkService = false
        onWorkServiceUnreachable()
    }

    private fun bindWorkService() {
        // A config-change relaunch can replay this while the first chain is
        // still in flight; a second START_SERVICE would strand a second
        // translucent bridge activity on top of the UI.
        if (workBindInFlight) {
            CapsuleLog.w("Work service bind already in flight; ignoring the duplicate")
            return
        }
        workBindInFlight = true
        val intent = Intent(DummyActivity.START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // Full settings snapshot: heals any preference the work profile missed.
        intent.putExtra("settings_sync", SettingsManager.buildSyncBundle())
        Utility.transferIntentToProfile(this, intent)
        bindWorkService.launch(intent)
    }

    private fun bindWorkServiceCb(result: ActivityResult) {
        workBindInFlight = false
        if (result.resultCode == RESULT_OK && result.data != null) {
            val extra = result.data!!.getBundleExtra("extra")
            val binder = extra!!.getBinder("service")
            serviceWork = ICapsuleService.Stub.asInterface(binder)
            CapsuleLog.i("Work profile service bound")
            if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
                // Adopting an existing profile (e.g. after flags were reset
                // while the profile survived): setup counts as done.
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            }
            attachWorkDeathRecipient()
            registerStartActivityProxies()
            startKiller()
            servicesReady.value = true
        } else {
            CapsuleLog.w("Failed to bind the work profile service (result=${result.resultCode})")
            onWorkServiceUnreachable()
        }
    }

    /** Reacts to the work service process dying (Work Mode off, removal...). */
    private fun attachWorkDeathRecipient() {
        detachWorkDeathRecipient()
        val recipient =
            IBinder.DeathRecipient {
                runOnUiThread { onWorkServiceDied() }
            }
        try {
            serviceWork?.asBinder()?.linkToDeath(recipient, 0)
            workDeathRecipient = recipient
        } catch (e: Exception) {
            // Already dead: handle it right away.
            workDeathRecipient = null
            onWorkServiceDied()
        }
    }

    private fun detachWorkDeathRecipient() {
        workDeathRecipient?.let {
            try {
                serviceWork?.asBinder()?.unlinkToDeath(it, 0)
            } catch (e: Exception) {
                // The binder is gone; the recipient fired or is moot.
            }
        }
        workDeathRecipient = null
    }

    private fun onWorkServiceDied() {
        if (serviceWork == null) return
        CapsuleLog.w("The work profile service died")
        detachWorkDeathRecipient()
        serviceWork = null
        servicesReady.value = false
        workListState.value = null
        // Work Mode off stops the whole profile; anything else (a crash, the
        // profile being removed) is re-probed so a transient death heals.
        connectWorkService()
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

        if (workAbsent.value) {
            if (Utility.getWorkProfileUserHandle(this) != null) {
                // A profile appeared while we were away: evaluate it.
                workAbsent.value = false
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            } else {
                return
            }
        }

        if (!servicesReady.value) {
            // While the setup wizard owns the flow there is nothing to bind;
            // recovering here would probe for a profile that does not exist
            // yet and dead-end with a misleading banner.
            if (storage.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) ||
                (
                    !storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP) &&
                        Utility.getWorkProfileUserHandle(this) == null
                )
            ) {
                return
            }
            // The bind chain was interrupted, e.g. a configuration change in
            // the background blocked an activity launch. Pick it up from where
            // it stopped now that we are foreground again, but never duplicate
            // a chain that is still in flight: a second bind would destroy the
            // work service mid-call and hang its app list request.
            if (serviceMain == null) {
                if (SystemClock.elapsedRealtime() - lastMainBindAt > MAIN_BIND_RATE_LIMIT_MS) {
                    bindServices()
                }
            } else {
                connectWorkService()
            }
            return
        }

        if (!servicesAlive()) {
            // The services died while we were away. Recover IN PLACE: a
            // restart via startActivity never worked here, because the
            // singleTask launch intent is delivered to this very instance,
            // which is about to finish - the app would simply close.
            recoverServicesInPlace()
            return
        }

        // States change while we are away (shortcuts, batch freeze, installs):
        // reload both lists on every resume.
        mainListState.value?.refresh()
        workListState.value?.refresh()
    }

    /** Drops the dead binders and restarts the bind chain without restarting. */
    private fun recoverServicesInPlace() {
        CapsuleLog.w("The services died; recovering the bind chain in place")
        stopService(Intent(this, KillerService::class.java))
        detachWorkDeathRecipient()
        serviceMain = null
        serviceWork = null
        mainServiceReady.value = false
        servicesReady.value = false
        mainListState.value = null
        workListState.value = null
        bindServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        // DO NOT kill anything while recreating for a configuration change:
        // the new instance may already have started its own services again.
        if (!isChangingConfigurations) {
            doOnDestroy()
        }
    }

    private fun doOnDestroy() {
        detachWorkDeathRecipient()
        refreshScope.cancel()
        stopService(Intent(this, KillerService::class.java))
        Utility.killCapsuleServices(serviceMain, serviceWork)
    }

    // ==== Menu actions ====

    /** Resumes the paused work profile, then re-runs the bind chain. */
    fun resumeWorkProfile() {
        val user =
            Utility.getPausedWorkProfileHandle(this)
                ?: run {
                    // Not (or no longer) in quiet mode: probe directly.
                    lastWorkProbeAt = 0
                    connectWorkService()
                    return
                }
        refreshScope.launch {
            val manager = getSystemService(UserManager::class.java)
            // Best effort: ask the system to leave quiet mode. The request is
            // asynchronous and the profile takes a moment to boot.
            val submitted =
                try {
                    @Suppress("DEPRECATION")
                    manager.requestQuietModeEnabled(false, user)
                } catch (e: Exception) {
                    CapsuleLog.w("requestQuietModeEnabled failed; probing anyway", e)
                    false
                }
            // Wait until the profile is both unpaused and resolvable before
            // probing. Even a timeout must not dead-end: the probe below then
            // either binds anyway or lands back on the paused banner.
            val ready =
                withContext(Dispatchers.IO) {
                    repeat(40) {
                        val unpaused =
                            runCatching { manager.isQuietModeEnabled(user) }
                                .getOrDefault(true)
                        if (!unpaused && Utility.resolvesInOtherProfile(this@MainActivity)) {
                            return@withContext true
                        }
                        kotlinx.coroutines.delay(250)
                    }
                    false
                }
            CapsuleLog.i("Work profile resume wait finished (ready=$ready)")
            withContext(Dispatchers.Main) {
                lastWorkProbeAt = 0
                workPaused.value = false
                workUnreachable.value = false
                connectWorkService()
            }
        }
    }

    /** Opens the system settings home screen. */
    fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: android.content.ActivityNotFoundException) {
            CapsuleLog.w("The settings home screen could not be opened", e)
        }
    }

    /** Offers the setup wizard again from the work tab placeholder. */
    fun startSetupWizard() {
        startSetup.launch(null)
    }

    fun freezeAll() {
        val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        intent.component = ComponentName(this, DummyActivity::class.java)
        // The freeze pass runs in the work profile; report through the
        // snackbar here instead of its toast.
        intent.putExtra("from_app", true)
        startActivity(intent)
        mainListState.value?.refresh()
        workListState.value?.refresh()
        refreshScope.launch {
            kotlinx.coroutines.delay(2000)
            mainListState.value?.refresh()
            workListState.value?.refresh()
            snackbarHostState.showSnackbar(getString(R.string.freeze_all_success))
        }
    }

    fun createFreezeAllShortcut() {
        val launchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        launchIntent.component = ComponentName(this, DummyActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ok =
            Utility.createLauncherShortcut(
                this,
                launchIntent,
                Icon.createWithResource(this, R.mipmap.ic_freeze),
                "capsule-freeze-all",
                getString(R.string.freeze_all_shortcut),
            )
        if (!ok) {
            refreshScope.launch {
                snackbarHostState.showSnackbar(getString(R.string.unsupported_launcher))
            }
        }
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
                            if (result == android.app.Activity.RESULT_OK) {
                                refreshScope.launch {
                                    snackbarHostState.showSnackbar(
                                        getString(R.string.install_app_to_profile_success),
                                    )
                                }
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
        if (!mainServiceReady.value) {
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
                androidx.compose.ui.focus
                    .FocusRequester()
            }

        // The "already installed in the other profile" badges come from a
        // persisted snapshot so they render instantly; the fresh lists then
        // replace the sets silently.
        var workPackages by remember { mutableStateOf(loadCachedPackages(isWork = true)) }
        var mainPackages by remember { mutableStateOf(loadCachedPackages(isWork = false)) }

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
                    cache = AppListCache(applicationContext, isRemote = false),
                ).also { mainListState.value = it }
            }
        // The work list only exists once the work service is bound; the
        // paused/unreachable banner covers the gap in its place.
        val workState =
            if (servicesReady.value && serviceWork != null) {
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
                        cache = AppListCache(applicationContext, isRemote = true),
                    ).also { workListState.value = it }
                }
            } else {
                null
            }

        // One effect per list: keys changing on one side must not re-run the
        // other side's refresh.
        LaunchedEffect(mainState) { mainState.refresh() }
        LaunchedEffect(workState) { workState?.refresh() }

        LaunchedEffect(mainState.apps) {
            val set = mainState.apps.map { it.packageName }.toSet()
            if (set != mainPackages) {
                mainPackages = set
                persistCachedPackages(isWork = false, set)
            }
        }
        LaunchedEffect(workState?.apps) {
            val apps = workState?.apps ?: return@LaunchedEffect
            val set = apps.map { it.packageName }.toSet()
            if (set != workPackages) {
                workPackages = set
                persistCachedPackages(isWork = true, set)
            }
        }

        val paused = workPaused.value
        val unreachable = workUnreachable.value
        val absent = workAbsent.value

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
                                installApkEnabled = workState != null,
                                onShowAll = {
                                    if (!showAll) {
                                        showAllConfirm = true
                                    } else {
                                        showAll = false
                                        mainState.refresh()
                                        workState?.refresh()
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
                            onResume = { resumeWorkProfile() },
                            onOpenSettings = { openSystemSettings() },
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
                            otherPackages = { workPackages },
                            cloneAvailable = workState != null,
                        )
                    }
                    else -> {
                        val state = workState
                        if (state != null) {
                            AppListPage(
                                state,
                                otherPackages = { mainPackages },
                                cloneAvailable = true,
                            )
                        } else {
                            WorkProfilePlaceholder(
                                paused = paused,
                                unreachable = unreachable,
                                absent = absent,
                                onResume = { resumeWorkProfile() },
                                onOpenSettings = { openSystemSettings() },
                                onStartSetup = { startSetupWizard() },
                            )
                        }
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
                            workState?.refresh()
                        },
                    ),
            )
        }
    }

    /** Thin notice shown under the app bar while the profile is unusable. */
    @Composable
    private fun WorkProfileBanner(
        paused: Boolean,
        onResume: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(16.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = if (paused) lock else settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text =
                            stringResource(
                                if (paused) {
                                    R.string.work_profile_paused_title
                                } else {
                                    R.string.work_profile_unreachable_title
                                },
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text =
                            stringResource(
                                if (paused) {
                                    R.string.work_profile_paused_text
                                } else {
                                    R.string.work_profile_unreachable_text
                                },
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Surface(
                    shape =
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = if (paused) onResume else onOpenSettings,
                ) {
                    Text(
                        text =
                            stringResource(
                                if (paused) {
                                    R.string.work_profile_resume
                                } else {
                                    R.string.work_profile_open_settings
                                },
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    /** Full-tab message shown where the work app list would be. */
    @Composable
    private fun WorkProfilePlaceholder(
        paused: Boolean,
        unreachable: Boolean,
        absent: Boolean,
        onResume: () -> Unit,
        onOpenSettings: () -> Unit,
        onStartSetup: () -> Unit,
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector =
                        when {
                            absent -> add
                            paused -> lock
                            else -> settings
                        },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text =
                        stringResource(
                            when {
                                absent -> R.string.work_profile_absent_title
                                paused -> R.string.work_profile_paused_title
                                else -> R.string.work_profile_unreachable_title
                            },
                        ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            when {
                                absent -> R.string.work_profile_absent_text
                                paused -> R.string.work_profile_paused_text
                                else -> R.string.work_profile_unreachable_text
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape =
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick =
                        when {
                            absent -> onStartSetup
                            paused -> onResume
                            else -> onOpenSettings
                        },
                ) {
                    Text(
                        text =
                            stringResource(
                                when {
                                    absent -> R.string.work_profile_start_setup
                                    paused -> R.string.work_profile_resume
                                    else -> R.string.work_profile_open_settings
                                },
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun MainDropdownMenu(
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

    /** Loads the persisted package set of one profile for instant badges. */
    private fun loadCachedPackages(isWork: Boolean): Set<String> =
        LocalStorageManager
            .getStringList(
                if (isWork) {
                    LocalStorageManager.PREF_CACHED_WORK_PACKAGES
                } else {
                    LocalStorageManager.PREF_CACHED_MAIN_PACKAGES
                },
            ).toSet()

    private fun persistCachedPackages(
        isWork: Boolean,
        packages: Set<String>,
    ) {
        LocalStorageManager.setStringList(
            if (isWork) {
                LocalStorageManager.PREF_CACHED_WORK_PACKAGES
            } else {
                LocalStorageManager.PREF_CACHED_MAIN_PACKAGES
            },
            packages.toList(),
        )
    }

    companion object {
        /** Minimum spacing between two main-service bind attempts. */
        private const val MAIN_BIND_RATE_LIMIT_MS = 5_000L

        /** Minimum spacing between two work-profile probes. */
        private const val WORK_PROBE_RATE_LIMIT_MS = 3_000L

        // Bind-chain pacing is process-wide on purpose: provisioning the
        // profile regenerates SystemUI overlays, whose CONFIG_ASSETS_PATHS
        // change relaunches this activity mid-chain. Instance fields would
        // reset with the relaunch and start a duplicate chain, stranding a
        // second translucent bridge activity on top of the UI.
        private var lastMainBindAt = 0L
        private var lastWorkProbeAt = 0L

        // True while a START_SERVICE bridge launch is pending its result.
        private var workBindInFlight = false
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
