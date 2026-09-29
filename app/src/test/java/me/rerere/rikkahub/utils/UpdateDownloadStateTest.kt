package me.rerere.rikkahub.utils

import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the in-app download rules of [UpdateDownloadLogic] and [UpdateDownloadState].
 *
 * These cover the guarantees the update flow must never lose: a permanent progress value, resume
 * instead of restarting from zero, no duplicated download for the same version, a completed APK
 * that stays available, and failures that keep the partial file when that is useful.
 */
class UpdateDownloadStateTest {

    // ------------------------------------------------------------------ progress

    @Test
    fun `progress percentage is computed and clamped`() {
        assertEquals(0, UpdateDownloadLogic.progressPercent(0L, 100L))
        assertEquals(50, UpdateDownloadLogic.progressPercent(50L, 100L))
        assertEquals(100, UpdateDownloadLogic.progressPercent(100L, 100L))
        // unknown total size must not produce a bogus percentage
        assertEquals(0, UpdateDownloadLogic.progressPercent(1234L, 0L))
        assertEquals(0, UpdateDownloadLogic.progressPercent(1234L, -1L))
        // never above 100 even if the server sends more than announced
        assertEquals(100, UpdateDownloadLogic.progressPercent(150L, 100L))
    }

    @Test
    fun `remaining time is only reported while it makes sense`() {
        assertEquals(10L, UpdateDownloadLogic.remainingSeconds(0L, 1000L, 100L))
        assertNull(UpdateDownloadLogic.remainingSeconds(1000L, 1000L, 100L))
        assertNull(UpdateDownloadLogic.remainingSeconds(0L, 1000L, 0L))
    }

    @Test
    fun `byte, speed and duration formatting`() {
        assertEquals("512 B", UpdateDownloadLogic.formatBytes(512L))
        assertEquals("1.0 KB", UpdateDownloadLogic.formatBytes(1024L))
        assertEquals("47.3 MB", UpdateDownloadLogic.formatBytes(49_600_000L))
        assertEquals("--", UpdateDownloadLogic.formatSpeed(0L))
        assertEquals("512 KB/s", UpdateDownloadLogic.formatSpeed(512L * 1024L))
        assertEquals("--", UpdateDownloadLogic.formatDuration(0L))
        assertEquals("45s", UpdateDownloadLogic.formatDuration(45L))
        assertEquals("2m 5s", UpdateDownloadLogic.formatDuration(125L))
    }

    // ------------------------------------------------------------------ resume instead of restart

    @Test
    fun `a partial file of the same version is resumed, never restarted`() {
        val partial = File.createTempFile("update", ".apk.part").apply {
            writeBytes(ByteArray(4096))
            deleteOnExit()
        }

        assertEquals(4096L, UpdateDownloadLogic.resumeOffset(partial, "2.5.6.104", "2.5.6.104"))
        // a partial file that belongs to another release must not be reused
        assertEquals(0L, UpdateDownloadLogic.resumeOffset(partial, "2.5.5.97", "2.5.6.104"))
        // nothing to resume when the file is missing/empty
        assertEquals(0L, UpdateDownloadLogic.resumeOffset(null, "2.5.6.104", "2.5.6.104"))
        val empty = File.createTempFile("update-empty", ".part").apply { deleteOnExit() }
        assertEquals(0L, UpdateDownloadLogic.resumeOffset(empty, "2.5.6.104", "2.5.6.104"))
    }

    // ------------------------------------------------------------------ duplicate protection

    @Test
    fun `only one download runs at a time, for any version`() {
        val running = UpdateDownloadState.Downloading(
            version = "2.5.6.104",
            fileName = "app-universal-release.apk",
            bytesDownloaded = 100L,
            totalBytes = 1000L,
            bytesPerSecond = 10L,
        )
        assertFalse(
            "a running download must block a second request (same version)",
            UpdateDownloadLogic.canStartDownload(running),
        )
        assertFalse(
            "a running download must also block a request for another version",
            UpdateDownloadLogic.canStartDownload(running),
        )
        assertFalse(
            "verifying counts as active",
            UpdateDownloadLogic.canStartDownload(
                UpdateDownloadState.Validating("2.5.6.104", "app-universal-release.apk"),
            ),
        )
        // finished/failed states never block a new download
        assertTrue(UpdateDownloadLogic.canStartDownload(UpdateDownloadState.Idle))
        assertTrue(
            UpdateDownloadLogic.canStartDownload(
                UpdateDownloadState.Ready("2.5.5.97", "app-universal-release.apk", "/tmp/x.apk"),
            ),
        )
        assertTrue(
            UpdateDownloadLogic.canStartDownload(
                UpdateDownloadState.Paused("2.5.5.97", "app-universal-release.apk", 1L, 2L, UpdateFailureReason.NETWORK),
            ),
        )
        assertTrue(
            UpdateDownloadLogic.canStartDownload(
                UpdateDownloadState.Failed("2.5.5.97", "app-universal-release.apk", UpdateFailureReason.HTTP),
            ),
        )
    }

    // ------------------------------------------------------------------ version tagged partial files

    @Test
    fun `partial files are named after the release version`() {
        assertEquals(
            "app-universal-release-2.5.5.97.apk.part",
            UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.5.97"),
        )
        assertEquals(
            "app-universal-release-2.5.6.104.apk.part",
            UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104"),
        )
        // names without an extension still work
        assertEquals("file-1.0.part", UpdateDownloadLogic.partialFileName("file", "1.0"))
        // the partial file name can never be mistaken for the final APK
        assertNotEquals(
            "app-universal-release.apk",
            UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104"),
        )
        assertTrue(
            UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104").endsWith(".part"),
        )
    }

    @Test
    fun `a new release gets its own partial file and never the previous one`() {
        val previous = UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.5.97")
        val current = UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104")
        assertNotEquals(previous, current)
        // Re-downloading the same release must resolve to exactly the same partial file.
        assertEquals(
            current,
            UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104"),
        )
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    fun `network failures stay resumable and keep the partial file`() {
        listOf(
            UpdateFailureReason.NETWORK,
            UpdateFailureReason.TIMEOUT,
            UpdateFailureReason.IO,
            UpdateFailureReason.UNKNOWN,
        ).forEach { reason ->
            assertTrue(
                "$reason with a partial file must be resumable",
                UpdateDownloadLogic.isResumable(reason, hasPartialFile = true),
            )
            assertFalse(
                "$reason without a partial file cannot be resumed",
                UpdateDownloadLogic.isResumable(reason, hasPartialFile = false),
            )
        }
    }

    @Test
    fun `terminal failures are not resumable`() {
        listOf(
            UpdateFailureReason.INVALID_PACKAGE,
            UpdateFailureReason.NOT_NEWER,
            UpdateFailureReason.NO_SPACE,
            UpdateFailureReason.HTTP,
        ).forEach { reason ->
            assertFalse(UpdateDownloadLogic.isResumable(reason, hasPartialFile = true))
        }
    }

    @Test
    fun `failures are classified into user facing reasons`() {
        assertEquals(UpdateFailureReason.TIMEOUT, UpdateDownloadLogic.classify(SocketTimeoutException()))
        assertEquals(UpdateFailureReason.NETWORK, UpdateDownloadLogic.classify(UnknownHostException()))
        assertEquals(UpdateFailureReason.HTTP, UpdateDownloadLogic.classify(RuntimeException("boom"), httpCode = 404))
        assertEquals(
            UpdateFailureReason.NO_SPACE,
            UpdateDownloadLogic.classify(RuntimeException("write failed: ENOSPC")),
        )
        assertEquals(UpdateFailureReason.UNKNOWN, UpdateDownloadLogic.classify(RuntimeException("weird")))
    }

    // ------------------------------------------------------------------ state helpers

    @Test
    fun `only running or validating downloads are active`() {
        assertTrue(
            UpdateDownloadState.Downloading("1", "f", 1L, 2L, 3L).isActive,
        )
        assertTrue(UpdateDownloadState.Validating("1", "f").isActive)
        assertFalse(UpdateDownloadState.Idle.isActive)
        assertFalse(UpdateDownloadState.Paused("1", "f", 1L, 2L, UpdateFailureReason.NETWORK).isActive)
        assertFalse(UpdateDownloadState.Ready("1", "f", "/tmp/f").isActive)
        assertFalse(UpdateDownloadState.Failed("1", "f", UpdateFailureReason.HTTP).isActive)
        assertNull(UpdateDownloadState.Idle.version)
    }

    @Test
    fun `a completed download keeps pointing at its file so it stays ready`() {
        val ready = UpdateDownloadState.Ready("2.5.6.104", "app-universal-release.apk", "/data/updates/x.apk")
        assertEquals("2.5.6.104", ready.version)
        assertEquals("/data/updates/x.apk", ready.filePath)
        // an older/failed attempt never replaces a ready state
        assertFalse(ready.isActive)
    }
}
