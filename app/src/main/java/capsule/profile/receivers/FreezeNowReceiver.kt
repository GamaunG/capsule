package capsule.profile.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import capsule.profile.R
import capsule.profile.services.FreezeService

/**
 * Handles the "Freeze now" action on the Auto Freeze notification. The pass
 * runs directly in this profile as a broadcast: the former implementation
 * crossed into the parent profile through two activity starts, and a start
 * dispatched while the notification shade was closing could be swallowed by
 * the launcher's return animation — the forwarded activity never ran and
 * the tap had no effect.
 */
class FreezeNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FreezeService.runFreezePass(context.applicationContext)
        Toast.makeText(context, R.string.freeze_all_success, Toast.LENGTH_SHORT).show()
    }
}
