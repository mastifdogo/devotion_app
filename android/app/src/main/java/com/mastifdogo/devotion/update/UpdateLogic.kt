package com.mastifdogo.devotion.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReleaseAsset(
    val name: String = "",
    /** API URL of the asset; needs "Accept: application/octet-stream" (works for private repos with a token). */
    val url: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    val size: Long = 0L,
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** Where to fetch an asset from and with which headers. */
data class AssetDownload(
    val url: String,
    val accept: String?,
    val useToken: Boolean,
)

/** Pure update-check logic; no Android or network dependencies so it can be unit tested on the JVM. */
object UpdateLogic {

    /**
     * "v1.2.3-beta1" -> [1, 2, 3]. A leading "v"/"V" and anything from the first '-' or '+' on
     * are ignored; components that are not numbers count as 0 (leading digits are used if present).
     */
    fun parseVersion(raw: String): List<Int> {
        var s = raw.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        s = s.substringBefore('-').substringBefore('+').trim()
        if (s.isEmpty()) return emptyList()
        return s.split('.').map { part ->
            part.trim().takeWhile { it.isDigit() }.take(9).toIntOrNull() ?: 0
        }
    }

    /** Numeric comparison per dot-separated component; missing components count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** True when the release tag is a strictly higher version than the installed one. */
    fun isNewer(remoteTag: String, currentVersion: String): Boolean =
        compareVersions(remoteTag, currentVersion) > 0

    /** The tag without its leading "v", for display. */
    fun displayVersion(tag: String): String {
        val t = tag.trim()
        return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
    }

    /** The first asset whose name ends with ".apk" (case-insensitive), or null. */
    fun pickApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.trim().endsWith(".apk", ignoreCase = true) }

    /**
     * With a token (private repo) the asset's API url is used with an octet-stream Accept header;
     * otherwise the public browser_download_url. Falls back to whichever URL exists.
     */
    fun downloadFor(asset: ReleaseAsset, hasToken: Boolean): AssetDownload? {
        val apiUrl = asset.url?.takeIf { it.isNotBlank() }
        val browserUrl = asset.browserDownloadUrl?.takeIf { it.isNotBlank() }
        return when {
            hasToken && apiUrl != null -> AssetDownload(apiUrl, "application/octet-stream", useToken = true)
            browserUrl != null -> AssetDownload(browserUrl, null, useToken = false)
            apiUrl != null -> AssetDownload(apiUrl, "application/octet-stream", useToken = hasToken)
            else -> null
        }
    }

    /**
     * For a 403/429 answer: seconds to wait when it is a rate limit, or null when it is not
     * (a plain "forbidden"). Uses Retry-After first, then x-ratelimit-remaining / x-ratelimit-reset.
     */
    fun rateLimitWaitSeconds(
        httpCode: Int,
        remaining: String?,
        resetEpochSeconds: String?,
        retryAfterSeconds: String?,
        nowEpochSeconds: Long,
    ): Long? {
        if (httpCode != 403 && httpCode != 429) return null
        val retryAfter = retryAfterSeconds?.trim()?.toLongOrNull()
        if (retryAfter != null) return retryAfter.coerceAtLeast(0L)
        val left = remaining?.trim()?.toLongOrNull()
        val reset = resetEpochSeconds?.trim()?.toLongOrNull()
        if (left == 0L || httpCode == 429) {
            return if (reset != null) (reset - nowEpochSeconds).coerceAtLeast(0L) else 60L
        }
        return null
    }

    /** "about 12 minutes", "about 1 minute", "a few seconds". */
    fun describeWait(seconds: Long): String {
        if (seconds < 45) return "a few seconds"
        val minutes = (seconds + 59) / 60
        if (minutes < 90) return if (minutes == 1L) "about 1 minute" else "about $minutes minutes"
        val hours = (minutes + 30) / 60
        return "about $hours hours"
    }
}
