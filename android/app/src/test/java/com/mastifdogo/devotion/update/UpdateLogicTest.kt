package com.mastifdogo.devotion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test
    fun parseVersion_stripsPrefixAndSuffix() {
        assertEquals(listOf(1, 2, 3), UpdateLogic.parseVersion("v1.2.3"))
        assertEquals(listOf(1, 2, 3), UpdateLogic.parseVersion("1.2.3-beta1"))
        assertEquals(listOf(1, 2, 3), UpdateLogic.parseVersion(" V1.2.3+build.7 "))
        assertEquals(listOf(2), UpdateLogic.parseVersion("2"))
        assertEquals(emptyList<Int>(), UpdateLogic.parseVersion(""))
        assertEquals(listOf(1, 0, 4), UpdateLogic.parseVersion("1.x.4"))
        assertEquals(listOf(1, 2), UpdateLogic.parseVersion("1.2rc1"))
    }

    @Test
    fun compareVersions_isNumericPerComponent() {
        assertTrue(UpdateLogic.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(UpdateLogic.compareVersions("0.2.0", "0.10.0") < 0)
        assertEquals(0, UpdateLogic.compareVersions("1.2", "1.2.0"))
        assertEquals(0, UpdateLogic.compareVersions("v1.2.3", "1.2.3"))
        assertTrue(UpdateLogic.compareVersions("1.2.1", "1.2") > 0)
        assertTrue(UpdateLogic.compareVersions("2", "10") < 0)
    }

    @Test
    fun isNewer_onlyForStrictlyHigherVersions() {
        assertTrue(UpdateLogic.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateLogic.isNewer("v1.0.0", "0.9.12"))
        assertFalse(UpdateLogic.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateLogic.isNewer("v0.1.0", "0.1.1"))
        // Suffixes are ignored on both sides.
        assertFalse(UpdateLogic.isNewer("v1.2.3-rc1", "1.2.3"))
        assertFalse(UpdateLogic.isNewer("v1.2.3", "1.2.3-debug"))
        assertTrue(UpdateLogic.isNewer("v1.2.4-rc1", "1.2.3-debug"))
        // Garbage never counts as newer than a real version.
        assertFalse(UpdateLogic.isNewer("latest", "0.1.0"))
    }

    @Test
    fun displayVersion_stripsLeadingV() {
        assertEquals("1.2.3", UpdateLogic.displayVersion("v1.2.3"))
        assertEquals("1.2.3", UpdateLogic.displayVersion("1.2.3"))
    }

    @Test
    fun pickApkAsset_findsFirstApk() {
        val assets = listOf(
            ReleaseAsset(name = "checksums.txt"),
            ReleaseAsset(name = "devotion-1.2.3.APK", url = "api-1"),
            ReleaseAsset(name = "other.apk", url = "api-2"),
        )
        assertEquals("api-1", UpdateLogic.pickApkAsset(assets)?.url)
        assertNull(UpdateLogic.pickApkAsset(listOf(ReleaseAsset(name = "notes.md"))))
        assertNull(UpdateLogic.pickApkAsset(emptyList()))
        assertNull(UpdateLogic.pickApkAsset(listOf(ReleaseAsset(name = "app.apk.sha256"))))
    }

    @Test
    fun downloadFor_usesApiUrlWithTokenAndBrowserUrlWithout() {
        val asset = ReleaseAsset(
            name = "app.apk",
            url = "https://api.github.com/repos/o/r/releases/assets/1",
            browserDownloadUrl = "https://github.com/o/r/releases/download/v1/app.apk",
        )
        val withToken = UpdateLogic.downloadFor(asset, hasToken = true)
        assertNotNull(withToken)
        assertEquals(asset.url, withToken!!.url)
        assertEquals("application/octet-stream", withToken.accept)
        assertTrue(withToken.useToken)

        val anonymous = UpdateLogic.downloadFor(asset, hasToken = false)
        assertNotNull(anonymous)
        assertEquals(asset.browserDownloadUrl, anonymous!!.url)
        assertNull(anonymous.accept)
        assertFalse(anonymous.useToken)

        assertNull(UpdateLogic.downloadFor(ReleaseAsset(name = "app.apk"), hasToken = false))
        val apiOnly = UpdateLogic.downloadFor(ReleaseAsset(name = "app.apk", url = "u"), hasToken = false)
        assertEquals("u", apiOnly?.url)
        assertEquals("application/octet-stream", apiOnly?.accept)
    }

    @Test
    fun downloadFor_sendsTheTokenToGithubsApiOnly() {
        // A release that names another host (or plain http) for its asset never gets the token.
        for (elsewhere in listOf(
            "https://evil.example/repos/o/r/releases/assets/1",
            "http://api.github.com/repos/o/r/releases/assets/1",
            "https://api.github.com.evil.example/assets/1",
        )) {
            val asset = ReleaseAsset(name = "app.apk", url = elsewhere, browserDownloadUrl = "https://github.com/o/r/releases/download/v1/app.apk")
            val download = UpdateLogic.downloadFor(asset, hasToken = true)
            assertFalse(elsewhere, download!!.useToken)
            assertEquals(asset.browserDownloadUrl, download.url)
            assertFalse(UpdateLogic.downloadFor(ReleaseAsset(name = "app.apk", url = elsewhere), hasToken = true)!!.useToken)
        }
        assertTrue(UpdateLogic.isGithubApiUrl("https://api.github.com/repos/o/r/releases/assets/1"))
    }

    @Test
    fun rateLimitWaitSeconds_readsHeaders() {
        // Exhausted quota: wait until the reset time.
        assertEquals(600L, UpdateLogic.rateLimitWaitSeconds(403, "0", "1600", null, 1000L))
        // Reset already in the past never goes negative.
        assertEquals(0L, UpdateLogic.rateLimitWaitSeconds(403, "0", "900", null, 1000L))
        // Retry-After wins.
        assertEquals(30L, UpdateLogic.rateLimitWaitSeconds(429, "0", "1600", "30", 1000L))
        // 429 without headers still counts as a rate limit.
        assertEquals(60L, UpdateLogic.rateLimitWaitSeconds(429, null, null, null, 1000L))
        // 403 with quota left is a permission problem, not a rate limit.
        assertNull(UpdateLogic.rateLimitWaitSeconds(403, "42", "1600", null, 1000L))
        assertNull(UpdateLogic.rateLimitWaitSeconds(403, null, null, null, 1000L))
        // Other codes are never rate limits.
        assertNull(UpdateLogic.rateLimitWaitSeconds(404, "0", "1600", null, 1000L))
    }

    @Test
    fun describeWait_isHumanReadable() {
        assertEquals("a few seconds", UpdateLogic.describeWait(10))
        assertEquals("about 1 minute", UpdateLogic.describeWait(60))
        assertEquals("about 10 minutes", UpdateLogic.describeWait(600))
        assertEquals("about 2 hours", UpdateLogic.describeWait(7200))
    }
}
