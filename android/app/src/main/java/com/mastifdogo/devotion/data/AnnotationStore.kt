package com.mastifdogo.devotion.data

import java.io.File

/**
 * Highlights and comments on the device, one file per server and profile:
 * filesDir/annotations-<serverkey>-<profileId>.json
 */
class AnnotationStore(private val filesDir: File) {

    private fun file(serverKey: String, profileId: Long): File =
        File(filesDir, "annotations-$serverKey-$profileId.json")

    /** A missing or corrupt file yields an empty list. */
    fun load(serverKey: String, profileId: Long): AnnotationFile {
        val f = file(serverKey, profileId)
        return try {
            if (f.isFile) AppJson.decodeFromString(AnnotationFile.serializer(), f.readText()) else AnnotationFile()
        } catch (e: Exception) {
            AnnotationFile()
        }
    }

    fun save(serverKey: String, profileId: Long, data: AnnotationFile) {
        writeAtomically(file(serverKey, profileId), AppJson.encodeToString(AnnotationFile.serializer(), data))
    }
}
