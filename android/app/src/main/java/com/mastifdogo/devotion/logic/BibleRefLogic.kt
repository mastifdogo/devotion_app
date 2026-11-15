package com.mastifdogo.devotion.logic

/** A parsed reference such as "1 Corinthians 13:4": [book] is normalized (see [BibleRefLogic.normalizeBookName]). */
data class ScriptureRef(val book: String, val chapter: Int?, val verse: Int?)

/** A place in a Bible version: [book] and [chapter] are 0-based indexes, [verse] is 1-based. */
data class BibleLocation(val book: Int, val chapter: Int, val verse: Int?)

/** Pure, best-effort parsing of scripture references and matching them to a version's books. */
object BibleRefLogic {
    private val ordinalPrefix = Regex(
        "^(1st|2nd|3rd|first|second|third|iii|ii|i|[1-3])\\s+(?=\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    private val gluedDigitPrefix = Regex("^([1-3])(?=\\p{L})")
    private val chapterVerse = Regex("^(\\d+)(?:\\s*[:.]\\s*(\\d+))?")

    /**
     * Lower-cases, removes dots, collapses spaces and writes a leading ordinal as a digit:
     * "I Corinthians", "1st Corinthians", "First Corinthians", "1Cor." -> "1 corinthians" / "1 cor".
     */
    fun normalizeBookName(raw: String): String {
        var s = raw.trim().replace(".", " ").replace(Regex("\\s+"), " ").trim()
        var number = ""
        val ordinal = ordinalPrefix.find(s)
        if (ordinal != null) {
            number = when (ordinal.groupValues[1].lowercase()) {
                "1", "i", "1st", "first" -> "1"
                "2", "ii", "2nd", "second" -> "2"
                else -> "3"
            }
            s = s.substring(ordinal.range.last + 1)
        } else {
            val glued = gluedDigitPrefix.find(s)
            if (glued != null) {
                number = glued.groupValues[1]
                s = s.substring(1)
            }
        }
        val name = s.trim().lowercase()
        return if (number.isEmpty()) name else "$number $name"
    }

    /**
     * "Psalm 23; John 1:1-5" -> [(psalm, 23, null), (john, 1, 1)]. Parts are separated by ';'.
     * A part without a book name ("John 3:16; 4:1") continues the previous book.
     */
    fun parseReferences(text: String): List<ScriptureRef> {
        val result = ArrayList<ScriptureRef>()
        var previousBook: String? = null
        for (rawPart in text.split(';', '\n')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            // The book name runs up to the first digit that is not the leading "1/2/3" of the name.
            var nameEnd = 0
            val leading = ordinalPrefix.find(part) ?: gluedDigitPrefix.find(part)
            if (leading != null) nameEnd = leading.range.last + 1
            while (nameEnd < part.length && !part[nameEnd].isDigit()) nameEnd++
            val bookText = part.substring(0, nameEnd).trim().trimEnd(',', ':')
            val numbers = chapterVerse.find(part.substring(nameEnd).trim())
            val chapter = numbers?.groupValues?.get(1)?.toIntOrNull()
            val verse = numbers?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()

            val book = if (bookText.any { it.isLetter() }) normalizeBookName(bookText) else previousBook
            if (book.isNullOrEmpty()) continue
            if (chapter == null && book == previousBook) continue
            previousBook = book
            result.add(ScriptureRef(book, chapter, verse))
        }
        return result
    }

    /**
     * Index of the book called [name] in [bookNames], or -1. Matching is case-insensitive and
     * tolerant of "Psalm"/"Psalms", of ordinals written as 1/I/First, and of abbreviations that
     * are a prefix of the book name ("Gen", "1 Cor").
     */
    fun findBook(bookNames: List<String>, name: String): Int {
        val wanted = normalizeBookName(name)
        if (wanted.isEmpty()) return -1
        val books = bookNames.map { normalizeBookName(it) }

        var index = books.indexOf(wanted)
        if (index >= 0) return index

        val wantedSingular = wanted.removeSuffix("s")
        index = books.indexOfFirst { it.removeSuffix("s") == wantedSingular }
        if (index >= 0) return index

        if (wantedSingular.count { it.isLetter() } >= 2) {
            index = books.indexOfFirst { it.startsWith(wanted) }
            if (index >= 0) return index
            index = books.indexOfFirst { it.startsWith(wantedSingular) }
            if (index >= 0) return index
        }
        return -1
    }

    /**
     * The first reference in [text] whose book exists in the version. [chapterCounts] holds the
     * number of chapters per book. A chapter that does not exist opens the book at its start;
     * "Jude 5" in a one-chapter book means verse 5. Returns null when nothing can be resolved.
     */
    fun resolve(text: String, bookNames: List<String>, chapterCounts: List<Int>): BibleLocation? {
        for (ref in parseReferences(text)) {
            val book = findBook(bookNames, ref.book)
            if (book < 0) continue
            val chapters = chapterCounts.getOrElse(book) { 0 }
            if (chapters <= 0) continue
            var chapter = ref.chapter
            var verse = ref.verse
            if (chapter == null) return BibleLocation(book, 0, null)
            if (chapters == 1 && verse == null && chapter > 1) {
                verse = chapter
                chapter = 1
            }
            if (chapter < 1 || chapter > chapters) return BibleLocation(book, 0, null)
            return BibleLocation(book, chapter - 1, verse)
        }
        return null
    }
}
