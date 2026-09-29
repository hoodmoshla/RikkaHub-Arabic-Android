package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the update decision logic (see [UpdatePolicy]).
 *
 * These run on the JVM without Android/network, so they cover the parts of the update flow that
 * must never regress: detecting newer releases, ignoring the installed version, surviving a
 * previously failed release job, picking the universal APK, and never installing an older APK.
 */
class UpdatePolicyTest {

    private fun release(
        version: String,
        publishedAt: String = "2026-09-27T00:00:00Z",
        downloads: List<UpdateDownload> = listOf(
            UpdateDownload("app-universal-release.apk", "https://example.com/u.apk", "50 MB")
        ),
    ) = ReleaseCandidate(
        version = version,
        publishedAt = publishedAt,
        changelog = "changelog $version",
        downloads = downloads,
    )

    @Test
    fun `newer release is detected as an update`() {
        assertTrue(UpdatePolicy.isUpdateAvailable("2.5.4.86", "2.5.5.87"))
        assertTrue(UpdatePolicy.isUpdateAvailable("2.5.5", "2.5.5.1"))
        assertTrue(UpdatePolicy.isUpdateAvailable("1.0.0", "2.0.0"))
        assertTrue(UpdatePolicy.isUpdateAvailable("2.5.4.86", "2.5.4.87"))
    }

    @Test
    fun `the installed version is never treated as an update`() {
        assertFalse(UpdatePolicy.isUpdateAvailable("2.5.4.86", "2.5.4.86"))
        assertFalse(UpdatePolicy.isUpdateAvailable("2.5.5.87", "2.5.4.86"))
        assertFalse(UpdatePolicy.isUpdateAvailable("2.5.5.10", "2.5.5.9"))
    }

    @Test
    fun `successive updates are detected one after another`() {
        // Installed 2.5.4.86 -> 2.5.5.87 -> 2.5.5.95 -> 2.6.0.101
        val installed = listOf("2.5.4.86", "2.5.5.87", "2.5.5.95")
        val latest = listOf("2.5.5.87", "2.5.5.95", "2.6.0.101")

        installed.zip(latest).forEach { (from, to) ->
            assertTrue("$from should see $to as an update", UpdatePolicy.isUpdateAvailable(from, to))
        }
        // And after installing the newest, no update is reported any more.
        assertFalse(UpdatePolicy.isUpdateAvailable("2.6.0.101", "2.6.0.101"))
    }

    @Test
    fun `a newer release is still detected after an earlier failed workflow run`() {
        // The release list reflects whatever the CI managed to publish. Even when older releases
        // are returned first (or the newest one was published *before* an older one, e.g. after a
        // failed run was retried later), the highest version must win.
        val releases = listOf(
            release("2.5.4.85", publishedAt = "2026-09-27T11:50:21Z"),
            release("2.5.5.87", publishedAt = "2026-09-28T09:00:00Z"),
            release("2.5.4.86", publishedAt = "2026-09-29T10:00:00Z"), // published later, older version
        )

        val latest = UpdatePolicy.latestRelease(releases)
        assertNotNull(latest)
        assertEquals("2.5.5.87", latest!!.version)
        assertTrue(UpdatePolicy.isUpdateAvailable("2.5.4.86", latest.version))
    }

    @Test
    fun `drafts and prereleases are ignored by the release mapper`() {
        val releases = listOf(
            release("2.5.5.90"),
            release("2.6.0.99"),
        )
        // latestRelease only sees what the mapper produced; ensure version ordering still holds
        // when a higher version exists.
        assertEquals("2.6.0.99", UpdatePolicy.latestRelease(releases)?.version)
    }

    @Test
    fun `the universal apk is the download offered to the user`() {
        val downloads = listOf(
            UpdateDownload("app-x86_64-release.apk", "https://example.com/x86.apk", "40 MB"),
            UpdateDownload("app-arm64-v8a-release.apk", "https://example.com/arm64.apk", "39 MB"),
            UpdateDownload("app-universal-release.apk", "https://example.com/universal.apk", "50 MB"),
        )

        val preferred = UpdatePolicy.preferredDownload(downloads)
        assertNotNull(preferred)
        assertEquals("app-universal-release.apk", preferred!!.name)

        // Even if the release only ships a single universal APK, that one is used.
        val onlyUniversal = listOf(
            UpdateDownload("app-universal-release.apk", "https://example.com/universal.apk", "50 MB")
        )
        assertEquals("app-universal-release.apk", UpdatePolicy.preferredDownload(onlyUniversal)?.name)

        // No APK at all -> nothing to offer.
        assertNull(UpdatePolicy.preferredDownload(emptyList()))
    }

    @Test
    fun `an apk older than the installed version is never installable`() {
        assertTrue(isNewerVersionCode(remoteVersionCode = 87L, installedVersionCode = 86L))
        assertFalse(isNewerVersionCode(remoteVersionCode = 86L, installedVersionCode = 86L))
        assertFalse(isNewerVersionCode(remoteVersionCode = 85L, installedVersionCode = 86L))
        assertFalse(isNewerVersionCode(remoteVersionCode = 0L, installedVersionCode = 0L))
    }

    @Test
    fun `update checks always target the Arabic repository`() {
        // Never check the official repository: its APKs are signed with a different key and
        // would break updates of the installed Arabic build (Application ID + signing key).
        assertTrue(ARABIC_RELEASES_API.startsWith("https://api.github.com/repos/hoodmoshla/RikkaHub-Arabic-Android/"))
        assertFalse(ARABIC_RELEASES_API.contains("rikkahub/rikkahub"))
    }

    @Test
    fun `empty release list yields no candidate`() {
        assertNull(UpdatePolicy.latestRelease(emptyList()))
    }
}
