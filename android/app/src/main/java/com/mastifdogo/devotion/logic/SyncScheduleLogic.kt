package com.mastifdogo.devotion.logic

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The "Sync automatically" choices; [key] is what is stored in SharedPreferences. */
enum class AutoSync(val key: String, val label: String) {
    ON_OPEN("on_open", "When the app opens"),
    HOURLY("hourly", "Every hour"),
    EVERY_6_HOURS("every_6h", "Every 6 hours"),
    DAILY("daily", "Once a day at"),
    OFF("off", "Off");

    companion object {
        fun fromKey(key: String?): AutoSync = entries.firstOrNull { it.key == key } ?: ON_OPEN
    }
}

/** What has to be scheduled in the background for an [AutoSync] choice. */
sealed class SyncSchedule {
    /** No background work (When the app opens, Off). */
    data object None : SyncSchedule()
    data class Periodic(val hours: Long) : SyncSchedule()
    data class Daily(val hour: Int, val minute: Int) : SyncSchedule()
}

/** Pure rules for automatic sync. */
object SyncScheduleLogic {
    /** 06:30: the server checks mail at 06:00 by default. */
    const val DEFAULT_DAILY_MINUTES = 6 * 60 + 30

    fun scheduleFor(mode: AutoSync, dailyMinutes: Int): SyncSchedule = when (mode) {
        AutoSync.ON_OPEN, AutoSync.OFF -> SyncSchedule.None
        AutoSync.HOURLY -> SyncSchedule.Periodic(1)
        AutoSync.EVERY_6_HOURS -> SyncSchedule.Periodic(6)
        AutoSync.DAILY -> {
            val m = normalizeMinutes(dailyMinutes)
            SyncSchedule.Daily(m / 60, m % 60)
        }
    }

    /** Every choice except Off also syncs once when the app starts. */
    fun syncOnStart(mode: AutoSync): Boolean = mode != AutoSync.OFF

    /** Minutes after midnight, kept within one day (negative values wrap). */
    fun normalizeMinutes(minutes: Int): Int = Math.floorMod(minutes, 24 * 60)

    /** "06:30". */
    fun timeLabel(minutes: Int): String {
        val m = normalizeMinutes(minutes)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    /**
     * Time from [now] until the next [hour]:[minute] in [now]'s zone, strictly in the future
     * (exactly now counts as tomorrow). Works on local date-times, so a daylight-saving change
     * moves the instant, not the wall-clock time; a time skipped by the change runs just after it.
     */
    fun delayUntilNext(now: ZonedDateTime, hour: Int, minute: Int): Duration {
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var next = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
        if (!next.isAfter(now)) next = ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
        return Duration.between(now, next)
    }

    /** "Never", "just now", "1 minute ago", "5 hours ago", or the date and time for older syncs. */
    fun lastSyncedLabel(nowMillis: Long, thenMillis: Long, zone: ZoneId, locale: Locale): String {
        if (thenMillis <= 0L) return "Never"
        val minutes = (nowMillis - thenMillis) / 60_000L
        return when {
            minutes < 1L -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60L -> "$minutes minutes ago"
            minutes < 120L -> "1 hour ago"
            minutes < 24L * 60L -> "${minutes / 60L} hours ago"
            else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(Instant.ofEpochMilli(thenMillis).atZone(zone))
        }
    }
}
