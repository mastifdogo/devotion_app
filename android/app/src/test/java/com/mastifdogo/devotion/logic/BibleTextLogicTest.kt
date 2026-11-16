package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibleTextLogicTest {
    private val verses = listOf("In the beginning.", "And the earth.", "Let there be light.")
    private val layout = BibleTextLogic.layout(verses)
    private val nb = "\u00A0"

    @Test
    fun layout_numbersEachVerseOnItsOwnLine() {
        assertEquals("1${nb}In the beginning.\n2${nb}And the earth.\n3${nb}Let there be light.", layout.text)
        assertEquals(listOf(0, 20, 37), layout.numberStarts)
        assertEquals(listOf(2, 22, 39), layout.textStarts)
        assertEquals(listOf(19, 36, 58), layout.textEnds)
        assertEquals(listOf(Passage(0, 1), Passage(20, 21), Passage(37, 38)), BibleTextLogic.numberRanges(layout))
        val ten = BibleTextLogic.layout(List(10) { "v" })
        assertEquals("10", ten.text.substring(ten.numberStarts[9], ten.textStarts[9] - 1))
    }

    @Test
    fun selectionInsideOneVerse() {
        // "beginning" in verse 1 (index 0): text offsets 7..16.
        val s = layout.textStarts[0] + 7
        assertEquals(Passage(7, 16), BibleTextLogic.selectionToRange(layout, s, s + 9))
        assertEquals(Passage(7, 16), BibleTextLogic.selectionToRange(layout, s + 9, s))
    }

    @Test
    fun selectionAcrossVersesNeverCountsNumbersOrSeparators() {
        // From "earth" in verse 2 to the end of verse 3's text.
        val start = layout.textStarts[1] + 8
        val end = layout.textEnds[2]
        assertEquals(Passage(1 * 10000 + 8, 2 * 10000 + verses[2].length), BibleTextLogic.selectionToRange(layout, start, end))
        // Starting on verse 2's number counts from the start of its text.
        assertEquals(10000, BibleTextLogic.startPosition(layout, layout.numberStarts[1]))
        // Starting on the newline after verse 1 starts at verse 2.
        assertEquals(10000, BibleTextLogic.startPosition(layout, layout.textEnds[0]  + 1))
        assertEquals(verses[0].length, BibleTextLogic.startPosition(layout, layout.textEnds[0]))
        // Ending on verse 3's number ends at the end of verse 2.
        assertEquals(10000 + verses[1].length, BibleTextLogic.endPosition(layout, layout.numberStarts[2] + 1))
        assertEquals(10000 + verses[1].length, BibleTextLogic.endPosition(layout, layout.textStarts[2]))
        // Selecting only a verse number (or a separator) selects no text.
        assertNull(BibleTextLogic.selectionToRange(layout, layout.numberStarts[1], layout.textStarts[1]))
        assertNull(BibleTextLogic.selectionToRange(layout, layout.textEnds[0], layout.textStarts[1]))
        // The whole chapter.
        assertEquals(Passage(0, 20000 + verses[2].length), BibleTextLogic.selectionToRange(layout, 0, layout.text.length))
    }

    @Test
    fun toDisplay_roundTripsAndClamps() {
        for (v in verses.indices) for (o in 0..verses[v].length) {
            val pos = v * 10000 + o
            val d = BibleTextLogic.toDisplay(layout, pos)
            assertEquals(layout.textStarts[v] + o, d)
        }
        assertEquals(layout.textEnds[0], BibleTextLogic.toDisplay(layout, 999))
        assertEquals(layout.textStarts[0], BibleTextLogic.toDisplay(layout, -5))
        assertEquals(layout.textEnds[2], BibleTextLogic.toDisplay(layout, 70000))
        // A highlight drawn from positions covers exactly the selected text.
        val range = BibleTextLogic.selectionToRange(layout, layout.textStarts[1] + 4, layout.textEnds[2])!!
        val shown = layout.text.substring(BibleTextLogic.toDisplay(layout, range.start), BibleTextLogic.toDisplay(layout, range.end))
        assertEquals("the earth.\n3${nb}Let there be light.", shown)
    }

    @Test
    fun versesRangeAndStart() {
        assertEquals(Passage(layout.numberStarts[1], layout.textEnds[2]), BibleTextLogic.versesRange(layout, 2, 3))
        assertEquals(Passage(layout.numberStarts[0], layout.textEnds[0]), BibleTextLogic.versesRange(layout, 1, 1))
        assertEquals(Passage(layout.numberStarts[2], layout.textEnds[2]), BibleTextLogic.versesRange(layout, 3, 99))
        assertNull(BibleTextLogic.versesRange(layout, 4, 4))
        assertNull(BibleTextLogic.versesRange(layout, 0, 2))
        assertEquals(layout.numberStarts[1], BibleTextLogic.verseStart(layout, 2))
        assertNull(BibleTextLogic.verseStart(layout, 9))
        val empty = BibleTextLogic.layout(emptyList())
        assertEquals("", empty.text)
        assertEquals(0, BibleTextLogic.toDisplay(empty, 5))
        assertNull(BibleTextLogic.selectionToRange(empty, 0, 0))
    }
}
