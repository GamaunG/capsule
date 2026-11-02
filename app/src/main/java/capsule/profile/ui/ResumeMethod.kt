package capsule.profile.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import capsule.profile.R
import capsule.profile.util.CapsuleLog
import capsule.profile.util.Utility

/**
 * The entry points the Resume button can use to turn a paused work profile
 * back on. The platform only lifts quiet mode after the user answers the
 * system confirmation dialog, but several APIs can request it. The method
 * is a persisted setting; every step of a run is logged with the
 * RESUME TEST prefix so the device log identifies the path that works.
 */
enum class ResumeMethod(
    val id: String,
) {
    /**
     * Plain start of an intent that our own cross-profile intent filter
     * forwards into the work profile. The default: it needs no API level,
     * permission or launcher role, and the filter is fully under our
     * control.
     */
    FORWARD("forward"),

    /** Public CrossProfileApps start of our own invisible proxy activity. */
    CROSSPROFILE("crossprofile"),

    /** LauncherApps start of the invisible launcher-category proxy. */
    LAUNCHER("launcher"),

    /** LauncherApps start of a real work app, like a launcher tap. */
    REALAPP("realapp"),

    /** System App Info screen; works everywhere but visibly opens Settings. */
    APPINFO("appinfo"),
    ;

    /** Picker label resource for this method. */
    val labelRes: Int
        get() =
            when (this) {
                FORWARD -> R.string.resume_method_forward
                CROSSPROFILE -> R.string.resume_method_crossprofile
                LAUNCHER -> R.string.resume_method_launcher
                REALAPP -> R.string.resume_method_realapp
                APPINFO -> R.string.resume_method_appinfo
            }

    /** One-line description resource shown under the picker label. */
    val hintRes: Int
        get() =
            when (this) {
                FORWARD -> R.string.resume_method_forward_hint
                CROSSPROFILE -> R.string.resume_method_crossprofile_hint
                LAUNCHER -> R.string.resume_method_launcher_hint
                REALAPP -> R.string.resume_method_realapp_hint
                APPINFO -> R.string.resume_method_appinfo_hint
            }

    /** Whether this method can run on this device at all. */
    fun isSupported(): Boolean =
        this != CROSSPROFILE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Runs this method against the paused profile. Returns false when the
     * call failed up front and no dialog can appear; true when it was
     * dispatched and the outcome depends on the system dialog.
     */
    fun run(activity: Activity): Boolean {
        val user = Utility.getWorkProfileUserHandle(activity)
        if (user == null) {
            CapsuleLog.w("RESUME TEST method=$id aborted: no work profile user")
            return false
        }
        return try {
            when (this) {
                FORWARD -> runForward(activity)
                CROSSPROFILE -> runCrossProfile(activity, user)
                LAUNCHER -> runLauncher(activity, user)
                REALAPP -> runRealApp(activity, user)
                APPINFO -> runAppDetails(activity, user)
            }
        } catch (e: Exception) {
            CapsuleLog.w(
                "RESUME TEST method=$id API failed: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
            false
        }
    }

    /** Plain start of an intent resolved by the cross-profile intent filter. */
    private fun runForward(activity: Activity): Boolean {
        val intent = Intent(DummyActivity.RESUME_PROFILE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        CapsuleLog.i("RESUME TEST method=$id starting the forwarded intent $intent")
        Utility.transferIntentToProfileUnsigned(activity, intent)
        activity.startActivity(intent)
        return true
    }

    /** CrossProfileApps.startMainActivity of the invisible proxy activity. */
    private fun runCrossProfile(activity: Activity, user: UserHandle): Boolean {
        if (!isSupported()) {
            CapsuleLog.w("RESUME TEST method=$id aborted: CrossProfileApps needs Android 9")
            return false
        }
        val crossProfile = activity.getSystemService(CrossProfileApps::class.java)
        val targets = crossProfile.targetUserProfiles
        CapsuleLog.i("RESUME TEST method=$id target profiles: $targets")
        val component = ComponentName(activity, UnfreezeLauncherActivity::class.java)
        CapsuleLog.i("RESUME TEST method=$id startMainActivity($component, $user)")
        crossProfile.startMainActivity(component, user)
        return true
    }

    /** LauncherApps.startMainActivity of the invisible proxy activity. */
    private fun runLauncher(activity: Activity, user: UserHandle): Boolean {
        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val component = ComponentName(activity, UnfreezeLauncherActivity::class.java)
        CapsuleLog.i("RESUME TEST method=$id LauncherApps.startMainActivity($component, $user)")
        launcherApps.startMainActivity(component, user, null, null)
        return true
    }

    /** LauncherApps start of the first real work app with a launcher entry. */
    private fun runRealApp(activity: Activity, user: UserHandle): Boolean {
        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val activities = launcherApps.getActivityList(null, user)
        CapsuleLog.i(
            "RESUME TEST method=$id work launcher activities visible while paused: ${activities.size}",
        )
        val target =
            activities.firstOrNull { it.componentName.packageName != activity.packageName }
        if (target == null) {
            CapsuleLog.w("RESUME TEST method=$id aborted: no other work app to target")
            return false
        }
        CapsuleLog.i(
            "RESUME TEST method=$id LauncherApps.startMainActivity(${target.componentName}, $user)",
        )
        launcherApps.startMainActivity(target.componentName, user, null, null)
        return true
    }

    /** LauncherApps.startAppDetailsActivity of Capsule's own package. */
    private fun runAppDetails(activity: Activity, user: UserHandle): Boolean {
        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val component = ComponentName(activity, MainActivity::class.java)
        CapsuleLog.i("RESUME TEST method=$id startAppDetailsActivity($component, $user)")
        launcherApps.startAppDetailsActivity(component, user, null, null)
        return true
    }

    companion object {
        /** The method used when nothing has been configured yet. */
        val DEFAULT = FORWARD

        /** Resolves a stored method id, falling back to the default. */
        fun fromId(id: String?): ResumeMethod = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
