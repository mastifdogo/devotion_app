package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerLogicTest {

    @Test
    fun isVisible_awayFromThePlayingEntryWhilePlayingOrPausedPartWay() {
        // Playing, on the lists / Bible / Settings (no entry shown) or on another entry.
        assertTrue(MiniPlayerLogic.isVisible(12, null, isPlaying = true, positionMs = 0))
        assertTrue(MiniPlayerLogic.isVisible(12, 13, isPlaying = true, positionMs = 5000))
        // Paused part-way.
        assertTrue(MiniPlayerLogic.isVisible(12, null, isPlaying = false, positionMs = 5000))
        // Never on the entry that is playing (it has the full audio bar).
        assertFalse(MiniPlayerLogic.isVisible(12, 12, isPlaying = true, positionMs = 5000))
        // Nothing loaded, or loaded but stopped at the start (e.g. after it finished).
        assertFalse(MiniPlayerLogic.isVisible(null, null, isPlaying = false, positionMs = 0))
        assertFalse(MiniPlayerLogic.isVisible(12, null, isPlaying = false, positionMs = 0))
    }

    @Test
    fun nowPlaying_headingOverTitle() {
        assertEquals(NowPlaying("Sunday, November 15, 2026", "The Good Shepherd"), MiniPlayerLogic.nowPlaying("Sunday, November 15, 2026", "The Good Shepherd"))
        assertEquals(NowPlaying("The Good Shepherd", "Devotion"), MiniPlayerLogic.nowPlaying(null, " The Good Shepherd "))
        assertEquals(NowPlaying("Lesson 11", "Devotion"), MiniPlayerLogic.nowPlaying("Lesson 11", ""))
        assertEquals(NowPlaying("Devotion", ""), MiniPlayerLogic.nowPlaying(null, null))
    }

    @Test
    fun notificationLines() {
        assertEquals("The Good Shepherd", MiniPlayerLogic.notificationTitle("Lesson 11", "The Good Shepherd"))
        assertEquals("Lesson 11", MiniPlayerLogic.notificationTitle("Lesson 11", " "))
        assertEquals("Devotion", MiniPlayerLogic.notificationTitle(null, null))
        assertEquals("Lesson 11", MiniPlayerLogic.notificationSubtitle("Lesson 11"))
        assertEquals("Devotion", MiniPlayerLogic.notificationSubtitle(null))
    }

    @Test
    fun progress() {
        assertEquals(0f, MiniPlayerLogic.progressFraction(5000, 0), 0f)
        assertEquals(0.25f, MiniPlayerLogic.progressFraction(15_000, 60_000), 1e-6f)
        assertEquals(1f, MiniPlayerLogic.progressFraction(90_000, 60_000), 0f)
        assertEquals(0f, MiniPlayerLogic.progressFraction(-5, 60_000), 0f)
        assertEquals("1:15", MiniPlayerLogic.time(75_000))
        assertEquals("1:02:05", MiniPlayerLogic.time(3_725_000))
        assertEquals("0:00", MiniPlayerLogic.time(-1))
        assertEquals("1:15 / 12:40", MiniPlayerLogic.progressText(75_000, 760_000))
        assertEquals("1:15", MiniPlayerLogic.progressText(75_000, 0))
        assertEquals("12:40 / 12:40", MiniPlayerLogic.progressText(999_000, 760_000))
    }

    @Test
    fun mediaId_roundTrips() {
        assertEquals("12:en_US-amy-medium", MiniPlayerLogic.mediaId(12, "en_US-amy-medium"))
        assertEquals(12L to "en_US-amy-medium", MiniPlayerLogic.parseMediaId("12:en_US-amy-medium"))
        assertEquals(7L to "", MiniPlayerLogic.parseMediaId(MiniPlayerLogic.mediaId(7, "")))
        assertEquals(7L to "a:b", MiniPlayerLogic.parseMediaId("7:a:b"))
        assertNull(MiniPlayerLogic.parseMediaId(null))
        assertNull(MiniPlayerLogic.parseMediaId(""))
        assertNull(MiniPlayerLogic.parseMediaId("abc"))
        assertNull(MiniPlayerLogic.parseMediaId("x:voice"))
        assertNull(MiniPlayerLogic.parseMediaId(":voice"))
    }
}
