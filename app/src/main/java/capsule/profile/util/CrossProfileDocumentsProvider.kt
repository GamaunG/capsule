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
    private var service: IFileShuttleService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseServiceTask = Runnable { service = null }
    private val bindLock = ReentrantLock()
    private val serviceBound = bindLock.newCondition()

    private fun doBindService() {
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
            Utility.transferIntentToProfile(context, intent)
        }
        context.startActivity(intent)

        // Convert the async bind into a synchronous wait.
        bindLock.withLock {
            try {
                while (service == null) {
                    serviceBound.await()
                }
            } catch (e: InterruptedException) {
                // Leave service as is; the query will fail like a remote error.
            }
        }
    }

    private fun ensureServiceBound() {
        if (service == null) {
            doBindService()
        } else {
            try {
                service!!.ping()
                resetReleaseService()
            } catch (e: RemoteException) {
                doBindService()
            }
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
        ensureServiceBound()
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
        ensureServiceBound()
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
        ensureServiceBound()
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
        ensureServiceBound()
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
        ensureServiceBound()
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
        ensureServiceBound()
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
        ensureServiceBound()
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
