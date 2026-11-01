package capsule.profile.receivers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import capsule.profile.R
import capsule.profile.ui.DummyActivity
import capsule.profile.util.Utility

class CapsuleDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        // On Oreo and later the activity-based ACTION_PROVISIONING_SUCCESSFUL flow
        // handles finalization (see FinalizeActivity), so ignore the event here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return

        // Complex logic in a BroadcastReceiver is not reliable: delegate to
        // DummyActivity through a notification (also works around background
        // activity start restrictions on heavily customized ROMs).
        val i = Intent(context.applicationContext, DummyActivity::class.java)
        i.action = DummyActivity.FINALIZE_PROVISION
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val notification =
            Utility.buildNotification(
                context,
                important = true,
                ticker = "capsule-finish-provision",
                title = context.getString(R.string.finish_provision_title),
                desc = context.getString(R.string.finish_provision_desc),
                icon = R.drawable.ic_notification,
            )
        notification.contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        @Suppress("DEPRECATION")
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        context
            .getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 114514
    }
}
