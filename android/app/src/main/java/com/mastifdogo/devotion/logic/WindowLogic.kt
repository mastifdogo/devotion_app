package com.mastifdogo.devotion.logic

import java.util.Locale
import kotlin.math.roundToInt

/** Where the text window sits: [insetDp] from the sides and bottom, at most [maxWidthDp] wide (null = no cap). */
data class WindowLayout(val insetDp: Int, val maxWidthDp: Int?)

/** Pure rules for the opaque "text window" shown over an app wallpaper. */
object WindowLogic {
    const val MIN_OPACITY = 0.5f
    const val MAX_OPACITY = 1f
    const val DEFAULT_OPACITY = 1f
    const val WIDE_SCREEN_DP = 600
    const val NARROW_INSET_DP = 12
    const val WIDE_INSET_DP = 24
    const val WIDE_MAX_WIDTH_DP = 720

    /** The window opacity, kept within 50%..100%; NaN or infinite values give the default (100%). */
    fun clampOpacity(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) DEFAULT_OPACITY else value.coerceIn(MIN_OPACITY, MAX_OPACITY)

    /** Black at this alpha over the window colour is the darkest list item shade (slider at 100%). */
    const val MAX_SHADE_ALPHA = 0.22f

    /** Out of the box the cards are slightly darker than the window. */
    const val DEFAULT_SHADE = 0.4f

    /** The list item shade, 0%..100%; NaN or infinite values give the default (40%). */
    fun clampShade(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) DEFAULT_SHADE else value.coerceIn(0f, 1f)

    /** Alpha of the black tint drawn over the window colour on list item cards: linear, 0..22%. */
    fun shadeAlpha(shade: Float): Float = clampShade(shade) * MAX_SHADE_ALPHA

    /**
     * The stored `list_item_opacity` value as a shade. Until the slider is moved under its new
     * meaning ([migrated] false), the old default 1.0 (never moved, or unset) means the new
     * default 40%; any other old value is kept.
     */
    fun migrateShade(stored: Float, migrated: Boolean): Float =
        if (!migrated && stored == 1f) DEFAULT_SHADE else clampShade(stored)

    /** "40%" for a shade of 0.4 (after clamping). */
    fun shadeLabel(value: Float): String =
        String.format(Locale.ROOT, "%d%%", (clampShade(value) * 100f).roundToInt())

    /** "95%" for 0.95 (after clamping). */
    fun opacityLabel(value: Float): String =
        String.format(Locale.ROOT, "%d%%", (clampOpacity(value) * 100f).roundToInt())

    /** Narrow screens: 12dp inset, full width. From 600dp: 24dp inset, at most 720dp wide (centred). */
    fun layoutFor(screenWidthDp: Int): WindowLayout =
        if (screenWidthDp >= WIDE_SCREEN_DP) {
            WindowLayout(WIDE_INSET_DP, WIDE_MAX_WIDTH_DP)
        } else {
            WindowLayout(NARROW_INSET_DP, null)
        }
}
