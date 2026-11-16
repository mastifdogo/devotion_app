package com.mastifdogo.devotion.logic

/** What the scroll button in the audio bar does right now. */
enum class ScrollMode(val label: String) {
    /** This entry's audio is playing: keep the spoken words in the middle of the screen. */
    FOLLOW("Follow"),
    /** Nothing is playing: move the text up at a constant, adjustable speed. */
    AUTO("Auto scroll"),
}

/** Whole pixels to scroll now, and the sub-pixel rest to carry into the next frame. */
data class ScrollStep(val pixels: Int, val carry: Float)

/** Pure maths for "follow along" and "auto scroll". */
object FollowLogic {
    /** Auto scroll speeds, in dp per second. */
    val AUTO_SPEEDS: List<Int> = listOf(8, 12, 16, 22, 30, 40, 55, 75)
    const val DEFAULT_AUTO_SPEED = 22

    /** Follow closes this share of the remaining distance per second... */
    const val FOLLOW_GAIN = 3f

    /** Frames longer than this (a pause, a dropped frame) count as this long, so nothing jumps. */
    const val MAX_FRAME_SECONDS = 0.1f

    /** Follow while this entry's audio is playing, auto scroll otherwise (paused, stopped, no audio). */
    fun modeFor(playingHere: Boolean): ScrollMode = if (playingHere) ScrollMode.FOLLOW else ScrollMode.AUTO

    /** The saved speed if it is one of the steps, else the nearest step. */
    fun sanitizeSpeed(speed: Int): Int = AUTO_SPEEDS.minByOrNull { kotlin.math.abs(it - speed) } ?: DEFAULT_AUTO_SPEED

    fun canSlowDown(speed: Int): Boolean = AUTO_SPEEDS.indexOf(sanitizeSpeed(speed)) > 0

    fun canSpeedUp(speed: Int): Boolean = AUTO_SPEEDS.indexOf(sanitizeSpeed(speed)) < AUTO_SPEEDS.size - 1

    /** One step slower (-1) or faster (+1), stopping at the ends. */
    fun stepSpeed(speed: Int, direction: Int): Int {
        val index = AUTO_SPEEDS.indexOf(sanitizeSpeed(speed))
        return AUTO_SPEEDS[(index + direction).coerceIn(0, AUTO_SPEEDS.size - 1)]
    }

    /** Seconds between two frame times in nanoseconds, capped at [MAX_FRAME_SECONDS]; 0 for the first frame. */
    fun frameSeconds(previousNanos: Long, nowNanos: Long): Float {
        if (previousNanos <= 0L || nowNanos <= previousNanos) return 0f
        return ((nowNanos - previousNanos) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
    }

    /**
     * How far (pixels, signed) to glide this frame toward a target [remaining] pixels away: a speed
     * of [FOLLOW_GAIN] x the remaining distance per second, at least [minSpeed] pixels per second,
     * and never past the target.
     */
    fun followDelta(remaining: Float, seconds: Float, minSpeed: Float): Float {
        if (seconds <= 0f || remaining == 0f || remaining.isNaN()) return 0f
        val distance = kotlin.math.abs(remaining)
        val speed = maxOf(distance * FOLLOW_GAIN, minSpeed.coerceAtLeast(0f))
        val move = minOf(distance, speed * seconds)
        return if (remaining > 0f) move else -move
    }

    /** How far (pixels) auto scroll moves this frame at [speed] pixels per second. */
    fun autoDelta(speed: Float, seconds: Float): Float =
        if (seconds <= 0f || speed <= 0f) 0f else speed * seconds

    /** Adds this frame's [delta] to the carried rest and splits off the whole pixels to scroll now. */
    fun step(carry: Float, delta: Float): ScrollStep {
        val total = carry + delta
        if (total.isNaN()) return ScrollStep(0, 0f)
        val whole = total.toInt()
        return ScrollStep(whole, total - whole)
    }

    /** Auto scroll has arrived when the page is at its bottom ([maxScroll] not yet measured never counts). */
    fun reachedEnd(value: Int, maxScroll: Int): Boolean = maxScroll != Int.MAX_VALUE && value >= maxScroll

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
