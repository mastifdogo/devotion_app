package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.AudioVariant
import com.mastifdogo.devotion.data.Entry

/** What the player offers for an entry (see [AudioLogic.playerOffer]). */
sealed class PlayerOffer {
    /** Play [voice]; [otherVoice] is true when it is not the Settings voice (kept archived audio). */
    data class Play(val voice: String, val otherVoice: Boolean) : PlayerOffer()
    /** The entry has audio, but not yet in the Settings voice [voice]. */
    data class NotReady(val voice: String) : PlayerOffer()
    data object NoAudio : PlayerOffer()
}

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

    // ---- one voice on the phone: the one chosen in Settings ---------------------------------

    /**
     * The variant that stands for the server's default voice when the Settings voice is not known
     * yet: the one whose sha256 matches has_audio/audio_sha256 (which describe the default voice),
     * else the first.
     */
    fun defaultVariant(entry: Entry): AudioVariant? {
        val all = variants(entry)
        val defaultSha = entry.audioSha256
        return all.firstOrNull { defaultSha != null && sameHash(it.sha256, defaultSha) } ?: all.firstOrNull()
    }

    /**
     * The voice the phone keeps (and downloads) for a live entry: [settingsVoice] if the entry has
     * it; the single variant of an older server (voice ""); with no known Settings voice, the
     * server default. Null when the entry does not have that voice (yet), or for archived entries,
     * whose audio can no longer be downloaded.
     */
    fun voiceToKeep(entry: Entry, settingsVoice: String?): String? {
        if (entry.archived) return null
        val all = variants(entry)
        if (all.isEmpty()) return null
        if (entry.audio == null) return all.first().voice
        preferredVoice(entry)?.let { return it }
        if (settingsVoice == null) return defaultVariant(entry)?.voice
        return all.firstOrNull { it.voice == settingsVoice }?.voice
    }

    /**
     * The entry's own voice (preferred_voice), when it is set and the entry really has audio in
     * it; such an entry is downloaded and played in that voice whatever Settings says.
     */
    fun preferredVoice(entry: Entry): String? {
        val wanted = entry.preferredVoice?.takeIf { it.isNotEmpty() } ?: return null
        return variants(entry).firstOrNull { it.voice == wanted }?.voice
    }

    /**
     * What the player offers. Live entries: the Settings voice (downloaded on demand), or "not
     * ready yet" when the entry does not have it; never another voice. Archived entries: their
     * cached audio, preferring the Settings voice, labelled when it is another voice.
     */
    fun playerOffer(entry: Entry, settingsVoice: String?, cachedFiles: Set<String>): PlayerOffer {
        if (entry.archived) {
            val cached = playableVariants(entry, cachedFiles)
            if (cached.isEmpty()) return PlayerOffer.NoAudio
            val wanted = preferredVoice(entry) ?: settingsVoice
            val same = cached.firstOrNull { it.voice == wanted } ?: cached.firstOrNull { it.voice == settingsVoice }
            if (same != null) return PlayerOffer.Play(same.voice, otherVoice = false)
            val voice = cached.first().voice
            return PlayerOffer.Play(voice, otherVoice = settingsVoice != null && voice != settingsVoice && voice.isNotEmpty())
        }
        val keep = voiceToKeep(entry, settingsVoice)
        if (keep != null) return PlayerOffer.Play(keep, otherVoice = false)
        return if (variants(entry).isNotEmpty() && settingsVoice != null) PlayerOffer.NotReady(settingsVoice) else PlayerOffer.NoAudio
    }

    /**
     * Cached audio to delete so that only [settingsVoice] stays on the phone: for live entries,
     * every file except the one of [voiceToKeep]; archived entries keep all their audio (it cannot
     * be downloaded again); files of no entry at all go too. Nothing is deleted while the Settings
     * voice is unknown (null).
     */
    fun filesForOtherVoices(cachedFiles: Collection<String>, entries: List<Entry>, settingsVoice: String?): List<String> {
        if (settingsVoice == null) return emptyList()
        val keep = HashSet<String>()
        for (entry in entries) {
            if (entry.archived) {
                for (v in variants(entry)) keep.add(fileName(entry.id, v.voice))
            } else {
                voiceToKeep(entry, settingsVoice)?.let { keep.add(fileName(entry.id, it)) }
            }
        }
        return cachedFiles.filter { it.endsWith(".mp3") && it !in keep }
    }
}
