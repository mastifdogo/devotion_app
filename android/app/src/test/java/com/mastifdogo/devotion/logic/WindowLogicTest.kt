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

    @Test
    fun shadeAlpha_isLinearUpTo22Percent() {
        assertEquals(0f, WindowLogic.shadeAlpha(0f), 0f)
        assertEquals(0.22f, WindowLogic.shadeAlpha(1f), 1e-6f)
        assertEquals(0.11f, WindowLogic.shadeAlpha(0.5f), 1e-6f)
        assertEquals(0.088f, WindowLogic.shadeAlpha(0.4f), 1e-6f)
        assertEquals(0f, WindowLogic.shadeAlpha(-1f), 0f)
        assertEquals(0.22f, WindowLogic.shadeAlpha(7f), 1e-6f)
        assertEquals(0.088f, WindowLogic.shadeAlpha(Float.NaN), 1e-6f)
    }

    @Test
    fun migrateShade_oldDefaultBecomes40Percent() {
        // Never moved (or unset, read as 1.0) before the new meaning: the new default.
        assertEquals(0.4f, WindowLogic.migrateShade(1f, migrated = false), 0f)
        // Moved under the old meaning: kept.
        assertEquals(0.7f, WindowLogic.migrateShade(0.7f, migrated = false), 0f)
        assertEquals(0f, WindowLogic.migrateShade(0f, migrated = false), 0f)
        // Chosen under the new meaning: 100% stays 100%.
        assertEquals(1f, WindowLogic.migrateShade(1f, migrated = true), 0f)
        assertEquals(0.4f, WindowLogic.migrateShade(Float.NaN, migrated = true), 0f)
        assertEquals(1f, WindowLogic.migrateShade(2f, migrated = true), 0f)
    }

    @Test
    fun shadeLabel() {
        assertEquals("40%", WindowLogic.shadeLabel(0.4f))
        assertEquals("0%", WindowLogic.shadeLabel(0f))
        assertEquals("100%", WindowLogic.shadeLabel(1.5f))
    }
}
