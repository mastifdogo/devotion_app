package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoplayLogicTest {
    private fun entry(id: Long, date: String, kind: String = "daily_reading", deleted: Boolean = false) =
        Entry(id = id, title = "T$id", entryDate = date, createdAt = "2026-11-01T10:00:00.000Z", kind = kind, deleted = deleted)

    private val entries = listOf(
        entry(1, "2026-11-13"),
        entry(2, "2026-11-14"),
        entry(3, "2026-11-15"),
        entry(10, "2026-11-08", kind = "lesson"),
        entry(11, "2026-11-15", kind = "lesson"),
        entry(20, "2026-11-16", deleted = true),
    )
    private val all: (Entry) -> Boolean = { true }

    @Test
    fun nextEntry_isTheLatestUnreadDailyReadingFirst() {
        assertEquals(3L, AutoplayLogic.nextEntry(entries, emptySet(), null, all)?.id)
        assertEquals(2L, AutoplayLogic.nextEntry(entries, setOf(3L), 3L, all)?.id)
        // The lesson is newer than what is left, and still waits for the daily readings.
        assertEquals(1L, AutoplayLogic.nextEntry(entries, setOf(2L, 3L, 11L), 2L, all)?.id)
    }

    @Test
    fun nextEntry_thenTheLatestUnreadLesson_thenNothing() {
        val dailyRead = setOf(1L, 2L, 3L)
        assertEquals(11L, AutoplayLogic.nextEntry(entries, dailyRead, 1L, all)?.id)
        assertEquals(10L, AutoplayLogic.nextEntry(entries, dailyRead + 11L, 11L, all)?.id)
        assertNull(AutoplayLogic.nextEntry(entries, dailyRead + 10L + 11L, 10L, all))
    }

    @Test
    fun nextEntry_skipsWhatCannotBePlayedAndWhatJustEnded() {
        assertEquals(2L, AutoplayLogic.nextEntry(entries, emptySet(), null) { it.id != 3L }?.id)
        // The entry that just ended is not played again, even when it could not be marked read.
        assertEquals(2L, AutoplayLogic.nextEntry(entries, emptySet(), 3L, all)?.id)
        assertNull(AutoplayLogic.nextEntry(entries, emptySet(), null) { false })
    }

    @Test
    fun nextChapter_worksThroughTheBible() {
        val counts = listOf(3, 0, 2)
        assertEquals(BibleStep(0, 1, 0L), AutoplayLogic.nextChapter(null, counts))
        // Unfinished: the same chapter, from where it stopped.
        assertEquals(BibleStep(0, 2, 4000L), AutoplayLogic.nextChapter(BiblePlace(0, 2, 4000L), counts))
        // Finished: the next chapter, the next book that has chapters, then the beginning again.
        assertEquals(BibleStep(0, 3, 0L), AutoplayLogic.nextChapter(BiblePlace(0, 2, 9000L, finished = true), counts))
        assertEquals(BibleStep(2, 1, 0L), AutoplayLogic.nextChapter(BiblePlace(0, 3, finished = true), counts))
        assertEquals(BibleStep(0, 1, 0L), AutoplayLogic.nextChapter(BiblePlace(2, 2, finished = true), counts))
        // A record that does not fit this Bible starts over.
        assertEquals(BibleStep(0, 1, 0L), AutoplayLogic.nextChapter(BiblePlace(7, 1), counts))
        assertEquals(BibleStep(0, 1, 0L), AutoplayLogic.nextChapter(BiblePlace(0, 9), counts))
        assertNull(AutoplayLogic.nextChapter(null, emptyList()))
        assertNull(AutoplayLogic.nextChapter(null, listOf(0, 0)))
    }

    @Test
    fun resumeMs_goesBackToTheStartOfTheVerse() {
        val timings = ChapterTimings(10_000L, true, listOf(VerseTime(1, 0L, 4000L), VerseTime(2, 4000L, 10_000L)))
        assertEquals(4000L, AutoplayLogic.resumeMs(timings, 6500L))
        assertEquals(0L, AutoplayLogic.resumeMs(timings, 3999L))
        assertEquals(6500L, AutoplayLogic.resumeMs(null, 6500L))
        assertEquals(0L, AutoplayLogic.resumeMs(timings, 0L))
    }
}
