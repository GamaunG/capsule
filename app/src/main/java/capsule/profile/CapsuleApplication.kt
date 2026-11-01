package capsule.profile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import capsule.profile.services.CapsuleService
import capsule.profile.services.FileShuttleService
import capsule.profile.util.LocalStorageManager
import capsule.profile.util.SettingsManager

class CapsuleApplication : Application() {
    private var capsuleServiceConnection: ServiceConnection? = null
    private var fileShuttleServiceConnection: ServiceConnection? = null

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(this)
        SettingsManager.initialize(this)
    }

    fun bindCapsuleService(conn: ServiceConnection, foreground: Boolean) {
        unbindCapsuleService()
        val intent = Intent(applicationContext, CapsuleService::class.java)
        intent.putExtra("foreground", foreground)
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
