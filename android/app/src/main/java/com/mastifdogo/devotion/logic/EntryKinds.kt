package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.sortedNewestFirst
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The tabs of the main screen; [key] is what is stored in SharedPreferences. */
enum class EntryTab(val key: String, val label: String) {
    DAILY("daily", "Daily readings"),
    LESSONS("lessons", "Lessons"),
    BIBLE("bible", "Bible");

    companion object {
        fun fromKey(key: String?): EntryTab = entries.firstOrNull { it.key == key } ?: DAILY
    }
}

/** Pure logic for the kinds of entries: which tab they go in and how they are labelled. */
object EntryKinds {
    const val DAILY_READING = "daily_reading"
    const val LESSON = "lesson"
    const val DEVOTION = "devotion"

    /** The entry's kind; anything other than a daily reading or a lesson counts as a devotion. */
    fun kindOf(entry: Entry): String = when (entry.kind.trim().lowercase(Locale.ROOT)) {
        DAILY_READING -> DAILY_READING
        LESSON -> LESSON
        else -> DEVOTION
    }

    /** Lessons go to the Lessons tab; daily readings and everything else (devotions) to Daily readings. */
    fun tabFor(entry: Entry): EntryTab =
        if (kindOf(entry) == LESSON) EntryTab.LESSONS else EntryTab.DAILY

    /** The entries of one tab, newest first (entry_date, falling back to created_at); none for Bible. */
    fun entriesFor(tab: EntryTab, entries: List<Entry>): List<Entry> =
        if (tab == EntryTab.BIBLE) emptyList() else entries.filter { tabFor(it) == tab }.sortedNewestFirst()

    /** The day an entry is for: its entry_date, else the local day of created_at; null if neither parses. */
    fun entryDay(entry: Entry, zone: ZoneId): LocalDate? {
        val explicit = entry.entryDate?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            try {
                return LocalDate.parse(explicit.take(10))
            } catch (e: Exception) {
                // fall through to created_at
            }
        }
        val created = entry.createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(created).atZone(zone).toLocalDate()
        } catch (e: Exception) {
            null
        }
    }

    /** "Sunday, November 15, 2026" (in [locale]'s format). */
    fun fullDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

    /** "November 15, 2026" (in [locale]'s format). */
    fun longDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)

    fun lessonLabel(number: Int): String = "Lesson $number"

    /**
     * The heading of a daily reading or lesson, or null for devotions (which keep their title as
     * heading and show their date the old way). Daily reading: the full date. Lesson: "Lesson 11 · November 15, 2026", just the
     * number or just the date when the other is missing. Null as well when there is nothing to show.
     */
    fun heading(entry: Entry, locale: Locale, zone: ZoneId): String? {
        val day = entryDay(entry, zone)
        return when (kindOf(entry)) {
            DAILY_READING -> day?.let { fullDate(it, locale) }
            LESSON -> {
                val parts = listOfNotNull(
                    entry.lessonNumber?.let { lessonLabel(it) },
                    day?.let { longDate(it, locale) },
                )
                if (parts.isEmpty()) null else parts.joinToString(" · ")
            }
            else -> null
        }
    }

    /** The small "Lesson 11" label of a daily reading that belongs to a lesson, else null. */
    fun dailyLessonLabel(entry: Entry): String? =
        if (kindOf(entry) == DAILY_READING) entry.lessonNumber?.let { lessonLabel(it) } else null

    /** The newest lesson entry with [number], or null. */
    fun newestLesson(entries: List<Entry>, number: Int): Entry? =
        entries.filter { kindOf(it) == LESSON && it.lessonNumber == number }.sortedNewestFirst().firstOrNull()
}
