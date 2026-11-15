package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation

/**
 * A stretch of the body drawn in one style. [color] is the background highlight colour name (null
 * for none), [annotationId] the annotation a tap there opens (null for none) and [link] the index
 * of the verse-reference link it belongs to (-1 for none). Offsets are body offsets, end exclusive.
 */
data class StyledRun(
    val start: Int,
    val end: Int,
    val color: String?,
    val annotationId: String?,
    val link: Int,
)

/** A comment marker drawn right after body offset [offset] (the end of a commented range). */
data class CommentMarker(val offset: Int, val annotationId: String)

/**
 * Pure logic for drawing highlights on an entry body and finding what was tapped.
 *
 * The displayed text is the body with [MARKER] inserted after every commented range, so offsets
 * in the displayed text and in the body differ; [toDisplayStart], [toDisplayEnd] and [toBody]
 * convert between them.
 */
object HighlightLogic {
    /** No-break space + speech balloon (3 UTF-16 chars). */
    const val MARKER = " 💬"

    /** Live passage annotations whose range, clamped to the body, is not empty. */
    private fun drawable(annotations: List<Annotation>, bodyLength: Int): List<Annotation> =
        annotations.filter { a ->
            !a.deleted && !TextLogic.isEntryNote(a) &&
                a.start.coerceIn(0, bodyLength) < a.end.coerceIn(0, bodyLength)
        }

    /** True when [a] (at list position [ai]) counts as newer than [b] (at [bi]); ties go to the later one in the list. */
    private fun isNewer(a: Annotation, ai: Int, b: Annotation, bi: Int): Boolean {
        val c = TextLogic.compareTimestamps(a.updatedAt, b.updatedAt)
        return c > 0 || (c == 0 && ai > bi)
    }

    private fun newestCovering(list: List<Annotation>, from: Int, to: Int, filter: (Annotation) -> Boolean): Annotation? {
        var best: Annotation? = null
        var bestIndex = -1
        for ((i, a) in list.withIndex()) {
            if (a.start > from || a.end < to || !filter(a)) continue
            val current = best
            if (current == null || isNewer(a, i, current, bestIndex)) {
                best = a
                bestIndex = i
            }
        }
        return best
    }

    /**
     * Cuts the body at every annotation and link boundary and returns, in order, the runs that
     * carry any style. Where coloured annotations overlap, the most recently updated one's colour
     * wins; the annotation a tap opens is the most recently updated one covering the run, with or
     * without colour. Adjacent runs with identical styles are merged.
     */
    fun styledRuns(bodyLength: Int, annotations: List<Annotation>, links: List<Passage?>): List<StyledRun> {
        val live = drawable(annotations, bodyLength).map {
            it.copy(start = it.start.coerceIn(0, bodyLength), end = it.end.coerceIn(0, bodyLength))
        }
        val cuts = sortedSetOf(0, bodyLength)
        for (a in live) {
            cuts.add(a.start)
            cuts.add(a.end)
        }
        for (link in links) {
            if (link == null) continue
            cuts.add(link.start.coerceIn(0, bodyLength))
            cuts.add(link.end.coerceIn(0, bodyLength))
        }
        val points = cuts.toList()
        val result = ArrayList<StyledRun>()
        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            if (from >= to) continue
            val coloured = newestCovering(live, from, to) { it.color in TextLogic.HIGHLIGHT_COLORS }
            val any = newestCovering(live, from, to) { true }
            val link = links.indexOfFirst { it != null && from >= it.start && to <= it.end }
            if (coloured == null && any == null && link < 0) continue
            val run = StyledRun(from, to, coloured?.color, any?.id, link)
            val last = result.lastOrNull()
            if (last != null && last.end == from && last.color == run.color &&
                last.annotationId == run.annotationId && last.link == run.link
            ) {
                result[result.size - 1] = last.copy(end = to)
            } else {
                result.add(run)
            }
        }
        return result
    }

    /** The most recently updated live passage annotation containing body offset [offset], or null. */
    fun annotationAt(annotations: List<Annotation>, bodyLength: Int, offset: Int): Annotation? {
        if (offset < 0 || offset >= bodyLength) return null
        val live = drawable(annotations, bodyLength)
        return newestCovering(live, offset, offset + 1) { true }
    }

    /** Index of the link containing body offset [offset], or -1. */
    fun linkAt(links: List<Passage?>, offset: Int): Int =
        links.indexOfFirst { it != null && offset >= it.start && offset < it.end }

    /** One marker per end offset of a commented range (the newest such annotation), sorted by offset. */
    fun commentMarkers(annotations: List<Annotation>, bodyLength: Int): List<CommentMarker> {
        val live = drawable(annotations, bodyLength).filter { it.comment.isNotBlank() }
        val byOffset = sortedMapOf<Int, Pair<Annotation, Int>>()
        for ((i, a) in live.withIndex()) {
            val offset = a.end.coerceIn(0, bodyLength)
            val current = byOffset[offset]
            if (current == null || isNewer(a, i, current.first, current.second)) byOffset[offset] = a to i
        }
        return byOffset.map { (offset, pair) -> CommentMarker(offset, pair.first.id) }
    }

    /** The body with a [MARKER] inserted at each marker offset. */
    fun displayText(body: String, markers: List<CommentMarker>): String {
        val sb = StringBuilder(body.length + markers.size * MARKER.length)
        var from = 0
        for (m in markers) {
            val at = m.offset.coerceIn(from, body.length)
            sb.append(body, from, at)
            sb.append(MARKER)
            from = at
        }
        sb.append(body, from, body.length)
        return sb.toString()
    }

    /** Display offset where a span starting at body offset [bodyOffset] starts (after a marker there). */
    fun toDisplayStart(markers: List<CommentMarker>, bodyOffset: Int): Int =
        bodyOffset + MARKER.length * markers.count { it.offset <= bodyOffset }

    /** Display offset where a span ending at body offset [bodyOffset] ends (before a marker there). */
    fun toDisplayEnd(markers: List<CommentMarker>, bodyOffset: Int): Int =
        bodyOffset + MARKER.length * markers.count { it.offset < bodyOffset }

    /** Body offset for a display offset; a position inside a marker maps to the marker's offset. */
    fun toBody(markers: List<CommentMarker>, displayOffset: Int): Int {
        var shift = 0
        for (m in markers) {
            val markerStart = m.offset + shift
            if (displayOffset <= markerStart) break
            if (displayOffset < markerStart + MARKER.length) return m.offset
            shift += MARKER.length
        }
        return (displayOffset - shift).coerceAtLeast(0)
    }

    /** The annotation whose marker covers display offset [displayOffset], or null. */
    fun markerAt(markers: List<CommentMarker>, displayOffset: Int): String? {
        var shift = 0
        for (m in markers) {
            val markerStart = m.offset + shift
            if (displayOffset < markerStart) return null
            if (displayOffset < markerStart + MARKER.length) return m.annotationId
            shift += MARKER.length
        }
        return null
    }
}
