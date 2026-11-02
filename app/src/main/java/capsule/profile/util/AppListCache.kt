package capsule.profile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Persists one profile's app list between process lives, so the UI can show a
 * stale-but-instant snapshot while the fresh list is being fetched. App icons
 * ride along as compressed PNGs: without them the snapshot would show names
 * with blank icons whenever the other profile is unreachable. The file is
 * best-effort: any read or parse failure simply yields no cache.
 */
class AppListCache(
    context: Context,
    isRemote: Boolean,
) {
    private val file: File =
        File(context.filesDir, if (isRemote) CACHE_FILE_WORK else CACHE_FILE_MAIN)

    /** A cached snapshot: the app list plus the icons known for it. */
    data class Snapshot(
        val apps: List<ApplicationInfoWrapper>,
        val icons: Map<String, Bitmap>,
    )

    /** Returns the cached snapshot, or null when absent or unreadable. */
    fun load(): Snapshot? {
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
                if (list == null) return null
                val icons = mutableMapOf<String, Bitmap>()
                val iconBundle =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        bundle?.getBundle(KEY_ICONS)
                    } else {
                        @Suppress("DEPRECATION")
                        bundle?.getBundle(KEY_ICONS)
                    }
                iconBundle?.keySet()?.forEach { pkg ->
                    iconBundle.getByteArray(pkg)?.let { png ->
                        BitmapFactory.decodeByteArray(png, 0, png.size)?.let { bmp ->
                            icons[pkg] = bmp
                        }
                    }
                }
                Snapshot(list, icons)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            // A cache from an incompatible build or a torn write; ignore it.
            null
        }
    }

    fun save(
        list: List<ApplicationInfoWrapper>,
        icons: Map<String, Bitmap>,
    ) {
        try {
            val parcel = Parcel.obtain()
            try {
                val bundle = Bundle()
                bundle.putParcelableArrayList(KEY_APPS, ArrayList(list))
                if (icons.isNotEmpty()) {
                    val iconBundle = Bundle()
                    for ((pkg, bmp) in icons) {
                        if (bmp.isRecycled) continue
                        val out = ByteArrayOutputStream()
                        if (bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            iconBundle.putByteArray(pkg, out.toByteArray())
                        }
                    }
                    bundle.putBundle(KEY_ICONS, iconBundle)
                }
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
        const val KEY_ICONS = "icons"
        const val CACHE_FILE_MAIN = "app_list_cache_main"
        const val CACHE_FILE_WORK = "app_list_cache_work"
    }
}
