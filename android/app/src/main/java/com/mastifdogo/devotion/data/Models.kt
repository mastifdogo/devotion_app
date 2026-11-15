package com.mastifdogo.devotion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared lenient JSON configuration: unknown keys are ignored, nulls fall back to defaults. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class Entry(
    val id: Long,
    val title: String = "",
    @SerialName("entry_date") val entryDate: String? = null,
    val scripture: String? = null,
    /** 2-3 sentence summary written by the server; "" for old entries. */
    val summary: String = "",
    /** "" when the entry is archived on the server and this device never had the text. */
    val body: String = "",
    @SerialName("has_audio") val hasAudio: Boolean = false,
    @SerialName("audio_bytes") val audioBytes: Long? = null,
    @SerialName("audio_sha256") val audioSha256: String? = null,
    @SerialName("source_from") val sourceFrom: String? = null,
    @SerialName("source_subject") val sourceSubject: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
    /** Archived off the server: only title, date, scripture and summary are still served. */
    val archived: Boolean = false,
) {
    /** "yyyy-MM-dd"-like key used for ordering: entry_date, falling back to created_at. */
    val sortKey: String
        get() = entryDate?.takeIf { it.isNotBlank() } ?: createdAt.orEmpty()
}

@Serializable
data class EntriesResponse(
    val entries: List<Entry> = emptyList(),
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val version: String? = null,
)

/** On-disk format of filesDir/entries.json. */
@Serializable
data class CacheFile(
    val entries: List<Entry> = emptyList(),
    /** `server_time` of the last successful sync; sent back as `since`. */
    @SerialName("server_time") val serverTime: String? = null,
    /** Server the cache was filled from; a different server forces a full sync. */
    @SerialName("server_url") val serverUrl: String? = null,
    /** Device clock (epoch millis) at the last successful sync, for display only. */
    @SerialName("last_sync_millis") val lastSyncMillis: Long = 0L,
)

/** Newest first: by entry_date (falling back to created_at), then created_at, then id. */
fun List<Entry>.sortedNewestFirst(): List<Entry> =
    sortedWith(
        compareByDescending<Entry> { it.sortKey }
            .thenByDescending { it.createdAt.orEmpty() }
            .thenByDescending { it.id }
    )

// ---- profiles -----------------------------------------------------------------------------------

@Serializable
data class Profile(
    val id: Long,
    val name: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val existing: Boolean = false,
)

@Serializable
data class ProfilesResponse(val profiles: List<Profile> = emptyList())

@Serializable
data class CreateProfileRequest(val name: String)

@Serializable
data class ErrorResponse(val error: String? = null)

// ---- annotations --------------------------------------------------------------------------------

/**
 * A highlight and/or comment. [start]/[end] are character offsets into the entry body (end
 * exclusive); start == 0 && end == 0 is a note on the whole entry. [color] is one of
 * yellow, green, blue, pink or none. This is exactly the JSON the server exchanges.
 */
@Serializable
data class Annotation(
    val id: String,
    @SerialName("entry_id") val entryId: Long,
    val start: Int = 0,
    val end: Int = 0,
    val color: String = "none",
    val comment: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

@Serializable
data class AnnotationsResponse(
    val annotations: List<Annotation> = emptyList(),
    @SerialName("server_time") val serverTime: String? = null,
)

/** An annotation as kept on the device; [dirty] means it still has to be uploaded. */
@Serializable
data class StoredAnnotation(
    val annotation: Annotation,
    val dirty: Boolean = false,
)

/** On-disk format of filesDir/annotations-<serverkey>-<profileId>.json. */
@Serializable
data class AnnotationFile(
    val annotations: List<StoredAnnotation> = emptyList(),
    @SerialName("server_time") val serverTime: String? = null,
)

// ---- bibles -------------------------------------------------------------------------------------

@Serializable
data class BibleInfo(
    val id: String,
    val name: String = "",
    val language: String? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
)

@Serializable
data class BiblesResponse(val bibles: List<BibleInfo> = emptyList())

@Serializable
data class BibleBook(
    val name: String = "",
    /** chapters[chapterIndex][verseIndex] = verse text. */
    val chapters: List<List<String>> = emptyList(),
)

@Serializable
data class Bible(
    val id: String = "",
    val name: String = "",
    val language: String? = null,
    val books: List<BibleBook> = emptyList(),
)

/** On-disk format of filesDir/bibles/index.json: the versions that are on this device. */
@Serializable
data class BibleIndex(val bibles: List<BibleInfo> = emptyList())
