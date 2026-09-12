package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.io.File
import me.rerere.rikkahub.R

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId < 0) return
                handleDownloadComplete(context, downloadId)
            }
            DownloadManager.ACTION_NOTIFICATION_CLICKED -> {
                handleNotificationClicked(context, intent)
            }
        }
    }

    private fun handleDownloadComplete(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query) ?: return

        cursor.use {
            if (!it.moveToFirst()) return

            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIndex >= 0) it.getInt(statusIndex) else -1

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val apkFile = resolveDownloadedApk(context, it, downloadId)
                if (apkFile != null && UpdateInstaller.isApkValid(context, apkFile)) {
                    UpdateInstaller.installApk(context, apkFile)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else -1
                Toast.makeText(
                    context,
                    "${context.getString(R.string.update_failed)} ($reason)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleNotificationClicked(context: Context, intent: Intent) {
        val pref = context.getSharedPreferences("update_download_pref", Context.MODE_PRIVATE)
        val lastPath = pref.getString("last_apk_path", null)
        if (lastPath != null) {
            val file = File(lastPath)
            if (UpdateInstaller.isApkValid(context, file)) {
                UpdateInstaller.installApk(context, file)
                return
            }
        }

        runCatching {
            val viewDownloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewDownloads)
        }
    }

    private fun resolveDownloadedApk(
        context: Context,
        cursor: android.database.Cursor,
        downloadId: Long
    ): File? {
        // 1. Check local URI from cursor
        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
        if (localUriIndex >= 0) {
            val uriStr = cursor.getString(localUriIndex)
            if (!uriStr.isNullOrEmpty()) {
                val path = Uri.parse(uriStr).path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) return file
                }
            }
        }

        // 2. Check SharedPreferences recorded path
        val pref = context.getSharedPreferences("update_download_pref", Context.MODE_PRIVATE)
        val recordedId = pref.getLong("last_download_id", -1L)
        if (recordedId == downloadId) {
            val recordedPath = pref.getString("last_apk_path", null)
            if (recordedPath != null) {
                val file = File(recordedPath)
                if (file.exists()) return file
            }
        }

        // 3. Fallback to external files downloads directory
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val recordedName = pref.getString("last_apk_name", null)
        if (downloadDir != null && recordedName != null) {
            val file = File(downloadDir, recordedName)
            if (file.exists()) return file
        }

        // 4. Scan downloadDir for any valid APK if recordedName not found
        if (downloadDir != null && downloadDir.isDirectory) {
            val apks = downloadDir.listFiles { f -> f.extension.equals("apk", ignoreCase = true) }
            val valid = apks?.firstOrNull { UpdateInstaller.isApkValid(context, it) }
            if (valid != null) return valid
        }

        return null
    }
}
