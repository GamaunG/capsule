package capsule.profile.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import capsule.profile.services.PaymentStubService
import capsule.profile.ui.DummyActivity

/**
 * Applies persisted settings to the system and mirrors them to the other
 * profile instance via the SYNCHRONIZE_PREFERENCE cross-profile intent.
 */
object SettingsManager {
    private lateinit var appContext: Context
    private val storage: LocalStorageManager get() = LocalStorageManager

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun syncBoolToProfile(name: String, value: Boolean) {
        syncToProfile(
            Intent(DummyActivity.SYNCHRONIZE_PREFERENCE).apply {
                putExtra("name", name)
                putExtra("boolean", value)
            },
        )
    }

    private fun syncIntToProfile(name: String, value: Int) {
        syncToProfile(
            Intent(DummyActivity.SYNCHRONIZE_PREFERENCE).apply {
                putExtra("name", name)
                putExtra("int", value)
            },
        )
    }

    private fun syncToProfile(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            Utility.transferIntentToProfile(appContext, intent)
            appContext.startActivity(intent)
        } catch (e: IllegalStateException) {
            // The other side is not available; the setting syncs on next contact.
        }
    }

    fun applyAll() {
        applyCrossProfileFileChooser()
        applyPaymentStub()
    }

    fun applyCrossProfileFileChooser() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)
        appContext.packageManager.setComponentEnabledSetting(
            ComponentName(appContext, CrossProfileDocumentsProvider::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    fun setCrossProfileFileChooserEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
        applyCrossProfileFileChooser()
        syncBoolToProfile(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
    }

    fun getCrossProfileFileChooserEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)

    fun setCrossProfilePickerEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_PICKER, enabled)
        syncBoolToProfile(LocalStorageManager.PREF_CROSS_PROFILE_PICKER, enabled)
    }

    fun getCrossProfilePickerEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_PICKER)

    fun setBlockContactsSearchingEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
        syncBoolToProfile(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
    }

    fun getBlockContactsSearchingEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING)

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
    }

    fun getAutoFreezeServiceEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE)

    fun setAutoFreezeDelay(seconds: Int) {
        storage.setInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
        syncIntToProfile(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
    }

    fun getAutoFreezeDelay(): Int {
        val ret = storage.getInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY)
        return if (ret == Int.MIN_VALUE) 0 else ret
    }

    fun setSkipForegroundEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
        syncBoolToProfile(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
    }

    fun getSkipForegroundEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND)

    fun getPaymentStubEnabled(): Boolean = storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)

    fun setPaymentStubEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, enabled)
        applyPaymentStub()
    }

    fun applyPaymentStub() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)
        appContext.packageManager.setComponentEnabledSetting(
            ComponentName(appContext, PaymentStubService::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}
