package me.rerere.rikkahub.utils

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Thrown when the update server answers with a non-successful status code. */
internal class UpdateHttpException(val code: Int) : IOException("HTTP $code")

internal data class DownloadOutcome(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val resumedFrom: Long,
)

/**
 * The streaming part of the in-app update downloader.
 *
 * Deliberately free of Android APIs (OkHttp + files + coroutines only) so the real transfer can be
 * unit tested against a real HTTP server: full download, resume with a `Range` header, servers that
 * ignore `Range`, and HTTP error handling.
 */
internal object UpdateDownloadEngine {

    const val BUFFER_SIZE = 64 * 1024
    const val PROGRESS_INTERVAL_MS = 300L

    /**
     * Downloads [url] into [partFile], continuing from [resumeFrom] bytes when the server supports
     * ranges. [onProgress] is called while transferring (throttled) and once at the end.
     *
     * The partial file is never deleted here except when the server rejects the requested range
     * (HTTP 416): callers decide what to keep on failure.
     */
    suspend fun download(
        client: OkHttpClient,
        url: String,
        partFile: File,
        resumeFrom: Long,
        userAgent: String = "",
        nowMillis: () -> Long = { System.currentTimeMillis() },
        onProgress: (bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit,
    ): DownloadOutcome {
        var offset = if (resumeFrom > 0L) resumeFrom else 0L
        var downloaded = offset
        var total = 0L

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", userAgent)
            .apply { if (offset > 0L) addHeader("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // The remote file no longer matches our partial file: restart from scratch.
                if (partFile.exists()) partFile.delete()
                throw UpdateHttpException(416)
            }
            if (!response.isSuccessful) throw UpdateHttpException(response.code)

            val body = response.body
            val declared = body.contentLength()
            total = when {
                response.code == 206 && declared > 0L -> offset + declared
                declared > 0L -> declared
                else -> 0L
            }
            if (response.code != 206 && offset > 0L) {
                // The server ignored our Range header: restart cleanly instead of corrupting data.
                offset = 0L
                downloaded = 0L
            }

            RandomAccessFile(partFile, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var windowStart = nowMillis()
                    var windowBytes = downloaded
                    var lastEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read

                        val now = nowMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            val elapsed = (now - windowStart).coerceAtLeast(1L)
                            val bytesPerSecond = ((downloaded - windowBytes) * 1000L) / elapsed
                            onProgress(downloaded, total, bytesPerSecond)
                            if (now - windowStart >= 1_000L) {
                                windowStart = now
                                windowBytes = downloaded
                            }
                        }
                    }
                }
            }
        }

        onProgress(downloaded, total, 0L)
        return DownloadOutcome(bytesDownloaded = downloaded, totalBytes = total, resumedFrom = resumeFrom)
    }
}

// ---------------------------------------------------------------------------- persisted state

internal enum class PersistedUpdateStatus {
    DOWNLOADING,
    VALIDATING,
    PAUSED,
    READY,
    FAILED,
}

/** Everything needed to restore an update download after the app was closed. */
internal data class PersistedUpdateDownload(
    val status: PersistedUpdateStatus,
    val version: String,
    val fileName: String,
    val url: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val reason: UpdateFailureReason,
)

/** What the app should do with a persisted update download when it starts again. */
internal sealed interface UpdateDownloadRestore {
    data object Nothing : UpdateDownloadRestore

    data class Ready(val version: String, val fileName: String, val url: String) : UpdateDownloadRestore

    /** Continue from [offset] instead of downloading the whole file again. */
    data class Resume(
        val version: String,
        val fileName: String,
        val url: String,
        val offset: Long,
        val totalBytes: Long,
        val reason: UpdateFailureReason,
    ) : UpdateDownloadRestore
}

/**
 * Pure restore rules: a finished APK stays "ready to install" and an interrupted one resumes from
 * its partial file rather than starting from zero.
 */
internal object UpdateDownloadPersistence {

    fun decide(
        persisted: PersistedUpdateDownload?,
        partialFileLength: Long?,
        readyFileIsInstallable: Boolean,
    ): UpdateDownloadRestore {
        if (persisted == null) return UpdateDownloadRestore.Nothing

        return when (persisted.status) {
            PersistedUpdateStatus.READY ->
                if (readyFileIsInstallable) {
                    UpdateDownloadRestore.Ready(persisted.version, persisted.fileName, persisted.url)
                } else {
                    UpdateDownloadRestore.Nothing
                }

            PersistedUpdateStatus.DOWNLOADING,
            PersistedUpdateStatus.VALIDATING,
            PersistedUpdateStatus.PAUSED -> {
                val partialLength = partialFileLength ?: 0L
                if (partialLength > 0L) {
                    UpdateDownloadRestore.Resume(
                        version = persisted.version,
                        fileName = persisted.fileName,
                        url = persisted.url,
                        offset = partialLength,
                        totalBytes = persisted.totalBytes,
                        reason = persisted.reason,
                    )
                } else {
                    UpdateDownloadRestore.Nothing
                }
            }

            PersistedUpdateStatus.FAILED -> UpdateDownloadRestore.Nothing
        }
    }
}
