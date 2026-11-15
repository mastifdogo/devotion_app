package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryKindsTest {
    private val us = Locale.US
    private val utc = ZoneId.of("UTC")

    private fun entry(
        id: Long,
        kind: String = "devotion",
        date: String? = null,
        created: String? = "2026-11-01T10:00:00.000Z",
        lesson: Int? = null,
    ) = Entry(id = id, title = "T$id", entryDate = date, createdAt = created, kind = kind, lessonNumber = lesson)

    @Test
    fun kindOf_unknownAndMissingAreDevotions() {
        assertEquals(EntryKinds.DAILY_READING, EntryKinds.kindOf(entry(1, "daily_reading")))
        assertEquals(EntryKinds.LESSON, EntryKinds.kindOf(entry(1, " Lesson ")))
        assertEquals(EntryKinds.DEVOTION, EntryKinds.kindOf(entry(1, "devotion")))
        assertEquals(EntryKinds.DEVOTION, EntryKinds.kindOf(entry(1, "sermon")))
        assertEquals(EntryKinds.DEVOTION, EntryKinds.kindOf(entry(1, "")))
        assertEquals(EntryKinds.DEVOTION, EntryKinds.kindOf(Entry(id = 1)))
    }

    @Test
    fun tabs_otherOnlyWhenThereAreDevotions() {
        val noOther = listOf(entry(1, "daily_reading"), entry(2, "lesson"))
        assertEquals(listOf(EntryTab.DAILY, EntryTab.LESSONS), EntryKinds.tabs(noOther))
        assertEquals(listOf(EntryTab.DAILY, EntryTab.LESSONS), EntryKinds.tabs(emptyList()))
        val withOther = noOther + entry(3, "weird")
        assertEquals(listOf(EntryTab.DAILY, EntryTab.LESSONS, EntryTab.OTHER), EntryKinds.tabs(withOther))
        assertEquals(EntryTab.DAILY, EntryKinds.visibleTab(EntryTab.OTHER, noOther))
        assertEquals(EntryTab.OTHER, EntryKinds.visibleTab(EntryTab.OTHER, withOther))
        assertEquals(EntryTab.LESSONS, EntryKinds.visibleTab(EntryTab.LESSONS, noOther))
        assertEquals(EntryTab.DAILY, EntryTab.fromKey("nonsense"))
        assertEquals(EntryTab.LESSONS, EntryTab.fromKey("lessons"))
    }

    @Test
    fun entriesFor_bucketsAndSortsNewestFirst() {
        val list = listOf(
            entry(1, "daily_reading", date = "2026-11-14"),
            entry(2, "daily_reading", date = null, created = "2026-11-15T08:00:00.000Z"),
            entry(3, "daily_reading", date = "2026-11-13"),
            entry(4, "lesson", date = "2026-11-08", lesson = 10),
            entry(5, "lesson", date = "2026-11-15", lesson = 11),
            entry(6, "other"),
        )
        assertEquals(listOf(2L, 1L, 3L), EntryKinds.entriesFor(EntryTab.DAILY, list).map { it.id })
        assertEquals(listOf(5L, 4L), EntryKinds.entriesFor(EntryTab.LESSONS, list).map { it.id })
        assertEquals(listOf(6L), EntryKinds.entriesFor(EntryTab.OTHER, list).map { it.id })
    }

    @Test
    fun entryDay_fallsBackToCreatedAtInTheZone() {
        assertEquals(LocalDate.of(2026, 11, 15), EntryKinds.entryDay(entry(1, date = "2026-11-15"), utc))
        assertEquals(LocalDate.of(2026, 11, 15), EntryKinds.entryDay(entry(1, date = "2026-11-15T00:00:00Z"), utc))
        val late = entry(1, date = null, created = "2026-11-15T23:30:00.000Z")
        assertEquals(LocalDate.of(2026, 11, 15), EntryKinds.entryDay(late, utc))
        assertEquals(LocalDate.of(2026, 11, 16), EntryKinds.entryDay(late, ZoneId.of("Europe/Berlin")))
        assertEquals(LocalDate.of(2026, 11, 1), EntryKinds.entryDay(entry(1, date = "garbage"), utc))
        assertNull(EntryKinds.entryDay(entry(1, date = null, created = null), utc))
    }

    @Test
    fun heading_dailyReadingIsTheFullDate() {
        val e = entry(1, "daily_reading", date = "2026-11-15", lesson = 11)
        assertEquals("Sunday, November 15, 2026", EntryKinds.heading(e, us, utc))
        assertEquals("Lesson 11", EntryKinds.dailyLessonLabel(e))
        assertNull(EntryKinds.dailyLessonLabel(entry(1, "daily_reading", date = "2026-11-15")))
        assertNull(EntryKinds.dailyLessonLabel(entry(1, "lesson", lesson = 11)))
        assertEquals("dimanche 15 novembre 2026", EntryKinds.heading(e, Locale.FRANCE, utc))
        assertNull(EntryKinds.heading(entry(1, "daily_reading", date = null, created = null), us, utc))
    }

    @Test
    fun heading_lessonCombinesNumberAndDate() {
        assertEquals(
            "Lesson 11 · November 15, 2026",
            EntryKinds.heading(entry(1, "lesson", date = "2026-11-15", lesson = 11), us, utc),
        )
        assertEquals("November 15, 2026", EntryKinds.heading(entry(1, "lesson", date = "2026-11-15"), us, utc))
        assertEquals("Lesson 4", EntryKinds.heading(entry(1, "lesson", date = null, created = null, lesson = 4), us, utc))
        assertNull(EntryKinds.heading(entry(1, "lesson", date = null, created = null), us, utc))
        assertNull(EntryKinds.heading(entry(1, "devotion", date = "2026-11-15"), us, utc))
    }

    @Test
    fun newestLesson_findsTheNewestWithThatNumber() {
        val list = listOf(
            entry(1, "lesson", date = "2026-08-01", lesson = 11),
            entry(2, "lesson", date = "2026-11-15", lesson = 11),
            entry(3, "lesson", date = "2026-11-20", lesson = 12),
            entry(4, "daily_reading", date = "2026-12-01", lesson = 11),
        )
        assertEquals(2L, EntryKinds.newestLesson(list, 11)?.id)
        assertNull(EntryKinds.newestLesson(list, 99))
    }
}
