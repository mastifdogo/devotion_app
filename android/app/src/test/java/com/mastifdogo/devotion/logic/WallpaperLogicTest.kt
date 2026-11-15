package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WallpaperLogicTest {

    private val sample = """
        {"topics":[{"id":"nature","name":"Nature","count":2},{"id":"sky","name":"Sky","count":1},
                   {"name":"No id"},{"id":"nature","name":"Duplicate","count":9},{"id":"ocean","count":null}],
         "wallpapers":[
           {"id":"abc123","topic":"nature","width":1080,"height":1920,"colors":["#424153","#ffffff"],
            "bytes":1234567,"sha256":"ABCDEF","source_url":"https://wallhaven.cc/w/abc123","extra":true},
           {"id":"def456","topic":"sky","colors":[],"source_url":"javascript:alert(1)"},
           {"topic":"nature","width":1},
           {"id":"ghi789","topic":"nature","width":0,"colors":["oops"]},
           {"id":"abc123","topic":"sky"}
         ],
         "version":2}
    """.trimIndent()

    @Test
    fun parseLibrary_isLenient() {
        val lib = WallpaperLogic.parseLibrary(sample)
        assertEquals(listOf("nature", "sky", "ocean"), lib.topics.map { it.id })
        assertEquals("Nature", lib.topics[0].name)
        assertEquals(WallTopic("ocean", "ocean", 0), lib.topics[2])
        assertEquals(listOf("abc123", "def456", "ghi789"), lib.wallpapers.map { it.id })
        val first = lib.wallpapers[0]
        assertEquals(1080, first.width)
        assertEquals(0xFF424153.toInt(), first.color)
        assertEquals(1234567L, first.bytes)
        assertEquals("ABCDEF", first.sha256)
        assertEquals("https://wallhaven.cc/w/abc123", first.sourceUrl)
        val second = lib.wallpapers[1]
        assertNull(second.color)
        assertNull(second.sourceUrl)
        assertNull(second.width)
        assertNull(second.sha256)
        assertNull(lib.wallpapers[2].width)
        assertNull(lib.wallpapers[2].color)
    }

    @Test
    fun parseLibrary_emptyAndMissing() {
        assertEquals(WallpaperLibrary(emptyList(), emptyList()), WallpaperLogic.parseLibrary("{}"))
        assertEquals(WallpaperLibrary(emptyList(), emptyList()), WallpaperLogic.parseLibrary("""{"topics":[],"wallpapers":[]}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseLibrary_malformedThrows() {
        WallpaperLogic.parseLibrary("<html>404</html>")
    }

    @Test
    fun chipsAndFilter() {
        val lib = WallpaperLogic.parseLibrary(sample)
        assertEquals(
            listOf(TopicChip(null, "All (3)"), TopicChip("nature", "Nature (2)"), TopicChip("sky", "Sky (1)"), TopicChip("ocean", "ocean (0)")),
            WallpaperLogic.chips(lib),
        )
        assertEquals(listOf("abc123", "def456", "ghi789"), WallpaperLogic.filter(lib, null).map { it.id })
        assertEquals(listOf("abc123", "ghi789"), WallpaperLogic.filter(lib, "nature").map { it.id })
        assertEquals(emptyList<String>(), WallpaperLogic.filter(lib, "ocean").map { it.id })
        assertEquals("sky", WallpaperLogic.validTopic(lib, "sky"))
        assertNull(WallpaperLogic.validTopic(lib, "gone"))
        assertNull(WallpaperLogic.validTopic(lib, ""))
        assertNull(WallpaperLogic.validTopic(lib, null))
        assertEquals(listOf(TopicChip(null, "All (0)")), WallpaperLogic.chips(WallpaperLibrary(emptyList(), emptyList())))
    }

    @Test
    fun resolutionAndColor() {
        val lib = WallpaperLogic.parseLibrary(sample)
        assertEquals("1080 × 1920", WallpaperLogic.resolutionLabel(lib.wallpapers[0]))
        assertNull(WallpaperLogic.resolutionLabel(lib.wallpapers[1]))
        assertEquals(0xFFFFFFFF.toInt(), WallpaperLogic.parseColor("FFFFFF"))
        assertNull(WallpaperLogic.parseColor("#12345"))
        assertNull(WallpaperLogic.parseColor("#12345g"))
    }

    @Test
    fun currentFileNamesAndHashes() {
        assertEquals("current.jpg", WallpaperLogic.currentFileName("image/jpeg"))
        assertEquals("current.png", WallpaperLogic.currentFileName("image/png; charset=binary"))
        assertEquals("current.webp", WallpaperLogic.currentFileName("IMAGE/WEBP"))
        assertEquals("current.jpg", WallpaperLogic.currentFileName(null))
        assertEquals("current.jpg", WallpaperLogic.currentFileName("application/octet-stream"))
        assertTrue(WallpaperLogic.isCurrentFileName("current.png"))
        assertFalse(WallpaperLogic.isCurrentFileName("current.png.part"))
        assertFalse(WallpaperLogic.isCurrentFileName("download.part"))
        assertTrue(WallpaperLogic.shaMatches("ABCdef", "abcDEF"))
        assertTrue(WallpaperLogic.shaMatches(null, "abc"))
        assertTrue(WallpaperLogic.shaMatches(" ", "abc"))
        assertFalse(WallpaperLogic.shaMatches("abc", "abd"))
    }

    @Test
    fun sampleSize_keepsLongSideWithinLimit() {
        assertEquals(2, WallpaperLogic.sampleSize(1080, 1920, 1440))
        assertEquals(1, WallpaperLogic.sampleSize(800, 1400, 1440))
        assertEquals(1, WallpaperLogic.sampleSize(1440, 900, 1440))
        assertEquals(4, WallpaperLogic.sampleSize(3840, 2160, 1440))
        assertEquals(8, WallpaperLogic.sampleSize(4000, 8000, 1440))
        assertEquals(1, WallpaperLogic.sampleSize(0, 0, 1440))
        for (w in listOf(1000, 2000, 2879, 2880, 2881, 5760, 6000)) {
            val s = WallpaperLogic.sampleSize(w, 100, 1440)
            assertTrue("w=$w s=$s", w / s <= 1440)
            // and not more than needed
            assertTrue("w=$w s=$s", s == 1 || w / (s / 2) > 1440)
        }
    }

    // ---- random pick on app start ---------------------------------------------------------------

    private fun lib(vararg items: Pair<String, String>) = WallpaperLibrary(
        topics = items.map { it.second }.distinct().map { WallTopic(it, it, 1) },
        wallpapers = items.map { ServerWallpaper(it.first, it.second, null, null, null, null, null, null) },
    )

    @Test
    fun pickRandom_respectsTopicAndAvoidsCurrent() {
        val library = lib("a" to "nature", "b" to "nature", "c" to "sky")
        repeat(50) { seed ->
            val pick = WallpaperLogic.pickRandom(library, "nature", "a", Random(seed))
            assertEquals("b", pick?.id)
        }
        val seen = HashSet<String>()
        repeat(100) { seed -> WallpaperLogic.pickRandom(library, null, "a", Random(seed))?.let { seen.add(it.id) } }
        assertEquals(setOf("b", "c"), seen)
    }

    @Test
    fun pickRandom_singleOrNone() {
        val library = lib("a" to "nature", "c" to "sky")
        // Only one in the topic: it may repeat the current one.
        assertEquals("c", WallpaperLogic.pickRandom(library, "sky", "c", Random(1))?.id)
        // A topic the server no longer has means all topics.
        assertTrue(WallpaperLogic.pickRandom(library, "gone", "a", Random(1))?.id == "c")
        assertNull(WallpaperLogic.pickRandom(WallpaperLibrary(emptyList(), emptyList()), null, null, Random(1)))
        assertEquals("a", WallpaperLogic.pickRandom(lib("a" to "nature"), null, null, Random(7))?.id)
    }

    @Test
    fun topicsRoundTrip() {
        val topics = listOf(WallTopic("nature", "Nature", 12), WallTopic("sky", "Big\tSky\n", 3))
        val decoded = WallpaperLogic.decodeTopics(WallpaperLogic.encodeTopics(topics))
        assertEquals(listOf(WallTopic("nature", "Nature", 0), WallTopic("sky", "Big Sky", 0)), decoded)
        assertEquals(emptyList<WallTopic>(), WallpaperLogic.decodeTopics(null))
        assertEquals(emptyList<WallTopic>(), WallpaperLogic.decodeTopics(""))
        assertEquals(listOf(WallTopic("x", "x", 0)), WallpaperLogic.decodeTopics("x\n\tnoid\nx\tdup"))
    }
}
