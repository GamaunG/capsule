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
import androidx.lifecycle.Lifecycle
import capsule.profile.R
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.ICapsuleService
import capsule.profile.services.IStartActivityProxy
import capsule.profile.ui.DummyActivity
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
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.AuthenticationUtility
import capsule.profile.util.CapsuleLog
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager
import capsule.profile.util.UriForwardProxy
import capsule.profile.util.Utility
import capsule.profile.util.WorkServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    // Returning from the work profile's unknown-sources settings screen:
    // when the consent was granted, this ROM usually restarts the whole
    // package (the app op change kills it) and this callback never fires.
    // It fires when the consent was declined, or on ROMs that do not restart
    // us — in the latter case the picker can open right away.
    private val installPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val work = serviceWork ?: return@registerForActivityResult
            refreshScope.launch {
                val granted =
                    withContext(Dispatchers.IO) {
                        runCatching { work.hasInstallPermission() }.getOrDefault(false)
                    }
                if (granted) {
                    selectApk.launch(null)
                } else {
                    snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_fail))
                }
            }
        }

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

    // The quiet-mode resume watcher: waits for the user to answer the system
    // "Unpause work apps?" dialog and then continues the bind chain.
    private var resumeWatchJob: Job? = null

    // Safety net for a lost main service connection: re-attempts the bind a
    // bounded number of times if onServiceConnected never arrives.
    private var bindWatchdogJob: Job? = null
    private var bindWatchdogRetries = 0

    // Bounded auto-retry while the work profile exists but is still booting
    // (the "not reachable" banner used to stick until a manual revisit).
    private var unreachableRetryJob: Job? = null

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
                    bindWatchdogJob?.cancel()
                    bindWatchdogRetries = 0
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
        scheduleBindWatchdog()
    }

    /** Re-attempts the main bind when the connection never lands in time. */
    private fun scheduleBindWatchdog() {
        bindWatchdogJob?.cancel()
        if (bindWatchdogRetries >= MAX_BIND_WATCHDOG_RETRIES) return
        bindWatchdogJob =
            refreshScope.launch {
                delay(BIND_WATCHDOG_TIMEOUT_MS)
                if (!mainServiceReady.value && !isFinishing) {
                    bindWatchdogRetries++
                    CapsuleLog.w("The main service bind did not complete in time; retrying ($bindWatchdogRetries)")
                    bindServices()
                }
            }
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
            WorkServiceHolder.update(null)
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
        WorkServiceHolder.update(null)
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
                // The profile resolves but our own instance inside it does
                // not: it may be managed by another app, removed, or simply
                // still booting after Work Mode came back. Give the boot a
                // few bounded retries before settling on the banner.
                CapsuleLog.w("Work profile exists but cannot be reached yet; showing the banner and retrying")
                showWorkBanner(paused = false)
                scheduleUnreachableRetry()
            }

            else -> {
                // No other profile in this group at all, e.g. it was deleted
                // from the system settings: offer the setup instead of exiting.
                CapsuleLog.w("No work profile found; offering the setup")
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                serviceWork = null
                WorkServiceHolder.update(null)
                servicesReady.value = false
                workListState.value = null
                workPaused.value = false
                workUnreachable.value = false
                workAbsent.value = true
            }
        }
    }

    /** Retries the bind chain for a while when the profile is only booting. */
    private fun scheduleUnreachableRetry() {
        if (unreachableRetryJob?.isActive == true) return
        unreachableRetryJob =
            refreshScope.launch {
                repeat(UNREACHABLE_RETRIES) {
                    delay(UNREACHABLE_RETRY_INTERVAL_MS)
                    if (servicesReady.value || workAbsent.value) return@launch
                    // Probing from the background is silently blocked by the
                    // system; only retry while the UI is in the foreground.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        CapsuleLog.i("Retrying the work profile connection")
                        connectWorkService()
                    }
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

        // While the resume watcher owns the flow, a refused probe is expected:
        // Android intercepts the launch into the paused profile and shows the
        // system "Unpause work apps?" dialog instead. The watcher reacts to
        // whichever way the user answers.
        if (resumeWatchJob?.isActive == true) {
            probingWorkService = false
            CapsuleLog.i("Probe refused while the resume watcher is active; waiting for the user")
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
        // translucent bridge activity on top of the UI. A launch whose result
        // was lost (e.g. to a process death) must not poison the flag
        // forever, so the guard goes stale after a timeout.
        val now = SystemClock.elapsedRealtime()
        if (workBindInFlight && now - workBindLaunchedAt < WORK_BIND_STALE_MS) {
            CapsuleLog.w("Work service bind already in flight; ignoring the duplicate")
            return
        }
        if (workBindInFlight) {
            CapsuleLog.w("A previous work service bind never returned; retrying")
        }
        workBindInFlight = true
        workBindLaunchedAt = now
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
            servicesReady.value = true
            WorkServiceHolder.update(serviceWork)
            unreachableRetryJob?.cancel()
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
        WorkServiceHolder.update(null)
        // Probes launched from the background are silently blocked by the
        // system; while we are not resumed, onResume owns the recovery.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            connectWorkService()
        }
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
        detachWorkDeathRecipient()
        serviceMain = null
        serviceWork = null
        WorkServiceHolder.update(null)
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
        WorkServiceHolder.update(null)
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
        if (resumeWatchJob?.isActive == true) return
        resumeWatchJob =
            refreshScope.launch {
                val manager = getSystemService(UserManager::class.java)
                // Best effort: ask the system to leave quiet mode. Stock
                // Android only honors this from the default launcher or the
                // system itself, so expect a refusal here.
                val requested =
                    try {
                        @Suppress("DEPRECATION")
                        manager.requestQuietModeEnabled(false, user)
                    } catch (e: Exception) {
                        CapsuleLog.i("Direct quiet-mode request refused; using the system dialog", e)
                        false
                    }
                // Give a honored direct request a moment before falling back
                // to the user-visible confirmation.
                val unpausedDirectly =
                    requested &&
                        withContext(Dispatchers.IO) {
                            repeat(8) {
                                if (!runCatching { manager.isQuietModeEnabled(user) }.getOrDefault(true)) {
                                    return@withContext true
                                }
                                kotlinx.coroutines.delay(250)
                            }
                            false
                        }
                if (unpausedDirectly) {
                    CapsuleLog.i("The work profile resumed without a confirmation")
                } else {
                    // Launch the bridge probe INTO the paused profile: Android
                    // intercepts the launch and offers the system "Unpause
                    // work apps?" dialog, which lifts quiet mode on
                    // confirmation — the same path launchers use.
                    CapsuleLog.i("Triggering the system unpause dialog for the work profile")
                    withContext(Dispatchers.Main) { launchPausedProfileProbe() }
                }

                // The dialog belongs to the user; allow a slow answer. Any
                // outcome must end in either a bound list or the banner.
                val unpaused =
                    withContext(Dispatchers.IO) {
                        repeat(60) {
                            if (!runCatching { manager.isQuietModeEnabled(user) }.getOrDefault(true)) {
                                return@withContext true
                            }
                            kotlinx.coroutines.delay(500)
                        }
                        runCatching { !manager.isQuietModeEnabled(user) }.getOrDefault(false)
                    }
                // Leaving quiet mode boots the profile user asynchronously:
                // cross-profile resolution keeps failing for a moment, and a
                // probe fired into that window dead-ends on the "not
                // reachable" banner. Wait for the profile to resolve first
                // (this is the wait the V8 watcher dropped by accident).
                if (unpaused) {
                    withContext(Dispatchers.IO) {
                        repeat(40) {
                            if (Utility.resolvesInOtherProfile(this@MainActivity)) {
                                return@withContext
                            }
                            kotlinx.coroutines.delay(500)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    resumeWatchJob = null
                    if (unpaused) {
                        CapsuleLog.i("The work profile is running again; continuing the bind chain")
                        lastWorkProbeAt = 0
                        workPaused.value = false
                        workUnreachable.value = false
                        connectWorkService()
                    } else if (Utility.isWorkProfilePaused(this@MainActivity)) {
                        CapsuleLog.w("The work profile did not resume; keeping the paused banner")
                    }
                }
            }
    }

    /**
     * Probes the bridge while the profile is paused: the launch itself is
     * intercepted by the system, which shows the unpause confirmation.
     * Cross-profile resolution can briefly fail while the profile user is
     * stopped, so it is retried instead of dead-ending the resume flow.
     */
    private fun launchPausedProfileProbe() {
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        refreshScope.launch {
            val resolved =
                withContext(Dispatchers.IO) {
                    repeat(5) {
                        try {
                            Utility.transferIntentToProfileUnsigned(this@MainActivity, intent)
                            return@withContext true
                        } catch (e: IllegalStateException) {
                            kotlinx.coroutines.delay(400)
                        }
                    }
                    false
                }
            if (!resolved) {
                // The watcher times out and lands on the banner.
                CapsuleLog.w("Could not launch the unpause probe; the system dialog will not appear")
                return@launch
            }
            AuthenticationUtility.signIntent(intent)
            lastWorkProbeAt = 0
            tryStartWorkService.launch(intent)
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
        val work = serviceWork ?: return
        refreshScope.launch {
            val granted =
                withContext(Dispatchers.IO) {
                    runCatching { work.hasInstallPermission() }.getOrDefault(false)
                }
            if (granted) {
                selectApk.launch(null)
                return@launch
            }
            // Ask for the work profile's unknown-sources consent BEFORE any
            // file is in flight: granting it restarts this package, which is
            // harmless while no picker or install stream is open. Once the
            // consent exists, the menu opens the picker straight away.
            CapsuleLog.i("Requesting the work profile install permission before the file picker")
            val intent = Intent(DummyActivity.OPEN_SETTINGS_IN_PROFILE)
            intent.putExtra("settings_action", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            try {
                Utility.transferIntentToProfile(this@MainActivity, intent)
            } catch (e: IllegalStateException) {
                CapsuleLog.w("Could not open the work profile install permission screen", e)
                return@launch
            }
            installPermissionLauncher.launch(intent)
        }
    }

    private fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val work = serviceWork ?: return
        CapsuleLog.i("Selected APK $uri for installation into the work profile")
        val proxy = UriForwardProxy(applicationContext, uri)
        try {
            work.installApk(
                proxy,
                object : IAppInstallCallback.Stub() {
                    override fun callback(result: Int) {
                        runOnUiThread { installResultCallback(result) }
                    }
                },
            )
        } catch (e: android.os.RemoteException) {
            CapsuleLog.w("Failed to forward the selected APK to the work profile", e)
        }
    }

    /** Reports an APK install result and refreshes the lists on success. */
    private fun installResultCallback(result: Int) {
        if (result == android.app.Activity.RESULT_OK) {
            refreshScope.launch {
                snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_success))
            }
            workListState.value?.refresh()
            mainListState.value?.refresh()
        } else {
            refreshScope.launch {
                snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_fail))
            }
        }
    }

    /** Opens the system App Info screen of [app] in its own profile. */
    fun openAppDetails(
        app: ApplicationInfoWrapper,
        isRemote: Boolean,
    ) {
        if (!isRemote) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", app.packageName, null),
                    ),
                )
            } catch (e: android.content.ActivityNotFoundException) {
                CapsuleLog.w("The app details screen could not be opened", e)
            }
            return
        }
        val intent = Intent(DummyActivity.OPEN_APP_DETAILS_IN_PROFILE)
        intent.putExtra("package", app.packageName)
        try {
            Utility.transferIntentToProfile(this, intent)
            startActivity(intent)
        } catch (e: IllegalStateException) {
            CapsuleLog.w("Could not open the app details in the work profile", e)
        }
    }

    fun openDocumentsUi() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(null, "vnd.android.document/root")
        startActivity(intent)
    }

    fun openSettings() {
        // The settings screen reads the work-profile bridge live from
        // WorkServiceHolder; a binder snapshot passed through the intent
        // would go stale the moment the bridge rebinds.
        startActivity(Intent(this, SettingsActivity::class.java))
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
            // A list that is still loading is transiently empty; publishing
            // that as the badge set would blank every "installed in the other
            // profile" checkmark until the fresh data lands.
            if (mainState.loading) return@LaunchedEffect
            val set = mainState.apps.map { it.packageName }.toSet()
            if (set != mainPackages) {
                mainPackages = set
                persistCachedPackages(isWork = false, set)
            }
        }
        LaunchedEffect(workState?.apps) {
            val state = workState ?: return@LaunchedEffect
            if (state.loading) return@LaunchedEffect
            val set = state.apps.map { it.packageName }.toSet()
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
                            onOpenAppInfo = { app -> openAppDetails(app, isRemote = false) },
                        )
                    }
                    else -> {
                        val state = workState
                        if (state != null) {
                            AppListPage(
                                state,
                                otherPackages = { mainPackages },
                                cloneAvailable = true,
                                onOpenAppInfo = { app -> openAppDetails(app, isRemote = true) },
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

        /** After this long an in-flight work bind is considered lost. */
        private const val WORK_BIND_STALE_MS = 20_000L

        /** The main bind watchdog: timeout and retry budget. */
        private const val BIND_WATCHDOG_TIMEOUT_MS = 2_500L
        private const val MAX_BIND_WATCHDOG_RETRIES = 3

        /** Auto-retry budget for a work profile that is only booting. */
        private const val UNREACHABLE_RETRIES = 5
        private const val UNREACHABLE_RETRY_INTERVAL_MS = 4_000L

        // Bind-chain pacing is process-wide on purpose: provisioning the
        // profile regenerates SystemUI overlays, whose CONFIG_ASSETS_PATHS
        // change relaunches this activity mid-chain. Instance fields would
        // reset with the relaunch and start a duplicate chain, stranding a
        // second translucent bridge activity on top of the UI.
        private var lastMainBindAt = 0L
        private var lastWorkProbeAt = 0L

        // True while a START_SERVICE bridge launch is pending its result.
        private var workBindInFlight = false
        private var workBindLaunchedAt = 0L
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
