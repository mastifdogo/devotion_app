package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.Annotation
import com.mastifdogo.devotion.data.StoredAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogicTest {

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

    // ---- links ------------------------------------------------------------------------------

    @Test
    fun usableLinks_dropOutOfRangeEmptyAndOverlapping() {
        val links = listOf(
            Passage(10, 20),
            Passage(15, 25), // overlaps the first
            Passage(-1, 4),
            Passage(30, 30),
            Passage(40, 60), // past the end of the body
            Passage(0, 5),
        )
        assertEquals(
            listOf(Passage(10, 20), null, null, null, null, Passage(0, 5)),
            TextLogic.usableLinks(links, 50),
        )
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
    fun mergeRemote_dirtyLocalAlwaysWinsCleanIsReplaced() {
        val local = listOf(
            StoredAnnotation(ann("same", 0, 5, "2026-11-15T12:00:00.000Z", comment = "local"), dirty = true),
            StoredAnnotation(ann("localNewer", 0, 5, "2026-11-15T13:00:00.000Z", comment = "local"), dirty = true),
            // The website edited it later, but the app's unsent edit still wins.
            StoredAnnotation(ann("remoteNewer", 0, 5, "2026-11-15T12:00:00.000Z", comment = "local"), dirty = true),
            StoredAnnotation(ann("clean", 0, 5, "2026-11-15T14:00:00.000Z", comment = "local"), dirty = false),
            StoredAnnotation(ann("untouched", 0, 5), dirty = false),
        )
        val remote = listOf(
            ann("same", 0, 5, "2026-11-15T12:00:00.000Z", comment = "server"),
            ann("localNewer", 0, 5, "2026-11-15T12:30:00.000Z", comment = "server"),
            ann("remoteNewer", 0, 5, "2026-11-15T12:30:00.000Z", comment = "server"),
            // Clean local copies take the server's, even an older one.
            ann("clean", 0, 5, "2026-11-15T10:00:00.000Z", comment = "server"),
            ann("brandNew", 7, 9, comment = "server"),
        )
        val merged = TextLogic.mergeRemote(local, remote).associateBy { it.annotation.id }
        assertEquals(6, merged.size)
        for (id in listOf("same", "localNewer", "remoteNewer")) {
            assertEquals(id, "local", merged.getValue(id).annotation.comment)
            assertTrue(id, merged.getValue(id).dirty)
        }
        assertEquals("server", merged.getValue("clean").annotation.comment)
        assertFalse(merged.getValue("clean").dirty)
        assertEquals("server", merged.getValue("brandNew").annotation.comment)
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
        // Dirty deletions stay until uploaded; a clean one deleted on the server is dropped.
        assertEquals(listOf("deletedHere", "deletedAndUploaded"), merged.map { it.annotation.id })
        assertTrue(merged.all { it.dirty })
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

    @Test
    fun markUploaded_cleansOnlyWhatWasSent() {
        val sent = ann("a", 0, 5, "2026-11-15T12:00:00.000Z", comment = "v1")
        val stored = sent.copy(source = "app")
        val cleaned = TextLogic.markUploaded(listOf(StoredAnnotation(sent, dirty = true)), sent, stored)
        assertEquals(listOf(StoredAnnotation(stored, dirty = false)), cleaned)
        // Edited again while uploading: the newer edit stays dirty.
        val newer = sent.copy(comment = "v2", updatedAt = "2026-11-15T12:00:01.000Z")
        val kept = TextLogic.markUploaded(listOf(StoredAnnotation(newer, dirty = true)), sent, stored)
        assertEquals(listOf(StoredAnnotation(newer, dirty = true)), kept)
        // An uploaded deletion disappears; other annotations are untouched.
        val gone = sent.copy(deleted = true)
        val other = StoredAnnotation(ann("b", 1, 2), dirty = true)
        assertEquals(listOf(other), TextLogic.markUploaded(listOf(StoredAnnotation(gone, true), other), gone, gone))
    }

    @Test
    fun entryAndChapterNotesAreSeparate() {
        val entryNote = ann("e", 0, 0, comment = "entry")
        val bibleNote = Annotation("b", 0, 0, 0, "none", "chapter", "2026-11-15T12:00:00.000Z", false, "kjv", 42, 3)
        val bibleHl = Annotation("h", 0, 10003, 20005, "yellow", "", "2026-11-15T12:00:00.000Z", false, "kjv", 42, 3)
        val list = listOf(entryNote, bibleNote, bibleHl)
        assertEquals("e", TextLogic.entryNote(list, 1)?.id)
        assertNull(TextLogic.entryNote(list, 0))
        assertEquals("b", TextLogic.chapterNote(list, "kjv", 42, 3)?.id)
        assertNull(TextLogic.chapterNote(list, "asv", 42, 3))
        assertNull(TextLogic.chapterNote(list, "kjv", 42, 4))
        assertTrue(TextLogic.isOnChapter(bibleHl, "kjv", 42, 3))
        assertTrue(TextLogic.isEntryAnnotation(entryNote))
        assertFalse(TextLogic.isEntryAnnotation(bibleHl))
    }

    @Test
    fun annotationJson_bibleFieldsAreOptional() {
        val json = com.mastifdogo.devotion.data.AppJson
        val old = json.decodeFromString(Annotation.serializer(), """{"id":"x","entry_id":12,"start":1,"end":4,"updated_at":"t"}""")
        assertEquals(12L, old.entryId)
        assertNull(old.bibleId)
        assertNull(old.book)
        assertNull(old.chapter)
        val bible = json.decodeFromString(
            Annotation.serializer(),
            """{"id":"y","entry_id":0,"start":20005,"end":20010,"color":"blue","updated_at":"t","bible_id":"kjv","book":0,"chapter":1,"source":"web","extra":1}""",
        )
        assertEquals(0L, bible.entryId)
        assertEquals("kjv", bible.bibleId)
        assertEquals(0, bible.book)
        assertEquals(1, bible.chapter)
        val encoded = json.encodeToString(Annotation.serializer(), bible.copy(source = "app"))
        assertTrue(encoded, encoded.contains("\"bible_id\":\"kjv\"") && encoded.contains("\"source\":\"app\"") && encoded.contains("\"chapter\":1"))
    }
}
