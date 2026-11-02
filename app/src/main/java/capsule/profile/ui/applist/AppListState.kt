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
import capsule.profile.util.Utility
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
    val service: ICapsuleService,
    val isRemote: Boolean,
    private val scope: CoroutineScope,
    private val onMessage: (Int, String?) -> Unit,
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
    var selectionMode by mutableStateOf(false)
        private set
    val selected = mutableStateSetOf<String>()
    val iconCache = mutableStateMapOf<String, Bitmap>()
    val crossProfileWidgetProviders = mutableStateSetOf<String>()
    val crossProfilePackages = mutableStateSetOf<String>()

    // Mirrored in Compose state so toggles reflect immediately in open sheets.
    val autoFreezePackages = mutableStateSetOf<String>()

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

    private suspend fun awaitGetApps(): List<ApplicationInfoWrapper> {
        // A service dying mid-request would hang the coroutine forever and
        // leave the list in its loading state; bound the wait instead.
        return try {
            withTimeout(APP_LIST_TIMEOUT_MS) { awaitGetAppsInner() }
        } catch (e: TimeoutCancellationException) {
            CapsuleLog.w("App list request timed out; the service may have died mid-call")
            emptyList()
        }
    }

    private suspend fun awaitGetAppsInner(): List<ApplicationInfoWrapper> =
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
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    fun refresh() {
        if (selectionMode) return
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
                                apps = cached
                                fromCache = true
                                loading = false
                            }
                        }
                    }
                    val list = withContext(Dispatchers.IO) { awaitGetApps() }
                    if (isRemote) {
                        withContext(Dispatchers.IO) {
                            loadCrossProfileListsLocked()
                            // Drop auto-freeze entries for apps that no longer exist.
                            Utility.deleteMissingApps(
                                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                                list,
                            )
                        }
                    }
                    withContext(Dispatchers.IO) { cache?.save(list) }
                    withContext(Dispatchers.Main) {
                        apps = list
                        if (isRemote) reloadAutoFreezeList()
                        fromCache = false
                        loading = false
                        refreshing = false
                    }
                } catch (e: RemoteException) {
                    withContext(Dispatchers.Main) {
                        refreshing = false
                    }
                }
            }
    }

    private fun reloadAutoFreezeList() {
        autoFreezePackages.clear()
        autoFreezePackages.addAll(SettingsManager.getAutoFreezeList())
    }

    /** Binder reads; must be called off the main thread. */
    private fun loadCrossProfileListsLocked() {
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

    fun toggleSelected(app: ApplicationInfoWrapper) {
        if (app.packageName in selected) {
            selected.remove(app.packageName)
            if (selected.isEmpty()) exitSelectionMode()
        } else {
            selected.add(app.packageName)
        }
    }

    fun enterSelectionMode(app: ApplicationInfoWrapper) {
        selectionMode = true
        selected.clear()
        selected.add(app.packageName)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
    }

    fun selectedApps(): List<ApplicationInfoWrapper> = apps.filter { it.packageName in selected }

    fun isAutoFreeze(app: ApplicationInfoWrapper): Boolean = app.packageName in autoFreezePackages

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

    fun freezeApp(app: ApplicationInfoWrapper) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { service.freezeApp(app) }
                withContext(Dispatchers.Main) {
                    onMessage(R.string.freeze_success, app.labelOrDefault)
                    refresh()
                }
            } catch (e: RemoteException) {
                CapsuleLog.w("Failed to freeze ${app.packageName}", e)
            }
        }
    }

    fun unfreezeApp(app: ApplicationInfoWrapper) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { service.unfreezeApp(app) }
                withContext(Dispatchers.Main) {
                    onMessage(R.string.unfreeze_success, app.labelOrDefault)
                    refresh()
                }
            } catch (e: RemoteException) {
                CapsuleLog.w("Failed to unfreeze ${app.packageName}", e)
            }
        }
    }

    /** Whether the app declares any app widget providers in its own profile. */
    suspend fun hasWidgets(app: ApplicationInfoWrapper): Boolean =
        try {
            withContext(Dispatchers.IO) { service.hasWidgetProviders(app.packageName) }
        } catch (e: Exception) {
            CapsuleLog.w("Failed to query widget providers of ${app.packageName}", e)
            false
        }

    /** Whether the app publishes any manifest-declared managed restrictions. */
    suspend fun hasEditableRestrictions(app: ApplicationInfoWrapper): Boolean =
        try {
            val response =
                withContext(Dispatchers.IO) {
                    service.getManagedApplicationRestrictions(app.packageName)
                }
            val raw: ArrayList<android.os.Bundle>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    response.getParcelableArrayList("schema", android.os.Bundle::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    response.getParcelableArrayList("schema")
                }
            !raw.isNullOrEmpty()
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
                if (isInstall) {
                    onOtherService()?.installApp(app, callback)
                } else {
                    service.uninstallApp(app, callback)
                }
            } catch (e: RemoteException) {
                CapsuleLog.w("Failed to request ${if (isInstall) "clone" else "uninstall"} of ${app.packageName}", e)
                scope.launch(Dispatchers.Main) {
                    onMessage(R.string.app_operation_cancelled, null)
                }
            }
        }
    }

    private fun installAppCallback(result: Int, app: ApplicationInfoWrapper, isInstall: Boolean) {
        CapsuleLog.i("${if (isInstall) "Clone" else "Uninstall"} of ${app.packageName} finished with result $result")
        when (result) {
            Activity.RESULT_OK -> {
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
                if (enabled) {
                    crossProfilePackages.add(app.packageName)
                } else {
                    crossProfilePackages.remove(app.packageName)
                }
                withContext(Dispatchers.IO) {
                    service.setCrossProfilePackages(ArrayList(crossProfilePackages))
                }
            } catch (e: RemoteException) {
                // Leave the state unchanged.
            }
        }
    }
}
