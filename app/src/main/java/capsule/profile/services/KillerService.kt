package capsule.profile.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import capsule.profile.util.Utility

/**
 * Activities get no callback when their task is removed from recents, so this
 * sticky service watches for task removal / memory trims and kills the
 * services on both sides.
 */
class KillerService : Service() {
    private var serviceMain: ICapsuleService? = null
    private var serviceWork: ICapsuleService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.getBundleExtra("extra") ?: return START_REDELIVER_INTENT
        serviceMain = ICapsuleService.Stub.asInterface(extras.getBinder("main"))
        serviceWork = ICapsuleService.Stub.asInterface(extras.getBinder("work"))
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            killEverything()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killEverything()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        killEverything()
    }

    private fun killEverything() {
        Utility.killCapsuleServices(serviceMain, serviceWork)
        stopSelf()
    }
}
