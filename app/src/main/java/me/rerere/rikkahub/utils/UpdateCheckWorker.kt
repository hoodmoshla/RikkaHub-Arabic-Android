package me.rerere.rikkahub.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.UPDATE_NOTIFICATION_CHANNEL_ID
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Periodic background update check.
 *
 * The Arabic build must notice a new release even when the update screen was never opened, so the
 * check does not depend on any UI. It only *notifies*: installing always goes through the official
 * Android package installer (see [UpdateInstaller]), never silently.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val checker = getKoin().get<UpdateChecker>()
            val latest = checker.checkForUpdate() ?: return Result.success()
            if (!UpdatePolicy.isUpdateAvailable(BuildConfig.VERSION_NAME, latest.version)) {
                return Result.success()
            }
            // Keep the in-app state in sync so the update card shows the new release immediately.
            checker.refresh()
            notifyUpdateAvailable(latest)
            Result.success()
        } catch (t: Throwable) {
            // Transient failures (offline, rate limit) are retried by WorkManager.
            Result.retry()
        }
    }

    private fun notifyUpdateAvailable(info: UpdateInfo) {
        val context = applicationContext
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationUtil.notify(context, UPDATE_NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID) {
            title = context.getString(R.string.update_notification_title)
            content = context.getString(R.string.update_notification_content, info.version)
            smallIcon = R.drawable.ic_stat_rikkahub
            autoCancel = true
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "rikkahub_arabic_periodic_update_check"

        /** WorkManager's minimum periodic interval is 15 minutes; a few hours is plenty here. */
        const val INTERVAL_HOURS = 6L

        private const val NOTIFICATION_ID = 0x10
    }
}
