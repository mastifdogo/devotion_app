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

    // ---- dark wallpapers ------------------------------------------------------------------------

    private fun wall(id: String, vararg colors: String, topic: String = "t") =
        ServerWallpaper(id, topic, null, null, null, null, null, null, colors.mapNotNull { WallpaperLogic.parseColor(it) })

    @Test
    fun luminanceAndDarkness() {
        assertEquals(0.0, WallpaperLogic.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, WallpaperLogic.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.2126, WallpaperLogic.relativeLuminance(0xFFFF0000.toInt()), 1e-4)
        assertTrue(WallpaperLogic.isDark(wall("night", "#0b1026", "#1c2541", "#3a506b")))
        assertFalse(WallpaperLogic.isDark(wall("day", "#f5f0e1", "#c9d6df", "#52616b")))
        // The first colours dominate: dark first with one bright accent is still dark...
        assertTrue(WallpaperLogic.isDark(wall("accent", "#101010", "#151515", "#181818", "#ffffff")))
        // ...and bright first with a dark accent is not.
        assertFalse(WallpaperLogic.isDark(wall("bright", "#ffffff", "#f0f0f0", "#eeeeee", "#000000")))
        // Unknown colours: not counted as dark.
        assertFalse(WallpaperLogic.isDark(wall("unknown")))
        assertNull(WallpaperLogic.meanLuminance(emptyList()))
        // Weights 2 and 1: (2 * 1.0 + 1 * 0.0) / 3.
        assertEquals(2.0 / 3.0, WallpaperLogic.meanLuminance(listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))!!, 1e-9)
    }

    @Test
    fun parseLibrary_keepsAllColours() {
        val lib = WallpaperLogic.parseLibrary("""{"wallpapers":[{"id":"a","colors":["#000000","bad","#ffffff"]}]}""")
        assertEquals(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), lib.wallpapers[0].colors)
        assertEquals(0xFF000000.toInt(), lib.wallpapers[0].color)
    }

    @Test
    fun pickRandom_darkOnlyFallsBackToAll() {
        val topics = listOf(WallTopic("t", "T", 3))
        val mixed = WallpaperLibrary(topics, listOf(wall("night", "#050505"), wall("day", "#ffffff"), wall("dusk", "#101820")))
        val seen = HashSet<String>()
        repeat(60) { seed -> WallpaperLogic.pickRandom(mixed, null, null, Random(seed), darkOnly = true)?.let { seen.add(it.id) } }
        assertEquals(setOf("night", "dusk"), seen)
        // Avoids the current one among the dark ones.
        repeat(20) { seed -> assertEquals("dusk", WallpaperLogic.pickRandom(mixed, null, "night", Random(seed), darkOnly = true)?.id) }
        // No dark wallpaper at all: any of them.
        val bright = WallpaperLibrary(topics, listOf(wall("day", "#ffffff"), wall("noon", "#eeeeee")))
        assertTrue(WallpaperLogic.pickRandom(bright, null, null, Random(1), darkOnly = true) != null)
        // Not asked for: unchanged behaviour.
        val all = HashSet<String>()
        repeat(60) { seed -> WallpaperLogic.pickRandom(mixed, null, null, Random(seed))?.let { all.add(it.id) } }
        assertEquals(setOf("night", "day", "dusk"), all)
    }

    // ---- random crop ----------------------------------------------------------------------------

    @Test
    fun cropRect_wideImageKeepsFullHeightAndMovesWithTheBias() {
        // A 4000x2000 image on a 1000x2000 screen: scale 1, 1000 of 4000 px wide are visible.
        assertEquals(CropRect(0f, 0f, 1000f, 2000f), WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 0f))
        assertEquals(CropRect(1500f, 0f, 2500f, 2000f), WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 0.5f))
        assertEquals(CropRect(3000f, 0f, 4000f, 2000f), WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 1f))
        // Scaled: 1920x1080 on 1080x2160 -> scale 2, 540 px wide visible, full height.
        val r = WallpaperLogic.cropRect(1920, 1080, 1080, 2160, 0.25f)
        assertEquals(345f, r.left, 0.01f)
        assertEquals(885f, r.right, 0.01f)
        assertEquals(0f, r.top, 0f)
        assertEquals(1080f, r.bottom, 0f)
        // Out-of-range bias is clamped; NaN is the centre.
        assertEquals(WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 1f), WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 9f))
        assertEquals(WallpaperLogic.cropRect(4000, 2000, 1000, 2000, 0.5f), WallpaperLogic.cropRect(4000, 2000, 1000, 2000, Float.NaN))
    }

    @Test
    fun cropRect_tallOrMatchingImageIgnoresTheBias() {
        // Taller than the screen: full width, centred vertically, whatever the bias.
        assertEquals(CropRect(0f, 500f, 1000f, 2500f), WallpaperLogic.cropRect(1000, 3000, 1000, 2000, 0f))
        assertEquals(CropRect(0f, 500f, 1000f, 2500f), WallpaperLogic.cropRect(1000, 3000, 1000, 2000, 1f))
        // Same shape: everything is visible.
        assertEquals(CropRect(0f, 0f, 1080f, 1920f), WallpaperLogic.cropRect(1080, 1920, 540, 960, 0.8f))
        assertEquals(CropRect(0f, 0f, 0f, 0f), WallpaperLogic.cropRect(0, 0, 100, 100, 0.5f))
        assertEquals(-1f, WallpaperLogic.alignmentBias(0f), 0f)
        assertEquals(0f, WallpaperLogic.alignmentBias(0.5f), 0f)
        assertEquals(1f, WallpaperLogic.alignmentBias(1f), 0f)
        assertEquals(0f, WallpaperLogic.alignmentBias(Float.NaN), 0f)
    }
}
