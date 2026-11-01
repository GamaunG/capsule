package capsule.profile.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.ui.DummyActivity
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility

/**
 * Freezes apps launched through "Unfreeze & Launch" when the screen is
 * locked. The freeze work is scheduled through AlarmManager so it can be
 * cancelled when the user unlocks within the configured delay.
 */
class FreezeService : Service() {
    private val lockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Snapshot usage stats at lock time for "skip foreground apps".
                screenLockTime = System.currentTimeMillis()
                if (SettingsManager.getSkipForegroundEnabled() &&
                    Utility.checkUsageStatsPermission(this@FreezeService)
                ) {
                    val usm = getSystemService(UsageStatsManager::class.java)
                    lastUsageStats =
                        usm.queryAndAggregateUsageStats(
                            screenLockTime - APP_INACTIVE_TIMEOUT,
                            screenLockTime,
                        )
                }

                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() +
                        SettingsManager.getAutoFreezeDelay().toLong() * 1000,
                    null,
                    freezeWork,
                    null,
                )
                registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            }
        }

    private val unlockReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                alarmManager.cancel(freezeWork)
            }
        }

    private var lastUsageStats: Map<String, UsageStats> = emptyMap()
    private var screenLockTime: Long = -1
    private lateinit var alarmManager: AlarmManager

    private val freezeWork =
        AlarmManager.OnAlarmListener {
            synchronized(FreezeService::class.java) {
                // The delay passed while the screen stayed locked.
                try {
                    unregisterReceiver(unlockReceiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered by an earlier freeze pass.
                }

                if (appsToFreeze.isNotEmpty()) {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    val adminComponent = ComponentName(this, CapsuleDeviceAdminReceiver::class.java)
                    appsToFreeze.forEach { app ->
                        var shouldFreeze = true
                        val stats = lastUsageStats[app]
                        if (stats != null &&
                            screenLockTime - stats.lastTimeUsed <= APP_INACTIVE_TIMEOUT &&
                            stats.totalTimeInForeground >= APP_INACTIVE_TIMEOUT
                        ) {
                            shouldFreeze = false
                        }
                        if (shouldFreeze) {
                            dpm.setApplicationHidden(adminComponent, app, true)
                        }
                    }
                    appsToFreeze.clear()
                }
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(AlarmManager::class.java)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(lockReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setForeground() {
        val notification =
            Utility.buildNotification(
                this,
                important = false,
                ticker = getString(R.string.service_auto_freeze_title),
                title = getString(R.string.service_auto_freeze_title),
                desc = getString(R.string.service_auto_freeze_desc),
                icon = R.drawable.ic_notification,
            )

        // Quick action to freeze everything right now; the intent lives in the
        // main profile while this service runs inside the work profile.
        val intentFreeze = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        try {
            Utility.transferIntentToProfileUnsigned(this, intentFreeze)
            val freezeNow =
                PendingIntent.getActivity(
                    this,
                    0,
                    intentFreeze,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            notification.actions =
                arrayOf(
                    Notification.Action
                        .Builder(
                            null,
                            getString(R.string.service_auto_freeze_now),
                            freezeNow,
                        ).build(),
                )
        } catch (e: IllegalStateException) {
            // Main profile side has nothing to forward to.
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        /** An app inactive for less than this window counts as foreground. */
        private const val APP_INACTIVE_TIMEOUT = 1000L
        private const val NOTIFICATION_ID = 0xE49C0

        private val appsToFreeze = ArrayList<String>()

        @JvmStatic
        @Synchronized
        fun registerAppToFreeze(app: String) {
            if (app !in appsToFreeze) appsToFreeze.add(app)
        }

        @JvmStatic
        @Synchronized
        fun hasPendingAppToFreeze(): Boolean = appsToFreeze.isNotEmpty()
    }
}
