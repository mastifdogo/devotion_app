package com.mastifdogo.devotion.data

import com.mastifdogo.devotion.logic.AudioFetchStep
import com.mastifdogo.devotion.logic.BibleAudioLogic
import com.mastifdogo.devotion.logic.BibleRef
import com.mastifdogo.devotion.logic.CachedChapterAudio
import com.mastifdogo.devotion.logic.ChapterTimings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Chapter audio made by the server and kept on the phone: filesDir/bible-audio/<version>-<book>-<chapter>.mp3
 * (downloaded to a .part file, then renamed). A file's modification time is when it was last
 * played, which is what the size limit trims by.
 */
class BibleAudioStore(filesDir: File) {
    private val dir = File(filesDir, "bible-audio")

    fun file(ref: BibleRef): File = File(dir, BibleAudioLogic.fileName(ref))

    /** Names of the complete chapter files on the phone. */
    fun cachedNames(): Set<String> = (dir.list() ?: emptyArray()).filter { it.endsWith(".mp3") }.toSet()

    fun totalBytes(): Long = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".mp3") }.sumOf { it.length() }

    fun timingsFile(ref: BibleRef): File = File(dir, BibleAudioLogic.timingsFileName(ref))

    /** The verse times kept next to the chapter's MP3, or null (older download, or none on the server). Blocking. */
    fun loadTimings(ref: BibleRef): ChapterTimings? =
        try {
            val f = timingsFile(ref)
            if (f.isFile) BibleAudioLogic.parseTimings(f.readText()) else null
        } catch (e: Exception) {
            null
        }

    /**
     * Gets the chapter's audio from the server. It asks for the verse times first: while the
     * chapter is being made (202) it waits and asks again; once they are there the MP3 is ready
     * and is downloaded, and both are saved. A server without verse times (404 there) still gives
     * the MP3. Suspends until done; cancellable. Throws ApiException with a message for the user
     * when the server says no or making it failed.
     */
    suspend fun fetch(api: ApiClient, baseUrl: String, ref: BibleRef) {
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the Bible audio folder.")
        }
        val dest = file(ref)
        val part = File(dir, dest.name + ".part")
        try {
            // 1. The verse times (this also starts the making).
            var timingsJson: String? = null
            while (true) {
                val answer = withContext(Dispatchers.IO) {
                    api.fetchChapterVerses(baseUrl, ref.bibleId, ref.book, ref.chapter)
                }
                if (answer.httpCode == 200) {
                    timingsJson = answer.body?.takeIf { BibleAudioLogic.parseTimings(it) != null }
                    break
                }
                // No verse times here: let the audio request decide (older server, or audio off).
                if (answer.httpCode == 404) break
                when (val step = BibleAudioLogic.nextStep(answer.httpCode, answer.message, System.currentTimeMillis() - started)) {
                    is AudioFetchStep.Wait -> delay(step.delayMs)
                    is AudioFetchStep.Failed -> throw ApiException(step.message, answer.httpCode)
                    is AudioFetchStep.Ready -> break
                }
            }
            // 2. The MP3.
            while (true) {
                val answer = withContext(Dispatchers.IO) {
                    api.fetchChapterAudio(baseUrl, ref.bibleId, ref.book, ref.chapter, part)
                }
                when (val step = BibleAudioLogic.nextStep(answer.httpCode, answer.message, System.currentTimeMillis() - started)) {
                    is AudioFetchStep.Ready -> {
                        val json = timingsJson
                        withContext(Dispatchers.IO) {
                            if (part.length() <= 0L) throw ApiException("The server sent no audio.")
                            if (dest.exists()) dest.delete()
                            if (!part.renameTo(dest)) throw IOException("Could not save the chapter audio.")
                            if (json != null) writeAtomically(timingsFile(ref), json) else timingsFile(ref).delete()
                        }
                        return
                    }
                    is AudioFetchStep.Wait -> delay(step.delayMs)
                    is AudioFetchStep.Failed -> throw ApiException(step.message, answer.httpCode)
                }
            }
        } finally {
            part.delete()
        }
    }

    /**
     * For a chapter whose MP3 is on the phone without verse times: one quiet attempt to get them.
     * Returns true when they were saved. Blocking; never throws.
     */
    fun fetchTimingsQuietly(api: ApiClient, baseUrl: String, ref: BibleRef): Boolean =
        try {
            if (!file(ref).isFile || timingsFile(ref).isFile) {
                false
            } else {
                val answer = api.fetchChapterVerses(baseUrl, ref.bibleId, ref.book, ref.chapter)
                val body = answer.body
                if (answer.httpCode == 200 && body != null && BibleAudioLogic.parseTimings(body) != null) {
                    writeAtomically(timingsFile(ref), body)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }

    /** Marks the chapter as played now (for least-recently-played trimming). */
    fun touch(ref: BibleRef) {
        file(ref).setLastModified(System.currentTimeMillis())
    }

    /** Deletes least recently played chapters until the cache is within the limit; never [keep]. Blocking. */
    fun trim(keep: BibleRef?) {
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".mp3") }
            .map { CachedChapterAudio(it.name, it.length(), it.lastModified()) }
        val keepName = keep?.let { BibleAudioLogic.fileName(it) }
        for (name in BibleAudioLogic.filesToTrim(files, BibleAudioLogic.MAX_CACHE_BYTES, keepName)) {
            File(dir, name).delete()
            // The verse times go with their MP3.
            File(dir, BibleAudioLogic.timingsFileFor(name)).delete()
        }
    }

    /** Deletes all chapter audio. Blocking. */
    fun clear() {
        dir.deleteRecursively()
    }
}
