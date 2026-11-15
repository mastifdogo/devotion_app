package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.StoredAnnotation
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A character range in an entry body; [end] is exclusive. */
data class Passage(val start: Int, val end: Int)

/**
 * Pure annotation logic (no Android): ranges, timestamps, and merging local and server
 * annotations. Drawing and hit-testing of highlights lives in [HighlightLogic].
 */
object TextLogic {
    const val COLOR_NONE = "none"
    val HIGHLIGHT_COLORS: List<String> = listOf("yellow", "green", "blue", "pink")

    private val isoMillis: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    // ---- links ---------------------------------------------------------------------------------

    /**
     * Links that can be drawn on a body of [bodyLength] characters: ranges outside the body or
     * empty are dropped (their index maps to null) and so are links overlapping an earlier one.
     */
    fun usableLinks(links: List<Passage>, bodyLength: Int): List<Passage?> {
        val result = arrayOfNulls<Passage>(links.size)
        var lastEnd = -1
        for (index in links.indices.sortedBy { links[it].start }) {
            val link = links[index]
            if (link.start < 0 || link.end > bodyLength || link.start >= link.end) continue
            if (link.start < lastEnd) continue
            result[index] = link
            lastEnd = link.end
        }
        return result.toList()
    }

    // ---- ranges -----------------------------------------------------------------------------

    fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean = aStart < bEnd && bStart < aEnd

    /** A note on the whole entry rather than on a passage. */
    fun isEntryNote(a: Annotation): Boolean = a.start == 0 && a.end == 0

    /** The live whole-entry note of [entryId] (the most recently changed one), or null. */
    fun entryNote(annotations: List<Annotation>, entryId: Long): Annotation? =
        annotations
            .filter { !it.deleted && it.entryId == entryId && isEntryNote(it) }
            .maxWithOrNull { x, y -> compareTimestamps(x.updatedAt, y.updatedAt) }

    /** The text an annotation points at, with its range clamped to the body. */
    fun quote(body: String, a: Annotation): String {
        val start = a.start.coerceIn(0, body.length)
        val end = a.end.coerceIn(start, body.length)
        return body.substring(start, end)
    }

    // ---- timestamps -------------------------------------------------------------------------

    /** ISO-8601 UTC with milliseconds, e.g. "2026-11-15T12:00:00.000Z". */
    fun timestamp(epochMillis: Long): String = isoMillis.format(Instant.ofEpochMilli(epochMillis))

    /** A timestamp for an edit made now that is strictly later than [previous] (clock-skew safe). */
    fun nextTimestamp(previous: String?, nowMillis: Long): String {
        val before = previous?.let { parseMillis(it) } ?: return timestamp(nowMillis)
        return timestamp(maxOf(nowMillis, before + 1))
    }

    private fun parseMillis(iso: String): Long? =
        try {
            Instant.parse(iso.trim()).toEpochMilli()
        } catch (e: Exception) {
            null
        }

    /** Compares two ISO timestamps by instant; unparseable values fall back to string order. */
    fun compareTimestamps(a: String, b: String): Int {
        val x = parseMillis(a)
        val y = parseMillis(b)
        return if (x != null && y != null) x.compareTo(y) else a.compareTo(b)
    }

    // ---- merge ------------------------------------------------------------------------------

    /**
     * Applies annotations received from the server. A server copy that is at least as new as the
     * local one replaces it (and is clean); a newer local edit stays and keeps its dirty flag.
     * Deleted annotations that no longer need uploading are dropped.
     */
    fun mergeRemote(local: List<StoredAnnotation>, remote: List<Annotation>): List<StoredAnnotation> {
        val byId = LinkedHashMap<String, StoredAnnotation>()
        for (item in local) byId[item.annotation.id] = item
        for (r in remote) {
            val mine = byId[r.id]
            if (mine == null || compareTimestamps(r.updatedAt, mine.annotation.updatedAt) >= 0) {
                byId[r.id] = StoredAnnotation(r, dirty = false)
            }
        }
        return byId.values.filter { it.dirty || !it.annotation.deleted }
    }

    /** Inserts or replaces a locally edited annotation and marks it for upload. */
    fun upsertLocal(local: List<StoredAnnotation>, edited: Annotation): List<StoredAnnotation> {
        val replaced = StoredAnnotation(edited, dirty = true)
        var found = false
        val result = local.map {
            if (it.annotation.id == edited.id) {
                found = true
                replaced
            } else {
                it
            }
        }
        return if (found) result else result + replaced
    }
}
