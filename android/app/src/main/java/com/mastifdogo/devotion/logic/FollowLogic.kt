package com.mastifdogo.devotion.logic

/** Pure maths for "follow along": keeping the words being spoken in the middle of the screen. */
object FollowLogic {

    /**
     * The estimated body offset of the word being spoken. There are no word timings, so speech is
     * taken to advance evenly through what the narrator reads: [speechOffset] characters of
     * introduction (lesson number, title, scripture; null = 0) and then the body:
     * position / duration x (speechOffset + bodyLength) - speechOffset, kept within the body.
     */
    fun spokenOffset(positionMs: Long, durationMs: Long, speechOffset: Int?, bodyLength: Int): Int {
        if (bodyLength <= 0 || durationMs <= 0L) return 0
        val intro = (speechOffset ?: 0).coerceAtLeast(0)
        val progress = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val spoken = progress * (intro + bodyLength) - intro
        return spoken.toInt().coerceIn(0, bodyLength)
    }

    /**
     * The scroll position that puts content y [lineCenterY] (measured from the top of the scrolled
     * content) in the vertical middle of a viewport [viewportHeight] high: 0 until the spoken line
     * has reached the middle, then centred, and no further than [maxScroll] at the end of the text.
     */
    fun targetScroll(lineCenterY: Int, viewportHeight: Int, maxScroll: Int): Int {
        if (viewportHeight <= 0 || maxScroll <= 0) return 0
        return (lineCenterY - viewportHeight / 2).coerceIn(0, maxScroll)
    }

    /** Whether it is worth scrolling: a move of less than [thresholdPx] is skipped to avoid jitter. */
    fun shouldScroll(current: Int, target: Int, thresholdPx: Int): Boolean =
        kotlin.math.abs(target - current) >= thresholdPx.coerceAtLeast(1)
}
