package me.rerere.rikkahub.utils

import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why an update download stopped. Persisted by name, so keep the names stable.
 */
enum class UpdateFailureReason {
    NETWORK,
    TIMEOUT,
    HTTP,
    NO_SPACE,
    INVALID_PACKAGE,
    NOT_NEWER,
    IO,
    UNKNOWN,
}

/** Localised, user-facing explanation for a failure reason. */
@androidx.annotation.StringRes
fun UpdateFailureReason.stringRes(): Int = when (this) {
    UpdateFailureReason.NETWORK -> me.rerere.rikkahub.R.string.update_reason_network
    UpdateFailureReason.TIMEOUT -> me.rerere.rikkahub.R.string.update_reason_timeout
    UpdateFailureReason.HTTP -> me.rerere.rikkahub.R.string.update_reason_http
    UpdateFailureReason.NO_SPACE -> me.rerere.rikkahub.R.string.update_reason_no_space
    UpdateFailureReason.INVALID_PACKAGE -> me.rerere.rikkahub.R.string.update_reason_invalid_package
    UpdateFailureReason.NOT_NEWER -> me.rerere.rikkahub.R.string.update_reason_not_newer
    UpdateFailureReason.IO -> me.rerere.rikkahub.R.string.update_reason_io
    UpdateFailureReason.UNKNOWN -> me.rerere.rikkahub.R.string.update_reason_unknown
}

/**
 * Single source of truth for the in-app update download.
 *
 * The UI (update card) renders this state; an Android notification is only a secondary helper.
 * Nothing in this model depends on DownloadManager or on any notification being visible.
 */
sealed interface UpdateDownloadState {
    /** Version this state belongs to (null while idle). */
    val version: String?

    /** APK file this state belongs to (null while idle). */
    val fileName: String?

    data object Idle : UpdateDownloadState {
        override val version: String? = null
        override val fileName: String? = null
    }

    data class Downloading(
        override val version: String,
        override val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : UpdateDownloadState

    /** Download finished, the APK is being checked before it is offered for install. */
    data class Validating(
        override val version: String,
        override val fileName: String,
    ) : UpdateDownloadState

    /** Interrupted but **resumable**: the partial file is kept on disk. */
    data class Paused(
        override val version: String,
        override val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val reason: UpdateFailureReason,
    ) : UpdateDownloadState

    /** Complete and verified: the user can now open the official Android installer. */
    data class Ready(
        override val version: String,
        override val fileName: String,
        val filePath: String,
    ) : UpdateDownloadState

    /** Terminal failure: the partial file was discarded. */
    data class Failed(
        override val version: String,
        override val fileName: String,
        val reason: UpdateFailureReason,
    ) : UpdateDownloadState

    val isActive: Boolean get() = this is Downloading || this is Validating
}

/**
 * Pure helpers for the update download so the important rules can be unit tested without Android:
 * progress maths, resume decisions, duplicate protection and failure classification.
 */
internal object UpdateDownloadLogic {

    /** Bytes that a partial file may contribute when resuming the same version, otherwise 0. */
    fun resumeOffset(partialFile: File?, partialVersion: String?, targetVersion: String): Long {
        if (partialFile == null || !partialFile.isFile) return 0L
        if (partialVersion != targetVersion) return 0L
        val length = partialFile.length()
        return if (length > 0L) length else 0L
    }

    /**
     * Only one download may run at a time: while a download is active (downloading or verifying)
     * a second request is never started, for the same version or for another one.
     */
    fun canStartDownload(current: UpdateDownloadState): Boolean = !current.isActive

    /**
     * Partial file name bound to a specific release, e.g.
     * `app-universal-release.apk` + `2.5.5.97` -> `app-universal-release-2.5.5.97.apk.part`.
     *
     * The release version is part of the file name, so a newer release can never resume (and
     * therefore never corrupt or silently reuse) the partial download of an older one, and the
     * same release always finds exactly its own partial file.
     */
    fun partialFileName(fileName: String, version: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            "${fileName.substring(0, dot)}-$version${fileName.substring(dot)}.part"
        } else {
            "$fileName-$version.part"
        }
    }

    fun progressPercent(bytesDownloaded: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val percent = (bytesDownloaded.toDouble() / totalBytes.toDouble() * 100.0).toInt()
        return percent.coerceIn(0, 100)
    }

    fun remainingSeconds(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long): Long? {
        if (bytesPerSecond <= 0L || totalBytes <= bytesDownloaded) return null
        return (totalBytes - bytesDownloaded) / bytesPerSecond
    }

    /** Failures that keep the partial file so the download can continue later. */
    fun isResumable(reason: UpdateFailureReason, hasPartialFile: Boolean): Boolean {
        if (!hasPartialFile) return false
        return reason == UpdateFailureReason.NETWORK ||
            reason == UpdateFailureReason.TIMEOUT ||
            reason == UpdateFailureReason.IO ||
            reason == UpdateFailureReason.UNKNOWN
    }

    fun classify(throwable: Throwable, httpCode: Int? = null): UpdateFailureReason {
        if (httpCode != null) return UpdateFailureReason.HTTP
        return when (throwable) {
            is SocketTimeoutException -> UpdateFailureReason.TIMEOUT
            is UnknownHostException -> UpdateFailureReason.NETWORK
            is InterruptedIOException -> UpdateFailureReason.TIMEOUT
            else -> {
                val message = throwable.message.orEmpty()
                when {
                    message.contains("ENOSPC", ignoreCase = true) ||
                        message.contains("No space left", ignoreCase = true) -> UpdateFailureReason.NO_SPACE
                    message.contains("Unable to resolve host", ignoreCase = true) -> UpdateFailureReason.NETWORK
                    else -> UpdateFailureReason.UNKNOWN
                }
            }
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0L -> "--"
        bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / 1024.0 / 1024.0)
        bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "--"
        val minutes = seconds / 60
        val remainder = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }
}
