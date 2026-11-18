package com.mastifdogo.devotion.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloaded Bible versions under filesDir/bibles/:
 *   <id>.json     the version exactly as served (downloaded to <id>.json.part, verified, renamed)
 *   index.json    name/language of the versions on the device, so they are listed offline
 *
 * One version comes with the app ([BUNDLED], opened by [openBundled] from the APK's assets): it is
 * there before any server has been reached and cannot be deleted. It is the same text, in the same
 * form, as the server serves; a copy downloaded from the server is used in its place.
 */
class BibleStore(filesDir: File, private val openBundled: (() -> InputStream)? = null) {
    private val dir = File(filesDir, "bibles")
    private val indexFile = File(dir, "index.json")

    /** Whether [id] is the version that comes with the app. */
    fun isBundled(id: String): Boolean = openBundled != null && id == BUNDLED.id

    private fun bibleFile(id: String): File = File(dir, safeName(id) + ".json")

    private fun safeName(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").ifEmpty { "bible" }

    /** Versions on the device: the one that comes with the app, and index entries whose file is really there. */
    fun downloaded(): List<BibleInfo> {
        val index = try {
            if (indexFile.isFile) AppJson.decodeFromString(BibleIndex.serializer(), indexFile.readText()) else BibleIndex()
        } catch (e: Exception) {
            BibleIndex()
        }
        val onDisk = index.bibles.filter { bibleFile(it.id).isFile }
        return if (openBundled == null || onDisk.any { it.id == BUNDLED.id }) onDisk else listOf(BUNDLED) + onDisk
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
                saveIndex(downloaded().filter { it.id != info.id && it != BUNDLED } + info)
            } finally {
                part.delete()
            }
        }
    }

    /** Deletes a downloaded version (the one that comes with the app stays). Blocking; call on a background thread. */
    fun delete(id: String) {
        bibleFile(id).delete()
        saveIndex(downloaded().filter { it.id != id && it != BUNDLED })
    }

    /** Parses a version into memory. Blocking and slow (several MB); call off the main thread. */
    fun load(id: String): Bible {
        val file = bibleFile(id)
        val open = openBundled
        val downloaded = file.isFile
        val text = when {
            downloaded -> file.readText()
            open != null && id == BUNDLED.id -> open().use { it.readBytes().toString(Charsets.UTF_8) }
            else -> throw IOException("This Bible version is not on the device.")
        }
        return try {
            AppJson.decodeFromString(Bible.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw IOException(
                if (downloaded) {
                    "The downloaded Bible file is damaged; delete it and download it again."
                } else {
                    "The Bible that comes with the app could not be read."
                },
            )
        }
    }

    /** Removes every downloaded version. */
    fun clear() {
        dir.deleteRecursively()
    }

    private fun saveIndex(list: List<BibleInfo>) {
        writeAtomically(indexFile, AppJson.encodeToString(BibleIndex.serializer(), BibleIndex(list)))
    }

    companion object {
        /** The version that comes with the app. */
        val BUNDLED = BibleInfo(id = "kjv", name = "King James Version", language = "en")

        /** Where it is in the APK's assets (written by server/scripts/app-bible.js). */
        const val BUNDLED_ASSET = "bibles/kjv.json"
    }
}
