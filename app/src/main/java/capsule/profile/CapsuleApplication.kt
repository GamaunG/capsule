package capsule.profile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.UserManager
import capsule.profile.services.CapsuleService
import capsule.profile.services.FileShuttleService
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager

class CapsuleApplication : Application() {
    private var capsuleServiceConnection: ServiceConnection? = null
    private var fileShuttleServiceConnection: ServiceConnection? = null

    // Initialized while the user is still locked, so a later unlock in this
    // same process can complete the storage initialization (see onCreate).
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            // Direct boot: only the invisible resume activities can run and
            // they need none of the profile storage; never touch locked
            // credential-encrypted files from here. The process may survive
            // the unlock (the resume flow keeps it around), so finish the
            // initialization the moment the user unlocks — otherwise every
            // later component in this process would crash on the unready
            // preferences.
            unlockReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        unregisterReceiver(this)
                        unlockReceiver = null
                        LocalStorageManager.initialize(context)
                        SettingsManager.initialize(context)
                    }
                }
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            return
        }
        LocalStorageManager.initialize(this)
        SettingsManager.initialize(this)
    }

    fun bindCapsuleService(conn: ServiceConnection) {
        unbindCapsuleService()
        val intent = Intent(applicationContext, CapsuleService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        capsuleServiceConnection = conn
    }

    fun bindFileShuttleService(conn: ServiceConnection) {
        unbindFileShuttleService()
        val intent = Intent(applicationContext, FileShuttleService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        fileShuttleServiceConnection = conn
    }

    fun unbindCapsuleService() {
        capsuleServiceConnection?.let {
            try {
                unbindService(it)
            } catch (e: IllegalArgumentException) {
                // Already unbound; we are stopping anyway.
            }
        }
        capsuleServiceConnection = null
    }

    fun unbindFileShuttleService() {
        fileShuttleServiceConnection?.let {
            try {
                unbindService(it)
            } catch (e: IllegalArgumentException) {
                // Already unbound.
            }
        }
        fileShuttleServiceConnection = null
    }
}
