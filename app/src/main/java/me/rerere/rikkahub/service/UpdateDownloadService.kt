package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.utils.UpdateDownloadLogic
import me.rerere.rikkahub.utils.UpdateDownloadManager
import me.rerere.rikkahub.utils.UpdateDownloadState
import me.rerere.rikkahub.utils.stringRes
import org.koin.android.ext.android.inject

private const val TAG = "UpdateDownloadService"

/**
 * Keeps the process alive while an update APK is downloading, so the download survives the user
 * leaving/closing the app.
 *
 * The download state itself lives in [UpdateDownloadManager]; this service only provides the
 * Android foreground-service lifetime and renders a **secondary** progress notification. If the
 * notification is hidden by the system or by the user, the download and the in-app state are
 * unaffected.
 */
class UpdateDownloadService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2003

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
            }.onFailure {
                Log.w(TAG, "Unable to start update download foreground service", it)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, UpdateDownloadService::class.java))
            }
        }
    }

    private val manager: UpdateDownloadManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var worker: Job? = null
    private var notifier: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            notifier = serviceScope.launch {
                manager.state.collectLatest { updateNotification(it) }
            }
            worker = serviceScope.launch {
                try {
                    manager.runDownloadWithRetries()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    Log.e(TAG, "Update download failed unexpectedly", t)
                }
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notifier?.cancel()
        worker?.cancel()
        serviceScope.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    private fun finish() {
        notifier?.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun startForegroundCompat(): Boolean = try {
        val notification = buildNotification(manager.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to enter foreground", e)
        false
    }

    private fun updateNotification(state: UpdateDownloadState) {
        if (!isForeground) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: UpdateDownloadState): Notification {
        val builder = NotificationCompat.Builder(this, UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.update_download_notification_title))
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        when (state) {
            is UpdateDownloadState.Downloading -> {
                val percent = UpdateDownloadLogic.progressPercent(state.bytesDownloaded, state.totalBytes)
                builder.setContentText(getString(R.string.update_download_notification_progress, percent))
                if (state.totalBytes > 0L) {
                    builder.setProgress(100, percent, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }

            is UpdateDownloadState.Validating -> builder
                .setContentText(getString(R.string.update_download_state_validating))
                .setProgress(0, 0, true)

            is UpdateDownloadState.Paused -> builder
                .setOngoing(false)
                .setContentText(getString(R.string.update_download_state_paused, getString(state.reason.stringRes())))

            is UpdateDownloadState.Failed -> builder
                .setOngoing(false)
                .setContentText(getString(R.string.update_download_state_failed, getString(state.reason.stringRes())))

            is UpdateDownloadState.Ready -> builder
                .setOngoing(false)
                .setContentText(getString(R.string.update_download_state_ready))

            UpdateDownloadState.Idle -> builder.setContentText(getString(R.string.update_download_state_idle))
        }
        return builder.build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
