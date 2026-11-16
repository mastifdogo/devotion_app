package com.mastifdogo.devotion.logic

/** Where an annotation on a Bible chapter belongs: version id, 0-based book, 1-based chapter. */
data class BibleRef(val bibleId: String, val book: Int, val chapter: Int)

/**
 * A chapter laid out as one selectable text: "1 In the beginning…\n2 And the earth…".
 * For verse i (0-based): its number starts at [numberStarts]`[i]`, its text runs from
 * [textStarts]`[i]` to [textEnds]`[i]` (exclusive) in [text].
 */
data class ChapterLayout(
    val text: String,
    val numberStarts: List<Int>,
    val textStarts: List<Int>,
    val textEnds: List<Int>,
) {
    val verseCount: Int get() = textStarts.size
}

/**
 * Pure conversion between offsets in the displayed chapter text and annotation positions.
 * A position is verseIndex(0-based) * 10000 + the UTF-16 offset inside that verse's text exactly
 * as stored in the Bible file; verse numbers and separators are never counted.
 */
object BibleTextLogic {
    const val VERSE_STRIDE = 10000

    /** Lays out [verses] as "N text" lines; the space after the number is a no-break space. */
    fun layout(verses: List<String>): ChapterLayout {
        val sb = StringBuilder()
        val numberStarts = ArrayList<Int>(verses.size)
        val textStarts = ArrayList<Int>(verses.size)
        val textEnds = ArrayList<Int>(verses.size)
        for ((i, verse) in verses.withIndex()) {
            if (i > 0) sb.append('\n')
            numberStarts.add(sb.length)
            sb.append(i + 1).append(' ')
            textStarts.add(sb.length)
            sb.append(verse)
            textEnds.add(sb.length)
        }
        return ChapterLayout(sb.toString(), numberStarts, textStarts, textEnds)
    }

    /** The ranges of the verse numbers (without the following space), for styling. */
    fun numberRanges(layout: ChapterLayout): List<Passage> =
        layout.numberStarts.indices.map { Passage(layout.numberStarts[it], layout.textStarts[it] - 1) }

    /** Index of the verse whose line contains display offset [display] (the last one starting at or before it). */
    private fun verseAt(layout: ChapterLayout, display: Int): Int {
        var verse = 0
        for (i in layout.numberStarts.indices) if (layout.numberStarts[i] <= display) verse = i
        return verse
    }

    private fun length(layout: ChapterLayout, verse: Int): Int = layout.textEnds[verse] - layout.textStarts[verse]

    /** Position for the start of a selection at [display]: inside a number -> that verse's start; on a separator -> the next verse's start. */
    fun startPosition(layout: ChapterLayout, display: Int): Int {
        if (layout.verseCount == 0) return 0
        val i = verseAt(layout, display)
        return when {
            display <= layout.textStarts[i] -> i * VERSE_STRIDE
            display <= layout.textEnds[i] -> i * VERSE_STRIDE + (display - layout.textStarts[i])
            i + 1 < layout.verseCount -> (i + 1) * VERSE_STRIDE
            else -> i * VERSE_STRIDE + length(layout, i)
        }
    }

    /** Position for the (exclusive) end of a selection at [display]: inside a number -> the previous verse's end. */
    fun endPosition(layout: ChapterLayout, display: Int): Int {
        if (layout.verseCount == 0) return 0
        val i = verseAt(layout, display)
        return when {
            display <= layout.textStarts[i] -> if (i > 0) (i - 1) * VERSE_STRIDE + length(layout, i - 1) else 0
            else -> i * VERSE_STRIDE + (minOf(display, layout.textEnds[i]) - layout.textStarts[i])
        }
    }

    /** The positions of a selected display range, or null when it covers no verse text. */
    fun selectionToRange(layout: ChapterLayout, displayStart: Int, displayEnd: Int): Passage? {
        val start = startPosition(layout, minOf(displayStart, displayEnd))
        val end = endPosition(layout, maxOf(displayStart, displayEnd))
        return if (start < end) Passage(start, end) else null
    }

    /** Display offset of a position (clamped to the chapter and to its verse's text). */
    fun toDisplay(layout: ChapterLayout, position: Int): Int {
        if (layout.verseCount == 0) return 0
        if (position <= 0) return layout.textStarts[0]
        val verse = position / VERSE_STRIDE
        if (verse >= layout.verseCount) return layout.textEnds[layout.verseCount - 1]
        val offset = (position % VERSE_STRIDE).coerceIn(0, length(layout, verse))
        return layout.textStarts[verse] + offset
    }

    /** Display range of verses [from]..[to] (1-based, inclusive), numbers included, for marking; null if out of range. */
    fun versesRange(layout: ChapterLayout, from: Int, to: Int): Passage? {
        if (layout.verseCount == 0) return null
        val a = (from - 1).coerceIn(0, layout.verseCount - 1)
        val b = (maxOf(from, to) - 1).coerceIn(a, layout.verseCount - 1)
        if (from < 1 || from > layout.verseCount) return null
        return Passage(layout.numberStarts[a], layout.textEnds[b])
    }

    /** Display offset where verse [verse] (1-based) begins, for scrolling to it. */
    fun verseStart(layout: ChapterLayout, verse: Int): Int? =
        layout.numberStarts.getOrNull(verse - 1)
}
