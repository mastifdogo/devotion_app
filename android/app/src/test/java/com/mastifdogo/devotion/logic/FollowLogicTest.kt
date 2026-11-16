package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLogicTest {

    @Test
    fun spokenOffset_advancesEvenlyAfterTheIntroduction() {
        // 100 characters of introduction, 900 of body, 10 minutes of audio.
        val d = 600_000L
        assertEquals(0, FollowLogic.spokenOffset(0, d, 100, 900))
        // 10% of the time = the introduction: the body starts.
        assertEquals(0, FollowLogic.spokenOffset(60_000, d, 100, 900))
        // 55% -> 550 characters spoken, 450 of them body.
        assertEquals(450, FollowLogic.spokenOffset(330_000, d, 100, 900))
        assertEquals(900, FollowLogic.spokenOffset(d, d, 100, 900))
        assertEquals(900, FollowLogic.spokenOffset(d + 5000, d, 100, 900))
    }

    @Test
    fun spokenOffset_withoutSpeechOffsetAndEdgeCases() {
        assertEquals(500, FollowLogic.spokenOffset(30_000, 60_000, null, 1000))
        assertEquals(500, FollowLogic.spokenOffset(30_000, 60_000, -7, 1000))
        assertEquals(0, FollowLogic.spokenOffset(30_000, 0, null, 1000))
        assertEquals(0, FollowLogic.spokenOffset(30_000, 60_000, null, 0))
        assertEquals(0, FollowLogic.spokenOffset(-5, 60_000, null, 1000))
    }

    @Test
    fun targetScroll_staysPutUntilTheMiddleThenCentresUntilTheEnd() {
        val viewport = 1000
        val max = 3000
        // Above the middle of the first screen: nothing moves.
        assertEquals(0, FollowLogic.targetScroll(200, viewport, max))
        assertEquals(0, FollowLogic.targetScroll(500, viewport, max))
        // Past the middle: the line is kept centred.
        assertEquals(1, FollowLogic.targetScroll(501, viewport, max))
        assertEquals(1500, FollowLogic.targetScroll(2000, viewport, max))
        // Near the end the page cannot scroll further.
        assertEquals(3000, FollowLogic.targetScroll(3900, viewport, max))
        // Nothing to scroll.
        assertEquals(0, FollowLogic.targetScroll(900, viewport, 0))
        assertEquals(0, FollowLogic.targetScroll(900, 0, max))
    }

    @Test
    fun shouldScroll_ignoresTinyMoves() {
        assertFalse(FollowLogic.shouldScroll(100, 103, 8))
        assertTrue(FollowLogic.shouldScroll(100, 108, 8))
        assertTrue(FollowLogic.shouldScroll(100, 60, 8))
        assertTrue(FollowLogic.shouldScroll(100, 101, 0))
        assertFalse(FollowLogic.shouldScroll(100, 100, 0))
    }
}
