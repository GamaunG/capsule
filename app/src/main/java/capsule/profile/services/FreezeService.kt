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
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.ui.DummyActivity
import capsule.profile.util.CapsuleLog
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility

/**
 * Freezes apps when the screen is locked: everything launched through
 * "Unfreeze &amp; Launch" during the current session plus every app on the
 * Auto Freeze list that is currently unfrozen. The freeze pass is scheduled
 * through AlarmManager so it can still be cancelled when the user unlocks
 * within the configured delay.
 */
class FreezeService : Service() {
    private val lockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Snapshot usage stats at lock time for "skip foreground apps".
                screenLockTime = System.currentTimeMillis()
                CapsuleLog.i("Screen locked; scheduling auto-freeze pass")
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

                scheduleFreezePass()
                registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            }
        }

    private val unlockReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                CapsuleLog.i("Screen unlocked before the delay; cancelling auto-freeze pass")
                alarmManager.cancel(freezeWork)
            }
        }

    private var lastUsageStats: Map<String, UsageStats> = emptyMap()
    private var screenLockTime: Long = -1
    private lateinit var alarmManager: AlarmManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private fun scheduleFreezePass() {
        val triggerAt =
            System.currentTimeMillis() + SettingsManager.getAutoFreezeDelay().toLong() * 1000
        // Plain set() is deferred while the device dozes, which could postpone
        // the freeze indefinitely. Prefer an exact alarm when the user allows
        // it and fall back to the doze-aware inexact variant otherwise.
        // Short-circuit keeps canScheduleExactAlarms() away from pre-S
        // devices where it does not exist yet.
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && canExact -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        ALARM_TAG,
                        ContextCompat.getMainExecutor(this),
                        freezeWork,
                    )
                }

                canExact -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, ALARM_TAG, freezeWork, null)
                }

                else -> {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, ALARM_TAG, freezeWork, null)
                }
            }
        } catch (e: SecurityException) {
            CapsuleLog.w("Alarm rejected; using a regular alarm", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, ALARM_TAG, freezeWork, null)
        }
    }

    private val freezeWork =
        AlarmManager.OnAlarmListener {
            synchronized(LOCK) {
                // The delay passed while the screen stayed locked.
                try {
                    unregisterReceiver(unlockReceiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered by an earlier freeze pass.
                }

                val targets = LinkedHashSet<String>()
                synchronized(LOCK) { targets.addAll(appsToFreeze) }

                // Apps on the Auto Freeze list freeze on every lock, no matter
                // how they were launched.
                if (SettingsManager.getAutoFreezeServiceEnabled()) {
                    for (pkg in SettingsManager.getAutoFreezeList()) {
                        val unfrozen =
                            try {
                                !dpm.isApplicationHidden(adminComponent, pkg)
                            } catch (e: Exception) {
                                false
                            }
                        if (unfrozen) targets.add(pkg)
                    }
                }

                if (targets.isNotEmpty()) {
                    var frozen = 0
                    targets.forEach { app ->
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
                            frozen++
                        } else {
                            CapsuleLog.i("Skipping foreground app $app")
                        }
                    }
                    CapsuleLog.i("Auto-freeze pass froze $frozen of ${targets.size} apps")
                }

                synchronized(LOCK) { appsToFreeze.clear() }

                if (shouldStayArmed()) {
                    // More apps may be launched before the next lock: stay
                    // alive and keep listening.
                    CapsuleLog.i("Auto-freeze stays armed; keeping the service running")
                } else {
                    stopSelf()
                }
            }
        }

    /** True when another lock event could still have something to freeze. */
    private fun shouldStayArmed(): Boolean {
        if (synchronized(LOCK) { appsToFreeze.isNotEmpty() }) return true
        if (!SettingsManager.getAutoFreezeServiceEnabled()) return false
        return hasUnfrozenListApps(this)
    }

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(AlarmManager::class.java)
        dpm = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, CapsuleDeviceAdminReceiver::class.java)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        isRunning = true
        setForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
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
        private const val ALARM_TAG = "capsule-auto-freeze"

        // Shared monitor for the pending-apps list; using one object keeps the
        // static accessors and the alarm listener mutually exclusive.
        private val LOCK = Any()
        private val appsToFreeze = ArrayList<String>()

        @Volatile
        private var isRunning = false

        /**
         * True when the auto-freeze service is enabled and at least one app on
         * the Auto Freeze list is currently unfrozen.
         */
        fun hasUnfrozenListApps(context: Context): Boolean {
            val list = SettingsManager.getAutoFreezeList()
            if (list.isEmpty()) return false
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(context, CapsuleDeviceAdminReceiver::class.java)
            return list.any { pkg ->
                try {
                    !dpm.isApplicationHidden(admin, pkg)
                } catch (e: Exception) {
                    false
                }
            }
        }

        /** True when the service should be running right now. */
        fun shouldRun(context: Context): Boolean =
            synchronized(LOCK) { appsToFreeze.isNotEmpty() } ||
                (SettingsManager.getAutoFreezeServiceEnabled() && hasUnfrozenListApps(context))

        /** True while the service instance is alive in this process. */
        fun isRunning(): Boolean = isRunning

        /** Starts the service when auto-freeze has something to watch over. */
        fun armIfNeeded(context: Context) {
            if (isRunning || !shouldRun(context)) return
            try {
                CapsuleLog.i("Arming the auto-freeze service")
                context.startService(Intent(context, FreezeService::class.java))
            } catch (e: IllegalStateException) {
                // The app is in the background on this side; arming happens on
                // the next foreground contact.
                CapsuleLog.w("Could not arm the auto-freeze service now", e)
            }
        }

        @JvmStatic
        fun registerAppToFreeze(app: String) {
            synchronized(LOCK) {
                if (app !in appsToFreeze) appsToFreeze.add(app)
            }
        }

        @JvmStatic
        fun hasPendingAppToFreeze(): Boolean = synchronized(LOCK) { appsToFreeze.isNotEmpty() }
    }
}
