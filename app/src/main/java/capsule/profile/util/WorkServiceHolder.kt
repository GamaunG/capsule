package capsule.profile.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import capsule.profile.services.ICapsuleService

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

    /** Publishes a fresh binder (or null) and updates [available]. */
    fun update(service: ICapsuleService?) {
        binder = service
        available = service != null
    }
}
