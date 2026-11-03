package capsule.profile.ui.main

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherApps
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import capsule.profile.R
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.ICapsuleService
import capsule.profile.ui.DummyActivity
import capsule.profile.ui.MainActivity
import capsule.profile.ui.ResumeMethod
import capsule.profile.ui.applist.AppListState
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

/**
 * Non-UI coordinator of the main screen: owns the two profile service bind
 * chains, the app list state holders, in-place recovery, the work-bridge
 * heartbeat, package change events and the app-bar menu actions. The
 * composables read its Compose state directly; the hosting [MainActivity]
 * forwards activity results and lifecycle callbacks into it. Its lifetime is
 * the activity instance, exactly like the fields it replaced.
 */
class MainController(
    private val activity: MainActivity,
) {
    private var probingWorkService = false

    var serviceMain: ICapsuleService? = null
        private set

    var serviceWork: ICapsuleService? = null
        private set

    // List states, published to the Compose UI once the services are bound.
    // The work state outlives work-service rebinds: only its binder swaps.
    val mainListState = mutableStateOf<AppListState?>(null)
    val workListState = mutableStateOf<AppListState?>(null)
    val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared with the Compose scaffold so non-composable code (install
    // callbacks, freeze results) can show in-app messages.
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()

    /**
     * Whether hidden and disabled apps are listed. A plain field on purpose:
     * every flip coincides with a state change that recomposes the menu
     * anyway, and the list providers read it at call time.
     */
    var showAll = false

    // Compose-visible service state.
    val servicesReady = mutableStateOf(false)

    // Set once the main-profile service binder is connected; the UI can run
    // with the work profile still binding or paused.
    val mainServiceReady = mutableStateOf(false)

    // Whether the in-app settings pane is shown instead of the app lists.
    // The settings controller flips it; the main screen renders the pane.
    val showSettings = mutableStateOf(false)

    // Work-profile status for the in-app banner: paused (Work Mode off),
    // present but unreachable (managed by another app / Capsule removed), or
    // absent entirely (nothing provisioned; the work tab offers setup).
    val workPaused = mutableStateOf(false)
    val workUnreachable = mutableStateOf(false)
    val workAbsent = mutableStateOf(false)

    // True while a lost work bridge is being recovered quietly, so the UI
    // keeps showing the last list instead of the placeholder.
    val workReconnecting = mutableStateOf(false)

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

    // Periodic oneway probe that keeps the work-profile bridge lease alive
    // while this UI is visible, and detects its death within one interval.
    private var heartbeatJob: Job? = null

    // Package change events from both profiles drive the list refreshes
    // while the UI is visible, replacing refresh churn on reconnects.
    private var packageEventsCallback: LauncherApps.Callback? = null
    private var packageEventsJob: Job? = null
    private val packageEventsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Watches the profile set itself: a profile deleted or added while the
    // UI is open must re-evaluate the work tab immediately, instead of
    // waiting for the next resume (the state used to stick on the
    // "unreachable" banner after deleting a foreign profile — there was no
    // bridge whose death could have triggered the recovery).
    private var profileEventsReceiver: android.content.BroadcastReceiver? = null

    // Guards against overlapping in-place recoveries launched from onResume.
    private var recoveryInFlight = false

    /** Application context for caches and file proxies. */
    val appContext: Context
        get() = activity.applicationContext

    fun init() {
        val hasSetup = LocalStorageManager.getBoolean(LocalStorageManager.PREF_HAS_SETUP)
        val profileExists = Utility.getWorkProfileUserHandle(activity) != null

        // Resolution-based checks are unreliable while a profile is paused,
        // so "does a profile exist" is answered from the user list only.
        workAbsent.value = !hasSetup && !profileExists
        if (!hasSetup && !profileExists) {
            // First run with no profile at all.
            activity.launchSetupWizard()
        } else if (!hasSetup && Utility.isForeignWorkProfilePresent(activity)) {
            // A profile exists but Capsule cannot own it: it is managed by
            // another app, and provisioning is blocked until it is gone. That
            // blocker belongs on the wizard, where all attention is on it.
            activity.launchSetupWizard()
        }

        // Bind the main service in every case so the app stays usable behind
        // the wizard; the work side is decided once the bind completes.
        SettingsManager.applyAll()
        bindServices()
    }

    fun setupWizardCb(result: Boolean) {
        if (result) {
            init()
        } else {
            // Canceled: stay in the app. The work tab offers the setup again
            // instead of kicking the user out to the launcher.
            workAbsent.value = Utility.getWorkProfileUserHandle(activity) == null
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
        (activity.application as capsule.profile.CapsuleApplication).bindCapsuleService(
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
                if (!mainServiceReady.value && !activity.isFinishing) {
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
        val profileExists = Utility.getWorkProfileUserHandle(activity) != null
        if (!LocalStorageManager.getBoolean(LocalStorageManager.PREF_HAS_SETUP) && !profileExists) {
            // Nothing was ever provisioned: the work tab offers the setup.
            showWorkAbsent()
            return
        }
        if (!LocalStorageManager.getBoolean(LocalStorageManager.PREF_HAS_SETUP) &&
            Utility.isForeignWorkProfilePresent(activity)
        ) {
            // A profile managed by another app blocks provisioning for good:
            // offer the setup instead of probing something we can never
            // reach — the wizard explains the blocker.
            showWorkAbsent()
            return
        }
        workAbsent.value = false
        if (Utility.isWorkProfilePaused(activity)) {
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

    /** Transitions the UI to "no profile, offer the setup". */
    private fun showWorkAbsent() {
        serviceWork = null
        WorkServiceHolder.update(null)
        servicesReady.value = false
        workReconnecting.value = false
        workListState.value = null
        workPaused.value = false
        workUnreachable.value = false
        workAbsent.value = true
    }

    private fun showWorkBanner(paused: Boolean) {
        serviceWork = null
        WorkServiceHolder.update(null)
        servicesReady.value = false
        workReconnecting.value = false
        workListState.value = null
        workPaused.value = paused
        workUnreachable.value = !paused
    }

    private fun tryStartWorkService() {
        // Probe the work profile: RESULT_CANCELED means Work Mode is off.
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            Utility.transferIntentToProfile(activity, intent)
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
        activity.launchWorkProbe(intent)
    }

    /** The bridge could not be resolved or the probe was refused: classify. */
    private fun onWorkServiceUnreachable() {
        when {
            Utility.isWorkProfilePaused(activity) -> {
                CapsuleLog.i("Work profile is paused; showing the paused banner")
                showWorkBanner(paused = true)
            }

            Utility.isForeignWorkProfilePresent(activity) -> {
                // The existing profile can never be ours (managed by another
                // app), or our own profile is gone and a foreign one took its
                // place: offer the setup; the wizard shows the blocker.
                CapsuleLog.w("The work profile is managed by another app; offering the setup")
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                showWorkAbsent()
            }

            Utility.getWorkProfileUserHandle(activity) != null -> {
                // The profile resolves but our own instance inside it does
                // not: it may be removed, or simply still booting after Work
                // Mode came back. Give the boot a few bounded retries before
                // settling on the banner.
                CapsuleLog.w("Work profile exists but cannot be reached yet; showing the banner and retrying")
                showWorkBanner(paused = false)
                scheduleUnreachableRetry()
            }

            else -> {
                // No other profile in this group at all, e.g. it was deleted
                // from the system settings: offer the setup instead of exiting.
                CapsuleLog.w("No work profile found; offering the setup")
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                showWorkAbsent()
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
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        CapsuleLog.i("Retrying the work profile connection")
                        connectWorkService()
                    }
                }
            }
    }

    fun tryStartWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
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
        if (Utility.isWorkProfilePaused(activity)) {
            probingWorkService = false
            CapsuleLog.i("Work profile is paused; switching to the paused banner")
            showWorkBanner(paused = true)
            return
        }

        // One retry with a fresh pairing heals the lost-key case.
        if (!probingWorkService && Utility.resolvesInOtherProfile(activity)) {
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
        try {
            Utility.transferIntentToProfile(activity, intent)
        } catch (e: IllegalStateException) {
            // The profile vanished between the probe and the bind.
            CapsuleLog.w("The work profile disappeared before the bind", e)
            workBindInFlight = false
            onWorkServiceUnreachable()
            return
        }
        activity.launchWorkBind(intent)
    }

    fun bindWorkServiceCb(result: ActivityResult) {
        workBindInFlight = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val extra = result.data!!.getBundleExtra("extra")
            val binder = extra!!.getBinder("service")
            serviceWork = ICapsuleService.Stub.asInterface(binder)
            CapsuleLog.i("Work profile service bound")
            if (!LocalStorageManager.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
                // Adopting an existing profile (e.g. after flags were reset
                // while the profile survived): setup counts as done.
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            }
            attachWorkDeathRecipient()
            registerStartActivityProxies()
            servicesReady.value = true
            WorkServiceHolder.update(serviceWork)
            workReconnecting.value = false
            unreachableRetryJob?.cancel()
            // The state holder outlives the rebind: swap the binder and
            // reload, so the visible list (and any open sheet) survives.
            val state = workListState.value
            if (state != null) {
                state.service = serviceWork!!
                state.refresh()
            } else {
                workListState.value = createWorkListState()
                workListState.value?.refresh()
            }
        } else {
            CapsuleLog.w("Failed to bind the work profile service (result=${result.resultCode})")
            onWorkServiceUnreachable()
        }
    }

    private fun createWorkListState(): AppListState =
        AppListState(
            service = serviceWork!!,
            isRemote = true,
            scope = refreshScope,
            onMessage = ::showListMessage,
            onOtherService = { serviceMain },
            showAllProvider = { showAll },
            refreshPeer = { mainListState.value?.refresh() },
            cache = AppListCache(activity.applicationContext, isRemote = true),
        )

    /** Shows a list snackbar from non-composable code paths. */
    fun showListMessage(res: Int, arg: String?) {
        val message = if (arg != null) activity.getString(res, arg) else activity.getString(res)
        refreshScope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** Reacts to the work service process dying (Work Mode off, removal...). */
    private fun attachWorkDeathRecipient() {
        detachWorkDeathRecipient()
        val recipient =
            IBinder.DeathRecipient {
                activity.runOnUiThread { onWorkServiceDied() }
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
        silentlyReconnectWorkService()
    }

    /**
     * Recovers a lost work bridge in place. The visible list is kept, so a
     * brief reconnect neither flashes the placeholder nor closes an open
     * sheet; the banner only settles in once the chain classifies the
     * profile as paused, unreachable or gone.
     */
    fun silentlyReconnectWorkService() {
        if (workReconnecting.value && !servicesReady.value) return
        workReconnecting.value = true
        CapsuleLog.w("Reconnecting the work profile service quietly")
        detachWorkDeathRecipient()
        serviceWork = null
        WorkServiceHolder.update(null)
        servicesReady.value = false
        // Probes launched from the background are silently blocked by the
        // system; while we are not resumed, onResume owns the recovery.
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            connectWorkService()
        }
        refreshScope.launch {
            delay(SILENT_RECONNECT_TIMEOUT_MS)
            if (workReconnecting.value && !servicesReady.value) {
                // Nothing concluded in time: classify and settle the banner.
                workReconnecting.value = false
                onWorkServiceUnreachable()
            }
        }
    }

    /** Keeps the work-profile service out of the freezer while the UI is up. */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob =
            refreshScope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!servicesReady.value || !WorkServiceHolder.available) continue
                    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                    if (!WorkServiceHolder.probe()) {
                        CapsuleLog.w("The work bridge missed a heartbeat; recovering")
                        silentlyReconnectWorkService()
                    }
                }
            }
    }

    /** Subscribes to package changes in both profiles while the UI is visible. */
    private fun registerPackageEvents() {
        if (packageEventsCallback == null) {
            val callback =
                object : LauncherApps.Callback() {
                    override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = onPackageEvent()

                    override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = onPackageEvent()

                    override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = onPackageEvent()

                    override fun onPackagesAvailable(
                        packageNames: Array<out String>?,
                        user: android.os.UserHandle?,
                        replacing: Boolean,
                    ) = onPackageEvent()

                    override fun onPackagesUnavailable(
                        packageNames: Array<out String>?,
                        user: android.os.UserHandle?,
                        replacing: Boolean,
                    ) = onPackageEvent()
                }
            try {
                activity.getSystemService(LauncherApps::class.java).registerCallback(callback, packageEventsHandler)
                packageEventsCallback = callback
            } catch (e: Exception) {
                // Without events the lists simply refresh on resume, as before.
                CapsuleLog.w("Could not subscribe to package events", e)
            }
        }
        if (profileEventsReceiver == null) {
            val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        CapsuleLog.i("The profile set changed (${intent.action}); re-evaluating")
                        when (intent.action) {
                            Intent.ACTION_MANAGED_PROFILE_REMOVED,
                            Intent.ACTION_MANAGED_PROFILE_ADDED,
                            -> {
                                if (!servicesReady.value) {
                                    // No bridge to lose: classify the new state
                                    // right away (covers the no-bridge banner
                                    // case the death recipient cannot see).
                                    onWorkServiceUnreachable()
                                }
                                // A live bridge reacts through its death
                                // recipient when its process is killed.
                            }
                        }
                    }
                }
            val filter =
                android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                    addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                }
            try {
                activity.registerReceiver(receiver, filter)
                profileEventsReceiver = receiver
            } catch (e: Exception) {
                CapsuleLog.w("Could not subscribe to profile events", e)
            }
        }
    }

    private fun unregisterPackageEvents() {
        packageEventsCallback?.let {
            runCatching { activity.getSystemService(LauncherApps::class.java).unregisterCallback(it) }
        }
        packageEventsCallback = null
        packageEventsJob?.cancel()
        packageEventsJob = null
        profileEventsReceiver?.let {
            runCatching { activity.unregisterReceiver(it) }
        }
        profileEventsReceiver = null
    }

    /**
     * Debounced refresh after any package change: installs and removals
     * arrive as bursts, and both lists are reloaded because each list's
     * badges mirror the other profile.
     */
    private fun onPackageEvent() {
        packageEventsJob?.cancel()
        packageEventsJob =
            refreshScope.launch {
                delay(PACKAGE_EVENT_DEBOUNCE_MS)
                mainListState.value?.refresh()
                workListState.value?.refresh()
            }
    }

    private fun registerStartActivityProxies() {
        try {
            serviceMain!!.setStartActivityProxy(
                object : capsule.profile.services.IStartActivityProxy.Stub() {
                    override fun startActivity(intent: Intent) {
                        activity.startActivity(intent)
                    }
                },
            )

            serviceWork!!.setStartActivityProxy(
                object : capsule.profile.services.IStartActivityProxy.Stub() {
                    override fun startActivity(intent: Intent) {
                        // Resolve the component in the other profile explicitly;
                        // a full intent may fail PackageManager resolution.
                        val dummyIntent = Intent(intent.action)
                        Utility.transferIntentToProfileUnsigned(activity, dummyIntent)
                        intent.component = dummyIntent.component
                        activity.startActivity(intent)
                    }
                },
            )
        } catch (e: android.os.RemoteException) {
            // The work service died right after the binder was handed over.
            // Crashing here (the old behavior) would take the whole UI down;
            // the quiet reconnect owns the recovery instead.
            CapsuleLog.w("Could not register the activity proxies; the bridge died", e)
            silentlyReconnectWorkService()
        }
    }

    fun onResumed() {
        startHeartbeat()
        registerPackageEvents()

        if (workAbsent.value) {
            if (Utility.getWorkProfileUserHandle(activity) != null) {
                // A profile appeared while we were away: evaluate it.
                workAbsent.value = false
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            } else {
                return
            }
        }

        if (!servicesReady.value) {
            // A profile that existed when we paused may be gone now: leaving
            // a settled banner up would hide the setup offer until a full
            // app restart (seen after deleting a foreign profile in V14).
            if (!LocalStorageManager.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) &&
                Utility.getWorkProfileUserHandle(activity) == null &&
                (workUnreachable.value || workPaused.value)
            ) {
                LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                showWorkAbsent()
                return
            }
            // While the setup wizard owns the flow there is nothing to bind;
            // recovering here would probe for a profile that does not exist
            // yet and dead-end with a misleading banner.
            if (LocalStorageManager.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) ||
                (
                    !LocalStorageManager.getBoolean(LocalStorageManager.PREF_HAS_SETUP) &&
                        Utility.getWorkProfileUserHandle(activity) == null
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

        // States change while we are away (shortcuts, batch freeze, installs):
        // reload both lists on every resume. The work service is confirmed
        // through the oneway probe: a synchronous ping() would kill its
        // process if the freezer got to it while we were away.
        if (recoveryInFlight) return
        recoveryInFlight = true
        refreshScope.launch {
            val mainAlive = serviceMain != null
            val workAlive = serviceWork == null || WorkServiceHolder.ensureAlive()
            withContext(Dispatchers.Main) {
                recoveryInFlight = false
                if (activity.isFinishing) return@withContext
                if (!mainAlive || !workAlive) {
                    // The services died while we were away. Recover IN PLACE:
                    // a restart via startActivity never worked here, because
                    // the singleTask launch intent is delivered to this very
                    // instance, which is about to finish - the app would
                    // simply close.
                    recoverServicesInPlace()
                } else {
                    mainListState.value?.refresh()
                    workListState.value?.refresh()
                }
            }
        }
    }

    fun onPaused() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterPackageEvents()
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
        // The work list state survives: it only swaps its binder on rebind.
        bindServices()
    }

    /** Final teardown; the activity skips it while recreating. */
    fun onDestroyed() {
        detachWorkDeathRecipient()
        refreshScope.cancel()
        WorkServiceHolder.update(null)
        Utility.killCapsuleServices(serviceMain, serviceWork)
    }

    // ==== Menu actions ====

    /** Resumes the paused work profile through the configured method. */
    fun resumeWorkProfile() {
        val user =
            Utility.getPausedWorkProfileHandle(activity)
                ?: run {
                    // Not (or no longer) in quiet mode: probe directly.
                    lastWorkProbeAt = 0
                    connectWorkService()
                    return
                }
        if (resumeWatchJob?.isActive == true) return
        val method = ResumeMethod.fromId(SettingsManager.getResumeMethodId())
        CapsuleLog.i("Resume requested with the configured method=${method.id}")
        runResumeMethod(method)
    }

    /**
     * Runs a [ResumeMethod]; when it was dispatched, watches for the user
     * to answer the system dialog and logs the outcome.
     */
    fun runResumeMethod(method: ResumeMethod) {
        if (resumeWatchJob?.isActive == true) return
        val dispatched = method.run(activity)
        if (!dispatched) {
            CapsuleLog.w("RESUME TEST method=${method.id} result=API_FAILED")
            showListMessage(R.string.resume_method_failed, null)
            return
        }
        CapsuleLog.i("RESUME TEST method=${method.id} dispatched; waiting for the profile")
        startResumeWatcher(method)
    }

    /**
     * Waits for the user to answer the system resume dialog, then continues
     * the bind chain once the profile has finished booting. Every outcome is
     * logged with the method id so the next system log identifies the path
     * that works on this device.
     */
    private fun startResumeWatcher(method: ResumeMethod) {
        if (resumeWatchJob?.isActive == true) return
        resumeWatchJob =
            refreshScope.launch {
                val started = SystemClock.elapsedRealtime()
                val manager = activity.getSystemService(UserManager::class.java)
                val user = Utility.getWorkProfileUserHandle(activity) ?: return@launch
                // The dialog belongs to the user; allow a slow answer. Any
                // outcome must end in either a bound list or the banner.
                val unpaused =
                    withContext(Dispatchers.IO) {
                        repeat(90) {
                            if (!runCatching { manager.isQuietModeEnabled(user) }.getOrDefault(true)) {
                                return@withContext true
                            }
                            delay(500)
                        }
                        runCatching { !manager.isQuietModeEnabled(user) }.getOrDefault(false)
                    }
                val waited = SystemClock.elapsedRealtime() - started
                if (!unpaused) {
                    withContext(Dispatchers.Main) {
                        resumeWatchJob = null
                    }
                    CapsuleLog.w("RESUME TEST method=${method.id} result=NOT_RESUMED after $waited ms")
                    return@launch
                }
                CapsuleLog.i("RESUME TEST method=${method.id} quiet mode lifted after $waited ms")
                // Leaving quiet mode boots the profile user asynchronously:
                // cross-profile resolution keeps failing for a moment, and a
                // probe fired into that window dead-ends on the "not
                // reachable" banner. Wait for the profile to resolve first.
                withContext(Dispatchers.IO) {
                    repeat(40) {
                        if (Utility.resolvesInOtherProfile(activity)) {
                            return@withContext
                        }
                        delay(500)
                    }
                }
                withContext(Dispatchers.Main) {
                    resumeWatchJob = null
                    CapsuleLog.i(
                        "RESUME TEST method=${method.id} result=RESUMED; continuing the bind chain",
                    )
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
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: android.content.ActivityNotFoundException) {
            CapsuleLog.w("The settings home screen could not be opened", e)
        }
    }

    /** Offers the setup wizard again from the work tab placeholder. */
    fun startSetupWizard() {
        activity.launchSetupWizard()
    }

    fun freezeAll() {
        val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        intent.component = ComponentName(activity, DummyActivity::class.java)
        // The freeze pass runs in the work profile; report through the
        // snackbar here instead of its toast.
        intent.putExtra("from_app", true)
        activity.startActivity(intent)
        mainListState.value?.refresh()
        workListState.value?.refresh()
        refreshScope.launch {
            delay(2000)
            mainListState.value?.refresh()
            workListState.value?.refresh()
            snackbarHostState.showSnackbar(activity.getString(R.string.freeze_all_success))
        }
    }

    fun createFreezeAllShortcut() {
        val launchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        launchIntent.component = ComponentName(activity, DummyActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ok =
            Utility.createLauncherShortcut(
                activity,
                launchIntent,
                Icon.createWithResource(activity, R.mipmap.ic_freeze),
                "capsule-freeze-all",
                activity.getString(R.string.freeze_all_shortcut),
            )
        if (!ok) {
            refreshScope.launch {
                snackbarHostState.showSnackbar(activity.getString(R.string.unsupported_launcher))
            }
        }
    }

    fun installApkToProfile() {
        val work = serviceWork ?: return
        refreshScope.launch {
            val granted =
                withContext(Dispatchers.IO) {
                    WorkServiceHolder.ensureAlive() &&
                        runCatching { work.hasInstallPermission() }.getOrDefault(false)
                }
            if (granted) {
                activity.launchApkPicker()
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
                Utility.transferIntentToProfile(activity, intent)
            } catch (e: IllegalStateException) {
                CapsuleLog.w("Could not open the work profile install permission screen", e)
                return@launch
            }
            activity.launchInstallPermissionSettings(intent)
        }
    }

    /**
     * Returning from the work profile's unknown-sources settings screen:
     * when the consent was granted, this ROM usually restarts the whole
     * package (the app op change kills it) and this method never runs.
     * It runs when the consent was declined, or on ROMs that do not restart
     * us — in the latter case the picker can open right away.
     */
    fun onInstallPermissionResult() {
        val work = serviceWork
        refreshScope.launch {
            if (work == null) {
                // The bridge was lost while the settings screen was up;
                // the reconnect paths own the recovery.
                snackbarHostState.showSnackbar(activity.getString(R.string.install_app_to_profile_fail))
                return@launch
            }
            val granted =
                withContext(Dispatchers.IO) {
                    WorkServiceHolder.ensureAlive() &&
                        runCatching { work.hasInstallPermission() }.getOrDefault(false)
                }
            if (granted) {
                activity.launchApkPicker()
            } else {
                snackbarHostState.showSnackbar(activity.getString(R.string.install_app_to_profile_fail))
            }
        }
    }

    fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val work = serviceWork ?: return
        CapsuleLog.i("Selected APK $uri for installation into the work profile")
        val proxy = UriForwardProxy(activity.applicationContext, uri)
        refreshScope.launch {
            val sent =
                withContext(Dispatchers.IO) {
                    if (!WorkServiceHolder.ensureAlive()) return@withContext false
                    try {
                        work.installApk(
                            proxy,
                            object : IAppInstallCallback.Stub() {
                                override fun callback(result: Int) {
                                    activity.runOnUiThread { installResultCallback(result) }
                                }
                            },
                        )
                        true
                    } catch (e: android.os.RemoteException) {
                        CapsuleLog.w("Failed to forward the selected APK to the work profile", e)
                        false
                    }
                }
            if (!sent) {
                snackbarHostState.showSnackbar(activity.getString(R.string.install_app_to_profile_fail))
            }
        }
    }

    /** Reports an APK install result and refreshes the lists on success. */
    private fun installResultCallback(result: Int) {
        if (result == Activity.RESULT_OK) {
            refreshScope.launch {
                snackbarHostState.showSnackbar(activity.getString(R.string.install_app_to_profile_success))
            }
            workListState.value?.refresh()
            mainListState.value?.refresh()
        } else {
            refreshScope.launch {
                snackbarHostState.showSnackbar(activity.getString(R.string.install_app_to_profile_fail))
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
                activity.startActivity(
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
            Utility.transferIntentToProfile(activity, intent)
            activity.startActivity(intent)
        } catch (e: IllegalStateException) {
            CapsuleLog.w("Could not open the app details in the work profile", e)
        }
    }

    fun openDocumentsUi() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(null, "vnd.android.document/root")
        activity.startActivity(intent)
    }

    /** Loads the persisted package set of one profile for instant badges. */
    fun loadCachedPackages(isWork: Boolean): Set<String> =
        LocalStorageManager
            .getStringList(
                if (isWork) {
                    LocalStorageManager.PREF_CACHED_WORK_PACKAGES
                } else {
                    LocalStorageManager.PREF_CACHED_MAIN_PACKAGES
                },
            ).toSet()

    fun persistCachedPackages(
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

        /** Work-bridge heartbeat spacing; well under the freeze debounce. */
        private const val HEARTBEAT_INTERVAL_MS = 3_000L

        /** Coalesces package event bursts into one list refresh. */
        private const val PACKAGE_EVENT_DEBOUNCE_MS = 700L

        /** Upper bound for a quiet reconnect before the banner settles in. */
        private const val SILENT_RECONNECT_TIMEOUT_MS = 15_000L

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
