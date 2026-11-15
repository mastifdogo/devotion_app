package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.StoredAnnotation
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A character range in an entry body; [end] is exclusive. */
data class Passage(val start: Int, val end: Int)

/** A paragraph of the body and the sentences inside it (all offsets are into the whole body). */
data class Paragraph(val start: Int, val end: Int, val sentences: List<Passage>)

/**
 * Pure text and annotation logic (no Android): splitting a body into paragraphs and sentences,
 * finding annotations by overlap, and merging local and server annotations.
 */
object TextLogic {
    const val COLOR_NONE = "none"
    val HIGHLIGHT_COLORS: List<String> = listOf("yellow", "green", "blue", "pink")

    private val paragraphBreak = Regex("\\r?\\n[ \\t]*(\\r?\\n[ \\t]*)+")
    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'”’»)]"
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "vs", "v", "vv", "cf", "etc", "rev", "fr", "sr", "jr",
        "ch", "no", "prof", "eg", "ie",
    )
    private val isoMillis: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    // ---- paragraphs and sentences -----------------------------------------------------------

    /** Paragraphs are separated by blank lines; surrounding whitespace is not part of a paragraph. */
    fun paragraphs(body: String): List<Paragraph> {
        val result = ArrayList<Paragraph>()
        var from = 0
        for (match in paragraphBreak.findAll(body)) {
            addParagraph(body, from, match.range.first, result)
            from = match.range.last + 1
        }
        addParagraph(body, from, body.length, result)
        return result
    }

    private fun addParagraph(body: String, rawStart: Int, rawEnd: Int, out: MutableList<Paragraph>) {
        var start = rawStart
        var end = rawEnd
        while (start < end && body[start].isWhitespace()) start++
        while (end > start && body[end - 1].isWhitespace()) end--
        if (start >= end) return
        out.add(Paragraph(start, end, sentences(body, start, end)))
    }

    /**
     * Sentences of body[[from], [to]). A sentence ends after ". ! ? …" (plus closing quotes or
     * brackets) when whitespace follows and the next word does not start in lower case, or at a
     * line break. Common abbreviations ("Mr.", "St.", "v.") and single-letter initials do not end
     * a sentence. Every non-whitespace character belongs to exactly one sentence.
     */
    fun sentences(body: String, from: Int = 0, to: Int = body.length): List<Passage> {
        val result = ArrayList<Passage>()
        var start = from
        var i = from
        while (i < to) {
            val c = body[i]
            if (c == '\n') {
                addSentence(body, start, i, result)
                start = i + 1
                i++
                continue
            }
            if (TERMINATORS.indexOf(c) >= 0) {
                var j = i + 1
                while (j < to && (TERMINATORS.indexOf(body[j]) >= 0 || CLOSERS.indexOf(body[j]) >= 0)) j++
                if (j >= to) {
                    i = j
                    continue
                }
                if (body[j].isWhitespace() && isBoundary(body, start, i, j, to)) {
                    addSentence(body, start, j, result)
                    start = j
                }
                i = j
                continue
            }
            i++
        }
        addSentence(body, start, to, result)
        return result
    }

    private fun isBoundary(body: String, sentenceStart: Int, terminatorAt: Int, afterEnd: Int, to: Int): Boolean {
        var next = afterEnd
        while (next < to && body[next].isWhitespace()) {
            if (body[next] == '\n') return true
            next++
        }
        if (next >= to) return true
        if (body[next].isLowerCase()) return false
        if (body[terminatorAt] == '.') {
            var wordStart = terminatorAt
            while (wordStart > sentenceStart && body[wordStart - 1].isLetter()) wordStart--
            val word = body.substring(wordStart, terminatorAt)
            if (word.length == 1 && word[0].isUpperCase()) return false
            if (word.lowercase() in abbreviations) return false
        }
        return true
    }

    private fun addSentence(body: String, rawStart: Int, rawEnd: Int, out: MutableList<Passage>) {
        var start = rawStart
        var end = rawEnd
        while (start < end && body[start].isWhitespace()) start++
        while (end > start && body[end - 1].isWhitespace()) end--
        if (start < end) out.add(Passage(start, end))
    }

    // ---- ranges -----------------------------------------------------------------------------

    fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean = aStart < bEnd && bStart < aEnd

    private fun overlapLength(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)

    /** A note on the whole entry rather than on a passage. */
    fun isEntryNote(a: Annotation): Boolean = a.start == 0 && a.end == 0

    /**
     * The live passage annotation of [entryId] that overlaps [start, end) the most
     * (the most recently changed one on a tie), or null.
     */
    fun findOverlapping(annotations: List<Annotation>, entryId: Long, start: Int, end: Int): Annotation? {
        var best: Annotation? = null
        var bestLength = 0
        for (a in annotations) {
            if (a.deleted || a.entryId != entryId || isEntryNote(a)) continue
            if (!overlaps(a.start, a.end, start, end)) continue
            val length = overlapLength(a.start, a.end, start, end)
            val current = best
            if (current == null || length > bestLength ||
                (length == bestLength && compareTimestamps(a.updatedAt, current.updatedAt) > 0)
            ) {
                best = a
                bestLength = length
            }
        }
        return best
    }

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
