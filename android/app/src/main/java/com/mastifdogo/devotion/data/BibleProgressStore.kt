package com.mastifdogo.devotion.data

import com.mastifdogo.devotion.logic.BiblePlace
import java.io.File
import kotlinx.serialization.Serializable

/** On-disk format of filesDir/bible-progress-<serverkey>-<profileId>.json. */
@Serializable
data class BibleProgressFile(
    val book: Int = 0,
    val chapter: Int = 1,
    val positionMs: Long = 0L,
    val finished: Boolean = false,
    val title: String = "",
)

/**
 * How far autoplay has read through the Bible, per server and profile (profile id 0 while none is
 * chosen): filesDir/bible-progress-<serverkey>-<profileId>.json. Only autoplay writes it; chapters
 * played by hand in the Bible tab leave it alone.
 */
class BibleProgressStore(private val filesDir: File) {
    private fun file(serverKey: String, profileId: Long): File = File(filesDir, "bible-progress-$serverKey-$profileId.json")

    /** Null when nothing has been played yet (or the file is corrupt). */
    fun load(serverKey: String, profileId: Long): BiblePlace? {
        val f = file(serverKey, profileId)
        return try {
            if (!f.isFile) return null
            val raw = AppJson.decodeFromString(BibleProgressFile.serializer(), f.readText())
            BiblePlace(raw.book, raw.chapter, raw.positionMs, raw.finished, raw.title)
        } catch (e: Exception) {
            null
        }
    }

    fun save(serverKey: String, profileId: Long, place: BiblePlace) {
        val raw = BibleProgressFile(place.book, place.chapter, place.positionMs, place.finished, place.title)
        writeAtomically(file(serverKey, profileId), AppJson.encodeToString(BibleProgressFile.serializer(), raw))
    }

    fun clear(serverKey: String, profileId: Long) {
        file(serverKey, profileId).delete()
    }
}
