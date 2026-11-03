package capsule.profile.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import capsule.profile.services.PaymentStubService
import capsule.profile.ui.DummyActivity

/**
 * Applies persisted settings to the system and mirrors them to the other
 * profile instance via the SYNCHRONIZE_PREFERENCE cross-profile intent.
 * A full snapshot of the synced keys also travels with every START_SERVICE
 * bind so the work profile converges even when a single change was missed.
 */
object SettingsManager {
    /** Cross-profile file picking directions. */
    const val PICKER_DISABLED = 0
    const val PICKER_MAIN_TO_WORK = 2
    const val PICKER_BOTH = 3
    private lateinit var appContext: Context
    private val storage: LocalStorageManager get() = LocalStorageManager

    fun initialize(context: Context) {
        // Idempotent: a direct-boot process may initialize again once the
        // user is unlocked.
        if (::appContext.isInitialized) return
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

    private fun syncStringListToProfile(name: String, value: List<String>) {
        syncToProfile(
            Intent(DummyActivity.SYNCHRONIZE_PREFERENCE).apply {
                putExtra("name", name)
                putExtra("list", value.toTypedArray())
            },
        )
    }

    private fun syncToProfile(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            Utility.transferIntentToProfile(appContext, intent)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            // The other side is not available, or the background launch was
            // blocked; the snapshot sent with the next START_SERVICE bind
            // repairs any divergence.
            CapsuleLog.w("Preference ${intent.getStringExtra("name")} could not be synced to the other profile", e)
        }
    }

    /**
     * Snapshot of every preference that must match on both sides. Sent along
     * with each work-profile service bind.
     */
    fun buildSyncBundle(): Bundle =
        Bundle().apply {
            putBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, getCrossProfileFileChooserEnabled())
            putInt(LocalStorageManager.PREF_CROSS_PROFILE_PICKER_MODE, getCrossProfilePickerMode())
            putBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, getBlockContactsSearchingEnabled())
            putBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, getAutoFreezeServiceEnabled())
            putInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, getAutoFreezeDelay())
            putBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, getSkipForegroundEnabled())
            putStringArray(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                storage.getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE).toTypedArray(),
            )
        }

    /**
     * Applies a snapshot produced by [buildSyncBundle] on the other side.
     * Returns true when any stored value actually changed; callers use that
     * to skip redundant policy re-enforcement.
     */
    fun applySyncBundle(bundle: Bundle): Boolean {
        val prefs = bundle.keySet()
        var changed = 0
        for (name in prefs) {
            val value: Any? =
                when (name) {
                    LocalStorageManager.PREF_CROSS_PROFILE_PICKER_MODE,
                    LocalStorageManager.PREF_AUTO_FREEZE_DELAY,
                    -> {
                        bundle.getInt(name)
                    }

                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE -> {
                        bundle.getStringArray(name)?.toList()
                    }

                    else -> {
                        bundle.getBoolean(name)
                    }
                }
            when (value) {
                is Boolean -> {
                    if (storage.getBoolean(name) != value) {
                        storage.setBoolean(name, value)
                        changed++
                    }
                }

                is Int -> {
                    // The delay getter maps the unset marker to 0; compare
                    // through the same mapping so a fresh work side does not
                    // report a phantom change on every first bind.
                    val current =
                        if (name == LocalStorageManager.PREF_AUTO_FREEZE_DELAY) {
                            getAutoFreezeDelay()
                        } else {
                            storage.getInt(name)
                        }
                    if (current != value) {
                        storage.setInt(name, value)
                        changed++
                    }
                }

                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<String>
                    if (storage.getStringList(name) != list) {
                        storage.setStringList(name, list)
                        changed++
                    }
                }
            }
        }
        if (changed > 0) {
            CapsuleLog.i("Applied $changed of ${prefs.size} synced preferences from the other profile")
            applyAll()
        }
        return changed > 0
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

    fun setCrossProfilePickerMode(mode: Int) {
        storage.setInt(LocalStorageManager.PREF_CROSS_PROFILE_PICKER_MODE, mode)
        syncIntToProfile(LocalStorageManager.PREF_CROSS_PROFILE_PICKER_MODE, mode)
    }

    fun getCrossProfilePickerMode(): Int {
        val mode = storage.getInt(LocalStorageManager.PREF_CROSS_PROFILE_PICKER_MODE)
        if (mode != Int.MIN_VALUE) return mode
        // Migrate the former boolean toggle: enabled meant both directions.
        return if (storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_PICKER)) {
            PICKER_BOTH
        } else {
            PICKER_DISABLED
        }
    }

    fun setBlockContactsSearchingEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
        syncBoolToProfile(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
    }

    fun getBlockContactsSearchingEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING)

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
        // The work profile keeps the freeze service armed based on this flag.
        syncBoolToProfile(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
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

    /** The auto-freeze list is edited in the main profile but consumed inside the work profile. */
    fun syncAutoFreezeList() {
        syncStringListToProfile(
            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
            storage.getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE),
        )
    }

    fun getAutoFreezeList(): List<String> =
        storage.getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)

    fun getPaymentStubEnabled(): Boolean = storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)

    /**
     * Id of the resume method the Resume button runs. Stored on the main
     * profile only: every resume entry point runs from the main-profile UI,
     * so no cross-profile mirror is needed.
     */
    fun setResumeMethodId(id: String) {
        storage.setString(LocalStorageManager.PREF_RESUME_METHOD, id)
    }

    fun getResumeMethodId(): String? = storage.getString(LocalStorageManager.PREF_RESUME_METHOD)

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
