package capsule.profile.util

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/**
 * FileProvider that forwards one content Uri at a time to a Fd opened in the
 * other profile through [UriForwardProxy]. Used to stream APKs across the
 * profile boundary for installation.
 */
class FileProviderProxy : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.path?.startsWith(FORWARD_PATH_PREFIX) == true) {
            sProxy?.let { return it.open(mode) }
        }
        return super.openFile(uri, mode)
    }

    companion object {
        private const val AUTHORITY_NAME = "capsule.profile.files"
        private const val FORWARD_PATH_PREFIX = "/forward/"
        private var sProxy: UriForwardProxy? = null

        /** Registers the Uri to forward and returns a temporary proxy Uri. */
        fun setUriForwardProxy(proxy: UriForwardProxy, suffix: String): Uri {
            sProxy = proxy
            return Uri.parse("content://$AUTHORITY_NAME$FORWARD_PATH_PREFIX/temp.$suffix")
        }

        fun clearForwardProxy() {
            sProxy = null
        }
    }
}
