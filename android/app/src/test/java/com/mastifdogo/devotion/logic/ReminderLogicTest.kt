package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderLogicTest {
    private fun entry(id: Long, date: String?, kind: String = "daily_reading", archived: Boolean = false, deleted: Boolean = false, title: String = "T$id") =
        Entry(id = id, title = title, entryDate = date, createdAt = "2026-11-01T10:00:00.000Z", kind = kind, archived = archived, deleted = deleted)

    private val entries = listOf(
        entry(1, "2026-11-14"),
        entry(2, "2026-11-15", kind = "lesson", title = " The Good Shepherd "),
        entry(3, "2026-11-16", archived = true),
        entry(4, "2026-11-17", deleted = true),
    )

    @Test
    fun timeWindow_wrapsMidnight() {
        val from = 21 * 60
        val until = 7 * 60
        assertTrue(TimeWindow.contains(22 * 60, from, until))
        assertTrue(TimeWindow.contains(0, from, until))
        assertTrue(TimeWindow.contains(6 * 60 + 59, from, until))
        assertTrue(TimeWindow.contains(21 * 60, from, until))
        assertFalse(TimeWindow.contains(7 * 60, from, until))
        assertFalse(TimeWindow.contains(12 * 60, from, until))
        // A window inside one day.
        assertTrue(TimeWindow.contains(10 * 60, 9 * 60, 17 * 60))
        assertFalse(TimeWindow.contains(17 * 60, 9 * 60, 17 * 60))
        assertFalse(TimeWindow.contains(8 * 60, 9 * 60, 17 * 60))
        // Equal times: no window at all.
        assertFalse(TimeWindow.contains(9 * 60, 9 * 60, 9 * 60))
        // Values outside one day wrap.
        assertTrue(TimeWindow.contains(24 * 60 + 30, from, until))
    }

    @Test
    fun latestEntry_isTheNewestLiveOneAcrossKinds() {
        assertEquals(2L, ReminderLogic.latestEntry(entries)?.id)
        assertNull(ReminderLogic.latestEntry(emptyList()))
        assertNull(ReminderLogic.latestEntry(listOf(entry(3, "2026-11-16", archived = true))))
    }

    @Test
    fun decide_notifiesAboutTheUnreadLatestOutsideQuietHours() {
        val noon = 12 * 60
        assertEquals(ReminderDecision.Notify(2, "The Good Shepherd"), ReminderLogic.decide(true, entries, emptySet(), noon, 21 * 60, 7 * 60))
        // Older entries being read does not matter.
        assertEquals(ReminderDecision.Notify(2, "The Good Shepherd"), ReminderLogic.decide(true, entries, setOf(1L), noon, 21 * 60, 7 * 60))
        // Read: cancel, also during quiet hours.
        assertEquals(ReminderDecision.Cancel, ReminderLogic.decide(true, entries, setOf(2L), noon, 21 * 60, 7 * 60))
        assertEquals(ReminderDecision.Cancel, ReminderLogic.decide(true, entries, setOf(2L), 23 * 60, 21 * 60, 7 * 60))
        // Quiet hours: nothing is posted.
        assertEquals(ReminderDecision.Quiet, ReminderLogic.decide(true, entries, emptySet(), 23 * 60, 21 * 60, 7 * 60))
        assertEquals(ReminderDecision.Quiet, ReminderLogic.decide(true, entries, emptySet(), 6 * 60, 21 * 60, 7 * 60))
        assertEquals(ReminderDecision.Notify(2, "The Good Shepherd"), ReminderLogic.decide(true, entries, emptySet(), 7 * 60, 21 * 60, 7 * 60))
        // Switched off, or nothing to read.
        assertEquals(ReminderDecision.Cancel, ReminderLogic.decide(false, entries, emptySet(), noon, 21 * 60, 7 * 60))
        assertEquals(ReminderDecision.Cancel, ReminderLogic.decide(true, emptyList(), emptySet(), noon, 21 * 60, 7 * 60))
        // A blank title still gives a usable text.
        val blank = listOf(entry(9, "2026-11-20", title = "  "))
        assertEquals(ReminderDecision.Notify(9, "Devotion"), ReminderLogic.decide(true, blank, emptySet(), noon, 21 * 60, 7 * 60))
        assertEquals("Today's reading is waiting: The Good Shepherd", ReminderLogic.notificationText("The Good Shepherd"))
    }

    @Test
    fun isAtBottom() {
        assertTrue(ReminderLogic.isAtBottom(1000, 1000, 0))
        assertTrue(ReminderLogic.isAtBottom(990, 1000, 16))
        assertFalse(ReminderLogic.isAtBottom(900, 1000, 16))
        // The whole text fits: at the bottom without scrolling.
        assertTrue(ReminderLogic.isAtBottom(0, 0, 16))
        // Not measured yet.
        assertFalse(ReminderLogic.isAtBottom(0, Int.MAX_VALUE, 16))
    }
}
