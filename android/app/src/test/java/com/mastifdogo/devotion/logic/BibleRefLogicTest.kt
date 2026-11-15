package com.mastifdogo.devotion.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibleRefLogicTest {

    private val books = listOf(
        "Genesis", "Psalms", "Song of Solomon", "Isaiah", "John", "1 Corinthians", "2 Corinthians",
        "Philemon", "1 John", "2 John", "3 John", "Jude", "Revelation",
    )
    private val chapters = listOf(50, 150, 8, 66, 21, 16, 13, 1, 5, 1, 1, 1, 22)

    private fun resolve(text: String) = BibleRefLogic.resolve(text, books, chapters)

    @Test
    fun normalizeBookName_handlesOrdinals() {
        assertEquals("1 corinthians", BibleRefLogic.normalizeBookName("1 Corinthians"))
        assertEquals("1 corinthians", BibleRefLogic.normalizeBookName("I Corinthians"))
        assertEquals("1 corinthians", BibleRefLogic.normalizeBookName("First  Corinthians"))
        assertEquals("2 cor", BibleRefLogic.normalizeBookName("2Cor."))
        assertEquals("3 john", BibleRefLogic.normalizeBookName("III John"))
        assertEquals("isaiah", BibleRefLogic.normalizeBookName("Isaiah"))
        assertEquals("song of solomon", BibleRefLogic.normalizeBookName(" Song of  Solomon "))
    }

    @Test
    fun parseReferences_readsBookChapterVerse() {
        assertEquals(listOf(ScriptureRef("john", 3, 16)), BibleRefLogic.parseReferences("John 3:16-17"))
        assertEquals(listOf(ScriptureRef("1 corinthians", 13, null)), BibleRefLogic.parseReferences("1 Corinthians 13"))
        assertEquals(
            listOf(ScriptureRef("psalm", 23, null), ScriptureRef("john", 1, 1)),
            BibleRefLogic.parseReferences("Psalm 23; John 1:1"),
        )
        assertEquals(
            listOf(ScriptureRef("john", 3, 16), ScriptureRef("john", 4, 1)),
            BibleRefLogic.parseReferences("John 3:16; 4:1"),
        )
        assertEquals(listOf(ScriptureRef("jude", null, null)), BibleRefLogic.parseReferences("Jude"))
        assertEquals(listOf(ScriptureRef("2 john", 1, 3)), BibleRefLogic.parseReferences("II John 1.3"))
        assertEquals(emptyList<ScriptureRef>(), BibleRefLogic.parseReferences("  ;  "))
        assertEquals(emptyList<ScriptureRef>(), BibleRefLogic.parseReferences("3:16"))
    }

    @Test
    fun findBook_matchesCaseInsensitivelyWithVariants() {
        assertEquals(4, BibleRefLogic.findBook(books, "john"))
        assertEquals(4, BibleRefLogic.findBook(books, "JOHN"))
        assertEquals(1, BibleRefLogic.findBook(books, "Psalm"))
        assertEquals(1, BibleRefLogic.findBook(books, "Psalms"))
        assertEquals(12, BibleRefLogic.findBook(books, "Revelations"))
        assertEquals(5, BibleRefLogic.findBook(books, "I Corinthians"))
        assertEquals(6, BibleRefLogic.findBook(books, "2 Cor"))
        assertEquals(8, BibleRefLogic.findBook(books, "1 John"))
        assertEquals(10, BibleRefLogic.findBook(books, "III John"))
        assertEquals(0, BibleRefLogic.findBook(books, "Gen."))
        assertEquals(-1, BibleRefLogic.findBook(books, "Hezekiah"))
        assertEquals(-1, BibleRefLogic.findBook(books, ""))
        // A version that writes its ordinals as Roman numerals.
        assertEquals(1, BibleRefLogic.findBook(listOf("I Corinthians", "II Corinthians"), "2 Corinthians"))
        // "Psalm" must also find a book called "Psalm" when the reference says "Psalms".
        assertEquals(0, BibleRefLogic.findBook(listOf("Psalm"), "Psalms"))
    }

    @Test
    fun resolve_findsTheFirstUsableReference() {
        assertEquals(BibleLocation(4, 2, 16), resolve("John 3:16-17"))
        assertEquals(BibleLocation(5, 12, null), resolve("1 Corinthians 13"))
        assertEquals(BibleLocation(1, 22, null), resolve("Psalm 23; John 1:1"))
        assertEquals(BibleLocation(4, 0, 1), resolve("Hezekiah 4:2; John 1:1"))
        assertEquals(BibleLocation(3, 52, 5), resolve("isaiah 53:5"))
        assertEquals(BibleLocation(2, 1, 1), resolve("Song of Solomon 2:1"))
    }

    @Test
    fun resolve_handlesMissingChaptersAndOneChapterBooks() {
        // No chapter: start of the book.
        assertEquals(BibleLocation(11, 0, null), resolve("Jude"))
        // One-chapter book: the number is a verse.
        assertEquals(BibleLocation(11, 0, 5), resolve("Jude 5"))
        assertEquals(BibleLocation(7, 0, 6), resolve("Philemon 1:6"))
        // Chapter that does not exist: start of the book.
        assertEquals(BibleLocation(4, 0, null), resolve("John 99:1"))
        assertNull(resolve("Nothing useful here"))
        assertNull(resolve(""))
    }
}
