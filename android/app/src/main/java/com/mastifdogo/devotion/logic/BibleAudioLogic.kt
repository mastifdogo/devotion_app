package com.mastifdogo.devotion.logic

/** What to do after one request for a chapter's audio. */
sealed class AudioFetchStep {
    /** 200: the MP3 was received. */
    data object Ready : AudioFetchStep()
    /** 202: the server is still making it; ask again after [delayMs]. */
    data class Wait(val delayMs: Long) : AudioFetchStep()
    /** Anything else: stop and show [message]; the user can retry. */
    data class Failed(val message: String) : AudioFetchStep()
}

/** A cached chapter audio file: its name, size and when it was last played. */
data class CachedChapterAudio(val name: String, val bytes: Long, val lastPlayedMillis: Long)

/** Pure rules for listening to Bible chapters: media ids, the fetch/poll decision, the cache. */
object BibleAudioLogic {
    private const val PREFIX = "bible:"

    /** Ask again this often while the server answers 202. */
    const val POLL_MS = 3000L

    /** Give up after this long of "being made" (it normally takes seconds to a few minutes). */
    const val GIVE_UP_MS = 10 * 60 * 1000L

    /** Keep at most about this much chapter audio on the phone. */
    const val MAX_CACHE_BYTES = 300L * 1024L * 1024L

    /**
     * Media id of a chapter, e.g. "bible:kjv:42:3" (book 0-based, chapter 1-based). Entry audio
     * uses "<entryId>:<voice>", which never starts with "bible:", so the two cannot be confused.
     */
    fun mediaId(ref: BibleRef): String = "$PREFIX${ref.bibleId}:${ref.book}:${ref.chapter}"

    /** The chapter of a media id made by [mediaId], or null for anything else (such as entry audio). */
    fun parseMediaId(mediaId: String?): BibleRef? {
        if (mediaId == null || !mediaId.startsWith(PREFIX)) return null
        val rest = mediaId.substring(PREFIX.length)
        // The version id may itself contain ':'; book and chapter are the last two parts.
        val lastColon = rest.lastIndexOf(':')
        if (lastColon <= 0) return null
        val middleColon = rest.lastIndexOf(':', lastColon - 1)
        if (middleColon <= 0) return null
        val chapter = rest.substring(lastColon + 1).toIntOrNull() ?: return null
        val book = rest.substring(middleColon + 1, lastColon).toIntOrNull() ?: return null
        if (book < 0 || chapter < 1) return null
        return BibleRef(rest.substring(0, middleColon), book, chapter)
    }

    /** "kjv-42-3.mp3"; characters unsafe in a file name become '_'. */
    fun fileName(ref: BibleRef): String {
        val safe = ref.bibleId.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.') c else '_'
        }.joinToString("").trim('.').ifEmpty { "bible" }
        return "$safe-${ref.book}-${ref.chapter}.mp3"
    }

    /**
     * The next step after the server answered with [httpCode] (and, for errors, [serverMessage]
     * from its {"error": …}) [elapsedMs] after the first request.
     */
    fun nextStep(httpCode: Int, serverMessage: String?, elapsedMs: Long): AudioFetchStep {
        val said = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
        return when (httpCode) {
            200 -> AudioFetchStep.Ready
            202 -> if (elapsedMs >= GIVE_UP_MS) {
                AudioFetchStep.Failed("The audio is taking too long to make. Please try again later.")
            } else {
                AudioFetchStep.Wait(POLL_MS)
            }
            404 -> AudioFetchStep.Failed(said ?: "Bible audio is not available on this server.")
            502, 503 -> AudioFetchStep.Failed(said ?: "Making the audio failed. Please try again.")
            else -> AudioFetchStep.Failed(said ?: "The server answered with HTTP $httpCode.")
        }
    }

    /**
     * The files to delete so the cache holds at most [maxBytes]: least recently played first,
     * never [keep] (the one that is playing). Empty when the cache is within the limit.
     */
    fun filesToTrim(files: List<CachedChapterAudio>, maxBytes: Long, keep: String?): List<String> {
        var total = files.sumOf { it.bytes.coerceAtLeast(0L) }
        if (total <= maxBytes) return emptyList()
        val result = ArrayList<String>()
        for (file in files.sortedWith(compareBy<CachedChapterAudio> { it.lastPlayedMillis }.thenBy { it.name })) {
            if (total <= maxBytes) break
            if (file.name == keep) continue
            result.add(file.name)
            total -= file.bytes.coerceAtLeast(0L)
        }
        return result
    }

    /** "Clear Bible audio (12 MB)": whole megabytes, "< 1 MB" for a little, "0 MB" for none. */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes < 1024L * 1024L -> "< 1 MB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }

    /** "John 3" for a book name and a 1-based chapter. */
    fun chapterTitle(bookName: String, chapter: Int): String = "${bookName.trim()} $chapter".trim()

    /**
     * Whether the mini player shows for a loaded chapter: while playing or paused part-way, on
     * every screen except that chapter's own reader ([shown] is the chapter open in the reader,
     * or null anywhere else).
     */
    fun isMiniPlayerVisible(loaded: BibleRef?, shown: BibleRef?, isPlaying: Boolean, positionMs: Long): Boolean {
        if (loaded == null || loaded == shown) return false
        return isPlaying || positionMs > 0L
    }
}
