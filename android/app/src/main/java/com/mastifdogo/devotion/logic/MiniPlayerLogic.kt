package com.mastifdogo.devotion.logic

import java.util.Locale

/** The two lines the mini player (and the media notification) show for what is playing. */
data class NowPlaying(val primary: String, val secondary: String)

/** Pure rules for the mini player and for describing the playing item to the media session. */
object MiniPlayerLogic {
    const val APP_NAME = "Devotion"

    /**
     * The mini player is shown while an entry's audio is loaded and playing, or paused part-way,
     * on every screen except that entry's own (which has the full audio bar). [shownEntryId] is
     * the entry whose detail screen is open, or null on any other screen.
     */
    fun isVisible(loadedEntryId: Long?, shownEntryId: Long?, isPlaying: Boolean, positionMs: Long): Boolean {
        if (loadedEntryId == null) return false
        if (shownEntryId == loadedEntryId) return false
        return isPlaying || positionMs > 0L
    }

    /**
     * What is playing: the entry's heading (date or "Lesson 11 · date") over its title; without a
     * heading, the title over the app name. Blank titles fall back sensibly.
     */
    fun nowPlaying(heading: String?, title: String?): NowPlaying {
        val h = heading?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        return when {
            h.isNotEmpty() && t.isNotEmpty() -> NowPlaying(h, t)
            h.isNotEmpty() -> NowPlaying(h, APP_NAME)
            t.isNotEmpty() -> NowPlaying(t, APP_NAME)
            else -> NowPlaying(APP_NAME, "")
        }
    }

    /** Title for the media notification: the entry's own title, else its heading, else the app name. */
    fun notificationTitle(heading: String?, title: String?): String =
        title?.trim()?.takeIf { it.isNotEmpty() } ?: heading?.trim()?.takeIf { it.isNotEmpty() } ?: APP_NAME

    /** Artist/subtitle line for the media notification: the heading, else the app name. */
    fun notificationSubtitle(heading: String?): String =
        heading?.trim()?.takeIf { it.isNotEmpty() } ?: APP_NAME

    /** 0..1 for the thin progress bar; 0 while the duration is unknown. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
    fun time(ms: Long): String {
        val totalSeconds = (if (ms < 0L) 0L else ms) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** "1:15 / 12:40"; "1:15" alone while the duration is unknown. */
    fun progressText(positionMs: Long, durationMs: Long): String =
        if (durationMs > 0L) "${time(positionMs.coerceAtMost(durationMs))} / ${time(durationMs)}" else time(positionMs)

    /** Media id of an entry's audio in a voice: lets the app find out what a running session plays. */
    fun mediaId(entryId: Long, voice: String): String = "$entryId:$voice"

    /** The entry id and voice of a [mediaId], or null when it is not one of ours. */
    fun parseMediaId(mediaId: String?): Pair<Long, String>? {
        if (mediaId == null) return null
        val colon = mediaId.indexOf(':')
        if (colon <= 0) return null
        val id = mediaId.substring(0, colon).toLongOrNull() ?: return null
        return id to mediaId.substring(colon + 1)
    }
}
