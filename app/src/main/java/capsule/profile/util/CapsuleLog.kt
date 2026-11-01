package capsule.profile.util

import capsule.profile.BuildConfig

/**
 * Logging wrapper that only emits in debug builds, so release users never see
 * diagnostic output. Include the failing operation, the package or profile
 * involved and the exception when logging failures.
 */
object CapsuleLog {
    private const val TAG = "Capsule"

    fun d(message: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, message, tr)
    }

    fun i(message: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, message, tr)
    }

    fun w(message: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, message, tr)
    }

    fun e(message: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) android.util.Log.e(TAG, message, tr)
    }
}
