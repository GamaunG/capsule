package capsule.profile.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import capsule.profile.CapsuleApplication
import capsule.profile.R
import capsule.profile.util.CrossProfileDocumentsProvider
import capsule.profile.util.Utility
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Forwards file metadata and Fds across the profile boundary. */
class FileShuttleService : Service() {
    private val suicideTask = Runnable { suicide() }
    private val handler = Handler(Looper.getMainLooper())

    private val stub =
        object : IFileShuttleService.Stub() {
            override fun ping() {
                resetSuicideTask()
            }

            override fun loadFiles(path: String?): List<Bundle>? {
                resetSuicideTask()
                val ret = ArrayList<Bundle>()
                val children = File(resolvePath(path ?: return null)).listFiles()
                children?.forEach { child ->
                    ret.add(loadFileMetaBundle(child.path))
                }
                return ret
            }

            override fun loadFileMeta(path: String?): Bundle? {
                resetSuicideTask()
                return loadFileMetaBundle(resolvePath(path ?: return null))
            }

            private fun loadFileMetaBundle(resolvedPath: String): Bundle {
                val f = File(resolvedPath)
                val bundle = Bundle()
                bundle.putString(DocumentsContract.Document.COLUMN_DOCUMENT_ID, f.absolutePath)
                bundle.putString(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    if (f == sharedStorageRoot) {
                        getString(R.string.app_name)
                    } else {
                        f.name
                    },
                )
                bundle.putLong(DocumentsContract.Document.COLUMN_SIZE, f.length())
                bundle.putLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified())

                if (f.isDirectory) {
                    bundle.putString(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                    bundle.putLong(
                        DocumentsContract.Document.COLUMN_FLAGS,
                        (
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                        ).toLong(),
                    )
                } else {
                    val mime =
                        MimeTypeMap
                            .getSingleton()
                            .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                    var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()
                    if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                        flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL.toLong()
                    }
                    bundle.putString(DocumentsContract.Document.COLUMN_MIME_TYPE, mime ?: "application/unknown")
                    bundle.putLong(DocumentsContract.Document.COLUMN_FLAGS, flags)
                }
                return bundle
            }

            override fun openFile(path: String?, mode: String?): ParcelFileDescriptor? {
                resetSuicideTask()
                val f = File(resolvePath(path ?: return null))
                // "w" and "wt" both map to WRITE_ONLY|CREATE|TRUNCATE in
                // ParcelFileDescriptor.parseMode, so writes always truncate.
                val numericMode = ParcelFileDescriptor.parseMode(mode ?: return null)

                return try {
                    if (numericMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
                        // For writable media files, rescan after the writer closes.
                        ParcelFileDescriptor.open(f, numericMode, handler) {
                            val mime =
                                MimeTypeMap
                                    .getSingleton()
                                    .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                            notifyMediaScannerIfNecessary(f, mime)
                        }
                    } else {
                        ParcelFileDescriptor.open(f, numericMode)
                    }
                } catch (e: IOException) {
                    null
                }
            }

            override fun openThumbnail(path: String?, sizeHint: android.graphics.Point?): ParcelFileDescriptor? {
                resetSuicideTask()
                val fullPath = resolvePath(path ?: return null)
                val mime =
                    MimeTypeMap
                        .getSingleton()
                        .getMimeTypeFromExtension(Utility.getFileExtension(fullPath)) ?: return null
                val hint = sizeHint ?: android.graphics.Point(1, 1)
                return when {
                    mime.startsWith("image/") -> loadImageThumbnail(fullPath, hint)
                    mime.startsWith("video/") -> loadVideoThumbnail(fullPath, hint)
                    else -> null
                }
            }

            override fun createFile(path: String?, mimeType: String?, displayName: String?): String? {
                resetSuicideTask()
                var fullPath = (path ?: return null) + "/" + (displayName ?: return null)
                val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                val shouldAppendExtension =
                    mimeType != null && !isDirectory && mimeType != "application/octet-stream"

                if (shouldAppendExtension) {
                    // An unknown MIME type has no extension mapping: keep the
                    // plain name instead of appending a literal ".null".
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    if (extension != null && !fullPath.endsWith(".$extension")) {
                        fullPath += "." + extension
                    }
                }

                val f = File(resolvePath(fullPath))
                try {
                    if ((isDirectory && !f.mkdir()) || (!isDirectory && !f.createNewFile())) {
                        return null
                    }
                } catch (e: IOException) {
                    return null
                }

                notifyMediaScannerIfNecessary(f, mimeType)
                return f.absolutePath
            }

            override fun deleteFile(path: String?): String? {
                resetSuicideTask()
                val f = File(resolvePath(path ?: return null))
                // Report the parent only when the file is really gone, so the
                // Documents UI does not announce a successful deletion that
                // the storage refused.
                if (!f.delete() && f.exists()) return null
                return f.parentFile?.absolutePath
            }

            override fun isChildOf(parentPath: String?, childPath: String?): Boolean {
                val parentFile = File(resolvePath(parentPath ?: return false))
                val childFile = File(resolvePath(childPath ?: return false))
                var parentDir = parentFile.absolutePath
                if (!parentDir.endsWith("/")) parentDir += "/"
                return parentFile.exists() && parentFile.isDirectory &&
                    childFile.exists() &&
                    childFile.absolutePath.startsWith(parentDir)
            }
        }

    override fun onBind(intent: Intent): IBinder {
        resetSuicideTask()
        return stub
    }

    private fun resolvePath(path: String): String =
        if (path.startsWith(CrossProfileDocumentsProvider.DUMMY_ROOT)) {
            path.replaceFirst(
                CrossProfileDocumentsProvider.DUMMY_ROOT.toRegex(),
                sharedStorageRoot.absolutePath,
            )
        } else {
            path
        }

    private fun resetSuicideTask() {
        handler.removeCallbacks(suicideTask)
        handler.postDelayed(suicideTask, TIMEOUT)
    }

    private fun suicide() {
        handler.removeCallbacks(suicideTask)
        (application as CapsuleApplication).unbindFileShuttleService()
        stopSelf()
    }

    private fun loadImageThumbnail(fullPath: String, sizeHint: android.graphics.Point): ParcelFileDescriptor? {
        val bmp =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ThumbnailUtils.createImageThumbnail(File(fullPath), thumbnailSize(sizeHint), null)
                } catch (e: IOException) {
                    Utility.decodeSampledBitmap(fullPath, sizeHint.x, sizeHint.y)
                }
            } else {
                Utility.decodeSampledBitmap(fullPath, sizeHint.x, sizeHint.y)
            }
        return bmp?.let { bitmapToFd(it) }
    }

    @Suppress("DEPRECATION")
    private fun loadVideoThumbnail(fullPath: String, sizeHint: android.graphics.Point): ParcelFileDescriptor? {
        val bmp =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ThumbnailUtils.createVideoThumbnail(File(fullPath), thumbnailSize(sizeHint), null)
                } catch (e: IOException) {
                    null
                }
            } else {
                // The String overload is the platform API on Android 7 through 9.
                ThumbnailUtils.createVideoThumbnail(
                    fullPath,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                )
            }
        return bmp?.let { bitmapToFd(it) }
    }

    private fun thumbnailSize(sizeHint: android.graphics.Point): Size =
        Size(maxOf(1, sizeHint.x), maxOf(1, sizeHint.y))

    private fun bitmapToFd(bmp: Bitmap): ParcelFileDescriptor? {
        val pair =
            try {
                ParcelFileDescriptor.createPipe()
            } catch (e: IOException) {
                return null
            }

        // Write the bitmap into the pipe on another thread so the read side is
        // returned before the PNG is fully serialized.
        Thread {
            try {
                FileOutputStream(pair[1].fileDescriptor).use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }
            } catch (e: IOException) {
                // The reader is gone.
            }
            bmp.recycle()
        }.start()

        return pair[0]
    }

    private fun notifyMediaScannerIfNecessary(f: File, mimeType: String?) {
        if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(f.absolutePath),
                arrayOf(mimeType),
                null,
            )
        }
    }

    companion object {
        const val TIMEOUT = 10000L

        @Suppress("DEPRECATION")
        val sharedStorageRoot: File
            get() = Environment.getExternalStorageDirectory()
    }
}
