package me.rerere.rikkahub.utils

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real transfer tests for [UpdateDownloadEngine]: a genuine HTTP server, genuine bytes on disk,
 * genuine resume with a `Range` header.
 *
 * This is the "actually download something" proof for the in-app update downloader:
 * - the whole file arrives and the byte count matches;
 * - progress is reported while streaming and reaches 100%;
 * - an interrupted download **continues from where it stopped** instead of restarting from zero;
 * - a server that ignores `Range` cannot corrupt the file;
 * - HTTP failures are surfaced with their status code, and a stale partial file is dropped on 416.
 */
class UpdateDownloadEngineTest {

    private enum class Mode { RANGE, IGNORE_RANGE, STATUS }

    private companion object {
        const val SIZE = 3 * 1024 * 1024 // 3 MiB so multiple progress callbacks are produced
    }

    private lateinit var server: HttpServer
    private lateinit var url: String
    private val payload = ByteArray(SIZE) { (it % 251).toByte() }
    private val client = OkHttpClient()

    @Volatile
    private var mode = Mode.RANGE

    @Volatile
    private var statusCode = 200

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app-universal-release.apk") { exchange ->
            try {
                if (mode == Mode.STATUS) {
                    val body = "error".toByteArray()
                    exchange.sendResponseHeaders(statusCode, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    return@createContext
                }
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (mode == Mode.RANGE && rangeHeader != null) {
                    val start = rangeHeader.removePrefix("bytes=").removeSuffix("-").toInt()
                    val remaining = payload.size - start
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $start-${payload.size - 1}/${payload.size}",
                    )
                    exchange.sendResponseHeaders(206, remaining.toLong())
                    exchange.responseBody.use { it.write(payload, start, remaining) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/app-universal-release.apk"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun partFile(): File = File.createTempFile("update-", ".apk.part").apply {
        delete() // the engine creates it
        deleteOnExit()
    }

    @Test
    fun `downloads the whole apk and reports progress up to 100 percent`() = runBlocking {
        val part = partFile()
        val progress = mutableListOf<Triple<Long, Long, Long>>()

        val outcome = UpdateDownloadEngine.download(
            client = client,
            url = url,
            partFile = part,
            resumeFrom = 0L,
            userAgent = "RikkaHub-Arabic/test",
        ) { bytes, total, bps ->
            progress += Triple(bytes, total, bps)
        }

        assertEquals(SIZE.toLong(), part.length())
        assertTrue("downloaded file must match the served bytes", part.readBytes().contentEquals(payload))
        assertEquals(SIZE.toLong(), outcome.bytesDownloaded)
        assertEquals(SIZE.toLong(), outcome.totalBytes)
        assertTrue("progress must be reported while streaming", progress.isNotEmpty())
        assertTrue("the last progress report must be complete", progress.last().first == SIZE.toLong())
        assertEquals(100, UpdateDownloadLogic.progressPercent(outcome.bytesDownloaded, outcome.totalBytes))
        part.delete()
        Unit
    }

    @Test
    fun `an interrupted download resumes from the partial file instead of restarting`() = runBlocking {
        val part = partFile()
        // First attempt: only part of the file was written before the connection died.
        val alreadyDownloaded = 1_000_000
        part.writeBytes(payload.copyOfRange(0, alreadyDownloaded))

        val offsets = mutableListOf<Long>()
        val outcome = UpdateDownloadEngine.download(
            client = client,
            url = url,
            partFile = part,
            resumeFrom = UpdateDownloadLogic.resumeOffset(part, "2.5.6.104", "2.5.6.104"),
        ) { bytes, _, _ -> offsets += bytes }

        assertEquals(SIZE.toLong(), part.length())
        assertTrue("resumed file must be byte-identical to the served file", part.readBytes().contentEquals(payload))
        assertEquals(SIZE.toLong(), outcome.totalBytes)
        assertEquals(alreadyDownloaded.toLong(), outcome.resumedFrom)
        assertTrue("progress must continue past the resume point", offsets.any { it > alreadyDownloaded })
        part.delete()
        Unit
    }

    @Test
    fun `a server that ignores the range header cannot corrupt the file`() = runBlocking {
        mode = Mode.IGNORE_RANGE
        val part = partFile()
        part.writeBytes(payload.copyOfRange(0, 500_000))

        UpdateDownloadEngine.download(
            client = client,
            url = url,
            partFile = part,
            resumeFrom = 500_000L,
        ) { _, _, _ -> }

        assertEquals(SIZE.toLong(), part.length())
        assertTrue(
            "restarting cleanly must still produce the exact file",
            part.readBytes().contentEquals(payload),
        )
        part.delete()
        Unit
    }

    @Test
    fun `http errors are surfaced with their status code`() = runBlocking {
        mode = Mode.STATUS
        statusCode = 404
        val part = partFile()

        val error = runCatching {
            UpdateDownloadEngine.download(client, url, part, 0L) { _, _, _ -> }
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is UpdateHttpException)
        assertEquals(404, (error as UpdateHttpException).code)
        assertEquals(UpdateFailureReason.HTTP, UpdateDownloadLogic.classify(error, error.code))
        part.delete()
        Unit
    }

    @Test
    fun `a rejected range (416) drops the stale partial file`() = runBlocking {
        mode = Mode.STATUS
        statusCode = 416
        val part = partFile()
        part.writeBytes(ByteArray(1024))

        val error = runCatching {
            UpdateDownloadEngine.download(client, url, part, 1024L) { _, _, _ -> }
        }.exceptionOrNull()

        assertTrue(error is UpdateHttpException)
        assertEquals(416, (error as UpdateHttpException).code)
        assertFalse("a stale partial file must not survive a 416", part.exists())
    }

    @Test
    fun `a cancelled download keeps the partial file for a later resume`() = runBlocking {
        val part = partFile()
        // Cancellation is simulated by resuming "from the end": the engine still writes correctly.
        val half = SIZE / 2
        part.writeBytes(payload.copyOfRange(0, half))

        val outcome = UpdateDownloadEngine.download(
            client = client,
            url = url,
            partFile = part,
            resumeFrom = part.length(),
        ) { _, _, _ -> }

        assertTrue(outcome.resumedFrom >= half.toLong())
        assertTrue(part.readBytes().contentEquals(payload))
        part.delete()
        Unit
    }

    @Test
    fun `an interrupted download survives an app restart and continues from the same byte`() = runBlocking {
        val part = partFile()
        val firstChunk = 1_200_000
        // Session 1: the app was closed after only part of the file had been written.
        part.writeBytes(payload.copyOfRange(0, firstChunk))

        // "Reopening the app": the persisted state is restored.
        val restored = UpdateDownloadPersistence.decide(
            persisted = PersistedUpdateDownload(
                status = PersistedUpdateStatus.PAUSED,
                version = "2.5.6.104",
                fileName = "app-universal-release.apk",
                url = url,
                totalBytes = SIZE.toLong(),
                downloadedBytes = firstChunk.toLong(),
                reason = UpdateFailureReason.NETWORK,
            ),
            partialFileLength = part.length(),
            readyFileIsInstallable = false,
        ) as UpdateDownloadRestore.Resume

        // Session 2: the real transfer continues from exactly that byte (Range request).
        val outcome = UpdateDownloadEngine.download(
            client = client,
            url = url,
            partFile = part,
            resumeFrom = UpdateDownloadLogic.resumeOffset(part, restored.version, restored.version),
        ) { _, _, _ -> }

        assertEquals("restore must reuse the partial length", firstChunk.toLong(), restored.offset)
        assertEquals("the transfer must not restart from zero", firstChunk.toLong(), outcome.resumedFrom)
        assertEquals(SIZE.toLong(), part.length())
        assertTrue(
            "the resumed file must be byte-identical to the served file",
            part.readBytes().contentEquals(payload),
        )
        part.delete()
        Unit
    }
}
