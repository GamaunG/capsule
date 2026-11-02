package capsule.profile.ui

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import capsule.profile.util.CapsuleLog
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager

/**
 * Invisible launcher-category twin of [UnfreezeProxyActivity]. LauncherApps
 * and CrossProfileApps starts require a MAIN/LAUNCHER component to aim at,
 * so this activity publishes that filter and still finishes without showing
 * anything. Direct-boot-aware, so it resolves while the profile is paused.
 * It adds a second Capsule entry to the work profile app drawer.
 */
class UnfreezeLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapsuleLog.i("RESUME TEST launcher proxy reached in user ${Process.myUserHandle()}")
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
