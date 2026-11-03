package capsule.profile.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import capsule.profile.services.FreezeService

/**
 * Re-arms the auto-freeze watcher after boot and after the (work profile)
 * user restarts — e.g. when Work Mode is toggled or the device reboots. The
 * service instance and its screen-off receiver do not outlive the process, so
 * without this the watcher would stay down until the next bridge bind.
 *
 * Runs in both profile instances: on the main side [FreezeService.armIfNeeded]
 * finds nothing it may own and does nothing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return
        FreezeService.armIfNeeded(context.applicationContext)
    }
}
