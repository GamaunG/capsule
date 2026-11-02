package capsule.profile.util

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import java.io.File

/**
 * Persists one profile's app list between process lives, so the UI can show a
 * stale-but-instant snapshot while the fresh list is being fetched. The file
 * is best-effort: any read or parse failure simply yields no cache.
 */
class AppListCache(
    context: Context,
    isRemote: Boolean,
) {
    private val file: File =
        File(context.filesDir, if (isRemote) CACHE_FILE_WORK else CACHE_FILE_MAIN)

    /** Returns the cached list, or null when absent or unreadable. */
    fun load(): List<ApplicationInfoWrapper>? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = parcel.readBundle(ApplicationInfoWrapper::class.java.classLoader)
                val list =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        bundle?.getParcelableArrayList(
                            KEY_APPS,
                            ApplicationInfoWrapper::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        bundle?.getParcelableArrayList(KEY_APPS)
                    }
                list.orEmpty()
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            // A cache from an incompatible build or a torn write; ignore it.
            null
        }
    }

    fun save(list: List<ApplicationInfoWrapper>) {
        try {
            val parcel = Parcel.obtain()
            try {
                val bundle = Bundle()
                bundle.putParcelableArrayList(KEY_APPS, ArrayList(list))
                parcel.writeBundle(bundle)
                file.writeBytes(parcel.marshall())
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            // The cache is optional; never let it break a refresh.
        }
    }

    private companion object {
        const val KEY_APPS = "apps"
        const val CACHE_FILE_MAIN = "app_list_cache_main"
        const val CACHE_FILE_WORK = "app_list_cache_work"
    }
}
