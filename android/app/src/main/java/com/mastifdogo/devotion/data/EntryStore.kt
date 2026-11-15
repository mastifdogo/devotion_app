package com.mastifdogo.devotion.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The on-device entry cache, all under filesDir:
 *   entries.json      full entry list + last server_time
 *   audio/<id>.mp3    narration (downloaded to <id>.mp3.part, verified, then renamed)
 */
class EntryStore(filesDir: File) {
    private val entriesFile = File(filesDir, "entries.json")
    private val audioDir = File(filesDir, "audio")

    @Volatile
    private var cache: CacheFile = CacheFile()

    /** Reads entries.json; a missing or corrupt file yields an empty cache. */
    fun load(): CacheFile {
        val loaded = try {
            if (entriesFile.isFile) {
                AppJson.decodeFromString(CacheFile.serializer(), entriesFile.readText())
            } else {
                CacheFile()
            }
        } catch (e: Exception) {
            CacheFile()
        }
        cache = loaded.copy(entries = loaded.entries.sortedNewestFirst())
        return cache
    }

    fun audioFile(id: Long): File = File(audioDir, "$id.mp3")

    fun cachedAudioIds(): Set<Long> {
        val names = audioDir.list() ?: return emptySet()
        return names.mapNotNull { name ->
            if (name.endsWith(".mp3")) name.removeSuffix(".mp3").toLongOrNull() else null
        }.toSet()
    }

    /**
     * Incremental sync (full when there is no previous server_time or the server changed).
     * Blocking; call on a background thread. Returns the new cache contents.
     */
    fun sync(api: ApiClient, baseUrl: String): CacheFile {
        val server = ApiClient.normalizeBaseUrl(baseUrl)
        val old = cache
        val sameServer = old.serverUrl == server
        val since = if (sameServer) old.serverTime?.takeIf { it.isNotBlank() } else null
        val full = since == null

        val response = api.entries(server, since)

        // Nothing cached from another server is worth keeping (ids would clash).
        if (!sameServer) audioDir.deleteRecursively()
        val oldById = if (sameServer) old.entries.associateBy { it.id } else emptyMap()
        val merged = LinkedHashMap<Long, Entry>()
        if (!full) merged.putAll(oldById)

        for (entry in response.entries) {
            if (entry.deleted) {
                merged.remove(entry.id)
                deleteAudio(entry.id)
                continue
            }
            merged[entry.id] = mergeEntry(oldById[entry.id], entry)
        }

        if (full) {
            // A full listing is authoritative: drop audio of entries that no longer exist.
            for (id in cachedAudioIds()) {
                if (!merged.containsKey(id)) deleteAudio(id)
            }
        }

        val updated = CacheFile(
            entries = merged.values.toList().sortedNewestFirst(),
            serverTime = response.serverTime ?: since,
            serverUrl = server,
            lastSyncMillis = System.currentTimeMillis(),
        )
        save(updated)
        cache = updated
        return updated
    }

    /**
     * Combines what the server sent with what is cached. An archived entry arrives without body
     * and audio: the cached body is kept and the cached audio file is left alone. For a live
     * entry a changed or removed narration deletes the stale audio file.
     */
    private fun mergeEntry(previous: Entry?, incoming: Entry): Entry {
        if (incoming.archived) {
            if (previous == null) return incoming
            return incoming.copy(
                body = if (incoming.body.isBlank()) previous.body else incoming.body,
                // Keep describing the audio file that is (possibly) still on the device.
                audioBytes = previous.audioBytes,
                audioSha256 = previous.audioSha256,
            )
        }
        val audioChanged = !incoming.hasAudio ||
            (previous != null && previous.audioSha256 != incoming.audioSha256)
        if (audioChanged) deleteAudio(incoming.id)
        return incoming
    }

    /**
     * Downloads the audio of [entry] to a .part file, verifies its SHA-256 when the entry has one,
     * then renames it into place. [onProgress] gets a 0..1 fraction, or null when the size is unknown.
     */
    suspend fun downloadAudio(
        api: ApiClient,
        baseUrl: String,
        entry: Entry,
        onProgress: (Float?) -> Unit,
    ) {
        val context = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if (!audioDir.isDirectory && !audioDir.mkdirs()) {
                throw IOException("Could not create the audio cache folder.")
            }
            val part = File(audioDir, "${entry.id}.mp3.part")
            try {
                val progress = ProgressThrottle(entry.audioBytes ?: 0L, onProgress)
                val actual = api.downloadAudio(baseUrl, entry.id, part) { read, total ->
                    context.ensureActive()
                    progress.report(read, total)
                }
                val expected = entry.audioSha256
                if (!expected.isNullOrBlank() && !expected.trim().equals(actual, ignoreCase = true)) {
                    throw ApiException("Downloaded audio failed its SHA-256 check; please try again.")
                }
                val dest = audioFile(entry.id)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    throw IOException("Could not move the downloaded audio into place.")
                }
            } finally {
                part.delete()
            }
        }
    }

    fun deleteAudio(id: Long) {
        audioFile(id).delete()
        File(audioDir, "$id.mp3.part").delete()
    }

    /** Removes all cached entries and audio. */
    fun clear() {
        audioDir.deleteRecursively()
        entriesFile.delete()
        cache = CacheFile()
    }

    private fun save(data: CacheFile) {
        writeAtomically(entriesFile, AppJson.encodeToString(CacheFile.serializer(), data))
    }
}

/** Turns (bytesRead, total) callbacks into at most one callback per percent. */
internal class ProgressThrottle(private val fallbackTotal: Long, private val onProgress: (Float?) -> Unit) {
    private var last = -1

    fun report(read: Long, total: Long) {
        val known = if (total > 0) total else fallbackTotal
        if (known > 0) {
            val percent = ((read * 100) / known).toInt().coerceIn(0, 100)
            if (percent != last) {
                last = percent
                onProgress(percent / 100f)
            }
        } else if (last != -2) {
            last = -2
            onProgress(null)
        }
    }
}

/** Writes [text] to a temporary file next to [target] and renames it into place. */
internal fun writeAtomically(target: File, text: String) {
    val parent = target.parentFile
    if (parent != null && !parent.isDirectory) parent.mkdirs()
    val tmp = File(parent, target.name + ".tmp")
    tmp.writeText(text)
    if (target.exists()) target.delete()
    if (!tmp.renameTo(target)) throw IOException("Could not write ${target.name}.")
}
