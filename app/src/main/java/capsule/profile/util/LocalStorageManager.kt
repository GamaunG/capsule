package capsule.profile.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences shared by both profile instances of the app. Values that must be
 * visible on both sides are synchronized through [SettingsManager].
 */
object LocalStorageManager {
    const val PREF_IS_SETTING_UP = "is_setting_up"
    const val PREF_HAS_SETUP = "has_setup"
    const val PREF_AUTO_FREEZE_LIST_WORK_PROFILE = "auto_freeze_list_work_profile"
    const val PREF_CROSS_PROFILE_FILE_CHOOSER = "cross_profile_file_chooser"
    const val PREF_CROSS_PROFILE_PICKER = "cross_profile_picker"
    const val PREF_CROSS_PROFILE_PICKER_MODE = "cross_profile_picker_mode"
    const val PREF_AUTH_KEY = "auth_key"
    const val PREF_AUTH_NONCES = "auth_nonces"
    const val PREF_AUTO_FREEZE_SERVICE = "auto_freeze_service"
    const val PREF_DONT_FREEZE_FOREGROUND = "dont_freeze_foreground"
    const val PREF_AUTO_FREEZE_DELAY = "auto_freeze_delay"
    const val PREF_BLOCK_CONTACTS_SEARCHING = "block_contacts_searching"
    const val PREF_PAYMENT_STUB = "payment_stub"

    // Id of the resume method the Resume button uses. Main profile only:
    // the resume entry points all run from the main-profile UI.
    const val PREF_RESUME_METHOD = "resume_method"

    // Apps exempt from the post-shortcut auto-freeze (opt-out list; freezing
    // after an "Unfreeze & Launch" shortcut is the default). Main profile only.
    const val PREF_SHORTCUT_FREEZE_EXEMPT = "shortcut_freeze_exempt"

    // Snapshots of the package sets installed in each profile, so the
    // "already installed in the other profile" badges render instantly on
    // startup instead of waiting for the slower cross-profile bind.
    const val PREF_CACHED_MAIN_PACKAGES = "cached_main_packages"
    const val PREF_CACHED_WORK_PACKAGES = "cached_work_packages"

    private const val LIST_DIVIDER = ","

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        // Idempotent: a direct-boot process may initialize again once the
        // user is unlocked.
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    fun remove(pref: String) = prefs.edit().remove(pref).apply()

    fun getBoolean(pref: String): Boolean = prefs.getBoolean(pref, false)

    fun setBoolean(pref: String, value: Boolean) = prefs.edit().putBoolean(pref, value).apply()

    fun getInt(pref: String): Int = prefs.getInt(pref, Int.MIN_VALUE)

    fun setInt(pref: String, value: Int) = prefs.edit().putInt(pref, value).apply()

    fun getString(pref: String): String? = prefs.getString(pref, null)

    fun setString(pref: String, value: String) = prefs.edit().putString(pref, value).apply()

    fun getStringList(pref: String): List<String> =
        prefs.getString(pref, "")!!.split(LIST_DIVIDER).filter { it.isNotEmpty() }

    fun setStringList(pref: String, list: List<String>) {
        setString(pref, list.joinToString(LIST_DIVIDER))
    }

    fun stringListContains(pref: String, item: String): Boolean = item in getStringList(pref)

    fun appendStringList(pref: String, newItem: String) {
        val list = getStringList(pref).toMutableList()
        if (newItem !in list) list.add(newItem)
        setStringList(pref, list)
    }

    fun removeFromStringList(pref: String, item: String) {
        setStringList(pref, getStringList(pref) - item)
    }
}
