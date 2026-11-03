package capsule.profile.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import capsule.profile.BuildConfig
import capsule.profile.R
import capsule.profile.ui.DummyActivity
import capsule.profile.ui.MainActivity
import capsule.profile.ui.main.MainController
import capsule.profile.util.CapsuleLog
import capsule.profile.util.Utility
import capsule.profile.util.WorkServiceHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat

/**
 * Non-UI coordinator of the settings pane: the sequential special-access
 * permission chains, the work-profile certificate store and the About rows.
 * Reads the live work bridge from [main]; the pane reads its Compose state
 * directly, exactly like the activity fields it replaced.
 */
class SettingsController(
    private val activity: MainActivity,
    val main: MainController,
) {
    /**
     * One step of a special-access permission chain. Each step is requested
     * in order; returning from its settings screen advances to the next
     * missing one, so enabling a feature never needs re-toggling.
     */
    enum class PermissionGate(
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

    /** One installed CA certificate with display metadata. */
    data class InstalledCert(
        val encoded: ByteArray,
        val label: String,
        val validUntil: String,
    )

    /** Outcome of a certificate operation, split by failure cause. */
    private enum class CertOutcome {
        OK,
        BRIDGE_DOWN,
        REJECTED,
    }

    /** The chain currently being walked; empty when idle. */
    private var permissionChain: List<PermissionGate> = emptyList()

    /** What to enable once the chain completes. */
    private var chainTarget: (() -> Unit)? = null

    /** The gate whose settings screen or dialog is currently up. */
    var pendingGate by mutableStateOf<PermissionGate?>(null)
        private set

    // Certificate state: the install launcher callback runs outside
    // composition, so it lives here.
    var certs by mutableStateOf<List<InstalledCert>?>(null)
        private set
    var certBusy by mutableStateOf(false)
        private set
    var certToRemove by mutableStateOf<InstalledCert?>(null)

    // Cancels a superseded certificate load (re-entry, resume).
    private var certLoadJob: Job? = null

    /** The unfreeze-method dialog opened from the settings pane. */
    var resumeMethodDialogOpen by mutableStateOf(false)

    /** The snackbar host shared with the main scaffold. */
    val snackbarHostState get() = main.snackbarHostState

    private val refreshScope get() = main.refreshScope

    private val serviceWork get() = main.serviceWork

    /**
     * Whether the File Shuttle can be enabled at all. All-files access
     * landed in R; low-RAM (Android Go) devices forbid the overlay
     * permission File Shuttle depends on. Lazy on purpose: the controller
     * is constructed before onCreate(), where the activity's system
     * services do not exist yet; first read happens in the pane.
     */
    val fileChooserSupported: Boolean by lazy {
        Build.VERSION.SDK_INT != Build.VERSION_CODES.Q &&
            !(activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    /** Shows the pane over the app lists and refreshes its certificate list. */
    fun open() {
        main.showSettings.value = true
        reloadCerts()
    }

    /** Opens the multi-file certificate picker. */
    fun pickCertificates() {
        activity.launchCertPicker(arrayOf("*/*"))
    }

    // ==== Permission chains ====

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
                    Utility.checkSystemAlertPermission(activity)
                }
                PermissionGate.MAIN_USAGE -> {
                    Utility.checkUsageStatsPermission(activity)
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
    fun advancePermissionChain() {
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
                        snackbarHostState.showSnackbar(activity.getString(R.string.work_profile_unreachable_title))
                        if (!WorkServiceHolder.available) {
                            main.silentlyReconnectWorkService()
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
    fun startPermissionChain(
        gates: List<PermissionGate>,
        onGranted: () -> Unit,
    ) {
        permissionChain = gates
        chainTarget = onGranted
        pendingGate = null
        advancePermissionChain()
    }

    /** Aborts the chain without enabling anything (dialog dismissed). */
    fun cancelPermissionChain() {
        pendingGate = null
        permissionChain = emptyList()
        chainTarget = null
    }

    /**
     * Asks the work profile instance for the notification permission, so the
     * Auto Freeze service's status notification (the thing that keeps its
     * watcher alive) can actually be seen. No other feature needs it.
     */
    fun requestWorkNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val intent = Intent(DummyActivity.REQUEST_NOTIFICATION_PERMISSION)
        try {
            Utility.transferIntentToProfile(activity, intent)
            activity.launchWorkNotificationPermission(intent)
        } catch (e: IllegalStateException) {
            // The work profile is unreachable; the service still works, only
            // its notification stays hidden.
            CapsuleLog.w("Could not reach the work profile for the notification permission", e)
        }
    }

    /** Launches the settings screen backing [gate], on the right profile. */
    fun launchGate(gate: PermissionGate) {
        if (gate.workSide) {
            val intent = Intent(DummyActivity.OPEN_SETTINGS_IN_PROFILE)
            intent.putExtra("settings_action", gate.settingsAction)
            try {
                Utility.transferIntentToProfile(activity, intent)
                activity.launchWorkPermissionSettings(intent)
            } catch (e: IllegalStateException) {
                CapsuleLog.w("The work profile is unreachable; stopping the chain", e)
                cancelPermissionChain()
            }
        } else {
            activity.launchMainPermissionSettings(Intent(gate.settingsAction))
        }
    }

    // ==== Certificates ====

    /**
     * Reloads the installed certificate list from the work profile. The
     * bridge is probed first so the call can never land on a frozen process,
     * and the profile user may not have finished unlocking — its trust store
     * then reads empty — so that condition is retried briefly instead of
     * publishing a misleading "none installed".
     */
    fun reloadCerts() {
        certLoadJob?.cancel()
        certLoadJob =
            refreshScope.launch {
                repeat(CERT_LOAD_ATTEMPTS) { attempt ->
                    val work = serviceWork
                    if (work == null && attempt == 0 && !WorkServiceHolder.available) {
                        // A dead bridge is usually back within a heartbeat;
                        // give it a chance before giving up on the list.
                        main.silentlyReconnectWorkService()
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
        val manager = activity.getSystemService(UserManager::class.java)
        val user = Utility.getWorkProfileUserHandle(activity) ?: return false
        return !manager.isUserUnlocked(user)
    }

    /**
     * Installs the picked certificate files into the profile store, one
     * after another. A dead bridge aborts the rest; rejected files are
     * counted and reported in the summary so a batch is never silently
     * half-finished.
     */
    fun installCertificates(uris: List<Uri>) {
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
                                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
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
                    bridgeDown -> activity.getString(R.string.work_bridge_unavailable)
                    rejected == 0 -> activity.getString(R.string.cert_install_summary_ok, installed)
                    else -> activity.getString(R.string.cert_install_summary_partial, installed, rejected)
                }
            snackbarHostState.showSnackbar(message)
            if (installed > 0) reloadCerts()
        }
    }

    /** Removes [cert] from the profile certificate store. */
    fun removeCertificate(cert: InstalledCert) {
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
                    activity.getString(R.string.cert_remove_success)
                }

                CertOutcome.BRIDGE_DOWN -> {
                    activity.getString(R.string.work_bridge_unavailable)
                }

                CertOutcome.REJECTED -> {
                    activity.getString(R.string.cert_remove_failed)
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
                    ?: dn.ifEmpty { activity.getString(R.string.cert_unnamed) }
            InstalledCert(
                encoded = encoded,
                label = label,
                validUntil = activity.getString(R.string.cert_valid_until, DateFormat.getDateInstance().format(cert.notAfter)),
            )
        } catch (e: Exception) {
            CapsuleLog.w("Failed to parse an installed certificate", e)
            null
        }

    // ==== About rows ====

    private val versionName: String
        get() =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.packageManager
                        .getPackageInfo(
                            activity.packageName,
                            android.content.pm.PackageManager.PackageInfoFlags
                                .of(0),
                        ).versionName
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                } ?: ""
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                ""
            }

    val versionDisplay: String
        get() =
            buildString {
                append(versionName)
                append(" (").append(BuildConfig.GIT_COMMIT_HASH).append(")")
                if (BuildConfig.DEBUG) append(" · Debug")
            }

    fun openUrl(res: Int) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(activity.getString(res)))
        activity.startActivity(intent)
    }

    private companion object {
        /** Attempts and spacing for the initial certificate list load. */
        const val CERT_LOAD_ATTEMPTS = 4
        const val CERT_LOAD_RETRY_MS = 1_500L
    }
}
