package capsule.profile.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

/** Parcelable [ApplicationInfo] plus the display label and frozen state. */
class ApplicationInfoWrapper private constructor() : Parcelable {
    private var info: ApplicationInfo? = null
    private var label: String? = null
    private var isHiddenState = false

    constructor(info: ApplicationInfo) : this() {
        this.info = info
    }

    fun loadLabel(pm: PackageManager): ApplicationInfoWrapper {
        label = info?.let { pm.getApplicationLabel(it).toString() }
        return this
    }

    fun setHidden(hidden: Boolean): ApplicationInfoWrapper {
        isHiddenState = hidden
        return this
    }

    val packageName: String get() = requireNotNull(info).packageName
    val labelOrDefault: String get() = label ?: packageName
    val sourceDir: String get() = requireNotNull(info).sourceDir
    val enabled: Boolean get() = requireNotNull(info).enabled
    val infoValue: ApplicationInfo get() = requireNotNull(info)

    val splitApks: Array<String>?
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireNotNull(info).splitSourceDirs
            } else {
                null
            }

    fun isHidden(): Boolean = isHiddenState

    fun isSystem(): Boolean =
        (requireNotNull(info).flags and ApplicationInfo.FLAG_SYSTEM) != 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(info, flags)
        dest.writeString(label)
        dest.writeByte(if (isHiddenState) 1.toByte() else 0.toByte())
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ApplicationInfoWrapper> {
        override fun createFromParcel(source: Parcel): ApplicationInfoWrapper {
            val wrapper = ApplicationInfoWrapper()
            wrapper.info = readInfoFromParcel(source)
            wrapper.label = source.readString()
            wrapper.isHiddenState = source.readByte() != 0.toByte()
            return wrapper
        }

        private fun readInfoFromParcel(source: Parcel): ApplicationInfo? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                source.readParcelable(ApplicationInfo::class.java.classLoader, ApplicationInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                source.readParcelable(ApplicationInfo::class.java.classLoader)
            }

        override fun newArray(size: Int): Array<ApplicationInfoWrapper?> = arrayOfNulls(size)
    }
}
