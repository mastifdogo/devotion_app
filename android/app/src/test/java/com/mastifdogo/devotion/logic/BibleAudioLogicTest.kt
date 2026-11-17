package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleAudioLogicTest {
    private val john3 = BibleRef("kjv", 42, 3)

    @Test
    fun mediaId_roundTripsAndIsDistinctFromEntries() {
        assertEquals("bible:kjv:42:3", BibleAudioLogic.mediaId(john3))
        assertEquals(john3, BibleAudioLogic.parseMediaId("bible:kjv:42:3"))
        // A version id with a colon in it.
        val odd = BibleRef("web:2020", 0, 1)
        assertEquals(odd, BibleAudioLogic.parseMediaId(BibleAudioLogic.mediaId(odd)))
        // Entry audio ("<entryId>:<voice>") is not a chapter, and a chapter is not an entry.
        assertNull(BibleAudioLogic.parseMediaId("12:en_US-amy-medium"))
        assertNull(BibleAudioLogic.parseMediaId("12:bible:kjv:1:1"))
        assertNull(MiniPlayerLogic.parseMediaId("bible:kjv:42:3"))
        // Malformed.
        assertNull(BibleAudioLogic.parseMediaId(null))
        assertNull(BibleAudioLogic.parseMediaId("bible:"))
        assertNull(BibleAudioLogic.parseMediaId("bible:kjv:42"))
        assertNull(BibleAudioLogic.parseMediaId("bible:kjv:x:3"))
        assertNull(BibleAudioLogic.parseMediaId("bible:kjv:42:0"))
        assertNull(BibleAudioLogic.parseMediaId("bible::42:3"))
        assertNull(BibleAudioLogic.parseMediaId("bible:kjv:-1:3"))
    }

    @Test
    fun fileName_isSafe() {
        assertEquals("kjv-42-3.mp3", BibleAudioLogic.fileName(john3))
        assertEquals("web_2020-0-1.mp3", BibleAudioLogic.fileName(BibleRef("web:2020", 0, 1)))
        assertEquals("_x-1-2.mp3", BibleAudioLogic.fileName(BibleRef("../x", 1, 2)))
        assertEquals("bible-1-2.mp3", BibleAudioLogic.fileName(BibleRef("", 1, 2)))
        assertEquals("John 3", BibleAudioLogic.chapterTitle(" John ", 3))
    }

    @Test
    fun nextStep_followsTheServerContract() {
        assertEquals(AudioFetchStep.Ready, BibleAudioLogic.nextStep(200, null, 0))
        // Being made: ask again every 3 s...
        assertEquals(AudioFetchStep.Wait(3000), BibleAudioLogic.nextStep(202, null, 0))
        assertEquals(AudioFetchStep.Wait(3000), BibleAudioLogic.nextStep(202, null, 5 * 60_000L))
        // ...but not for ever.
        assertTrue(BibleAudioLogic.nextStep(202, null, 10 * 60_000L) is AudioFetchStep.Failed)
        // Off, or no such chapter.
        assertEquals(AudioFetchStep.Failed("Bible audio is switched off"), BibleAudioLogic.nextStep(404, " Bible audio is switched off ", 0))
        assertEquals(AudioFetchStep.Failed("Bible audio is not available on this server."), BibleAudioLogic.nextStep(404, null, 0))
        // Making it failed: the server's message, and the user can retry.
        assertEquals(AudioFetchStep.Failed("voice engine crashed"), BibleAudioLogic.nextStep(502, "voice engine crashed", 0))
        assertEquals(AudioFetchStep.Failed("Making the audio failed. Please try again."), BibleAudioLogic.nextStep(503, "", 0))
        assertEquals(AudioFetchStep.Failed("The server answered with HTTP 500."), BibleAudioLogic.nextStep(500, null, 0))
    }

    @Test
    fun filesToTrim_deletesLeastRecentlyPlayedFirst() {
        val mb = 1024L * 1024L
        val files = listOf(
            CachedChapterAudio("new.mp3", 100 * mb, 3000),
            CachedChapterAudio("old.mp3", 100 * mb, 1000),
            CachedChapterAudio("mid.mp3", 100 * mb, 2000),
            CachedChapterAudio("newest.mp3", 100 * mb, 4000),
        )
        // 400 MB with a 300 MB limit: the oldest goes.
        assertEquals(listOf("old.mp3"), BibleAudioLogic.filesToTrim(files, 300 * mb, null))
        // A tighter limit takes the next oldest too.
        assertEquals(listOf("old.mp3", "mid.mp3"), BibleAudioLogic.filesToTrim(files, 250 * mb, null))
        // The file that is playing is never deleted, even when it is the oldest.
        assertEquals(listOf("mid.mp3"), BibleAudioLogic.filesToTrim(files, 300 * mb, "old.mp3"))
        // Within the limit: nothing.
        assertEquals(emptyList<String>(), BibleAudioLogic.filesToTrim(files, 400 * mb, null))
        assertEquals(emptyList<String>(), BibleAudioLogic.filesToTrim(emptyList(), 300 * mb, null))
        assertEquals(300L * mb, BibleAudioLogic.MAX_CACHE_BYTES)
    }

    @Test
    fun sizeLabel() {
        assertEquals("0 MB", BibleAudioLogic.sizeLabel(0))
        assertEquals("< 1 MB", BibleAudioLogic.sizeLabel(500_000))
        assertEquals("12 MB", BibleAudioLogic.sizeLabel(12L * 1024 * 1024 + 5))
    }

    @Test
    fun miniPlayer_forAChapter() {
        // On another chapter, another version, or any other screen.
        assertTrue(BibleAudioLogic.isMiniPlayerVisible(john3, BibleRef("kjv", 42, 4), isPlaying = true, positionMs = 0))
        assertTrue(BibleAudioLogic.isMiniPlayerVisible(john3, BibleRef("asv", 42, 3), isPlaying = true, positionMs = 0))
        assertTrue(BibleAudioLogic.isMiniPlayerVisible(john3, null, isPlaying = false, positionMs = 5000))
        // Not on the chapter that is playing, not when nothing is loaded or it is stopped at the start.
        assertFalse(BibleAudioLogic.isMiniPlayerVisible(john3, john3, isPlaying = true, positionMs = 5000))
        assertFalse(BibleAudioLogic.isMiniPlayerVisible(null, null, isPlaying = true, positionMs = 5000))
        assertFalse(BibleAudioLogic.isMiniPlayerVisible(john3, null, isPlaying = false, positionMs = 0))
    }
}
