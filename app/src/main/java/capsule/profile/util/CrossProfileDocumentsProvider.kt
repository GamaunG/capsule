package capsule.profile.util

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import capsule.profile.R
import capsule.profile.services.FileShuttleService
import capsule.profile.services.IFileShuttleService
import capsule.profile.services.IFileShuttleServiceCallback
import capsule.profile.ui.DummyActivity
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DocumentsProvider exposing the other profile's shared storage in the system
 * Documents UI. All file operations are forwarded over AIDL to the
 * [FileShuttleService] on the other side.
 */
class CrossProfileDocumentsProvider : DocumentsProvider() {
    // Written under bindLock from the bind callback and from the main-thread
    // release task; read on binder threads. Volatile so those reads are never
    // served a stale null.
    @Volatile
    private var service: IFileShuttleService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseServiceTask =
        Runnable {
            bindLock.withLock {
                service = null
            }
        }
    private val bindLock = ReentrantLock()
    private val serviceBound = bindLock.newCondition()

    private fun doBindService(): Boolean {
        val intent = Intent(DummyActivity.START_FILE_SHUTTLE)
        val extra = Bundle()
        extra.putBinder(
            "callback",
            object : IFileShuttleServiceCallback.Stub() {
                override fun callback(shuttle: IFileShuttleService?) {
                    bindLock.withLock {
                        service = shuttle
                        serviceBound.signalAll()
                    }
                }
            },
        )
        intent.putExtra("extra", extra)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val context = context!!
        try {
            Utility.transferIntentToProfile(context, intent)
        } catch (e: IllegalStateException) {
            // Distinct actions per direction avoid an action chooser; retry the other one.
            intent.action = DummyActivity.START_FILE_SHUTTLE_2
            try {
                Utility.transferIntentToProfile(context, intent)
            } catch (e2: IllegalStateException) {
                // The other profile is not reachable at all.
                return false
            }
        }
        context.startActivity(intent)

        // Convert the async bind into a bounded synchronous wait: hanging a
        // binder thread forever on an answer that never comes (profile
        // paused, launch silently blocked) would pile up and freeze the
        // Documents UI.
        bindLock.withLock {
            try {
                val deadline = System.nanoTime() + BIND_TIMEOUT_NS
                while (service == null) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0 || !serviceBound.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                        return false
                    }
                }
            } catch (e: InterruptedException) {
                // Leave service as is; the query fails like a remote error.
                return false
            }
        }
        return true
    }

    private fun ensureServiceBound(): Boolean {
        if (service == null) {
            return doBindService()
        }
        try {
            service!!.ping()
            resetReleaseService()
            return true
        } catch (e: RemoteException) {
            return doBindService()
        }
    }

    private fun resetReleaseService() {
        handler.removeCallbacks(releaseServiceTask)
        handler.postDelayed(releaseServiceTask, FileShuttleService.TIMEOUT / 2)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = context!!
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, DUMMY_ROOT)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                DocumentsContract.Root.COLUMN_TITLE,
                ctx.getString(
                    if (Utility.isProfileOwner(ctx)) {
                        R.string.fragment_profile_main
                    } else {
                        R.string.fragment_profile_work
                    },
                ),
            )
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor? {
        if (!ensureServiceBound()) return null
        val fileInfo =
            try {
                service!!.loadFileMeta(documentId)
            } catch (e: RemoteException) {
                null
            } ?: return null
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, fileInfo)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!ensureServiceBound()) return null
        val files =
            try {
                service!!.loadFiles(parentDocumentId)
            } catch (e: RemoteException) {
                null
            } ?: return null
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        result.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId),
        )
        files.forEach { includeFile(result, it) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor? {
        if (!ensureServiceBound()) throw java.io.FileNotFoundException("The other profile is not reachable")
        return try {
            service!!.openFile(documentId, mode)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        if (!ensureServiceBound()) return null
        return try {
            AssetFileDescriptor(
                service!!.openThumbnail(documentId, sizeHint),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH,
            )
        } catch (e: RemoteException) {
            null
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String? {
        if (!ensureServiceBound()) return null
        return try {
            val ret = service!!.createFile(parentDocumentId, mimeType, displayName)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId),
                null,
            )
            ret
        } catch (e: RemoteException) {
            null
        }
    }

    override fun deleteDocument(documentId: String) {
        if (!ensureServiceBound()) return
        try {
            val parent = service!!.deleteFile(documentId)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parent),
                null,
            )
        } catch (e: RemoteException) {
            // Nothing sensible to do; the Documents UI shows its own error.
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (!ensureServiceBound()) return false
        return try {
            service!!.isChildOf(parentDocumentId, documentId)
        } catch (e: RemoteException) {
            false
        }
    }

    private fun includeFile(cursor: MatrixCursor, fileInfo: Bundle) {
        val row = cursor.newRow()
        for (col in DEFAULT_DOCUMENT_PROJECTION) {
            // Typed getters avoid the deprecated generic Bundle#get.
            val value: Any? =
                when (col) {
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    -> fileInfo.getLong(col)

                    else -> fileInfo.getString(col)
                }
            row.add(col, value)
        }
    }

    companion object {
        /** Dummy root path remapped to shared storage on the other side. */
        const val DUMMY_ROOT = "/capsule_storage_root/"
        private const val AUTHORITY = "capsule.profile.documents"

        /** Upper bound for the cross-profile shuttle bind, in nanoseconds. */
        private const val BIND_TIMEOUT_NS = 10_000_000_000L
        private val DEFAULT_ROOT_PROJECTION =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
            )
        private val DEFAULT_DOCUMENT_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
