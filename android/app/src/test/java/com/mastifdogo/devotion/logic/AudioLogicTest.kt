package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.AudioVariant
import com.mastifdogo.devotion.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioLogicTest {

    private val amy = AudioVariant("en_US-amy-medium", 100, "aa")
    private val joe = AudioVariant("en_US-joe-medium", 200, "bb")

    private fun entry(
        id: Long = 12,
        audio: List<AudioVariant>? = listOf(amy, joe),
        hasAudio: Boolean = true,
        sha: String? = "aa",
        archived: Boolean = false,
    ) = Entry(id = id, hasAudio = hasAudio, audioBytes = 100, audioSha256 = sha, audio = audio, archived = archived)

    @Test
    fun variants_useListOrFallBackToSingleAudioFields() {
        assertEquals(listOf(amy, joe), AudioLogic.variants(entry()))
        assertEquals(emptyList<AudioVariant>(), AudioLogic.variants(entry(audio = emptyList())))
        assertEquals(listOf(AudioVariant("", 100, "aa")), AudioLogic.variants(entry(audio = null)))
        assertEquals(emptyList<AudioVariant>(), AudioLogic.variants(entry(audio = null, hasAudio = false)))
        // Duplicate voices are ignored.
        assertEquals(listOf(amy), AudioLogic.variants(entry(audio = listOf(amy, amy.copy(sha256 = "zz")))))
    }

    @Test
    fun fileName_isSafe() {
        assertEquals("12-en_US-amy-medium.mp3", AudioLogic.fileName(12, "en_US-amy-medium"))
        assertEquals("12-default.mp3", AudioLogic.fileName(12, ""))
        assertEquals("3-a_b_c.mp3", AudioLogic.fileName(3, "a/b c"))
        assertEquals("3-_x.mp3", AudioLogic.fileName(3, "..\\x"))
        assertEquals("3-default.mp3", AudioLogic.fileName(3, ".."))
    }

    @Test
    fun pickVoice_preferredThenServerDefaultThenFirst() {
        val list = listOf(amy, joe)
        assertEquals("en_US-joe-medium", AudioLogic.pickVoice(list, "en_US-joe-medium", "en_US-amy-medium"))
        assertEquals("en_US-amy-medium", AudioLogic.pickVoice(list, "missing", "en_US-amy-medium"))
        assertEquals("en_US-amy-medium", AudioLogic.pickVoice(list, null, null))
        assertEquals("en_US-joe-medium", AudioLogic.pickVoice(listOf(joe), "missing", "also-missing"))
        assertEquals("", AudioLogic.pickVoice(listOf(AudioVariant("")), "en_US-amy-medium", null))
        assertNull(AudioLogic.pickVoice(emptyList(), "en_US-amy-medium", "x"))
    }

    @Test
    fun playableVariants_archivedOnlyCached() {
        val cached = setOf(AudioLogic.fileName(12, joe.voice))
        assertEquals(listOf(amy, joe), AudioLogic.playableVariants(entry(), cached))
        assertEquals(listOf(joe), AudioLogic.playableVariants(entry(archived = true), cached))
        assertEquals(emptyList<AudioVariant>(), AudioLogic.playableVariants(entry(archived = true), emptySet()))
    }

    @Test
    fun changedFiles_onlyWhereShaDiffers() {
        val before = entry()
        val after = entry(audio = listOf(amy, joe.copy(sha256 = "BB"), AudioVariant("new", 1, "cc")))
        // "BB" vs "bb" is the same hash; a brand-new voice is not "changed".
        assertEquals(emptyList<String>(), AudioLogic.changedFiles(before, after))
        val changed = entry(audio = listOf(amy.copy(sha256 = "a2"), joe))
        assertEquals(listOf("12-en_US-amy-medium.mp3"), AudioLogic.changedFiles(before, changed))
        assertEquals(emptyList<String>(), AudioLogic.changedFiles(null, changed))
        // Old single-audio fields: a changed audio_sha256 changes the "" variant.
        assertEquals(
            listOf("12-default.mp3"),
            AudioLogic.changedFiles(entry(audio = null, sha = "aa"), entry(audio = null, sha = "a3")),
        )
    }

    @Test
    fun staleFiles_findsRemovedVoicesDeletedEntriesAndOldFormat() {
        val entries = listOf(entry(id = 12, audio = listOf(amy)), entry(id = 13, audio = null))
        val cached = listOf(
            "12-en_US-amy-medium.mp3", // still there
            "12-en_US-joe-medium.mp3", // voice removed from the entry
            "13-default.mp3", // old server single variant
            "14-en_US-amy-medium.mp3", // entry gone
            "12.mp3", // v0.1.0 format
            "12-en_US-amy-medium.mp3.part", // download in progress: not an .mp3
        )
        assertEquals(
            listOf("12-en_US-joe-medium.mp3", "14-en_US-amy-medium.mp3", "12.mp3"),
            AudioLogic.staleFiles(cached, entries),
        )
    }
}
