package me.rerere.rikkahub.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.service.UpdateDownloadService
import okhttp3.OkHttpClient

private const val TAG = "UpdateDownloadManager"
private const val PREFS_NAME = "update_download_state"
private const val MAX_AUTO_RETRIES = 3
private const val AUTO_RETRY_DELAY_MS = 5_000L
private const val REQUEST_CODE_INSTALL = 0x51
private const val REQUEST_CODE_OPEN = 0x52
private const val NOTIFICATION_ID_READY = 0x12

private const val KEY_STATUS = "status"
private const val KEY_VERSION = "version"
private const val KEY_FILE = "file_name"
private const val KEY_URL = "url"
private const val KEY_TOTAL = "total_bytes"
private const val KEY_DOWNLOADED = "downloaded_bytes"
private const val KEY_REASON = "reason"

/** What is currently being downloaded. */
internal data class UpdateDownloadTarget(
    val version: String,
    val fileName: String,
    val url: String,
)

/**
 * In-app update downloader.
 *
 * This is the **only** source of truth for the download state: the update card renders [state]
 * directly. No DownloadManager request is used and no notification is required for the state to be
 * correct — notifications are only a secondary helper.
 *
 * The transfer runs inside [UpdateDownloadService] (a `dataSync` foreground service) so it keeps
 * going after the user leaves the app. Progress, the partial file and the target version are
 * persisted, so closing/restarting the app restores the download instead of restarting it from
 * zero, and a finished APK stays "ready to install".
 */
class UpdateDownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    @Volatile
    private var target: UpdateDownloadTarget? = null

    @Volatile
    private var cancelledByUser = false

    /** Folder holding the downloaded APK and its `.part` file (internal, permission-free). */
    fun updatesDir(): File = File(context.filesDir, "updates").apply { mkdirs() }

    // ------------------------------------------------------------------ public API

    /**
     * Start (or continue) the download of [download] taken from release [version].
     * A second request for a version that is already downloading is ignored.
     */
    fun start(download: UpdateDownload, version: String) {
        if (!UpdateDownloadLogic.canStartDownload(_state.value)) {
            Log.i(TAG, "A download is already running; ignoring request for $version")
            return
        }

        cancelledByUser = false
        val newTarget = UpdateDownloadTarget(version, download.name, download.url)
        target = newTarget

        val finalFile = File(updatesDir(), newTarget.fileName)
        // A verified, newer APK is already on disk: nothing to download (never reuse an older one).
        if (UpdateInstaller.isInstallableUpdate(context, finalFile)) {
            _state.value = UpdateDownloadState.Ready(version, newTarget.fileName, finalFile.absolutePath)
            persist()
            return
        }

        // Each release has its own partial file, so a new version can never continue an old one.
        val partial = partialFile(newTarget)
        cleanupStalePartials(keep = partial)
        val offset = UpdateDownloadLogic.resumeOffset(partial, version, version)
        _state.value = UpdateDownloadState.Downloading(
            version = version,
            fileName = newTarget.fileName,
            bytesDownloaded = offset,
            // A partial file of another release is never reused, so its stored total is meaningless.
            totalBytes = if (offset > 0L) prefs.getLong(KEY_TOTAL, 0L) else 0L,
            bytesPerSecond = 0L,
        )
        persist()
        startDownloadService()
    }

    /** Retry/resume the current target; keeps the partial file so the download continues. */
    fun retry() {
        val current = target ?: return
        start(UpdateDownload(name = current.fileName, url = current.url, size = ""), current.version)
    }

    /** Stop the download and discard the partial file. A verified APK is never deleted here. */
    fun cancel() {
        cancelledByUser = true
        target?.let { partialFile(it).delete() }
        target = null
        _state.value = UpdateDownloadState.Idle
        clearPersisted()
        UpdateDownloadService.stop(context)
    }

    /**
     * Hide a finished/failed download. Deliberately refused while a download is running so the
     * progress bar cannot be dismissed by accident.
     */
    fun dismiss() {
        if (_state.value.isActive) return
        _state.value = UpdateDownloadState.Idle
        clearPersisted()
    }

    /** Opens the official Android installer for the ready APK. Must be called from the UI. */
    fun install(): Boolean {
        val ready = _state.value as? UpdateDownloadState.Ready ?: return false
        val file = File(ready.filePath)
        if (!UpdateInstaller.isInstallableUpdate(context, file)) {
            _state.value = UpdateDownloadState.Failed(
                version = ready.version,
                fileName = ready.fileName,
                reason = UpdateFailureReason.INVALID_PACKAGE,
            )
            persist()
            return false
        }
        UpdateInstaller.installApk(context, file)
        return true
    }

    // ------------------------------------------------------------------ restore after restart

    /**
     * Restores a previous session (called on app start): a finished APK stays "ready to install",
     * an interrupted download keeps its partial file and resumes instead of starting from zero.
     */
    fun restoreAndResume() {
        val persisted = readPersisted()
        // Look up the partial file by the *persisted release*: the name is version-tagged, so a
        // partial download of any other release is invisible here.
        val partial = persisted?.let {
            File(updatesDir(), UpdateDownloadLogic.partialFileName(it.fileName, it.version))
        }
        val finalFile = persisted?.let { File(updatesDir(), it.fileName) }

        val decision = UpdateDownloadPersistence.decide(
            persisted = persisted,
            partialFileLength = partial?.takeIf { it.isFile }?.length(),
            readyFileIsInstallable = finalFile != null &&
                finalFile.isFile &&
                UpdateInstaller.isInstallableUpdate(context, finalFile),
        )

        when (decision) {
            is UpdateDownloadRestore.Nothing -> clearPersisted()

            is UpdateDownloadRestore.Ready -> {
                target = UpdateDownloadTarget(decision.version, decision.fileName, decision.url)
                _state.value = UpdateDownloadState.Ready(
                    decision.version, decision.fileName, finalFile!!.absolutePath,
                )
                Log.i(TAG, "Restored ready update ${decision.version}")
            }

            is UpdateDownloadRestore.Resume -> {
                target = UpdateDownloadTarget(decision.version, decision.fileName, decision.url)
                cleanupStalePartials(keep = partial)
                _state.value = UpdateDownloadState.Paused(
                    version = decision.version,
                    fileName = decision.fileName,
                    bytesDownloaded = decision.offset,
                    totalBytes = decision.totalBytes,
                    reason = decision.reason,
                )
                Log.i(TAG, "Restored partial download of ${decision.version} (${decision.offset} bytes) - resuming")
                startDownloadService()
            }
        }
    }

    // ------------------------------------------------------------------ the download itself

    /**
     * Performs the download; runs inside the foreground service and suspends until it finished,
     * failed or was cancelled. The state below is authoritative — never a notification.
     */
    suspend fun runDownload(): Unit = withContext(Dispatchers.IO) {
        val current = target ?: return@withContext
        val finalFile = File(updatesDir(), current.fileName)
        val partFile = File(updatesDir(), "${current.fileName}.part")
        var downloaded = 0L
        var total = prefs.getLong(KEY_TOTAL, 0L)

        try {
            if (UpdateInstaller.isInstallableUpdate(context, finalFile)) {
                _state.value = UpdateDownloadState.Ready(current.version, current.fileName, finalFile.absolutePath)
                persist()
                return@withContext
            }

            var offset = UpdateDownloadLogic.resumeOffset(partFile, current.version, current.version)

            val outcome = UpdateDownloadEngine.download(
                client = client,
                url = current.url,
                partFile = partFile,
                resumeFrom = offset,
                userAgent = "RikkaHub-Arabic/${BuildConfig.VERSION_NAME}",
            ) { bytes, totalBytes, bytesPerSecond ->
                downloaded = bytes
                total = totalBytes
                // Never publish a partial percentage as "active" after the transfer stopped.
                if (bytes < totalBytes || totalBytes == 0L) {
                    _state.value = UpdateDownloadState.Downloading(
                        version = current.version,
                        fileName = current.fileName,
                        bytesDownloaded = bytes,
                        totalBytes = totalBytes,
                        bytesPerSecond = bytesPerSecond,
                    )
                    persist()
                }
            }
            downloaded = outcome.bytesDownloaded
            total = outcome.totalBytes

            // ---- verify BEFORE the file is promoted to a complete APK -------------------
            _state.value = UpdateDownloadState.Validating(current.version, current.fileName)
            if (!partFile.isFile || partFile.length() <= 0L) throw IOException("empty download")

            if (!UpdateInstaller.isApkValid(context, partFile)) {
                partFile.delete()
                _state.value = UpdateDownloadState.Failed(
                    current.version, current.fileName, UpdateFailureReason.INVALID_PACKAGE,
                )
                persist()
                return@withContext
            }
            if (!UpdateInstaller.isInstallableUpdate(context, partFile)) {
                partFile.delete()
                _state.value = UpdateDownloadState.Failed(
                    current.version, current.fileName, UpdateFailureReason.NOT_NEWER,
                )
                persist()
                return@withContext
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            _state.value = UpdateDownloadState.Ready(current.version, current.fileName, finalFile.absolutePath)
            persist()
            notifyReady(current.version)
        } catch (cancellation: CancellationException) {
            if (cancelledByUser) {
                partFile.delete()
                _state.value = UpdateDownloadState.Idle
                clearPersisted()
            } else {
                // The service/process was stopped: keep the partial file so the user can resume.
                _state.value = UpdateDownloadState.Paused(
                    version = current.version,
                    fileName = current.fileName,
                    bytesDownloaded = if (partFile.isFile) partFile.length() else downloaded,
                    totalBytes = total,
                    reason = UpdateFailureReason.NETWORK,
                )
                persist()
            }
            throw cancellation
        } catch (error: Throwable) {
            val reason = UpdateDownloadLogic.classify(error, (error as? UpdateHttpException)?.code)
            val hasPartial = partFile.isFile && partFile.length() > 0L
            if (UpdateDownloadLogic.isResumable(reason, hasPartial)) {
                // Not a permanent failure: keep everything and let the user retry/resume.
                _state.value = UpdateDownloadState.Paused(
                    version = current.version,
                    fileName = current.fileName,
                    bytesDownloaded = partFile.length(),
                    totalBytes = total,
                    reason = reason,
                )
            } else {
                partFile.delete()
                _state.value = UpdateDownloadState.Failed(current.version, current.fileName, reason)
            }
            persist()
        }
    }

    /**
     * Runs the download with a few automatic retries (network hiccups / lost connection) before
     * leaving a resumable paused state for the user.
     */
    suspend fun runDownloadWithRetries() {
        var attempt = 0
        while (true) {
            runDownload()
            if (_state.value !is UpdateDownloadState.Paused) return
            if (attempt >= MAX_AUTO_RETRIES) return
            attempt++
            Log.i(TAG, "Retrying update download (attempt $attempt)")
            delay(AUTO_RETRY_DELAY_MS)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Version-tagged partial file of [target]; never shared with another release. */
    private fun partialFile(target: UpdateDownloadTarget): File =
        File(updatesDir(), UpdateDownloadLogic.partialFileName(target.fileName, target.version))

    /**
     * Removes leftover partial files of other releases (only `*.part`, never a completed APK).
     * A completed and verified `.apk` is always kept.
     */
    private fun cleanupStalePartials(keep: File?) {
        updatesDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.filter { keep == null || it.name != keep.name }
            ?.forEach { stale ->
                if (stale.delete()) Log.i(TAG, "Removed stale partial download ${stale.name}")
            }
    }

    private fun startDownloadService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
        }.onFailure {
            Log.w(TAG, "Unable to start the update download foreground service", it)
            val current = _state.value
            if (current is UpdateDownloadState.Downloading) {
                _state.value = UpdateDownloadState.Paused(
                    version = current.version,
                    fileName = current.fileName,
                    bytesDownloaded = current.bytesDownloaded,
                    totalBytes = current.totalBytes,
                    reason = UpdateFailureReason.UNKNOWN,
                )
                persist()
            }
        }
    }

    /** Secondary helper notification: tapping it opens the official installer (never automatic). */
    private fun notifyReady(version: String) {
        val ready = _state.value as? UpdateDownloadState.Ready ?: return
        val installIntent = UpdateInstaller.buildInstallIntent(context, File(ready.filePath))
        val pendingIntent = if (installIntent != null) {
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_INSTALL,
                installIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN,
                Intent(context, RouteActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        NotificationUtil.notify(context, UPDATE_NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID_READY) {
            title = context.getString(R.string.update_ready_notification_title)
            content = context.getString(R.string.update_ready_notification_content, version)
            smallIcon = R.drawable.ic_stat_rikkahub
            autoCancel = true
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    private fun readPersisted(): PersistedUpdateDownload? {
        val status = prefs.getString(KEY_STATUS, null)?.let {
            runCatching { PersistedUpdateStatus.valueOf(it) }.getOrNull()
        } ?: return null
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val fileName = prefs.getString(KEY_FILE, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        val reason = prefs.getString(KEY_REASON, null)
            ?.let { runCatching { UpdateFailureReason.valueOf(it) }.getOrNull() }
            ?: UpdateFailureReason.UNKNOWN
        return PersistedUpdateDownload(
            status = status,
            version = version,
            fileName = fileName,
            url = url,
            totalBytes = prefs.getLong(KEY_TOTAL, 0L),
            downloadedBytes = prefs.getLong(KEY_DOWNLOADED, 0L),
            reason = reason,
        )
    }

    private fun persist() {
        val state = _state.value
        val editor = prefs.edit()
        if (state is UpdateDownloadState.Idle) {
            editor.clear().apply()
            return
        }
        editor.putString(KEY_VERSION, state.version)
        editor.putString(KEY_FILE, state.fileName)
        editor.putString(KEY_URL, target?.url)
        when (state) {
            is UpdateDownloadState.Downloading -> {
                editor.putString(KEY_STATUS, PersistedUpdateStatus.DOWNLOADING.name)
                editor.putLong(KEY_TOTAL, state.totalBytes)
                editor.putLong(KEY_DOWNLOADED, state.bytesDownloaded)
            }

            is UpdateDownloadState.Validating -> {
                editor.putString(KEY_STATUS, PersistedUpdateStatus.VALIDATING.name)
            }

            is UpdateDownloadState.Paused -> {
                editor.putString(KEY_STATUS, PersistedUpdateStatus.PAUSED.name)
                editor.putLong(KEY_TOTAL, state.totalBytes)
                editor.putLong(KEY_DOWNLOADED, state.bytesDownloaded)
                editor.putString(KEY_REASON, state.reason.name)
            }

            is UpdateDownloadState.Ready -> {
                editor.putString(KEY_STATUS, PersistedUpdateStatus.READY.name)
            }

            is UpdateDownloadState.Failed -> {
                editor.putString(KEY_STATUS, PersistedUpdateStatus.FAILED.name)
                editor.putString(KEY_REASON, state.reason.name)
            }

            UpdateDownloadState.Idle -> Unit
        }
        editor.apply()
    }

    private fun clearPersisted() {
        prefs.edit().clear().apply()
    }
}
