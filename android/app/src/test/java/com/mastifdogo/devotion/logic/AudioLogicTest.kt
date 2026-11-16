package com.mastifdogo.devotion.logic

import com.mastifdogo.devotion.data.AudioVariant
import com.mastifdogo.devotion.data.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- one voice on the phone -------------------------------------------------------------

    private val AMY = "en_US-amy-medium"
    private val JOE = "en_US-joe-medium"

    @Test
    fun voiceToKeep_onlyTheSettingsVoice() {
        assertEquals(JOE, AudioLogic.voiceToKeep(entry(), JOE))
        assertNull(AudioLogic.voiceToKeep(entry(audio = listOf(amy)), JOE))
        // Unknown Settings voice: the server default, i.e. the variant matching audio_sha256 ("aa" = Amy).
        assertEquals(AMY, AudioLogic.voiceToKeep(entry(audio = listOf(joe, amy)), null))
        // Older server: its single variant whatever the setting.
        assertEquals("", AudioLogic.voiceToKeep(entry(audio = null), JOE))
        assertNull(AudioLogic.voiceToKeep(entry(audio = emptyList()), JOE))
        assertNull(AudioLogic.voiceToKeep(entry(archived = true), JOE))
    }

    @Test
    fun playerOffer_liveEntries() {
        assertEquals(PlayerOffer.Play(JOE, false), AudioLogic.playerOffer(entry(), JOE, emptySet()))
        // The entry does not have the Settings voice: not ready, no other voice offered.
        assertEquals(PlayerOffer.NotReady(JOE), AudioLogic.playerOffer(entry(audio = listOf(amy)), JOE, setOf("12-en_US-amy-medium.mp3")))
        assertEquals(PlayerOffer.NoAudio, AudioLogic.playerOffer(entry(audio = emptyList(), hasAudio = false), JOE, emptySet()))
        assertEquals(PlayerOffer.Play("", false), AudioLogic.playerOffer(entry(audio = null), JOE, emptySet()))
        assertEquals(PlayerOffer.Play(AMY, false), AudioLogic.playerOffer(entry(), null, emptySet()))
    }

    @Test
    fun playerOffer_archivedEntriesPlayWhatIsCached() {
        val archived = entry(archived = true)
        assertEquals(PlayerOffer.NoAudio, AudioLogic.playerOffer(archived, JOE, emptySet()))
        assertEquals(PlayerOffer.Play(JOE, false), AudioLogic.playerOffer(archived, JOE, setOf("12-en_US-joe-medium.mp3", "12-en_US-amy-medium.mp3")))
        assertEquals(PlayerOffer.Play(AMY, true), AudioLogic.playerOffer(archived, JOE, setOf("12-en_US-amy-medium.mp3")))
    }

    @Test
    fun filesForOtherVoices_keepsSettingsVoiceAndArchivedAudio() {
        val entries = listOf(
            entry(id = 12),
            entry(id = 13, audio = listOf(amy)),
            entry(id = 14, archived = true),
            entry(id = 15, audio = null),
        )
        val cached = listOf(
            "12-en_US-amy-medium.mp3", "12-en_US-joe-medium.mp3",
            "13-en_US-amy-medium.mp3",
            "14-en_US-amy-medium.mp3", "14-en_US-joe-medium.mp3",
            "15-default.mp3",
            "99-en_US-joe-medium.mp3",
            "12-en_US-amy-medium.mp3.part",
        )
        assertEquals(
            listOf("12-en_US-amy-medium.mp3", "13-en_US-amy-medium.mp3", "99-en_US-joe-medium.mp3"),
            AudioLogic.filesForOtherVoices(cached, entries, JOE),
        )
        assertTrue(AudioLogic.filesForOtherVoices(cached, entries, null).isEmpty())
    }

    // ---- the entry's own (preferred) voice ----------------------------------------------------

    private val premium = AudioVariant("premium-lesson-voice", 300, "cc")

    private fun lesson(audio: List<AudioVariant>, preferred: String?, archived: Boolean = false) =
        Entry(id = 20, hasAudio = true, audioBytes = 100, audioSha256 = "aa", audio = audio, preferredVoice = preferred, archived = archived)

    @Test
    fun preferredVoice_winsOverTheSettingsVoiceWhenTheEntryHasIt() {
        val e = lesson(listOf(amy, joe, premium), "premium-lesson-voice")
        assertEquals("premium-lesson-voice", AudioLogic.preferredVoice(e))
        assertEquals("premium-lesson-voice", AudioLogic.voiceToKeep(e, JOE))
        assertEquals("premium-lesson-voice", AudioLogic.voiceToKeep(e, null))
        assertEquals(PlayerOffer.Play("premium-lesson-voice", false), AudioLogic.playerOffer(e, JOE, emptySet()))
        // The Settings voice's file of that entry is "another voice" and goes; the preferred one stays.
        val cached = listOf("20-premium-lesson-voice.mp3", "20-en_US-joe-medium.mp3")
        assertEquals(listOf("20-en_US-joe-medium.mp3"), AudioLogic.filesForOtherVoices(cached, listOf(e), JOE))
    }

    @Test
    fun preferredVoice_ignoredWhenTheEntryDoesNotHaveIt() {
        // Not (yet) in the audio list: the Settings voice as usual.
        val notYet = lesson(listOf(amy, joe), "premium-lesson-voice")
        assertNull(AudioLogic.preferredVoice(notYet))
        assertEquals(JOE, AudioLogic.voiceToKeep(notYet, JOE))
        assertEquals(PlayerOffer.Play(JOE, false), AudioLogic.playerOffer(notYet, JOE, emptySet()))
        // No preference at all, or an empty one.
        assertNull(AudioLogic.preferredVoice(lesson(listOf(amy, premium), null)))
        assertNull(AudioLogic.preferredVoice(lesson(listOf(amy, premium), "")))
        assertEquals(AMY, AudioLogic.voiceToKeep(lesson(listOf(amy, premium), null), AMY))
    }

    @Test
    fun preferredVoice_archivedEntryPlaysItWhenCached() {
        val e = lesson(listOf(amy, joe, premium), "premium-lesson-voice", archived = true)
        val cached = setOf("20-premium-lesson-voice.mp3", "20-en_US-joe-medium.mp3")
        assertEquals(PlayerOffer.Play("premium-lesson-voice", false), AudioLogic.playerOffer(e, JOE, cached))
        assertEquals(PlayerOffer.Play(JOE, false), AudioLogic.playerOffer(e, JOE, setOf("20-en_US-joe-medium.mp3")))
    }
}
