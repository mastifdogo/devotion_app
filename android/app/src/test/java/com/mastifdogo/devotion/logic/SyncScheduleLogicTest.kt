package com.mastifdogo.devotion.logic

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScheduleLogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    @Test
    fun scheduleFor_mapsEveryChoice() {
        assertEquals(SyncSchedule.None, SyncScheduleLogic.scheduleFor(AutoSync.ON_OPEN, 390))
        assertEquals(SyncSchedule.None, SyncScheduleLogic.scheduleFor(AutoSync.OFF, 390))
        assertEquals(SyncSchedule.Periodic(1), SyncScheduleLogic.scheduleFor(AutoSync.HOURLY, 390))
        assertEquals(SyncSchedule.Periodic(6), SyncScheduleLogic.scheduleFor(AutoSync.EVERY_6_HOURS, 390))
        assertEquals(SyncSchedule.Daily(6, 30), SyncScheduleLogic.scheduleFor(AutoSync.DAILY, 390))
        assertEquals(SyncSchedule.Daily(23, 59), SyncScheduleLogic.scheduleFor(AutoSync.DAILY, -1))
        assertEquals(SyncSchedule.Daily(0, 0), SyncScheduleLogic.scheduleFor(AutoSync.DAILY, 24 * 60))
    }

    @Test
    fun syncOnStart_exceptOff() {
        for (mode in AutoSync.entries) assertEquals(mode != AutoSync.OFF, SyncScheduleLogic.syncOnStart(mode))
        assertEquals(AutoSync.ON_OPEN, AutoSync.fromKey(null))
        assertEquals(AutoSync.ON_OPEN, AutoSync.fromKey("weird"))
        assertEquals(AutoSync.DAILY, AutoSync.fromKey("daily"))
    }

    @Test
    fun timeLabel() {
        assertEquals("06:30", SyncScheduleLogic.timeLabel(SyncScheduleLogic.DEFAULT_DAILY_MINUTES))
        assertEquals("00:05", SyncScheduleLogic.timeLabel(5))
        assertEquals("23:59", SyncScheduleLogic.timeLabel(-1))
    }

    @Test
    fun delayUntilNext_laterTodayOrTomorrow() {
        assertEquals(Duration.ofMinutes(30), SyncScheduleLogic.delayUntilNext(at(utc, 2026, 11, 15, 6, 0), 6, 30))
        // Exactly the time: the next run is tomorrow.
        assertEquals(Duration.ofHours(24), SyncScheduleLogic.delayUntilNext(at(utc, 2026, 11, 15, 6, 30), 6, 30))
        assertEquals(Duration.ofHours(23).plusMinutes(59), SyncScheduleLogic.delayUntilNext(at(utc, 2026, 11, 15, 6, 31), 6, 30))
        // After midnight, before the time.
        assertEquals(Duration.ofHours(6).plusMinutes(20), SyncScheduleLogic.delayUntilNext(at(utc, 2026, 11, 16, 0, 10), 6, 30))
        // Late evening for an early-morning time.
        assertEquals(Duration.ofHours(7), SyncScheduleLogic.delayUntilNext(at(utc, 2026, 12, 31, 23, 30), 6, 30))
    }

    @Test
    fun delayUntilNext_keepsWallClockTimeAcrossDaylightSaving() {
        // Berlin springs forward on 2026-03-29 at 02:00 -> 03:00: that night is 23 hours long.
        assertEquals(Duration.ofHours(23), SyncScheduleLogic.delayUntilNext(at(berlin, 2026, 3, 28, 6, 30), 6, 30))
        // ...and falls back on 2026-10-25 at 03:00 -> 02:00: that night is 25 hours long.
        assertEquals(Duration.ofHours(25), SyncScheduleLogic.delayUntilNext(at(berlin, 2026, 10, 24, 6, 30), 6, 30))
        // A time skipped by the change (02:30 on 2026-03-29) runs just after the jump, at 03:30 local.
        val now = at(berlin, 2026, 3, 29, 1, 0)
        val delay = SyncScheduleLogic.delayUntilNext(now, 2, 30)
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), now.plus(delay).toLocalDateTime())
        assertTrue(!delay.isNegative && !delay.isZero)
    }

    @Test
    fun lastSyncedLabel() {
        val now = 1_794_744_000_000L
        assertEquals("Never", SyncScheduleLogic.lastSyncedLabel(now, 0L, utc, Locale.US))
        assertEquals("just now", SyncScheduleLogic.lastSyncedLabel(now, now - 20_000L, utc, Locale.US))
        assertEquals("1 minute ago", SyncScheduleLogic.lastSyncedLabel(now, now - 60_000L, utc, Locale.US))
        assertEquals("45 minutes ago", SyncScheduleLogic.lastSyncedLabel(now, now - 45 * 60_000L, utc, Locale.US))
        assertEquals("1 hour ago", SyncScheduleLogic.lastSyncedLabel(now, now - 90 * 60_000L, utc, Locale.US))
        assertEquals("5 hours ago", SyncScheduleLogic.lastSyncedLabel(now, now - 5 * 3_600_000L, utc, Locale.US))
        val old = SyncScheduleLogic.lastSyncedLabel(now, now - 3 * 86_400_000L, utc, Locale.US)
        assertTrue(old, old.startsWith("Nov 12, 2026"))
        assertFalse(old.contains("ago"))
    }
}
