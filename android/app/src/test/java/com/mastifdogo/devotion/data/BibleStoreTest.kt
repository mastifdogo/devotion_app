package com.mastifdogo.devotion.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BibleStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The file packaged into the APK (unit tests run in the app module's folder). */
    private val asset = File("src/main/assets/" + BibleStore.BUNDLED_ASSET)

    private fun store() = BibleStore(tmp.root) { asset.inputStream() }

    @Test
    fun theKingJamesVersionComesWithTheApp() {
        val store = store()
        // There without any download, and whole.
        assertEquals(listOf(BibleStore.BUNDLED), store.downloaded())
        assertTrue(store.isBundled("kjv"))
        assertFalse(store.isBundled("asv"))
        val bible = store.load("kjv")
        assertEquals("kjv", bible.id)
        assertEquals("King James Version", bible.name)
        assertEquals(66, bible.books.size)
        assertEquals(1189, bible.books.sumOf { it.chapters.size })
        assertEquals(31102, bible.books.sumOf { book -> book.chapters.sumOf { it.size } })
        assertEquals("Genesis", bible.books.first().name)
        assertEquals("Revelation", bible.books.last().name)
        assertEquals("In the beginning God created the heaven and the earth.", bible.books[0].chapters[0][0])
        assertEquals("Jesus wept.", bible.books[42].chapters[10][34])
        // It cannot be deleted.
        store.delete("kjv")
        assertEquals(listOf(BibleStore.BUNDLED), store.downloaded())
        assertEquals(66, store.load("kjv").books.size)
    }

    @Test
    fun aCopyFromTheServerStandsInForIt() {
        val dir = File(tmp.root, "bibles").apply { mkdirs() }
        val fromServer = BibleInfo(id = "kjv", name = "King James Version", language = "en", bytes = 60L, sha256 = "abc")
        val other = BibleInfo(id = "asv", name = "American Standard Version (1901)", language = "en", bytes = 60L)
        File(dir, "kjv.json").writeText("""{"id":"kjv","name":"From the server","books":[{"name":"Genesis","chapters":[["one"]]}]}""")
        File(dir, "asv.json").writeText("""{"id":"asv","name":"ASV","books":[]}""")
        File(dir, "index.json").writeText(AppJson.encodeToString(BibleIndex.serializer(), BibleIndex(listOf(fromServer, other))))
        val store = store()
        assertEquals(listOf(fromServer, other), store.downloaded())
        assertEquals("From the server", store.load("kjv").name)
        // Deleting the copy brings back the one that comes with the app; other versions simply go.
        store.delete("kjv")
        assertEquals(listOf(BibleStore.BUNDLED, other), store.downloaded())
        assertEquals("King James Version", store.load("kjv").name)
        store.delete("asv")
        assertEquals(listOf(BibleStore.BUNDLED), store.downloaded())
        assertThrows(IOException::class.java) { store.load("asv") }
        // What comes with the app is never written into the list of downloads.
        assertEquals(BibleIndex(), AppJson.decodeFromString(BibleIndex.serializer(), File(dir, "index.json").readText()))
    }

    @Test
    fun withoutABundledVersionOnlyDownloadsCount() {
        val store = BibleStore(tmp.root)
        assertEquals(emptyList<BibleInfo>(), store.downloaded())
        assertFalse(store.isBundled("kjv"))
        assertThrows(IOException::class.java) { store.load("kjv") }
    }
}
