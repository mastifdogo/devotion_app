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
    val summary: String? = null,
    val body: String = "",
    @SerialName("has_audio") val hasAudio: Boolean = false,
    @SerialName("audio_bytes") val audioBytes: Long? = null,
    @SerialName("audio_sha256") val audioSha256: String? = null,
    @SerialName("source_from") val sourceFrom: String? = null,
    @SerialName("source_subject") val sourceSubject: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
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
