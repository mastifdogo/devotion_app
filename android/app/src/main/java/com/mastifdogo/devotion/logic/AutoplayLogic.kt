package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry

/**
 * Where autoplay is in the Bible: the chapter it played last (book 0-based, chapter 1-based), how
 * far into it, and whether it was heard to the end. [title] is that chapter's name ("John 3").
 */
data class BiblePlace(
    val book: Int,
    val chapter: Int,
    val positionMs: Long = 0L,
    val finished: Boolean = false,
    val title: String = "",
)

/** The chapter autoplay reads next, and where in it to start. */
data class BibleStep(val book: Int, val chapter: Int, val startMs: Long)

/**
 * Pure rules for what plays by itself when an entry (or a chapter autoplay started) has been heard
 * to the end: the latest unread daily reading, then the latest unread lesson, then the Bible from
 * where autoplay left it.
 */
object AutoplayLogic {
    /**
     * The entry to play next, or null when everything that can be played has been read: the newest
     * unread entry of the Daily readings tab, else the newest unread lesson. [finishedId] is the
     * entry that just ended (never chosen again, whatever its read mark says); [playable] says
     * whether an entry has audio that can be played.
     */
    fun nextEntry(entries: List<Entry>, readIds: Set<Long>, finishedId: Long?, playable: (Entry) -> Boolean): Entry? {
        for (tab in listOf(EntryTab.DAILY, EntryTab.LESSONS)) {
            EntryKinds.entriesFor(tab, entries)
                .firstOrNull { !it.deleted && it.id != finishedId && it.id !in readIds && playable(it) }
                ?.let { return it }
        }
        return null
    }

    /**
     * The chapter to read next in a Bible whose books have [chapterCounts] chapters: with no record
     * (or one that does not fit this Bible) the first chapter; an unfinished chapter again, from
     * where it stopped; after a finished one the next chapter, the next book's first, and after the
     * last book the first again. Null when the Bible has no chapters.
     */
    fun nextChapter(place: BiblePlace?, chapterCounts: List<Int>): BibleStep? {
        val first = chapterCounts.indexOfFirst { it > 0 }
        if (first < 0) return null
        val start = BibleStep(first, 1, 0L)
        if (place == null) return start
        val count = chapterCounts.getOrNull(place.book) ?: return start
        if (place.chapter < 1 || place.chapter > count) return start
        if (!place.finished) return BibleStep(place.book, place.chapter, place.positionMs.coerceAtLeast(0L))
        if (place.chapter < count) return BibleStep(place.book, place.chapter + 1, 0L)
        for (book in place.book + 1 until chapterCounts.size) {
            if (chapterCounts[book] > 0) return BibleStep(book, 1, 0L)
        }
        return start
    }

    /**
     * Where to start an unfinished chapter: the beginning of the verse that was being read at
     * [positionMs], or the position itself without verse times.
     */
    fun resumeMs(timings: ChapterTimings?, positionMs: Long): Long {
        if (positionMs <= 0L) return 0L
        val verse = BibleAudioLogic.verseAt(timings, positionMs) ?: return positionMs
        return BibleAudioLogic.seekTarget(timings, verse) ?: positionMs
    }
}
