package com.mastifdogo.devotion.data

import java.io.File
import kotlinx.serialization.Serializable

/** On-disk format of filesDir/read-<serverkey>-<profileId>.json. */
@Serializable
data class ReadFile(val ids: List<Long> = emptyList())

/**
 * Which entries have been read, per server and profile (profile id 0 while none is chosen):
 * filesDir/read-<serverkey>-<profileId>.json
 */
class ReadStore(private val filesDir: File) {
    private fun file(serverKey: String, profileId: Long): File = File(filesDir, "read-$serverKey-$profileId.json")

    /** A missing or corrupt file yields an empty set. */
    fun load(serverKey: String, profileId: Long): Set<Long> {
        val f = file(serverKey, profileId)
        return try {
            if (f.isFile) AppJson.decodeFromString(ReadFile.serializer(), f.readText()).ids.toSet() else emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun save(serverKey: String, profileId: Long, ids: Set<Long>) {
        writeAtomically(file(serverKey, profileId), AppJson.encodeToString(ReadFile.serializer(), ReadFile(ids.sorted())))
    }
}
