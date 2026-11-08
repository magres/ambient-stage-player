package shower.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageItemTypeTest {

    private val videos = listOf("a.mp4", "b.webm", "c.mkv", "d.mov", "e.m4v", "f.avi")

    @Test
    fun videoExtensionsRecognized() {
        for (n in videos) {
            assertEquals("$n → VIDEO", StageMediaType.VIDEO, StageItemFactory.typeOf(n))
        }
    }

    @Test
    fun photoExtensionsRecognized() {
        for (n in listOf("a.jpg", "b.jpeg", "c.png", "d.webp", "e.bmp")) {
            assertEquals("$n → PHOTO", StageMediaType.PHOTO, StageItemFactory.typeOf(n))
        }
    }

    @Test
    fun caseInsensitive() {
        assertEquals(StageMediaType.VIDEO, StageItemFactory.typeOf("MOVIE.MP4"))
        assertEquals(StageMediaType.PHOTO, StageItemFactory.typeOf("IMG.JPG"))
    }

    @Test
    fun unknownExtensionReturnsNull() {
        assertNull(StageItemFactory.typeOf("notes.txt"))
        assertNull(StageItemFactory.typeOf("archive.zip"))
        assertNull(StageItemFactory.typeOf("noext"))
    }

    @Test
    fun partFileIsSkipped() {
        assertNull(StageItemFactory.typeOf("video.mp4.part"))
        assertNull(StageItemFactory.typeOf("photo.jpg.part"))
    }
}
