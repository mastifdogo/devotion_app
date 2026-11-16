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

    @Test
    fun darken_mixesTowardBlackPerChannel() {
        assertEquals(0xFF25241F.toInt(), WindowLogic.darken(0xFF25241F.toInt(), 0f))
        assertEquals(0xFF000000.toInt(), WindowLogic.darken(0xFFFFFFFF.toInt(), 1f))
        // 22% black over white: 255 * 0.78 = 198.9 -> 199 (0xC7).
        assertEquals(0xFFC7C7C7.toInt(), WindowLogic.darken(0xFFFFFFFF.toInt(), 0.22f))
        // The alpha of the input is ignored; the result is opaque.
        assertEquals(0xFF808080.toInt(), WindowLogic.darken(0x00808080, 0f))
    }

    @Test
    fun itemFill_matchesTheWebsite() {
        val card = 0xFF25241F.toInt() // the website's dark window colour
        // With a wallpaper: window colour darkened by shade x 22%, at the window opacity.
        val fill = WindowLogic.itemFillArgb(card, hasWallpaper = true, windowOpacity = 0.7f, shade = 1f)
        assertEquals((0.7f * 255f).toInt() + 1, (fill ushr 24)) // 178.5 -> 179
        assertEquals(WindowLogic.darken(card, 0.22f) and 0xFFFFFF, fill and 0xFFFFFF)
        // Shade 0 with a wallpaper: the window colour itself at the window opacity.
        assertEquals(0xFF25241F.toInt(), WindowLogic.itemFillArgb(card, true, 1f, 0f))
        // Without a wallpaper: the background darkened by shade x 10%, opaque, whatever the window opacity.
        val bg = 0xFFF6F4EF.toInt()
        assertEquals(WindowLogic.darken(bg, 0.04f), WindowLogic.itemFillArgb(bg, false, 0.5f, 0.4f))
        assertEquals(0xFF, WindowLogic.itemFillArgb(bg, false, 0.5f, 0.4f) ushr 24)
        assertEquals(bg, WindowLogic.itemFillArgb(bg, false, 0.5f, 0f))
    }
}
