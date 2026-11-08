package shower.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

fun item(rel: String, t: StageMediaType) = StageItem(rel, rel, t, "file:///$rel")

class RunGrouperTest {

    @Test
    fun emptyListGivesNoRuns() {
        assertTrue(RunGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun allVideosInOneRun() {
        val items = (1..3).map { item("v$it.mp4", StageMediaType.VIDEO) }
        val runs = RunGrouper.group(items)
        assertEquals(1, runs.size)
        assertTrue(runs[0] is Run.VideoRun)
        assertEquals(3, runs[0].items.size)
    }

    @Test
    fun allPhotosInOneRun() {
        val items = (1..4).map { item("p$it.jpg", StageMediaType.PHOTO) }
        val runs = RunGrouper.group(items)
        assertEquals(1, runs.size)
        assertTrue(runs[0] is Run.PhotoRun)
        assertEquals(4, runs[0].items.size)
    }

    @Test
    fun alternatingTypesGiveFourRuns() {
        val items = listOf(
            item("v1.mp4", StageMediaType.VIDEO),
            item("p1.jpg", StageMediaType.PHOTO),
            item("p2.jpg", StageMediaType.PHOTO),
            item("v2.mp4", StageMediaType.VIDEO),
            item("v3.mp4", StageMediaType.VIDEO),
            item("v4.mp4", StageMediaType.VIDEO),
            item("p3.png", StageMediaType.PHOTO)
        )
        val runs = RunGrouper.group(items)
        assertEquals(4, runs.size)
        assertTrue(runs[0] is Run.VideoRun && runs[0].items.size == 1)
        assertTrue(runs[1] is Run.PhotoRun && runs[1].items.size == 2)
        assertTrue(runs[2] is Run.VideoRun && runs[2].items.size == 3)
        assertTrue(runs[3] is Run.PhotoRun && runs[3].items.size == 1)
        assertEquals("v1.mp4", runs[0].items[0].relPath)
        assertEquals("p2.jpg", runs[1].items[1].relPath)
    }

    @Test
    fun orderPreservedAcrossRuns() {
        val items = listOf(
            item("a.mp4", StageMediaType.VIDEO),
            item("b.jpg", StageMediaType.PHOTO),
            item("c.mp4", StageMediaType.VIDEO)
        )
        val rels = RunGrouper.group(items).flatMap { it.items }.map { it.relPath }
        assertEquals(listOf("a.mp4", "b.jpg", "c.mp4"), rels)
    }

    @Test
    fun singleItemGivesSingleRun() {
        val runs = RunGrouper.group(listOf(item("only.webm", StageMediaType.VIDEO)))
        assertEquals(1, runs.size)
        assertTrue(runs[0] is Run.VideoRun)
    }
}
