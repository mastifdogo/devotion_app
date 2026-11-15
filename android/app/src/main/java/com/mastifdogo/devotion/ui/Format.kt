package com.mastifdogo.devotion.ui

import com.mastifdogo.devotion.data.Entry
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

private val displayDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

/** Human-readable date of an entry: entry_date, falling back to the day of created_at. */
fun entryDateLabel(entry: Entry): String {
    val explicit = entry.entryDate?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        return try {
            LocalDate.parse(explicit.take(10)).format(displayDate)
        } catch (e: Exception) {
            explicit
        }
    }
    val created = entry.createdAt?.takeIf { it.isNotBlank() } ?: return ""
    return try {
        Instant.parse(created).atZone(ZoneId.systemDefault()).toLocalDate().format(displayDate)
    } catch (e: Exception) {
        created.take(10)
    }
}

fun formatSyncTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
fun formatDuration(ms: Long): String {
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
