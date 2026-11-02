package capsule.profile.util

import android.os.RemoteException
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import capsule.profile.services.IAliveCallback
import capsule.profile.services.ICapsuleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Live reference to the work-profile bridge binder, owned by MainActivity
 * and read by every screen that needs the work profile (the app list, the
 * settings screen).
 *
 * Replaces the previous pattern of handing a binder snapshot to other
 * activities through intent extras: a snapshot goes stale the moment the
 * work-profile process dies or the bridge rebinds, and the receiving screen
 * then quietly talked to a dead proxy. Reading the holder always yields the
 * binder that is current right now.
 */
object WorkServiceHolder {
    /** The current bridge binder, or null while the bridge is down. */
    @Volatile
    var binder: ICapsuleService? = null
        private set

    /** True while a bridge binder is set. Compose-observable for UI gating. */
    var available by mutableStateOf(false)
        private set

    /** Elapsed time of the last confirmed round trip, or 0 when never. */
    @Volatile
    private var lastAliveAt = 0L

    /** Publishes a fresh binder (or null) and updates [available]. */
    fun update(service: ICapsuleService?) {
        binder = service
        available = service != null
        if (service != null) {
            lastAliveAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * One round-trip liveness probe. The request itself is oneway, so it is
     * merely queued when the target process is frozen instead of triggering
     * the system kill a synchronous call would cause; only the callback
     * proves the service is actually running.
     */
    suspend fun probe(timeoutMs: Long = PROBE_TIMEOUT_MS): Boolean {
        val service = binder ?: return false
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(timeoutMs) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        val callback =
                            object : IAliveCallback.Stub() {
                                override fun alive() {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        try {
                            service.probeAlive(callback)
                        } catch (e: RemoteException) {
                            if (cont.isActive) {
                                cont.resumeWith(Result.failure(e))
                            }
                        }
                    }
                }
            }
            lastAliveAt = SystemClock.elapsedRealtime()
            true
        } catch (e: TimeoutCancellationException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Probes only when the last confirmation is older than [maxAgeMs]; a
     * fresh confirmation makes this free. Call before any synchronous bridge
     * call so it can never land on a frozen process.
     */
    suspend fun ensureAlive(maxAgeMs: Long = FRESH_ALIVE_MS): Boolean =
        if (binder == null) {
            false
        } else {
            SystemClock.elapsedRealtime() - lastAliveAt <= maxAgeMs || probe()
        }

    /** Generous enough to cover a freezer unfreeze and a process restart. */
    private const val PROBE_TIMEOUT_MS = 2_000L

    /** A confirmation younger than this is treated as still valid. */
    private const val FRESH_ALIVE_MS = 3_000L
}
