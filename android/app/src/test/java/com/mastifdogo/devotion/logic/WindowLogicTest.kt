package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowLogicTest {

    @Test
    fun clampOpacity_staysBetweenHalfAndFull() {
        assertEquals(1f, WindowLogic.clampOpacity(1f), 0f)
        assertEquals(0.75f, WindowLogic.clampOpacity(0.75f), 0f)
        assertEquals(0.5f, WindowLogic.clampOpacity(0.2f), 0f)
        assertEquals(0.5f, WindowLogic.clampOpacity(-3f), 0f)
        assertEquals(1f, WindowLogic.clampOpacity(1.7f), 0f)
        assertEquals(1f, WindowLogic.clampOpacity(Float.NaN), 0f)
        assertEquals(1f, WindowLogic.clampOpacity(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, WindowLogic.DEFAULT_OPACITY, 0f)
    }

    @Test
    fun opacityLabel_isARoundedPercentage() {
        assertEquals("95%", WindowLogic.opacityLabel(0.95f))
        assertEquals("100%", WindowLogic.opacityLabel(1f))
        assertEquals("50%", WindowLogic.opacityLabel(0.5f))
        assertEquals("50%", WindowLogic.opacityLabel(0.1f))
        assertEquals("73%", WindowLogic.opacityLabel(0.7349f))
        assertEquals("100%", WindowLogic.opacityLabel(Float.NaN))
    }

    @Test
    fun layoutFor_switchesAt600dp() {
        assertEquals(WindowLayout(12, null), WindowLogic.layoutFor(360))
        assertEquals(WindowLayout(12, null), WindowLogic.layoutFor(599))
        assertEquals(WindowLayout(24, 720), WindowLogic.layoutFor(600))
        assertEquals(WindowLayout(24, 720), WindowLogic.layoutFor(1280))
        assertEquals(WindowLayout(12, null), WindowLogic.layoutFor(0))
    }
}
