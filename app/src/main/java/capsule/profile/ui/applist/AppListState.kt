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
import capsule.profile.util.ApplicationInfoWrapper
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.Utility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
) {
    var apps by mutableStateOf<List<ApplicationInfoWrapper>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
    var selectionMode by mutableStateOf(false)
        private set
    val selected = mutableStateSetOf<String>()
    val iconCache = mutableStateMapOf<String, Bitmap>()
    val crossProfileWidgetProviders = mutableStateSetOf<String>()
    val crossProfilePackages = mutableStateSetOf<String>()

    val displayedApps: List<ApplicationInfoWrapper>
        get() {
            val query = searchQuery.trim().lowercase()
            return if (query.isEmpty()) {
                apps
            } else {
                apps.filter {
                    it.packageName.lowercase().contains(query) ||
                        it.labelOrDefault.lowercase().contains(query)
                }
            }
        }

    private var refreshingJob: Job? = null

    private suspend fun awaitGetApps(): List<ApplicationInfoWrapper> =
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
        if (refreshing) return
        if (selectionMode) {
            refreshing = false
            return
        }
        refreshing = true
        refreshingJob =
            scope.launch {
                try {
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
                    withContext(Dispatchers.Main) {
                        apps = list
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

    fun isAutoFreeze(app: ApplicationInfoWrapper): Boolean =
        LocalStorageManager.stringListContains(
            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
            app.packageName,
        )

    fun toggleAutoFreeze(app: ApplicationInfoWrapper) {
        if (isAutoFreeze(app)) {
            LocalStorageManager.removeFromStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                app.packageName,
            )
        } else {
            LocalStorageManager.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                app.packageName,
            )
        }
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
                // Nothing meaningful to report.
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
                // Nothing meaningful to report.
            }
        }
    }

    fun installOrUninstall(app: ApplicationInfoWrapper, isInstall: Boolean) {
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
                // TODO: report to the user.
            }
        }
    }

    private fun installAppCallback(result: Int, app: ApplicationInfoWrapper, isInstall: Boolean) {
        when (result) {
            Activity.RESULT_OK -> {
                onMessage(
                    if (isInstall) R.string.clone_success else R.string.uninstall_success,
                    app.labelOrDefault,
                )
                refresh()
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
