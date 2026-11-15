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
