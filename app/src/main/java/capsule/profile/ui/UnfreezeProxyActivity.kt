package capsule.profile.ui

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import capsule.profile.util.CapsuleLog
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager

/**
 * Invisible launch target inside the work profile, addressed by the
 * forwarded cross-profile intent. While quiet mode is on, the system
 * replaces the start with its own unpause confirmation; once the profile
 * runs again this activity starts, logs the successful arrival and finishes
 * without showing anything. It is direct-boot-aware so it stays resolvable
 * while the paused profile is still locked, which is when it is needed.
 */
class UnfreezeProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapsuleLog.i("RESUME TEST forwarded proxy reached in user ${Process.myUserHandle()}")
        ensureManagers()
        // A no-display activity must finish before it is ever shown.
        finish()
    }

    /**
     * The app process may have been started while the user was still locked,
     * in which case the storage managers were skipped; create them as soon
     * as the profile storage is available.
     */
    private fun ensureManagers() {
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            LocalStorageManager.initialize(this)
            SettingsManager.initialize(this)
        }
    }
}
