package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.StoredAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {

    private fun texts(body: String, passages: List<Passage>): List<String> =
        passages.map { body.substring(it.start, it.end) }

    private fun ann(
        id: String,
        start: Int,
        end: Int,
        updatedAt: String = "2026-11-15T12:00:00.000Z",
        entryId: Long = 1,
        color: String = "yellow",
        comment: String = "",
        deleted: Boolean = false,
    ) = Annotation(id, entryId, start, end, color, comment, updatedAt, deleted)

    // ---- splitting --------------------------------------------------------------------------

    @Test
    fun sentences_splitOnTerminators() {
        val body = "God is love. Do you believe this? Yes! Amen"
        assertEquals(
            listOf("God is love.", "Do you believe this?", "Yes!", "Amen"),
            texts(body, TextLogic.sentences(body)),
        )
    }

    @Test
    fun sentences_keepClosingQuotesAndIgnoreAbbreviations() {
        val body = "He said, \"Follow me.\" Then St. Paul wrote to Mr. Smith in A. D. 60. It was e.g. a letter."
        assertEquals(
            listOf(
                "He said, \"Follow me.\"",
                "Then St. Paul wrote to Mr. Smith in A. D. 60.",
                "It was e.g. a letter.",
            ),
            texts(body, TextLogic.sentences(body)),
        )
    }

    @Test
    fun sentences_doNotSplitInsideNumbersAndSplitOnLineBreaks() {
        val body = "See John 3.16 today...\nSecond line\nThird line."
        assertEquals(
            listOf("See John 3.16 today...", "Second line", "Third line."),
            texts(body, TextLogic.sentences(body)),
        )
    }

    @Test
    fun paragraphs_splitOnBlankLinesWithBodyOffsets() {
        val body = "  First one. Second one.\n\nNext paragraph here.\r\n \r\n\r\nLast.\n"
        val paragraphs = TextLogic.paragraphs(body)
        assertEquals(3, paragraphs.size)
        assertEquals("First one. Second one.", body.substring(paragraphs[0].start, paragraphs[0].end))
        assertEquals(listOf("First one.", "Second one."), texts(body, paragraphs[0].sentences))
        assertEquals(listOf("Next paragraph here."), texts(body, paragraphs[1].sentences))
        assertEquals(listOf("Last."), texts(body, paragraphs[2].sentences))
        // Every non-whitespace character is inside exactly one sentence.
        val covered = BooleanArray(body.length)
        for (p in paragraphs) for (s in p.sentences) for (i in s.start until s.end) {
            assertFalse(covered[i])
            covered[i] = true
        }
        for (i in body.indices) if (!body[i].isWhitespace()) assertTrue(covered[i])
    }

    @Test
    fun paragraphs_emptyBody() {
        assertTrue(TextLogic.paragraphs("").isEmpty())
        assertTrue(TextLogic.paragraphs(" \n\n ").isEmpty())
    }

    // ---- ranges -----------------------------------------------------------------------------

    @Test
    fun overlaps_isExclusiveAtTheEnd() {
        assertTrue(TextLogic.overlaps(0, 10, 5, 15))
        assertTrue(TextLogic.overlaps(5, 6, 0, 10))
        assertFalse(TextLogic.overlaps(0, 10, 10, 20))
        assertFalse(TextLogic.overlaps(10, 20, 0, 10))
    }

    @Test
    fun findOverlapping_picksLargestOverlapAndSkipsDeletedNotesAndOtherEntries() {
        val list = listOf(
            ann("small", 0, 12),
            ann("big", 10, 40),
            ann("deleted", 10, 40, deleted = true),
            ann("note", 0, 0),
            ann("other-entry", 10, 40, entryId = 2),
        )
        assertEquals("big", TextLogic.findOverlapping(list, 1, 10, 30)?.id)
        assertEquals("small", TextLogic.findOverlapping(list, 1, 0, 10)?.id)
        assertNull(TextLogic.findOverlapping(list, 1, 40, 50))
        assertNull(TextLogic.findOverlapping(list, 3, 10, 30))
    }

    @Test
    fun findOverlapping_tieGoesToTheNewest() {
        val list = listOf(
            ann("old", 0, 10, updatedAt = "2026-11-15T12:00:00.000Z"),
            ann("new", 0, 10, updatedAt = "2026-11-15T12:00:01.000Z"),
        )
        assertEquals("new", TextLogic.findOverlapping(list, 1, 0, 10)?.id)
    }

    @Test
    fun entryNote_andQuote() {
        val list = listOf(ann("a", 4, 9), ann("n", 0, 0, comment = "whole"), ann("x", 0, 0, deleted = true))
        assertEquals("n", TextLogic.entryNote(list, 1)?.id)
        assertNull(TextLogic.entryNote(list, 2))
        assertTrue(TextLogic.isEntryNote(list[1]))
        assertFalse(TextLogic.isEntryNote(list[0]))
        assertEquals("quick", TextLogic.quote("The quick fox", list[0]))
        assertEquals("fox", TextLogic.quote("The quick fox", ann("far", 10, 99)))
        assertEquals("", TextLogic.quote("short", ann("out", 50, 60)))
    }

    // ---- timestamps -------------------------------------------------------------------------

    @Test
    fun timestamp_alwaysHasMilliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", TextLogic.timestamp(0))
        assertEquals("2026-11-15T12:00:00.000Z", TextLogic.timestamp(1794744000000L))
        assertEquals("2026-11-15T12:00:00.007Z", TextLogic.timestamp(1794744000007L))
    }

    @Test
    fun nextTimestamp_isStrictlyLaterThanThePreviousOne() {
        assertEquals("1970-01-01T00:00:05.000Z", TextLogic.nextTimestamp(null, 5000))
        assertEquals("1970-01-01T00:00:05.000Z", TextLogic.nextTimestamp("1970-01-01T00:00:01.000Z", 5000))
        // Device clock is behind the previous edit: still moves forward.
        assertEquals("1970-01-01T00:00:09.001Z", TextLogic.nextTimestamp("1970-01-01T00:00:09.000Z", 5000))
    }

    @Test
    fun compareTimestamps_comparesInstantsNotStrings() {
        assertTrue(TextLogic.compareTimestamps("2026-11-15T12:00:00Z", "2026-11-15T12:00:00.001Z") < 0)
        assertEquals(0, TextLogic.compareTimestamps("2026-11-15T12:00:00Z", "2026-11-15T12:00:00.000Z"))
        assertTrue(TextLogic.compareTimestamps("2026-11-15T12:00:01.000Z", "2026-11-15T12:00:00.999Z") > 0)
        assertTrue(TextLogic.compareTimestamps("b", "a") > 0)
    }

    // ---- merge ------------------------------------------------------------------------------

    @Test
    fun mergeRemote_laterWins() {
        val local = listOf(
            StoredAnnotation(ann("same", 0, 5, "2026-11-15T12:00:00.000Z", comment = "local"), dirty = true),
            StoredAnnotation(ann("localNewer", 0, 5, "2026-11-15T13:00:00.000Z", comment = "local"), dirty = true),
            StoredAnnotation(ann("remoteNewer", 0, 5, "2026-11-15T12:00:00.000Z", comment = "local"), dirty = true),
            StoredAnnotation(ann("untouched", 0, 5), dirty = false),
        )
        val remote = listOf(
            ann("same", 0, 5, "2026-11-15T12:00:00.000Z", comment = "server"),
            ann("localNewer", 0, 5, "2026-11-15T12:30:00.000Z", comment = "server"),
            ann("remoteNewer", 0, 5, "2026-11-15T12:30:00.000Z", comment = "server"),
            ann("brandNew", 7, 9, comment = "server"),
        )
        val merged = TextLogic.mergeRemote(local, remote).associateBy { it.annotation.id }
        assertEquals(5, merged.size)
        assertEquals("server", merged.getValue("same").annotation.comment)
        assertFalse(merged.getValue("same").dirty)
        assertEquals("local", merged.getValue("localNewer").annotation.comment)
        assertTrue(merged.getValue("localNewer").dirty)
        assertEquals("server", merged.getValue("remoteNewer").annotation.comment)
        assertFalse(merged.getValue("remoteNewer").dirty)
        assertFalse(merged.getValue("brandNew").dirty)
        assertFalse(merged.getValue("untouched").dirty)
    }

    @Test
    fun mergeRemote_dropsSyncedTombstonesButKeepsDirtyOnes() {
        val local = listOf(
            StoredAnnotation(ann("goneOnServer", 0, 5, "2026-11-15T12:00:00.000Z"), dirty = false),
            StoredAnnotation(ann("deletedHere", 0, 5, "2026-11-15T12:00:00.000Z", deleted = true), dirty = true),
            StoredAnnotation(ann("deletedAndUploaded", 0, 5, "2026-11-15T12:00:00.000Z", deleted = true), dirty = true),
        )
        val remote = listOf(
            ann("goneOnServer", 0, 5, "2026-11-15T12:05:00.000Z", deleted = true),
            ann("deletedAndUploaded", 0, 5, "2026-11-15T12:00:00.000Z", deleted = true),
            ann("neverSeenTombstone", 0, 5, deleted = true),
        )
        val merged = TextLogic.mergeRemote(local, remote)
        assertEquals(listOf("deletedHere"), merged.map { it.annotation.id })
        assertTrue(merged[0].dirty)
    }

    @Test
    fun upsertLocal_replacesOrAppendsAndMarksDirty() {
        val start = listOf(StoredAnnotation(ann("a", 0, 5), dirty = false))
        val replaced = TextLogic.upsertLocal(start, ann("a", 0, 5, comment = "edited"))
        assertEquals(1, replaced.size)
        assertEquals("edited", replaced[0].annotation.comment)
        assertTrue(replaced[0].dirty)
        val appended = TextLogic.upsertLocal(start, ann("b", 6, 9))
        assertEquals(listOf("a", "b"), appended.map { it.annotation.id })
        assertFalse(appended[0].dirty)
        assertTrue(appended[1].dirty)
    }
}
