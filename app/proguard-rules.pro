# Keep AIDL-generated stubs and their parcelables intact for cross-profile IPC.
-keep class capsule.profile.services.** { *; }
-keep class capsule.profile.util.ApplicationInfoWrapper { *; }
-keep class capsule.profile.util.UriForwardProxy { *; }
-keep class capsule.profile.util.IUriOpener { *; }

# Parcelable CREATOR fields are accessed reflectively by the framework.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
