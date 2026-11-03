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
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.receivers.FreezeAlarmReceiver
import capsule.profile.receivers.FreezeNowReceiver
import capsule.profile.util.CapsuleLog
import capsule.profile.util.SettingsManager
import capsule.profile.util.Utility

/**
 * Freezes apps when the screen is locked: everything launched through
 * "Unfreeze &amp; Launch" during the current session plus every app on the
 * Auto Freeze list that is currently unfrozen. The freeze pass is scheduled
 * through a PendingIntent alarm, so it survives a process death and can still
 * be cancelled when the user unlocks within the configured delay.
 */
class FreezeService : Service() {
    private val lockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Snapshot usage stats at lock time for "skip foreground apps".
                synchronized(LOCK) {
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
                }
                CapsuleLog.i("Screen locked; scheduling auto-freeze pass")
                scheduleFreezePass()
                registerUnlockReceiver()
            }
        }

    private val unlockReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                CapsuleLog.i("Screen unlocked before the delay; cancelling auto-freeze pass")
                cancelFreezePass()
                unregisterUnlockReceiver()
            }
        }

    private var unlockReceiverRegistered = false
    private lateinit var alarmManager: AlarmManager

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        runCatching { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_SCREEN_ON)) }
        unlockReceiverRegistered = true
    }

    private fun unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) return
        runCatching { unregisterReceiver(unlockReceiver) }
        unlockReceiverRegistered = false
    }

    private fun scheduleFreezePass() {
        val triggerAt =
            System.currentTimeMillis() + SettingsManager.getAutoFreezeDelay().toLong() * 1000
        val pendingIntent = freezePendingIntent(this)
        // Plain set() is deferred while the device dozes, which could postpone
        // the freeze indefinitely. Prefer an exact alarm when the user allows
        // it and fall back to the doze-aware inexact variant otherwise. The
        // PendingIntent form works on every API level and, unlike the
        // in-process OnAlarmListener, survives a process death.
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            CapsuleLog.w("Alarm rejected; using a regular alarm", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelFreezePass() {
        alarmManager.cancel(freezePendingIntent(this))
    }

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(AlarmManager::class.java)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        isRunning = true
        setForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The service exists to hold the screen-off receiver; nothing to
        // redeliver. The alarm itself is a PendingIntent and survives without us.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        runCatching { unregisterReceiver(lockReceiver) }
        unregisterUnlockReceiver()
        if (::alarmManager.isInitialized) cancelFreezePass()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setForeground() {
        val notification =
            Utility.buildNotification(
                this,
                ticker = getString(R.string.service_auto_freeze_title),
                title = getString(R.string.service_auto_freeze_title),
                desc = getString(R.string.service_auto_freeze_desc),
                icon = R.drawable.ic_notification,
            )

        // Quick action to freeze everything right now. The receiver runs
        // the pass directly in this profile: routing the tap through the
        // parent profile's trampoline activity raced with the launcher's
        // return animation while the notification shade closed, and the
        // system could drop the forwarded activity start entirely.
        val freezeNow =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(this, FreezeNowReceiver::class.java),
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

        // An explicit type keeps system_server from logging a typeless
        // foreground-service complaint on API 34+.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** An app inactive for less than this window counts as foreground. */
        private const val APP_INACTIVE_TIMEOUT = 1000L
        private const val NOTIFICATION_ID = 0xE49C0

        // Shared monitor for the pending-apps list; using one object keeps the
        // static accessors and the freeze pass mutually exclusive.
        private val LOCK = Any()
        private val appsToFreeze = ArrayList<String>()

        // Snapshot of the usage stats taken when the screen went off; read by
        // the freeze pass, which may run in another process instance.
        private var lastUsageStats: Map<String, UsageStats> = emptyMap()
        private var screenLockTime = -1L

        @Volatile
        private var isRunning = false

        /** PendingIntent identifying the delayed freeze pass. */
        fun freezePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                Intent(context.applicationContext, FreezeAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Runs one freeze pass. Called from the alarm receiver, the
         * notification's "Freeze now" action and the "Freeze all" shortcut;
         * safe to call whether or not the service instance is alive.
         *
         * [extraTargets] are frozen unconditionally — an explicit user action
         * passes its own copy of the Auto Freeze list. [forceListApps] also
         * includes the local Auto Freeze list even when its service toggle is
         * off, again for explicit user actions.
         */
        fun runFreezePass(
            context: Context,
            extraTargets: List<String> = emptyList(),
            forceListApps: Boolean = false,
        ) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val adminComponent =
                ComponentName(context, CapsuleDeviceAdminReceiver::class.java)

            val targets = LinkedHashSet<String>()
            synchronized(LOCK) {
                targets.addAll(appsToFreeze)
                targets.addAll(extraTargets)
                val lockTime = screenLockTime
                val stats = lastUsageStats

                // Apps on the Auto Freeze list freeze on every lock, no matter
                // how they were launched — unless the whole service is off and
                // this pass comes from an explicit user action.
                if (forceListApps || SettingsManager.getAutoFreezeServiceEnabled()) {
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
                        val stat = stats[app]
                        if (lockTime > 0 &&
                            stat != null &&
                            lockTime - stat.lastTimeUsed <= APP_INACTIVE_TIMEOUT &&
                            stat.totalTimeInForeground >= APP_INACTIVE_TIMEOUT
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

                appsToFreeze.clear()
            }

            if (shouldRun(context)) {
                // Something is still worth watching; make sure the screen-off
                // listener is actually up — the pass may have run while the
                // service instance was gone.
                armIfNeeded(context)
            } else {
                // Nothing left to watch: drop the watcher so it is not left
                // running (and re-armed) for nothing.
                context.stopService(Intent(context, FreezeService::class.java))
            }
        }

        /** True when another lock event could still have something to freeze. */
        fun shouldRun(context: Context): Boolean =
            synchronized(LOCK) { appsToFreeze.isNotEmpty() } ||
                (SettingsManager.getAutoFreezeServiceEnabled() && hasUnfrozenListApps(context))

        /** True when the auto-freeze service is enabled and at least one app on
         * the Auto Freeze list is currently unfrozen. */
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

        fun registerAppToFreeze(app: String) {
            synchronized(LOCK) {
                if (app !in appsToFreeze) appsToFreeze.add(app)
            }
        }

        fun hasPendingAppToFreeze(): Boolean = synchronized(LOCK) { appsToFreeze.isNotEmpty() }
    }
}
