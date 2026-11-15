package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.AudioVariant
import com.mastifdogo.devotion.data.Entry

/** Pure logic for an entry's audio in several voices: variants, voice choice and cache file names. */
object AudioLogic {
    /** File-name part used for the voice id "" (the server default on older servers). */
    const val DEFAULT_VOICE_FILE = "default"

    /**
     * The audio variants of [entry]: its `audio` list, or, from servers that do not send one,
     * a single variant with voice "" described by has_audio / audio_bytes / audio_sha256.
     */
    fun variants(entry: Entry): List<AudioVariant> {
        val list = entry.audio
        if (list != null) return list.distinctBy { it.voice }
        return if (entry.hasAudio) listOf(AudioVariant("", entry.audioBytes, entry.audioSha256)) else emptyList()
    }

    /** "12-en_US-amy-medium.mp3"; characters unsafe in a file name become '_', "" becomes "default". */
    fun fileName(entryId: Long, voice: String): String {
        val safe = voice.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("").trim('.')
        return "$entryId-${safe.ifEmpty { DEFAULT_VOICE_FILE }}.mp3"
    }

    /**
     * The variants that can be played: all of them, except for an archived entry, whose audio is
     * no longer on the server, where only the cached ones count.
     */
    fun playableVariants(entry: Entry, cachedFiles: Set<String>): List<AudioVariant> {
        val all = variants(entry)
        if (!entry.archived) return all
        return all.filter { fileName(entry.id, it.voice) in cachedFiles }
    }

    /** The user's preferred voice if the entry has it, else the server default, else the first; null if none. */
    fun pickVoice(variants: List<AudioVariant>, preferred: String?, serverDefault: String?): String? {
        if (variants.isEmpty()) return null
        if (preferred != null && variants.any { it.voice == preferred }) return preferred
        if (serverDefault != null && variants.any { it.voice == serverDefault }) return serverDefault
        return variants.first().voice
    }

    /** Files of variants whose sha256 differs between the cached and the newly synced version of an entry. */
    fun changedFiles(previous: Entry?, incoming: Entry): List<String> {
        if (previous == null) return emptyList()
        val before = variants(previous).associateBy { it.voice }
        return variants(incoming)
            .filter { v -> before[v.voice].let { it != null && !sameHash(it.sha256, v.sha256) } }
            .map { fileName(incoming.id, it.voice) }
    }

    private fun sameHash(a: String?, b: String?): Boolean =
        a?.trim()?.lowercase() == b?.trim()?.lowercase()

    /**
     * Cached .mp3 files that no entry variant accounts for any more: deleted entries, voices
     * removed from an entry, and old-format "<id>.mp3" files from version 0.1.0.
     */
    fun staleFiles(cachedFiles: Collection<String>, entries: List<Entry>): List<String> {
        val expected = HashSet<String>()
        for (entry in entries) for (v in variants(entry)) expected.add(fileName(entry.id, v.voice))
        return cachedFiles.filter { it.endsWith(".mp3") && it !in expected }
    }
}
