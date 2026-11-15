package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.AppJson
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A topic the server has wallpapers for. */
data class WallTopic(val id: String, val name: String, val count: Int)

/** A wallpaper on the server. [color] is the first of its colours as ARGB (loading placeholder). */
data class ServerWallpaper(
    val id: String,
    val topic: String?,
    val width: Int?,
    val height: Int?,
    val color: Int?,
    val bytes: Long?,
    val sha256: String?,
    /** The wallhaven.cc page, for attribution. */
    val sourceUrl: String?,
)

/** The answer of GET /api/wallpapers. */
data class WallpaperLibrary(val topics: List<WallTopic>, val wallpapers: List<ServerWallpaper>)

/** A topic chip; [topicId] null is "All". */
data class TopicChip(val topicId: String?, val label: String)

/** Pure logic for the server's wallpaper library and the app background file. */
object WallpaperLogic {

    @Serializable
    private data class RawTopic(val id: String? = null, val name: String? = null, val count: Int? = null)

    @Serializable
    private data class RawWallpaper(
        val id: String? = null,
        val topic: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val colors: List<String>? = null,
        val bytes: Long? = null,
        val sha256: String? = null,
        @SerialName("source_url") val sourceUrl: String? = null,
    )

    @Serializable
    private data class RawLibrary(
        val topics: List<RawTopic>? = null,
        val wallpapers: List<RawWallpaper>? = null,
    )

    /**
     * Parses GET /api/wallpapers leniently: unknown keys are ignored, missing lists are empty,
     * items and topics without an id are skipped, duplicate ids keep the first. Throws
     * IllegalArgumentException on malformed JSON.
     */
    fun parseLibrary(json: String): WallpaperLibrary {
        val raw = AppJson.decodeFromString(RawLibrary.serializer(), json)
        val topics = (raw.topics ?: emptyList())
            .mapNotNull { t ->
                val id = t.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                WallTopic(id, t.name?.trim()?.takeIf { it.isNotEmpty() } ?: id, (t.count ?: 0).coerceAtLeast(0))
            }
            .distinctBy { it.id }
        val wallpapers = (raw.wallpapers ?: emptyList())
            .mapNotNull { w ->
                val id = w.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ServerWallpaper(
                    id = id,
                    topic = w.topic?.takeIf { it.isNotBlank() },
                    width = w.width?.takeIf { it > 0 },
                    height = w.height?.takeIf { it > 0 },
                    color = w.colors?.firstOrNull()?.let { parseColor(it) },
                    bytes = w.bytes?.takeIf { it > 0 },
                    sha256 = w.sha256?.trim()?.takeIf { it.isNotEmpty() },
                    sourceUrl = w.sourceUrl?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                )
            }
            .distinctBy { it.id }
        return WallpaperLibrary(topics, wallpapers)
    }

    /** "All (N)" first, then each topic with its count, in the server's order. */
    fun chips(library: WallpaperLibrary): List<TopicChip> =
        listOf(TopicChip(null, "All (${library.wallpapers.size})")) +
            library.topics.map { TopicChip(it.id, "${it.name} (${it.count})") }

    /** The remembered topic if the server still has it, else null (All). "" means All. */
    fun validTopic(library: WallpaperLibrary, topicId: String?): String? =
        topicId?.takeIf { id -> id.isNotEmpty() && library.topics.any { it.id == id } }

    /** The wallpapers shown for [topicId] (null = all), in the server's order (newest first). */
    fun filter(library: WallpaperLibrary, topicId: String?): List<ServerWallpaper> =
        if (topicId == null) library.wallpapers else library.wallpapers.filter { it.topic == topicId }

    /** "1080 × 1920", or null when unknown. */
    fun resolutionLabel(w: ServerWallpaper): String? {
        val width = w.width ?: return null
        val height = w.height ?: return null
        return "$width × $height"
    }

    /** "#424153" -> 0xFF424153 as an Int; null when it is not a 6-digit hex colour. */
    fun parseColor(hex: String): Int? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6 || s.any { Character.digit(it, 16) < 0 }) return null
        return (0xFF000000.toInt()) or s.toInt(16)
    }

    // ---- a new wallpaper on each app start --------------------------------------------------

    /**
     * A random wallpaper from [topicId] (null, or a topic the server no longer has, = all topics),
     * never [currentId] when there are at least two to choose from. Null when there is none.
     */
    fun pickRandom(library: WallpaperLibrary, topicId: String?, currentId: String?, random: Random): ServerWallpaper? {
        val candidates = filter(library, validTopic(library, topicId))
        if (candidates.isEmpty()) return null
        val choices = if (candidates.size >= 2) candidates.filter { it.id != currentId } else candidates
        return choices[random.nextInt(choices.size)]
    }

    /** The server's topics as stored for the Settings chips: one "id<TAB>name" per line. */
    fun encodeTopics(topics: List<WallTopic>): String =
        topics.joinToString("\n") { t -> clean(t.id) + "\t" + clean(t.name) }

    fun decodeTopics(text: String?): List<WallTopic> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val id = line.substringBefore('\t').trim()
            if (id.isEmpty()) return@mapNotNull null
            val name = line.substringAfter('\t', id).trim().ifEmpty { id }
            WallTopic(id, name, 0)
        }.distinctBy { it.id }
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    // ---- the app background file ------------------------------------------------------------

    /** File extension from the download's Content-Type: png, webp, else jpg. */
    fun extensionFor(contentType: String?): String {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when (type) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    /** "current.jpg" etc. */
    fun currentFileName(contentType: String?): String = "current." + extensionFor(contentType)

    /** True when a background file is named like one [currentFileName] produces. */
    fun isCurrentFileName(name: String): Boolean =
        name.startsWith("current.") && name.substringAfter("current.") in setOf("jpg", "png", "webp")

    /** True when the download's hash matches the list's (no expected hash: accepted). */
    fun shaMatches(expected: String?, actual: String): Boolean =
        expected.isNullOrBlank() || expected.trim().equals(actual.trim(), ignoreCase = true)

    /** Power-of-two BitmapFactory inSampleSize so that the longer side is at most [maxLongSide]. */
    fun sampleSize(width: Int, height: Int, maxLongSide: Int): Int {
        val longSide = maxOf(width, height)
        if (longSide <= 0 || maxLongSide <= 0) return 1
        var sample = 1
        while (longSide / sample > maxLongSide) sample *= 2
        return sample
    }
}
