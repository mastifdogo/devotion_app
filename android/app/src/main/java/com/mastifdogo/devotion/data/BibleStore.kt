package com.mastifdogo.devotion.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloaded Bible versions under filesDir/bibles/:
 *   <id>.json     the version exactly as served (downloaded to <id>.json.part, verified, renamed)
 *   index.json    name/language of the versions on the device, so they are listed offline
 */
class BibleStore(filesDir: File) {
    private val dir = File(filesDir, "bibles")
    private val indexFile = File(dir, "index.json")

    private fun bibleFile(id: String): File = File(dir, safeName(id) + ".json")

    private fun safeName(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").ifEmpty { "bible" }

    /** Versions on the device (index entries whose file is really there). */
    fun downloaded(): List<BibleInfo> {
        val index = try {
            if (indexFile.isFile) AppJson.decodeFromString(BibleIndex.serializer(), indexFile.readText()) else BibleIndex()
        } catch (e: Exception) {
            BibleIndex()
        }
        return index.bibles.filter { bibleFile(it.id).isFile }
    }

    /**
     * Streams the version to a .part file, verifies its SHA-256 when the list gave one, renames it
     * into place and records it in the index. [onProgress] gets 0..1, or null when the size is unknown.
     */
    suspend fun download(api: ApiClient, baseUrl: String, info: BibleInfo, onProgress: (Float?) -> Unit) {
        val context = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the Bible folder.")
            val dest = bibleFile(info.id)
            val part = File(dir, dest.name + ".part")
            try {
                val progress = ProgressThrottle(info.bytes ?: 0L, onProgress)
                val actual = api.downloadBible(baseUrl, info.id, part) { read, total ->
                    context.ensureActive()
                    progress.report(read, total)
                }
                val expected = info.sha256
                if (!expected.isNullOrBlank() && !expected.trim().equals(actual, ignoreCase = true)) {
                    throw ApiException("The downloaded Bible failed its SHA-256 check; please try again.")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("Could not move the downloaded Bible into place.")
                saveIndex(downloaded().filter { it.id != info.id } + info)
            } finally {
                part.delete()
            }
        }
    }

    /** Blocking; call on a background thread. */
    fun delete(id: String) {
        bibleFile(id).delete()
        saveIndex(downloaded().filter { it.id != id })
    }

    /** Parses a downloaded version into memory. Blocking and slow (several MB); call off the main thread. */
    fun load(id: String): Bible {
        val file = bibleFile(id)
        if (!file.isFile) throw IOException("This Bible version is not on the device.")
        return try {
            AppJson.decodeFromString(Bible.serializer(), file.readText())
        } catch (e: IllegalArgumentException) {
            throw IOException("The downloaded Bible file is damaged; delete it and download it again.")
        }
    }

    /** Removes every downloaded version. */
    fun clear() {
        dir.deleteRecursively()
    }

    private fun saveIndex(list: List<BibleInfo>) {
        writeAtomically(indexFile, AppJson.encodeToString(BibleIndex.serializer(), BibleIndex(list)))
    }
}
