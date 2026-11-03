package capsule.profile.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import capsule.profile.services.FreezeService

/**
 * Fires the delayed auto-freeze pass. A manifest-declared receiver behind an
 * explicit PendingIntent: unlike the in-process AlarmManager listener it used
 * to be, it survives a process death, so the freeze still happens after the
 * system reclaimed the work-profile process between lock and alarm.
 */
class FreezeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FreezeService.runFreezePass(context.applicationContext)
    }
}
