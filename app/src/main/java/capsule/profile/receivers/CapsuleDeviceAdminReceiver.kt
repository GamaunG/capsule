package capsule.profile.receivers

import android.app.admin.DeviceAdminReceiver

/**
 * Implementation of the device policy controller. Provisioning finalization
 * is driven by the activity-based ACTION_PROVISIONING_SUCCESSFUL flow (see
 * FinalizeActivity) on every supported Android version.
 */
class CapsuleDeviceAdminReceiver : DeviceAdminReceiver()
