package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "close and reopen the app" behaviour of the update downloader
 * ([UpdateDownloadPersistence]): the state is restored, a finished APK stays ready to install, and
 * an interrupted download continues from its partial file instead of starting from zero.
 */
class UpdateDownloadRestoreTest {

    private fun persisted(
        status: PersistedUpdateStatus,
        version: String = "2.5.6.104",
        fileName: String = "app-universal-release.apk",
        url: String = "https://example.com/app-universal-release.apk",
        total: Long = 49_600_000L,
        downloaded: Long = 0L,
        reason: UpdateFailureReason = UpdateFailureReason.NETWORK,
    ) = PersistedUpdateDownload(status, version, fileName, url, total, downloaded, reason)

    @Test
    fun `a finished download stays ready to install after a restart`() {
        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.READY),
            partialFileLength = 0L,
            readyFileIsInstallable = true,
        )
        assertTrue(decision is UpdateDownloadRestore.Ready)
        decision as UpdateDownloadRestore.Ready
        assertEquals("2.5.6.104", decision.version)
        assertEquals("app-universal-release.apk", decision.fileName)
    }

    @Test
    fun `a finished download is dropped when the apk is gone or not installable`() {
        val missing = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.READY),
            partialFileLength = 0L,
            readyFileIsInstallable = false,
        )
        assertEquals(UpdateDownloadRestore.Nothing, missing)
    }

    @Test
    fun `an interrupted download resumes from the partial file, never from zero`() {
        val partialLength = 18_400_000L
        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(
                status = PersistedUpdateStatus.DOWNLOADING,
                total = 49_600_000L,
                downloaded = partialLength,
            ),
            partialFileLength = partialLength,
            readyFileIsInstallable = false,
        )

        assertTrue(decision is UpdateDownloadRestore.Resume)
        decision as UpdateDownloadRestore.Resume
        assertEquals(partialLength, decision.offset)
        assertTrue("resume must start after byte zero", decision.offset > 0L)
        assertEquals(49_600_000L, decision.totalBytes)
        assertEquals("2.5.6.104", decision.version)
    }

    @Test
    fun `a paused or validating download is also resumed`() {
        listOf(PersistedUpdateStatus.PAUSED, PersistedUpdateStatus.VALIDATING).forEach { status ->
            val decision = UpdateDownloadPersistence.decide(
                persisted = persisted(status),
                partialFileLength = 1_024L,
                readyFileIsInstallable = false,
            )
            assertTrue("$status must resume", decision is UpdateDownloadRestore.Resume)
        }
    }

    @Test
    fun `a download without a partial file is not restored`() {
        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.DOWNLOADING, downloaded = 5_000L),
            partialFileLength = null,
            readyFileIsInstallable = false,
        )
        assertEquals(UpdateDownloadRestore.Nothing, decision)
    }

    @Test
    fun `failed downloads and empty state restore nothing`() {
        assertEquals(
            UpdateDownloadRestore.Nothing,
            UpdateDownloadPersistence.decide(
                persisted = persisted(PersistedUpdateStatus.FAILED),
                partialFileLength = 1_024L,
                readyFileIsInstallable = false,
            ),
        )
        assertEquals(
            UpdateDownloadRestore.Nothing,
            UpdateDownloadPersistence.decide(
                persisted = null,
                partialFileLength = 1_024L,
                readyFileIsInstallable = true,
            ),
        )
    }

    @Test
    fun `resumed offset matches the offset the engine will request from the server`() {
        // The restore decision and the Range request must agree on the same byte offset.
        val partialLength = 7_777_777L
        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.PAUSED),
            partialFileLength = partialLength,
            readyFileIsInstallable = false,
        ) as UpdateDownloadRestore.Resume

        val partialFile = java.io.File.createTempFile("resume", ".part").apply {
            writeBytes(ByteArray(partialLength.toInt()))
            deleteOnExit()
        }
        assertEquals(
            partialLength,
            UpdateDownloadLogic.resumeOffset(partialFile, decision.version, decision.version),
        )
        partialFile.delete()
        Unit
    }

    @Test
    fun `a partial file that belongs to another release is never resumed`() {
        val previousRelease = UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.5.97")
        val currentRelease = UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104")
        assertNotEquals(previousRelease, currentRelease)

        // Restoring the new release looks up `currentRelease`; the old file is simply not found,
        // so the download starts clean instead of continuing wrong bytes.
        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.PAUSED, version = "2.5.6.104"),
            partialFileLength = null,
            readyFileIsInstallable = false,
        )
        assertEquals(UpdateDownloadRestore.Nothing, decision)
    }

    @Test
    fun `the same release resumes exactly its own partial file`() {
        val ownName = UpdateDownloadLogic.partialFileName("app-universal-release.apk", "2.5.6.104")
        assertEquals("app-universal-release-2.5.6.104.apk.part", ownName)

        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted(PersistedUpdateStatus.PAUSED, version = "2.5.6.104"),
            partialFileLength = 4_000_000L,
            readyFileIsInstallable = false,
        ) as UpdateDownloadRestore.Resume
        assertEquals(4_000_000L, decision.offset)
        assertEquals("2.5.6.104", decision.version)
    }
}
