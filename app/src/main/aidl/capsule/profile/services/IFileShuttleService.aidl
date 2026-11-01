package capsule.profile.services;

import android.graphics.Point;
import android.os.ParcelFileDescriptor;

// Typed collections keep the generated stubs free of deprecated Parcel
// methods such as readArrayList and readHashMap.
interface IFileShuttleService {
    void ping();
    List<Bundle> loadFiles(String path);
    Bundle loadFileMeta(String path);
    ParcelFileDescriptor openFile(String path, String mode);
    ParcelFileDescriptor openThumbnail(String path, in Point sizeHint);
    String createFile(String path, String mimeType, String displayName);
    String deleteFile(String path);
    boolean isChildOf(String parentPath, String childPath);
}
