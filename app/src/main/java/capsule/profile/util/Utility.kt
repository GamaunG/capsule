package capsule.profile.util

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Environment
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import capsule.profile.R
import capsule.profile.receivers.CapsuleDeviceAdminReceiver
import capsule.profile.services.ICapsuleService
import capsule.profile.ui.DummyActivity
import capsule.profile.ui.MainActivity
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.InputStreamReader

/** Shared helpers for profile management, compat checks and notifications. */
object Utility {
    const val NOTIFICATION_CHANNEL_ID = "CapsuleService"
    const val NOTIFICATION_CHANNEL_IMPORTANT = "CapsuleService-Important"

    fun isProfileOwner(context: Context): Boolean =
        context
            .getSystemService(DevicePolicyManager::class.java)
            .isProfileOwnerApp(context.packageName)

    /**
     * Targets an Intent at our own instance in the other profile by resolving
     * it outside this package. Throws [IllegalStateException] when no other
     * profile exists.
     */
    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        val info = context.packageManager.queryIntentActivities(intent, 0)
        val target = info.firstOrNull { it.activityInfo.packageName != context.packageName }
        if (target != null) {
            intent.component =
                ComponentName(
                    target.activityInfo.packageName,
                    target.activityInfo.name,
                )
        } else {
            throw IllegalStateException("Cannot find an intent in other profile")
        }
    }

    fun transferIntentToProfile(context: Context, intent: Intent) {
        transferIntentToProfileUnsigned(context, intent)
        AuthenticationUtility.signIntent(intent)
    }

    /**
     * Detects whether the work profile instance is reachable. Caches the
     * setup-complete flags on success.
     */
    fun isWorkProfileAvailable(context: Context): Boolean {
        // Do NOT sign this probe: signing the very first probe would store a
        // key the other side has never received.
        val reachable = resolvesInOtherProfile(context)
        if (reachable) {
            LocalStorageManager.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            LocalStorageManager.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
        }
        return reachable
    }

    /** True when this app's own bridge resolves in another profile. Side-effect free. */
    fun resolvesInOtherProfile(context: Context): Boolean =
        try {
            transferIntentToProfileUnsigned(context, Intent(DummyActivity.TRY_START_SERVICE))
            true
        } catch (e: IllegalStateException) {
            false
        }

    /**
     * The first profile other than ours in this profile group, if any. Used
     * for existence checks only; profile types are not distinguishable
     * through public APIs.
     */
    fun getWorkProfileUserHandle(context: Context): UserHandle? {
        val manager = context.getSystemService(UserManager::class.java)
        val me = android.os.Process.myUserHandle()
        return manager.userProfiles.firstOrNull { it != me }
    }

    /**
     * The profile group member currently in quiet mode (Work Mode off), if
     * any. Quiet mode only applies to managed profiles, so this doubles as a
     * safe handle for resume requests. API 29+.
     */
    fun getPausedWorkProfileHandle(context: Context): UserHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(UserManager::class.java)
        val me = android.os.Process.myUserHandle()
        return manager.userProfiles.firstOrNull {
            it != me && runCatching { manager.isQuietModeEnabled(it) }.getOrDefault(false)
        }
    }

    /** True while the managed profile is paused (Work Mode off). API 29+. */
    fun isWorkProfilePaused(context: Context): Boolean = getPausedWorkProfileHandle(context) != null

    /** (Re)installs all cross-profile intent filters and profile policies. */
    @Suppress("DEPRECATION")
    fun enforceWorkProfilePolicies(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent =
            ComponentName(
                context.applicationContext,
                CapsuleDeviceAdminReceiver::class.java,
            )

        manager.clearCrossProfileIntentFilters(adminComponent)

        fun addFilter(action: String, flags: Int) {
            manager.addCrossProfileIntentFilter(adminComponent, IntentFilter(action), flags)
        }

        addFilter(DummyActivity.START_SERVICE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.TRY_START_SERVICE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.UNFREEZE_AND_LAUNCH, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.FREEZE_ALL_IN_LIST, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        // Used by FreezeService inside the profile.
        addFilter(DummyActivity.PUBLIC_FREEZE_ALL, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        addFilter(DummyActivity.FINALIZE_PROVISION, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        addFilter(DummyActivity.START_FILE_SHUTTLE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.START_FILE_SHUTTLE_2, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        addFilter(DummyActivity.SYNCHRONIZE_PREFERENCE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        // Cross from the parent profile into the managed profile: these
        // actions are resolved and launched by the MainActivity proxy in the
        // main profile on behalf of the service inside the work profile.
        addFilter(DummyActivity.INSTALL_PACKAGE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.UNINSTALL_PACKAGE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)

        // Settings screens opened on behalf of the main profile: the
        // special-access permission chain, the per-app App Info screen and
        // the notification permission request for the Auto Freeze service.
        // Without these filters the cross-profile resolution fails and the
        // work-profile permission steps can never be shown.
        addFilter(DummyActivity.OPEN_SETTINGS_IN_PROFILE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.OPEN_APP_DETAILS_IN_PROFILE, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
        addFilter(DummyActivity.REQUEST_NOTIFICATION_PERMISSION, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)

        // Share targets from the work profile may reach the main profile.
        val actionSendFilter = IntentFilter()
        actionSendFilter.addAction(Intent.ACTION_SEND)
        actionSendFilter.addAction(Intent.ACTION_SEND_MULTIPLE)
        actionSendFilter.addDataType("*/*")
        actionSendFilter.addCategory(Intent.CATEGORY_DEFAULT)
        manager.addCrossProfileIntentFilter(
            adminComponent,
            actionSendFilter,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
        )

        // Picker-based sharing is user mediated and independent from File Shuttle.
        // Only the main-to-work direction is ours to control: Android installs
        // system-owned defaults that always let work profile pickers reach the
        // main profile, and no profile owner can remove those. Our filter uses
        // FLAG_MANAGED_CAN_ACCESS_PARENT so intents sent from the main profile
        // resolve in the work profile.
        val pickerMode = SettingsManager.getCrossProfilePickerMode()
        val pickerEnabled =
            pickerMode == SettingsManager.PICKER_MAIN_TO_WORK ||
                pickerMode == SettingsManager.PICKER_BOTH
        if (pickerEnabled) {
            CapsuleLog.i("Installing system picker filters for the main-to-work direction")

            val photoPickerFilter = IntentFilter(MediaStore.ACTION_PICK_IMAGES)
            photoPickerFilter.addCategory(Intent.CATEGORY_DEFAULT)
            manager.addCrossProfileIntentFilter(
                adminComponent,
                photoPickerFilter,
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
            )

            val documentPickerFilter = IntentFilter()
            documentPickerFilter.addAction(Intent.ACTION_GET_CONTENT)
            documentPickerFilter.addAction(Intent.ACTION_OPEN_DOCUMENT)
            documentPickerFilter.addCategory(Intent.CATEGORY_DEFAULT)
            documentPickerFilter.addCategory(Intent.CATEGORY_OPENABLE)
            documentPickerFilter.addDataType("*/*")
            manager.addCrossProfileIntentFilter(
                adminComponent,
                documentPickerFilter,
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
            )
        }

        // Browser links open in the main profile's browser.
        listOf(false, true).forEach { withDefaultCategory ->
            val browsableFilter = IntentFilter(Intent.ACTION_VIEW)
            browsableFilter.addCategory(Intent.CATEGORY_BROWSABLE)
            if (withDefaultCategory) {
                browsableFilter.addCategory(Intent.CATEGORY_DEFAULT)
            }
            browsableFilter.addDataScheme("http")
            browsableFilter.addDataScheme("https")
            manager.addCrossProfileIntentFilter(
                adminComponent,
                browsableFilter,
                DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
            )
        }

        manager.setCrossProfileContactsSearchDisabled(
            adminComponent,
            SettingsManager.getBlockContactsSearchingEnabled(),
        )

        manager.setProfileEnabled(adminComponent)
    }

    /** Hides the work-profile launcher entry after provisioning returned to Android. */
    fun hideMainActivityInManagedProfile(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.applicationContext, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Suppress("DEPRECATION")
    fun enforceUserRestrictions(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent =
            ComponentName(
                context.applicationContext,
                CapsuleDeviceAdminReceiver::class.java,
            )
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-Oreo polyfill for DISALLOW_INSTALL_UNKNOWN_SOURCES. Crashes on O+.
            manager.setSecureSetting(adminComponent, Settings.Secure.INSTALL_NON_MARKET_APPS, "1")
        }

        manager.addUserRestriction(adminComponent, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING)
    }

    /** Detects MIUI via a system property read. */
    fun isMIUI(): Boolean =
        try {
            val proc = Runtime.getRuntime().exec("getprop ro.miui.ui.version.name")
            val line = BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()
            !line.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun killCapsuleServices(serviceMain: ICapsuleService?, serviceWork: ICapsuleService?) {
        try {
            serviceWork?.stopCapsuleService(true)
        } catch (e: Exception) {
            // We are stopping anyway.
        }
        try {
            serviceMain?.stopCapsuleService(false)
        } catch (e: Exception) {
            // We are stopping anyway.
        }
    }

    /** Drops entries from a stored app list that are no longer installed. */
    fun deleteMissingApps(pref: String, apps: List<ApplicationInfoWrapper>) {
        val installed = apps.map { it.packageName }.toSet()
        val list = LocalStorageManager.getStringList(pref).filter { it in installed }
        LocalStorageManager.setStringList(pref, list)
    }

    /** Creates a launcher shortcut, with a legacy broadcast fallback before Oreo.
     * Returns false when the launcher does not support pinning. */
    @Suppress("DEPRECATION")
    fun createLauncherShortcut(
        context: Context,
        launchIntent: Intent,
        icon: Icon,
        id: String,
        label: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            if (shortcutManager.isRequestPinShortcutSupported) {
                val info =
                    ShortcutInfo
                        .Builder(context, id)
                        .setIntent(launchIntent)
                        .setIcon(icon)
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .build()
                val addIntent = shortcutManager.createShortcutResultIntent(info)
                shortcutManager.requestPinShortcut(
                    info,
                    PendingIntent
                        .getBroadcast(
                            context,
                            0,
                            addIntent,
                            PendingIntent.FLAG_IMMUTABLE,
                        ).intentSender,
                )
                return true
            }
            return false
        } else {
            val shortcutIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            icon.loadDrawable(context)?.let { drawable ->
                shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, drawableToBitmap(drawable))
            }
            context.sendBroadcast(shortcutIntent)
            return true
        }
    }

    // Scaled bitmap loading: https://developer.android.com/topic/performance/graphics/load-bitmap
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(filePath, options)
    }

    fun decodeSampledBitmap(fd: FileDescriptor, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFileDescriptor(fd, null, options)
    }

    /** Returns the extension after the last dot, or null when there is none. */
    fun getFileExtension(filePath: String): String? {
        val index = filePath.lastIndexOf(".")
        return if (index > 0) filePath.substring(index + 1) else null
    }

    fun checkUsageStatsPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_GET_USAGE_STATS)

    fun checkSystemAlertPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)

    @RequiresApi(Build.VERSION_CODES.R)
    fun checkAllFileAccessPermission(): Boolean = Environment.isExternalStorageManager()

    private fun checkSpecialAccessPermission(context: Context, name: String): Boolean {
        val appops = context.getSystemService(AppOpsManager::class.java)
        val mode = appops.checkOpNoThrow(name, android.os.Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Creating a notification channel is an IPC round-trip; the channel
    // persists per id, so each one only needs to be registered once.
    @Volatile
    private var createdChannels = mutableSetOf<String>()

    /** Cross-version compatible notification builder with silent channels. */
    @Suppress("DEPRECATION")
    fun buildNotification(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int,
    ): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val id = if (important) NOTIFICATION_CHANNEL_IMPORTANT else NOTIFICATION_CHANNEL_ID
            val nm = context.getSystemService(NotificationManager::class.java)
            if (createdChannels.add(id)) {
                val channel =
                    NotificationChannel(
                        id,
                        if (important) {
                            context.getString(R.string.notifications_important)
                        } else {
                            context.getString(R.string.app_name)
                        },
                        if (important) {
                            NotificationManager.IMPORTANCE_HIGH
                        } else {
                            NotificationManager.IMPORTANCE_MIN
                        },
                    )
                if (important) {
                    channel.enableVibration(true)
                } else {
                    channel.enableVibration(false)
                    channel.enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
            return Notification
                .Builder(context, id)
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(desc)
                .setSmallIcon(icon)
                .build()
        }
        return Notification
            .Builder(context)
            .setTicker(ticker)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(icon)
            .setPriority(
                if (important) Notification.PRIORITY_MAX else Notification.PRIORITY_MIN,
            ).build()
    }
}
