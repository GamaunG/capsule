package capsule.profile.ui.applist

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.RemoteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import capsule.profile.R
import capsule.profile.services.CapsuleService
import capsule.profile.services.IAppInstallCallback
import capsule.profile.services.ICapsuleService
import capsule.profile.services.IGetAppsCallback
import capsule.profile.services.ILoadIconCallback
import capsule.profile.util.AppListCache
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.CapsuleLog
import capsule.profile.util.Fuzzy
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager
import capsule.profile.util.WorkServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * State holder for one profile's app list page. Wraps the AIDL service calls
 * in coroutines and exposes Compose state.
 */
class AppListState(
    // Swappable on purpose: the work-profile bridge rebinds while its state
    // holder survives, so the service reference must follow the fresh binder.
    @Volatile
    var service: ICapsuleService,
    val isRemote: Boolean,
    private val scope: CoroutineScope,
    val onMessage: (Int, String?) -> Unit,
    private val onOtherService: () -> ICapsuleService?,
    private val showAllProvider: () -> Boolean,
    private val refreshPeer: () -> Unit = {},
    private val cache: AppListCache? = null,
) {
    var apps by mutableStateOf<List<ApplicationInfoWrapper>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** True while [apps] is the stale on-disk snapshot, not fresh data. */
    var fromCache by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
    val iconCache = mutableStateMapOf<String, Bitmap>()
    val crossProfileWidgetProviders = mutableStateSetOf<String>()
    val crossProfilePackages = mutableStateSetOf<String>()

    // Mirrored in Compose state so toggles reflect immediately in open sheets.
    val autoFreezePackages = mutableStateSetOf<String>()

    // Apps the user exempted from freezing after an "Unfreeze & Launch"
    // shortcut launch; the default is to always freeze those on lock.
    val shortcutFreezeExempt = mutableStateSetOf<String>()

    val displayedApps: List<ApplicationInfoWrapper>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return apps
            // Keep the base order for equal scores: the list is already
            // sorted with frozen apps last, then alphabetically.
            return apps
                .mapNotNull { app ->
                    val score = Fuzzy.score(app.labelOrDefault, app.packageName, query)
                    if (score > 0) app to score else null
                }.sortedByDescending { it.second }
                .map { it.first }
        }

    private var refreshingJob: Job? = null

    // Hydrates from the disk cache once per state lifetime.
    private var cacheHydrated = false

    private companion object {
        const val APP_LIST_TIMEOUT_MS = 10_000L
    }

    /**
     * Confirms the bridge is alive before a synchronous call. A frozen
     * work-profile process would be killed by such a call, so remote calls
     * are always preceded by the oneway probe.
     */
    suspend fun ensureBridge(): Boolean = !isRemote || WorkServiceHolder.ensureAlive()

    private suspend fun awaitGetApps(): List<ApplicationInfoWrapper>? {
        // A service dying mid-request would hang the coroutine forever and
        // leave the list in its loading state; bound the wait instead. Null
        // marks a failed request so callers can keep the previous list.
        return try {
            withTimeout(APP_LIST_TIMEOUT_MS) { awaitGetAppsInner() }
        } catch (e: TimeoutCancellationException) {
            CapsuleLog.w("App list request timed out; the service may have died mid-call")
            null
        }
    }

    private suspend fun awaitGetAppsInner(): List<ApplicationInfoWrapper>? =
        suspendCancellableCoroutine { cont ->
            try {
                service.getApps(
                    object : IGetAppsCallback.Stub() {
                        override fun callback(apps: MutableList<ApplicationInfoWrapper>?) {
                            if (cont.isActive) cont.resume(apps.orEmpty())
                        }
                    },
                    showAllProvider(),
                )
            } catch (e: RemoteException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    fun refresh() {
        // Replace an in-flight refresh instead of dropping the new one, so a
        // quick succession of actions never leaves stale data behind.
        refreshingJob?.cancel()
        refreshing = true
        refreshingJob =
            scope.launch {
                try {
                    // A stale on-disk snapshot shows instantly and gets dimmed;
                    // the fresh query replaces it right after.
                    if (!cacheHydrated && cache != null && apps.isEmpty() && loading) {
                        cacheHydrated = true
                        val cached = withContext(Dispatchers.IO) { cache.load() }
                        if (cached != null && apps.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                apps = cached.apps
                                iconCache.putAll(cached.icons)
                                fromCache = true
                                loading = false
                            }
                        }
                    }
                    if (!ensureBridge()) {
                        // The bridge is down: keep the current list on screen;
                        // the recovery paths own the reconnection.
                        withContext(Dispatchers.Main) { refreshing = false }
                        return@launch
                    }
                    val list = withContext(Dispatchers.IO) { awaitGetApps() }
                    if (list == null) {
                        // The service died mid-request, e.g. Work Mode was
                        // switched off: keep the current list on screen; the
                        // banner state is handled by the death callback.
                        withContext(Dispatchers.Main) { refreshing = false }
                        return@launch
                    }

                    // Publish to the UI first: everything below is
                    // housekeeping and must never delay the fresh data.
                    withContext(Dispatchers.Main) {
                        apps = list
                        if (isRemote) {
                            reloadAutoFreezeList()
                            reloadShortcutFreezeExemptions()
                        }
                        fromCache = false
                        loading = false
                        refreshing = false
                    }
                    if (isRemote) {
                        withContext(Dispatchers.IO) {
                            loadCrossProfileLists()
                        }
                    }
                    withContext(Dispatchers.IO) {
                        // Persist the icons we already have so the next cold
                        // start can show them before the service responds.
                        val icons = HashMap<String, Bitmap>(iconCache.size)
                        for (app in list) {
                            iconCache[app.packageName]?.let { icons[app.packageName] = it }
                        }
                        cache?.save(list, icons)
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    // Replaced by a newer refresh: this is the old job being
                    // cancelled, not a failure, and the replacing job owns the
                    // spinner and the data. Rethrow so the cancellation
                    // completes instead of being misreported as an error.
                    throw e
                } catch (e: Exception) {
                    // Any unexpected failure must still release the spinner;
                    // the next resume or package event refreshes again.
                    CapsuleLog.w("The app list refresh failed", e)
                    withContext(Dispatchers.Main) {
                        refreshing = false
                    }
                }
            }
    }

    /** Shows a snackbar through the hosting screen. */
    fun showMessage(res: Int, arg: String? = null) = onMessage(res, arg)

    private fun reloadAutoFreezeList() {
        autoFreezePackages.clear()
        autoFreezePackages.addAll(SettingsManager.getAutoFreezeList())
    }

    /** Binder reads; must be called off the main thread. */
    private fun loadCrossProfileLists() {
        try {
            val providers = service.crossProfileWidgetProviders
            crossProfileWidgetProviders.clear()
            crossProfileWidgetProviders.addAll(providers)
        } catch (e: Exception) {
            // Optional list; ignore failures.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val packages = service.crossProfilePackages
                crossProfilePackages.clear()
                crossProfilePackages.addAll(packages)
            } catch (e: Exception) {
                // Optional list; ignore failures.
            }
        }
    }

    fun loadIcon(app: ApplicationInfoWrapper) {
        if (iconCache.containsKey(app.packageName)) return
        try {
            service.loadIcon(
                app,
                object : ILoadIconCallback.Stub() {
                    override fun callback(icon: Bitmap?) {
                        if (icon != null) {
                            scope.launch(Dispatchers.Main) {
                                iconCache[app.packageName] = icon
                            }
                        }
                    }
                },
            )
        } catch (e: RemoteException) {
            // Keep the placeholder icon.
        }
    }

    fun isAutoFreeze(app: ApplicationInfoWrapper): Boolean = app.packageName in autoFreezePackages

    /** True when the app still freezes on lock after a shortcut launch (default). */
    fun isShortcutAutoFreeze(app: ApplicationInfoWrapper): Boolean = app.packageName !in shortcutFreezeExempt

    private fun reloadShortcutFreezeExemptions() {
        shortcutFreezeExempt.clear()
        shortcutFreezeExempt.addAll(
            LocalStorageManager.getStringList(LocalStorageManager.PREF_SHORTCUT_FREEZE_EXEMPT),
        )
    }

    fun toggleAutoFreeze(app: ApplicationInfoWrapper) {
        if (app.packageName in autoFreezePackages) {
            autoFreezePackages.remove(app.packageName)
            LocalStorageManager.removeFromStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                app.packageName,
            )
        } else {
            autoFreezePackages.add(app.packageName)
            LocalStorageManager.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                app.packageName,
            )
        }
        // The freeze pass runs inside the work profile and reads the list there.
        SettingsManager.syncAutoFreezeList()
    }

    /** Toggles freezing after "Unfreeze &amp; Launch" shortcut launches. */
    fun toggleShortcutAutoFreeze(app: ApplicationInfoWrapper) {
        if (app.packageName in shortcutFreezeExempt) {
            shortcutFreezeExempt.remove(app.packageName)
            LocalStorageManager.removeFromStringList(
                LocalStorageManager.PREF_SHORTCUT_FREEZE_EXEMPT,
                app.packageName,
            )
        } else {
            shortcutFreezeExempt.add(app.packageName)
            LocalStorageManager.appendStringList(
                LocalStorageManager.PREF_SHORTCUT_FREEZE_EXEMPT,
                app.packageName,
            )
        }
    }

    fun freezeApp(app: ApplicationInfoWrapper) {
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    try {
                        if (!ensureBridge()) {
                            Outcome.BRIDGE_DOWN
                        } else {
                            service.freezeApp(app)
                            Outcome.OK
                        }
                    } catch (e: Exception) {
                        CapsuleLog.w("Failed to freeze ${app.packageName}", e)
                        Outcome.FAILED
                    }
                }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    Outcome.OK -> {
                        onMessage(R.string.freeze_success, app.labelOrDefault)
                        refresh()
                    }

                    Outcome.BRIDGE_DOWN -> {
                        onMessage(R.string.work_bridge_unavailable, null)
                    }

                    Outcome.FAILED -> {
                        onMessage(R.string.freeze_fail, app.labelOrDefault)
                    }
                }
            }
        }
    }

    fun unfreezeApp(app: ApplicationInfoWrapper) {
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    try {
                        if (!ensureBridge()) {
                            Outcome.BRIDGE_DOWN
                        } else {
                            service.unfreezeApp(app)
                            Outcome.OK
                        }
                    } catch (e: Exception) {
                        CapsuleLog.w("Failed to unfreeze ${app.packageName}", e)
                        Outcome.FAILED
                    }
                }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    Outcome.OK -> {
                        onMessage(R.string.unfreeze_success, app.labelOrDefault)
                        refresh()
                    }

                    Outcome.BRIDGE_DOWN -> {
                        onMessage(R.string.work_bridge_unavailable, null)
                    }

                    Outcome.FAILED -> {
                        onMessage(R.string.unfreeze_fail, app.labelOrDefault)
                    }
                }
            }
        }
    }

    /** Result of a one-shot app action. */
    private enum class Outcome {
        OK,
        BRIDGE_DOWN,
        FAILED,
    }

    /** Whether the app declares any app widget providers in its own profile. */
    suspend fun hasWidgets(app: ApplicationInfoWrapper): Boolean =
        try {
            withContext(Dispatchers.IO) {
                if (!ensureBridge()) return@withContext false
                service.hasWidgetProviders(app.packageName)
            }
        } catch (e: Exception) {
            CapsuleLog.w("Failed to query widget providers of ${app.packageName}", e)
            false
        }

    /** Whether the app publishes any manifest-declared managed restrictions. */
    suspend fun hasEditableRestrictions(app: ApplicationInfoWrapper): Boolean =
        try {
            withContext(Dispatchers.IO) {
                if (!ensureBridge()) return@withContext false
                val response = service.getManagedApplicationRestrictions(app.packageName)
                val raw: ArrayList<android.os.Bundle>? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        response.getParcelableArrayList("schema", android.os.Bundle::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        response.getParcelableArrayList("schema")
                    }
                !raw.isNullOrEmpty()
            }
        } catch (e: Exception) {
            CapsuleLog.w("Failed to query managed restrictions of ${app.packageName}", e)
            false
        }

    fun installOrUninstall(app: ApplicationInfoWrapper, isInstall: Boolean) {
        CapsuleLog.i("Requesting ${if (isInstall) "clone" else "uninstall"} of ${app.packageName}")
        val callback =
            object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    scope.launch(Dispatchers.Main) {
                        installAppCallback(result, app, isInstall)
                    }
                }
            }
        scope.launch(Dispatchers.IO) {
            try {
                val target = if (isInstall) onOtherService() else service
                if (target == null) {
                    reportCancelled()
                    return@launch
                }
                if (isInstall && !isRemote) {
                    // Cloning from the main list runs on the work side: a
                    // synchronous call into a frozen bridge process would be
                    // killed by the system, surfacing as a spurious failure.
                    if (!WorkServiceHolder.ensureAlive()) {
                        reportCancelled()
                        return@launch
                    }
                } else if (!isInstall && !ensureBridge()) {
                    reportCancelled()
                    return@launch
                }
                if (isInstall) {
                    target.installApp(app, callback)
                } else {
                    target.uninstallApp(app, callback)
                }
            } catch (e: RemoteException) {
                CapsuleLog.w("Failed to request ${if (isInstall) "clone" else "uninstall"} of ${app.packageName}", e)
                reportCancelled()
            }
        }
    }

    /** Reports a request that never reached the other side. */
    private fun reportCancelled() {
        scope.launch(Dispatchers.Main) {
            onMessage(R.string.app_operation_cancelled, null)
        }
    }

    private fun installAppCallback(result: Int, app: ApplicationInfoWrapper, isInstall: Boolean) {
        CapsuleLog.i("${if (isInstall) "Clone" else "Uninstall"} of ${app.packageName} finished with result $result")
        when (result) {
            Activity.RESULT_OK -> {
                if (!isInstall) {
                    // The app is gone from the profile (uninstalled, or a
                    // system app now hidden): it cannot stay on the Auto
                    // Freeze list. A refresh-time sweep is not a substitute:
                    // frozen system apps also vanish from the plain list, and
                    // such a sweep would silently drop them from the list
                    // just for being frozen.
                    LocalStorageManager.removeFromStringList(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        app.packageName,
                    )
                    autoFreezePackages.remove(app.packageName)
                    SettingsManager.syncAutoFreezeList()
                }
                onMessage(
                    if (isInstall) {
                        R.string.clone_success
                    } else if (app.isSystem()) {
                        // System apps are hidden rather than uninstalled.
                        R.string.remove_system_app_success
                    } else {
                        R.string.uninstall_success
                    },
                    app.labelOrDefault,
                )
                refresh()
                // The other profile's list decides whether clone options are
                // offered at all; reload it too.
                refreshPeer()
            }

            CapsuleService.RESULT_ALREADY_EXISTS -> {
                onMessage(R.string.clone_fail_already_exists, app.labelOrDefault)
                refreshPeer()
            }

            CapsuleService.RESULT_CANNOT_INSTALL_SYSTEM_APP -> {
                onMessage(
                    if (isInstall) {
                        R.string.clone_fail_system_app
                    } else {
                        R.string.uninstall_fail_system_app
                    },
                    null,
                )
            }

            CapsuleService.RESULT_CANNOT_ENABLE_SYSTEM_APP -> {
                onMessage(R.string.clone_fail_system_app_enable, app.labelOrDefault)
            }

            Activity.RESULT_CANCELED -> {
                onMessage(R.string.app_operation_cancelled, null)
            }
        }
    }

    fun setCrossProfileWidgetProvider(app: ApplicationInfoWrapper, enabled: Boolean) {
        scope.launch {
            try {
                val changed =
                    withContext(Dispatchers.IO) {
                        if (!ensureBridge()) return@withContext false
                        service.setCrossProfileWidgetProviderEnabled(app.packageName, enabled)
                    }
                if (changed) {
                    if (enabled) {
                        crossProfileWidgetProviders.add(app.packageName)
                    } else {
                        crossProfileWidgetProviders.remove(app.packageName)
                    }
                }
            } catch (e: RemoteException) {
                // Leave the state unchanged.
            }
        }
    }

    fun setCrossProfileInteraction(app: ApplicationInfoWrapper, enabled: Boolean) {
        scope.launch {
            try {
                // Mirror the widget toggle: only a confirmed change updates
                // the visible state, so a failed call cannot leave the row
                // showing the opposite of what the profile applies.
                val changed =
                    withContext(Dispatchers.IO) {
                        if (!ensureBridge()) {
                            false
                        } else {
                            val next =
                                if (enabled) {
                                    crossProfilePackages + app.packageName
                                } else {
                                    crossProfilePackages - app.packageName
                                }
                            service.setCrossProfilePackages(ArrayList(next))
                            true
                        }
                    }
                if (changed) {
                    if (enabled) {
                        crossProfilePackages.add(app.packageName)
                    } else {
                        crossProfilePackages.remove(app.packageName)
                    }
                }
            } catch (e: Exception) {
                CapsuleLog.w("Could not update the cross-profile interaction of ${app.packageName}", e)
            }
        }
    }
}
