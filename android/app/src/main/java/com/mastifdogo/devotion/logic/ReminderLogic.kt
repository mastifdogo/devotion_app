package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Entry
import com.mastifdogo.devotion.data.sortedNewestFirst

/** A daily time window given as minutes after midnight; it may run past midnight (21:00–07:00). */
object TimeWindow {
    /** True when [nowMinutes] is in [from, until); from == until is an empty window. */
    fun contains(nowMinutes: Int, fromMinutes: Int, untilMinutes: Int): Boolean {
        val now = Math.floorMod(nowMinutes, 24 * 60)
        val from = Math.floorMod(fromMinutes, 24 * 60)
        val until = Math.floorMod(untilMinutes, 24 * 60)
        return when {
            from == until -> false
            from < until -> now in from until until
            else -> now >= from || now < until
        }
    }
}

/** What the reminder check should do right now. */
sealed class ReminderDecision {
    /** Post (or refresh) the reminder for this entry. */
    data class Notify(val entryId: Long, val title: String) : ReminderDecision()
    /** Nothing to remind about: remove a reminder that is showing. */
    data object Cancel : ReminderDecision()
    /** Quiet hours: post nothing now, and leave things as they are. */
    data object Quiet : ReminderDecision()
}

/** Pure rules for read tracking and the "today's reading is waiting" reminder. */
object ReminderLogic {
    const val DEFAULT_QUIET_FROM = 21 * 60
    const val DEFAULT_QUIET_UNTIL = 7 * 60

    /**
     * The latest entry: the newest by the lists' order (entry_date, falling back to created_at)
     * across daily readings and lessons, not archived or deleted. Null when there is none.
     */
    fun latestEntry(entries: List<Entry>): Entry? =
        entries.filter { !it.archived && !it.deleted }.sortedNewestFirst().firstOrNull()

    /**
     * Should a reminder fire now? Off, no entries or the latest one read -> Cancel; unread but in
     * the quiet hours -> Quiet; otherwise Notify.
     */
    fun decide(
        enabled: Boolean,
        entries: List<Entry>,
        readIds: Set<Long>,
        nowMinutes: Int,
        quietFrom: Int,
        quietUntil: Int,
    ): ReminderDecision {
        if (!enabled) return ReminderDecision.Cancel
        val latest = latestEntry(entries) ?: return ReminderDecision.Cancel
        if (latest.id in readIds) return ReminderDecision.Cancel
        if (TimeWindow.contains(nowMinutes, quietFrom, quietUntil)) return ReminderDecision.Quiet
        return ReminderDecision.Notify(latest.id, latest.title.trim().ifEmpty { "Devotion" })
    }

    /** "Today's reading is waiting: The Good Shepherd". */
    fun notificationText(title: String): String = "Today's reading is waiting: $title"

    /**
     * True when a screen scrolled to [value] of [maxValue] is at its bottom (within [slopPx]);
     * a text that fits without scrolling (maxValue 0) is at the bottom. [maxValue] is
     * Int.MAX_VALUE until the content has been measured, which never counts.
     */
    fun isAtBottom(value: Int, maxValue: Int, slopPx: Int): Boolean =
        maxValue != Int.MAX_VALUE && value >= maxValue - slopPx.coerceAtLeast(0)
}
