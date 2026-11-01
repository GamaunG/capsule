package capsule.profile.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import java.io.FileNotFoundException

/**
 * Wraps a content [Uri] so it can be opened remotely through AIDL across the
 * profile boundary. The opener binder survives the parcel round-trip.
 */
class UriForwardProxy private constructor(
    private val opener: IUriOpener,
) : Parcelable {
    constructor(context: Context, uri: Uri) : this(
        object : IUriOpener.Stub() {
            override fun openFile(mode: String): ParcelFileDescriptor? =
                try {
                    context.contentResolver.openFileDescriptor(uri, mode)
                } catch (e: FileNotFoundException) {
                    null
                }
        },
    )

    fun open(mode: String): ParcelFileDescriptor? =
        try {
            opener.openFile(mode)
        } catch (e: RemoteException) {
            null
        }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBinderArray(arrayOf<IBinder>(opener.asBinder()))
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<UriForwardProxy> {
        override fun createFromParcel(source: Parcel): UriForwardProxy {
            val arr = arrayOfNulls<IBinder>(1)
            source.readBinderArray(arr)
            return UriForwardProxy(IUriOpener.Stub.asInterface(arr[0]))
        }

        override fun newArray(size: Int): Array<UriForwardProxy?> = arrayOfNulls(size)
    }
}
