package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightLogicTest {

    private fun ann(
        id: String,
        start: Int,
        end: Int,
        color: String = "yellow",
        updatedAt: String = "2026-11-15T12:00:00.000Z",
        comment: String = "",
        deleted: Boolean = false,
    ) = Annotation(id, 1, start, end, color, comment, updatedAt, deleted)

    private val t1 = "2026-11-15T12:00:00.000Z"
    private val t2 = "2026-11-15T12:00:01.000Z"

    // ---- runs -------------------------------------------------------------------------------

    @Test
    fun styledRuns_singleHighlightAndLink() {
        val runs = HighlightLogic.styledRuns(
            bodyLength = 30,
            annotations = listOf(ann("a", 2, 8)),
            links = listOf(Passage(20, 25)),
        )
        assertEquals(
            listOf(
                StyledRun(2, 8, "yellow", "a", -1),
                StyledRun(20, 25, null, null, 0),
            ),
            runs,
        )
    }

    @Test
    fun styledRuns_laterUpdatedColourWinsOnOverlap() {
        val runs = HighlightLogic.styledRuns(
            bodyLength = 30,
            annotations = listOf(
                ann("new", 5, 15, color = "green", updatedAt = t2),
                ann("old", 0, 10, color = "yellow", updatedAt = t1),
            ),
            links = emptyList(),
        )
        assertEquals(
            listOf(
                StyledRun(0, 5, "yellow", "old", -1),
                StyledRun(5, 15, "green", "new", -1),
            ),
            runs,
        )
        // Equal timestamps: the later one in the list wins.
        val tie = HighlightLogic.styledRuns(10, listOf(ann("x", 0, 10, "blue"), ann("y", 0, 10, "pink")), emptyList())
        assertEquals(listOf(StyledRun(0, 10, "pink", "y", -1)), tie)
    }

    @Test
    fun styledRuns_commentOnlyKeepsUnderlyingColourButOpensNewest() {
        val runs = HighlightLogic.styledRuns(
            bodyLength = 20,
            annotations = listOf(
                ann("hl", 0, 10, color = "blue", updatedAt = t1),
                ann("note", 4, 6, color = "none", comment = "hi", updatedAt = t2),
            ),
            links = emptyList(),
        )
        assertEquals(
            listOf(
                StyledRun(0, 4, "blue", "hl", -1),
                StyledRun(4, 6, "blue", "note", -1),
                StyledRun(6, 10, "blue", "hl", -1),
            ),
            runs,
        )
    }

    @Test
    fun styledRuns_linkInsideHighlightSplitsIt() {
        val runs = HighlightLogic.styledRuns(20, listOf(ann("a", 0, 20)), listOf(null, Passage(5, 9)))
        assertEquals(
            listOf(
                StyledRun(0, 5, "yellow", "a", -1),
                StyledRun(5, 9, "yellow", "a", 1),
                StyledRun(9, 20, "yellow", "a", -1),
            ),
            runs,
        )
    }

    @Test
    fun styledRuns_ignoresDeletedNotesEmptyAndClampsToBody() {
        val runs = HighlightLogic.styledRuns(
            bodyLength = 10,
            annotations = listOf(
                ann("del", 0, 5, deleted = true),
                ann("note", 0, 0, color = "none", comment = "whole entry"),
                ann("empty", 3, 3),
                ann("outside", 20, 30),
                ann("clamped", 8, 99),
            ),
            links = emptyList(),
        )
        assertEquals(listOf(StyledRun(8, 10, "yellow", "clamped", -1)), runs)
        assertEquals(emptyList<StyledRun>(), HighlightLogic.styledRuns(0, emptyList(), emptyList()))
    }

    // ---- lookup -----------------------------------------------------------------------------

    @Test
    fun annotationAt_andLinkAt() {
        val list = listOf(ann("old", 0, 10, updatedAt = t1), ann("new", 5, 15, updatedAt = t2), ann("gone", 0, 30, deleted = true))
        assertEquals("old", HighlightLogic.annotationAt(list, 30, 0)?.id)
        assertEquals("new", HighlightLogic.annotationAt(list, 30, 7)?.id)
        assertEquals("new", HighlightLogic.annotationAt(list, 30, 14)?.id)
        assertNull(HighlightLogic.annotationAt(list, 30, 15))
        assertNull(HighlightLogic.annotationAt(list, 30, -1))
        assertNull(HighlightLogic.annotationAt(list, 12, 12))
        val links = listOf(null, Passage(3, 6))
        assertEquals(1, HighlightLogic.linkAt(links, 3))
        assertEquals(1, HighlightLogic.linkAt(links, 5))
        assertEquals(-1, HighlightLogic.linkAt(links, 6))
    }

    @Test
    fun numberAt_includesTheSpaceAfterTheNumber() {
        // "1 In…\n12 And…": numbers at [0,1) and [20,22).
        val numbers = listOf(Passage(0, 1), Passage(20, 22))
        assertEquals(0, HighlightLogic.numberAt(numbers, 0))
        assertEquals(0, HighlightLogic.numberAt(numbers, 1)) // the space after "1"
        assertEquals(-1, HighlightLogic.numberAt(numbers, 2)) // verse text
        assertEquals(1, HighlightLogic.numberAt(numbers, 21))
        assertEquals(1, HighlightLogic.numberAt(numbers, 22))
        assertEquals(-1, HighlightLogic.numberAt(numbers, 23))
        assertEquals(-1, HighlightLogic.numberAt(numbers, 1, slack = 0))
        assertEquals(-1, HighlightLogic.numberAt(emptyList(), 0))
    }

    // ---- markers and offset mapping ---------------------------------------------------------

    @Test
    fun commentMarkers_onePerEndOffsetNewestWins() {
        val markers = HighlightLogic.commentMarkers(
            listOf(
                ann("a", 0, 5, comment = "x", updatedAt = t1),
                ann("b", 2, 5, comment = "y", updatedAt = t2),
                ann("c", 6, 9, comment = " "),
                ann("d", 1, 3, comment = "z"),
            ),
            bodyLength = 10,
        )
        assertEquals(listOf(CommentMarker(3, "d"), CommentMarker(5, "b")), markers)
    }

    @Test
    fun displayMapping_roundTrips() {
        val body = "abcdefghij"
        val markers = listOf(CommentMarker(3, "m1"), CommentMarker(5, "m2"))
        val m = HighlightLogic.MARKER
        val shown = HighlightLogic.displayText(body, markers)
        assertEquals("abc${m}de${m}fghij", shown)
        val l = m.length

        // Every body character keeps its identity through the mapping.
        for (b in body.indices) {
            val d = HighlightLogic.toDisplayStart(markers, b)
            assertEquals(body[b], shown[d])
            assertEquals(b, HighlightLogic.toBody(markers, d))
        }
        // A span ending at a marker offset ends before the marker; one starting there starts after it.
        assertEquals(3, HighlightLogic.toDisplayEnd(markers, 3))
        assertEquals(3 + l, HighlightLogic.toDisplayStart(markers, 3))
        assertEquals(5 + l, HighlightLogic.toDisplayEnd(markers, 5))
        assertEquals(10 + 2 * l, HighlightLogic.toDisplayEnd(markers, 10))
        // Inside a marker: maps to its offset and is recognised as that marker.
        assertEquals(3, HighlightLogic.toBody(markers, 4))
        assertEquals("m1", HighlightLogic.markerAt(markers, 3))
        assertEquals("m1", HighlightLogic.markerAt(markers, 3 + l - 1))
        assertEquals("m2", HighlightLogic.markerAt(markers, 5 + l))
        assertNull(HighlightLogic.markerAt(markers, 2))
        assertNull(HighlightLogic.markerAt(markers, 3 + l))
        // End of the display text maps to the end of the body.
        assertEquals(10, HighlightLogic.toBody(markers, shown.length))
    }

    @Test
    fun displayMapping_markerAtEndOfBodyAndNoMarkers() {
        val markers = listOf(CommentMarker(4, "end"))
        assertEquals("abcd" + HighlightLogic.MARKER, HighlightLogic.displayText("abcd", markers))
        assertEquals("end", HighlightLogic.markerAt(markers, 4))
        assertEquals(4, HighlightLogic.toBody(markers, 4 + HighlightLogic.MARKER.length))
        assertEquals("abcd", HighlightLogic.displayText("abcd", emptyList()))
        assertEquals(2, HighlightLogic.toBody(emptyList(), 2))
        assertEquals(2, HighlightLogic.toDisplayStart(emptyList(), 2))
    }
}
