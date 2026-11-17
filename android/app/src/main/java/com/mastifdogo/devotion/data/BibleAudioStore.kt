package com.mastifdogo.devotion.data

import com.mastifdogo.devotion.logic.AudioFetchStep
import com.mastifdogo.devotion.logic.BibleAudioLogic
import com.mastifdogo.devotion.logic.BibleRef
import com.mastifdogo.devotion.logic.CachedChapterAudio
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

    /**
     * Gets the chapter's audio from the server: asks, waits while it is being made (202), and
     * saves the MP3. Suspends until it is there; cancellable. Throws ApiException with a message
     * for the user when the server says no or making it failed.
     */
    suspend fun fetch(api: ApiClient, baseUrl: String, ref: BibleRef) {
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the Bible audio folder.")
        }
        val dest = file(ref)
        val part = File(dir, dest.name + ".part")
        try {
            while (true) {
                val answer = withContext(Dispatchers.IO) {
                    api.fetchChapterAudio(baseUrl, ref.bibleId, ref.book, ref.chapter, part)
                }
                when (val step = BibleAudioLogic.nextStep(answer.httpCode, answer.message, System.currentTimeMillis() - started)) {
                    is AudioFetchStep.Ready -> {
                        withContext(Dispatchers.IO) {
                            if (part.length() <= 0L) throw ApiException("The server sent no audio.")
                            if (dest.exists()) dest.delete()
                            if (!part.renameTo(dest)) throw IOException("Could not save the chapter audio.")
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
        }
    }

    /** Deletes all chapter audio. Blocking. */
    fun clear() {
        dir.deleteRecursively()
    }
}
