package capsule.profile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilityTest {
    @Test
    fun fileExtension_isReadFromLastDot() {
        assertEquals("gz", Utility.getFileExtension("archive.tar.gz"))
        assertEquals("apk", Utility.getFileExtension("/tmp/capsule-release.apk"))
    }

    @Test
    fun fileExtension_isNullWhenNoExtensionExists() {
        assertNull(Utility.getFileExtension("README"))
        assertNull(Utility.getFileExtension(".profile"))
    }

    @Test
    fun fileExtension_allowsEmptyExtension() {
        assertEquals("", Utility.getFileExtension("document."))
    }
}
