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

    // ---- modes and speeds -------------------------------------------------------------------

    @Test
    fun modeAndLabels() {
        assertEquals(ScrollMode.FOLLOW, FollowLogic.modeFor(playingHere = true))
        assertEquals(ScrollMode.AUTO, FollowLogic.modeFor(playingHere = false))
        assertEquals("Follow", ScrollMode.FOLLOW.label)
        assertEquals("Auto scroll", ScrollMode.AUTO.label)
    }

    @Test
    fun autoSpeedSteps() {
        assertEquals(22, FollowLogic.DEFAULT_AUTO_SPEED)
        assertTrue(FollowLogic.DEFAULT_AUTO_SPEED in FollowLogic.AUTO_SPEEDS)
        assertEquals(30, FollowLogic.stepSpeed(22, 1))
        assertEquals(16, FollowLogic.stepSpeed(22, -1))
        assertEquals(8, FollowLogic.stepSpeed(8, -1))
        assertEquals(75, FollowLogic.stepSpeed(75, 1))
        assertFalse(FollowLogic.canSlowDown(8))
        assertTrue(FollowLogic.canSpeedUp(8))
        assertTrue(FollowLogic.canSlowDown(75))
        assertFalse(FollowLogic.canSpeedUp(75))
        // A stored value that is not a step snaps to the nearest one.
        assertEquals(22, FollowLogic.sanitizeSpeed(20))
        assertEquals(75, FollowLogic.sanitizeSpeed(900))
        assertEquals(8, FollowLogic.sanitizeSpeed(-3))
        assertEquals(30, FollowLogic.stepSpeed(20, 1))
    }

    // ---- per-frame maths --------------------------------------------------------------------

    @Test
    fun frameSeconds_cappedAndZeroForTheFirstFrame() {
        assertEquals(0f, FollowLogic.frameSeconds(0, 5_000_000_000L), 0f)
        assertEquals(0.016f, FollowLogic.frameSeconds(1_000_000_000L, 1_016_000_000L), 1e-6f)
        assertEquals(0.1f, FollowLogic.frameSeconds(1_000_000_000L, 9_000_000_000L), 0f)
        assertEquals(0f, FollowLogic.frameSeconds(2_000_000_000L, 1_000_000_000L), 0f)
    }

    @Test
    fun followDelta_glidesProportionallyAndNeverOvershoots() {
        // 300 px away at 60 fps: 300 * 3 px/s * (1/60 s) = 15 px.
        assertEquals(15f, FollowLogic.followDelta(300f, 1f / 60f, 10f), 1e-4f)
        assertEquals(-15f, FollowLogic.followDelta(-300f, 1f / 60f, 10f), 1e-4f)
        // Close to the target the minimum speed takes over...
        assertEquals(0.5f, FollowLogic.followDelta(2f, 0.05f, 10f), 1e-5f)
        // ...but never past the target.
        assertEquals(0.3f, FollowLogic.followDelta(0.3f, 0.05f, 10f), 1e-6f)
        assertEquals(40f, FollowLogic.followDelta(40f, 1f, 10f), 0f)
        assertEquals(0f, FollowLogic.followDelta(0f, 0.016f, 10f), 0f)
        assertEquals(0f, FollowLogic.followDelta(100f, 0f, 10f), 0f)
        // Repeated frames converge on the target without passing it.
        var position = 0f
        repeat(600) { position += FollowLogic.followDelta(500f - position, 1f / 60f, 12f) }
        assertEquals(500f, position, 1e-3f)
    }

    @Test
    fun autoDelta_isConstantSpeed() {
        assertEquals(1f, FollowLogic.autoDelta(60f, 1f / 60f), 1e-5f)
        assertEquals(0f, FollowLogic.autoDelta(60f, 0f), 0f)
        assertEquals(0f, FollowLogic.autoDelta(-5f, 0.016f), 0f)
    }

    @Test
    fun step_carriesSubPixelRemainders() {
        // 0.4 px per frame: a whole pixel every third frame, nothing lost.
        var carry = 0f
        var scrolled = 0
        repeat(10) {
            val s = FollowLogic.step(carry, 0.4f)
            scrolled += s.pixels
            carry = s.carry
        }
        assertEquals(3, scrolled)
        assertEquals(1.0f, carry + scrolled - 3f, 1e-4f)
        assertEquals(ScrollStep(2, 0.5f), FollowLogic.step(0.25f, 2.25f))
        // Upward (negative) movement carries too.
        val up = FollowLogic.step(-0.5f, -0.75f)
        assertEquals(-1, up.pixels)
        assertEquals(-0.25f, up.carry, 1e-6f)
        assertEquals(ScrollStep(0, 0f), FollowLogic.step(Float.NaN, 1f))
    }

    @Test
    fun reachedEnd() {
        assertTrue(FollowLogic.reachedEnd(1000, 1000))
        assertFalse(FollowLogic.reachedEnd(999, 1000))
        assertTrue(FollowLogic.reachedEnd(0, 0))
        assertFalse(FollowLogic.reachedEnd(0, Int.MAX_VALUE))
    }
}
