package capsule.profile.ui

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherApps
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import capsule.profile.BuildConfig
import capsule.profile.R
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.ICapsuleService
import capsule.profile.ui.applist.AppListPage
import capsule.profile.ui.applist.AppListState
import capsule.profile.ui.components.AppDialog
import capsule.profile.ui.components.AppSearchField
import capsule.profile.ui.components.AppSnackbarHost
import capsule.profile.ui.components.AppTextField
import capsule.profile.ui.components.DialogButton
import capsule.profile.ui.components.GroupedCard
import capsule.profile.ui.components.HtmlText
import capsule.profile.ui.components.SectionHeader
import capsule.profile.ui.components.SettingActionRow
import capsule.profile.ui.components.SettingToggleRow
import capsule.profile.ui.icons.ac_unit
import capsule.profile.ui.icons.add
import capsule.profile.ui.icons.arrow_back
import capsule.profile.ui.icons.bolt
import capsule.profile.ui.icons.bug_report
import capsule.profile.ui.icons.code
import capsule.profile.ui.icons.contactless
import capsule.profile.ui.icons.done
import capsule.profile.ui.icons.folder
import capsule.profile.ui.icons.home
import capsule.profile.ui.icons.info
import capsule.profile.ui.icons.lock
import capsule.profile.ui.icons.lock_clock
import capsule.profile.ui.icons.lock_open
import capsule.profile.ui.icons.more_vert
import capsule.profile.ui.icons.search
import capsule.profile.ui.icons.settings
import capsule.profile.ui.icons.shield_person
import capsule.profile.ui.icons.sync_alt
import capsule.profile.ui.icons.verified
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
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat

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
            val work = serviceWork
            refreshScope.launch {
                if (work == null) {
                    // The bridge was lost while the settings screen was up;
                    // the reconnect paths own the recovery.
                    snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_fail))
                    return@launch
                }
                val granted =
                    withContext(Dispatchers.IO) {
                        WorkServiceHolder.ensureAlive() &&
                            runCatching { work.hasInstallPermission() }.getOrDefault(false)
                    }
                if (granted) {
                    selectApk.launch(null)
                } else {
                    snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_fail))
                }
            }
        }

    // ==== Settings pane state (merged from the former SettingsActivity) ====

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
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) installCertificates(uris)
        }

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

    /** Whether the in-app settings pane is shown instead of the app lists. */
    private val showSettings = mutableStateOf(false)

    private lateinit var storage: LocalStorageManager

    private var probingWorkService = false

    private var serviceMain: ICapsuleService? = null
    private var serviceWork: ICapsuleService? = null

    // List states, published to the Compose UI once the services are bound.
    // The work state outlives work-service rebinds: only its binder swaps.
    private val mainListState = mutableStateOf<AppListState?>(null)
    private val workListState = mutableStateOf<AppListState?>(null)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared with the Compose scaffold so non-composable code (install
    // callbacks, freeze results) can show in-app messages.
    private val snackbarHostState = androidx.compose.material3.SnackbarHostState()

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

    // True while a lost work bridge is being recovered quietly, so the UI
    // keeps showing the last list instead of the placeholder.
    private val workReconnecting = mutableStateOf(false)

    // Notified when the work-profile service process dies, e.g. when the user
    // switches Work Mode off from quick settings while the app is open.
    private var workDeathRecipient: IBinder.DeathRecipient? = null

    // The quiet-mode resume watcher: waits for the user to answer the system
    // "Unpause work apps?" dialog and then continues the bind chain.
    private var resumeWatchJob: Job? = null

    // The unfreeze-method dialog opened from Settings.
    private var resumeMethodDialogOpen by mutableStateOf(false)

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
        } else if (!hasSetup && Utility.isForeignWorkProfilePresent(this)) {
            // A profile exists but Capsule cannot own it: it is managed by
            // another app, and provisioning is blocked until it is gone. That
            // blocker belongs on the wizard, where all attention is on it.
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
        val profileExists = Utility.getWorkProfileUserHandle(this) != null
        if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP) && !profileExists) {
            // Nothing was ever provisioned: the work tab offers the setup.
            showWorkAbsent()
            return
        }
        if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP) &&
            Utility.isForeignWorkProfilePresent(this)
        ) {
            // A profile managed by another app blocks provisioning for good:
            // offer the setup instead of probing something we can never
            // reach — the wizard explains the blocker.
            showWorkAbsent()
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

            Utility.isForeignWorkProfilePresent(this) -> {
                // The existing profile can never be ours (managed by another
                // app), or our own profile is gone and a foreign one took its
                // place: offer the setup; the wizard shows the blocker.
                CapsuleLog.w("The work profile is managed by another app; offering the setup")
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
                showWorkAbsent()
            }

            Utility.getWorkProfileUserHandle(this) != null -> {
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
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
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
        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (e: IllegalStateException) {
            // The profile vanished between the probe and the bind.
            CapsuleLog.w("The work profile disappeared before the bind", e)
            workBindInFlight = false
            onWorkServiceUnreachable()
            return
        }
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
            cache = AppListCache(applicationContext, isRemote = true),
        )

    /** Shows a list snackbar from non-composable code paths. */
    private fun showListMessage(res: Int, arg: String?) {
        val message = if (arg != null) getString(res, arg) else getString(res)
        refreshScope.launch { snackbarHostState.showSnackbar(message) }
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
        silentlyReconnectWorkService()
    }

    /**
     * Recovers a lost work bridge in place. The visible list is kept, so a
     * brief reconnect neither flashes the placeholder nor closes an open
     * sheet; the banner only settles in once the chain classifies the
     * profile as paused, unreachable or gone.
     */
    private fun silentlyReconnectWorkService() {
        if (workReconnecting.value && !servicesReady.value) return
        workReconnecting.value = true
        CapsuleLog.w("Reconnecting the work profile service quietly")
        detachWorkDeathRecipient()
        serviceWork = null
        WorkServiceHolder.update(null)
        servicesReady.value = false
        // Probes launched from the background are silently blocked by the
        // system; while we are not resumed, onResume owns the recovery.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
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
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                    if (!WorkServiceHolder.probe()) {
                        CapsuleLog.w("The work bridge missed a heartbeat; recovering")
                        silentlyReconnectWorkService()
                    }
                }
            }
    }

    fun getOtherService(isRemote: Boolean): ICapsuleService? =
        if (isRemote) serviceMain else serviceWork

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
                getSystemService(LauncherApps::class.java).registerCallback(callback, packageEventsHandler)
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
                registerReceiver(receiver, filter)
                profileEventsReceiver = receiver
            } catch (e: Exception) {
                CapsuleLog.w("Could not subscribe to profile events", e)
            }
        }
    }

    private fun unregisterPackageEvents() {
        packageEventsCallback?.let {
            runCatching { getSystemService(LauncherApps::class.java).unregisterCallback(it) }
        }
        packageEventsCallback = null
        packageEventsJob?.cancel()
        packageEventsJob = null
        profileEventsReceiver?.let {
            runCatching { unregisterReceiver(it) }
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
                        this@MainActivity.startActivity(intent)
                    }
                },
            )

            serviceWork!!.setStartActivityProxy(
                object : capsule.profile.services.IStartActivityProxy.Stub() {
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
            // The work service died right after the binder was handed over.
            // Crashing here (the old behavior) would take the whole UI down;
            // the quiet reconnect owns the recovery instead.
            CapsuleLog.w("Could not register the activity proxies; the bridge died", e)
            silentlyReconnectWorkService()
        }
    }

    override fun onResume() {
        super.onResume()

        startHeartbeat()
        registerPackageEvents()
        if (showSettings.value) reloadCerts()

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
            // A profile that existed when we paused may be gone now: leaving
            // a settled banner up would hide the setup offer until a full
            // app restart (seen after deleting a foreign profile in V14).
            if (!storage.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) &&
                Utility.getWorkProfileUserHandle(this) == null &&
                (workUnreachable.value || workPaused.value)
            ) {
                storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
                showWorkAbsent()
                return
            }
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
                if (isFinishing) return@withContext
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

    override fun onPause() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterPackageEvents()
        super.onPause()
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

    /** Resumes the paused work profile through the configured method. */
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
        val dispatched = method.run(this)
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
                val manager = getSystemService(UserManager::class.java)
                val user = Utility.getWorkProfileUserHandle(this@MainActivity) ?: return@launch
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
                        if (Utility.resolvesInOtherProfile(this@MainActivity)) {
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
            delay(2000)
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
                    WorkServiceHolder.ensureAlive() &&
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
        refreshScope.launch {
            val sent =
                withContext(Dispatchers.IO) {
                    if (!WorkServiceHolder.ensureAlive()) return@withContext false
                    try {
                        work.installApk(
                            proxy,
                            object : IAppInstallCallback.Stub() {
                                override fun callback(result: Int) {
                                    runOnUiThread { installResultCallback(result) }
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
                snackbarHostState.showSnackbar(getString(R.string.install_app_to_profile_fail))
            }
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
        // The settings pane reads the work-profile bridge live from
        // WorkServiceHolder; a binder snapshot passed around would go stale
        // the moment the bridge rebinds.
        showSettings.value = true
        reloadCerts()
    }

    // ==== Permission chains (merged from the former SettingsActivity) ====

    /**
     * Whether [gate] currently holds. Null means the work bridge is down, so
     * the answer is unknown and the chain must stop without claiming the
     * user declined anything.
     */
    private suspend fun isGateSatisfied(gate: PermissionGate): Boolean? {
        if (gate.workSide) {
            val work = serviceWork ?: return null
            if (!WorkServiceHolder.ensureAlive()) return null
            return try {
                withContext(Dispatchers.IO) {
                    when (gate) {
                        PermissionGate.WORK_ALL_FILES -> work.hasAllFileAccessPermission()
                        PermissionGate.WORK_OVERLAY -> work.hasSystemAlertPermission()
                        PermissionGate.WORK_USAGE -> work.hasUsageStatsPermission()
                        else -> true
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        return try {
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
        } catch (e: Exception) {
            false
        }
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
        refreshScope.launch {
            for (gate in chain) {
                val satisfied = isGateSatisfied(gate)
                if (satisfied == null) {
                    withContext(Dispatchers.Main) {
                        CapsuleLog.w("The work bridge is down; stopping the permission chain")
                        pendingGate = null
                        permissionChain = emptyList()
                        chainTarget = null
                        snackbarHostState.showSnackbar(getString(R.string.work_profile_unreachable_title))
                        if (!WorkServiceHolder.available) {
                            silentlyReconnectWorkService()
                        }
                    }
                    return@launch
                }
                if (!satisfied) {
                    withContext(Dispatchers.Main) {
                        if (gate == pendingGate) {
                            // Back from this very permission's screen without
                            // it being granted: the user declined; stop asking.
                            CapsuleLog.i("Permission ${gate.name} was declined; stopping the chain")
                            pendingGate = null
                            permissionChain = emptyList()
                            chainTarget = null
                        } else {
                            pendingGate = gate
                        }
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                CapsuleLog.i("Permission chain completed")
                pendingGate = null
                permissionChain = emptyList()
                chainTarget?.invoke()
                chainTarget = null
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
                pendingGate = null
                permissionChain = emptyList()
                chainTarget = null
            }
        } else {
            mainPermissionLauncher.launch(Intent(gate.settingsAction))
        }
    }

    // ==== Certificates (merged from the former SettingsActivity) ====

    /**
     * Reloads the installed certificate list from the work profile. The
     * bridge is probed first so the call can never land on a frozen process,
     * and the profile user may not have finished unlocking — its trust store
     * then reads empty — so that condition is retried briefly instead of
     * publishing a misleading "none installed".
     */
    private fun reloadCerts() {
        certLoadJob?.cancel()
        certLoadJob =
            refreshScope.launch {
                repeat(CERT_LOAD_ATTEMPTS) { attempt ->
                    val work = serviceWork
                    if (work == null && attempt == 0 && !WorkServiceHolder.available) {
                        // A dead bridge is usually back within a heartbeat;
                        // give it a chance before giving up on the list.
                        silentlyReconnectWorkService()
                    }
                    if (work != null) {
                        val result =
                            withContext(Dispatchers.IO) {
                                try {
                                    if (!WorkServiceHolder.ensureAlive()) return@withContext null
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
                    }
                    if (attempt < CERT_LOAD_ATTEMPTS - 1) {
                        delay(CERT_LOAD_RETRY_MS)
                    }
                }
                certs = null
            }
    }

    /** True while the work profile user exists but is still unlocking. */
    private fun isWorkUserLocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val manager = getSystemService(UserManager::class.java)
        val user = Utility.getWorkProfileUserHandle(this) ?: return false
        return !manager.isUserUnlocked(user)
    }

    /** Outcome of a certificate operation, split by failure cause. */
    private enum class CertOutcome {
        OK,
        BRIDGE_DOWN,
        REJECTED,
    }

    /**
     * Installs the picked certificate files into the profile store, one
     * after another. A dead bridge aborts the rest; rejected files are
     * counted and reported in the summary so a batch is never silently
     * half-finished.
     */
    private fun installCertificates(uris: List<Uri>) {
        val work = serviceWork
        if (work == null) {
            refreshScope.launch { showCertOutcome(CertOutcome.BRIDGE_DOWN) }
            return
        }
        certBusy = true
        refreshScope.launch {
            var installed = 0
            var rejected = 0
            var bridgeDown = false
            for (uri in uris) {
                val outcome =
                    withContext(Dispatchers.IO) {
                        try {
                            val bytes =
                                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: return@withContext CertOutcome.REJECTED
                            if (!WorkServiceHolder.ensureAlive()) {
                                return@withContext CertOutcome.BRIDGE_DOWN
                            }
                            // Only a survived call returning false blames the file.
                            if (work.installCaCert(bytes)) CertOutcome.OK else CertOutcome.REJECTED
                        } catch (e: Exception) {
                            CapsuleLog.w("Failed to install the certificate", e)
                            CertOutcome.BRIDGE_DOWN
                        }
                    }
                when (outcome) {
                    CertOutcome.OK -> {
                        installed++
                    }
                    CertOutcome.REJECTED -> {
                        rejected++
                    }
                    CertOutcome.BRIDGE_DOWN -> {
                        bridgeDown = true
                        break
                    }
                }
            }
            certBusy = false
            val message =
                when {
                    bridgeDown -> getString(R.string.work_bridge_unavailable)
                    rejected == 0 -> getString(R.string.cert_install_summary_ok, installed)
                    else -> getString(R.string.cert_install_summary_partial, installed, rejected)
                }
            snackbarHostState.showSnackbar(message)
            if (installed > 0) reloadCerts()
        }
    }

    /** Removes [cert] from the profile certificate store. */
    private fun removeCertificate(cert: InstalledCert) {
        val work = serviceWork
        if (work == null) {
            refreshScope.launch { showCertOutcome(CertOutcome.BRIDGE_DOWN) }
            return
        }
        certBusy = true
        refreshScope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    try {
                        if (!WorkServiceHolder.ensureAlive()) {
                            return@withContext CertOutcome.BRIDGE_DOWN
                        }
                        work.uninstallCaCert(cert.encoded)
                        CertOutcome.OK
                    } catch (e: Exception) {
                        CapsuleLog.w("Failed to remove the certificate", e)
                        CertOutcome.BRIDGE_DOWN
                    }
                }
            showCertOutcome(outcome)
            if (outcome == CertOutcome.OK) reloadCerts()
        }
    }

    /** Ends a certificate removal: clears the busy flag and reports. */
    private suspend fun showCertOutcome(outcome: CertOutcome) {
        certBusy = false
        val message =
            when (outcome) {
                CertOutcome.OK -> {
                    getString(R.string.cert_remove_success)
                }

                CertOutcome.BRIDGE_DOWN -> {
                    getString(R.string.work_bridge_unavailable)
                }

                CertOutcome.REJECTED -> {
                    getString(R.string.cert_remove_failed)
                }
            }
        snackbarHostState.showSnackbar(message)
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

    // ==== Compose UI ====

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        BackHandler(enabled = showSettings.value) { showSettings.value = false }

        if (showSettings.value) {
            SettingsPane(onBack = { showSettings.value = false })
            return
        }

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
                    onMessage = ::showListMessage,
                    onOtherService = { serviceWork },
                    showAllProvider = { showAll },
                    refreshPeer = { workListState.value?.refresh() },
                    cache = AppListCache(applicationContext, isRemote = false),
                ).also { mainListState.value = it }
            }
        // The work state is created by the activity when the bridge binds
        // and survives rebinds, so a brief reconnect never drops the sheet.
        val workState = workListState.value

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
                                Icon(more_vert, contentDescription = stringResource(R.string.more_options))
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
                            showAll = true
                            showAllConfirm = false
                            mainState.refresh()
                            workState?.refresh()
                        },
                    ),
            )
        }
    }

    /**
     * Selection dialog for the unfreeze method: tapping a row persists the
     * choice, and the Resume button runs it from then on. Unsupported
     * methods are disabled instead of hidden, so the full set stays
     * discoverable on every device.
     */
    @Composable
    private fun ResumeMethodPickerDialog(
        selected: ResumeMethod,
        onSelect: (ResumeMethod) -> Unit,
        onDismiss: () -> Unit,
    ) {
        AppDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.resume_method_title),
            dismiss = DialogButton(text = stringResource(R.string.cancel), onClick = onDismiss),
            content = {
                Column {
                    Text(
                        text = stringResource(R.string.resume_method_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        ResumeMethod.entries.forEach { method ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                onClick = { onSelect(method) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(method.labelRes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = stringResource(method.hintRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (method == selected) {
                                        Icon(
                                            imageVector = done,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

    // ==== Settings pane (merged from the former SettingsActivity) ====

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsPane(onBack: () -> Unit) {
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
                    SettingActionRow(
                        title = stringResource(R.string.settings_unfreeze_method),
                        desc = stringResource(resumeMethod.labelRes),
                        icon = lock_open,
                        onClick = { resumeMethodDialogOpen = true },
                    )
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

        if (resumeMethodDialogOpen) {
            ResumeMethodPickerDialog(
                selected = resumeMethod,
                onSelect = {
                    SettingsManager.setResumeMethodId(it.id)
                    resumeMethod = it
                },
                onDismiss = { resumeMethodDialogOpen = false },
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

        /** Work-bridge heartbeat spacing; well under the freeze debounce. */
        private const val HEARTBEAT_INTERVAL_MS = 3_000L

        /** Coalesces package event bursts into one list refresh. */
        private const val PACKAGE_EVENT_DEBOUNCE_MS = 700L

        /** Upper bound for a quiet reconnect before the banner settles in. */
        private const val SILENT_RECONNECT_TIMEOUT_MS = 15_000L

        /** Attempts and spacing for the initial certificate list load. */
        private const val CERT_LOAD_ATTEMPTS = 4
        private const val CERT_LOAD_RETRY_MS = 1_500L

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
