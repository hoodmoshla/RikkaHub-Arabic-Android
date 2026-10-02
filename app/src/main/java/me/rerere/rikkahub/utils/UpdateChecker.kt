package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Arabic distribution publishes its own releases in this repository. The update check must
 * always read the *Arabic* releases (never the official ones): only the Arabic APKs are signed
 * with the key that is allowed to update an installed Arabic build.
 */
internal const val ARABIC_RELEASES_API =
    "https://api.github.com/repos/hoodmoshla/RikkaHub-Arabic-Android/releases?per_page=30"

/** Re-check for a new Arabic release periodically, even when no app screen was opened. */
private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

class UpdateChecker(
    private val client: OkHttpClient,
    // The application's own managed scope. It must be the concrete `AppScope` (not a free-floating
    // `CoroutineScope`): the app module only ever declares `AppScope`, and depending on a generic
    // `CoroutineScope` made Koin fail to build the checker - and therefore ChatVM - with
    // "No definition found for type 'kotlinx.coroutines.CoroutineScope'" whenever ChatPage opened.
    appScope: AppScope,
    // Overridable so the whole update flow can be exercised against a real HTTP endpoint.
    private val releasesApi: String = ARABIC_RELEASES_API,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Bumped by refresh() so the flow below re-runs the check.
    private val refreshTrigger = MutableStateFlow(System.currentTimeMillis())

    val updateState: StateFlow<UiState<UpdateInfo>> = refreshTrigger
        .flatMapLatest { checkUpdate() }
        .stateIn(
            scope = appScope,
            // Eagerly: checking starts as soon as the app process starts instead of waiting for
            // a specific screen (update card / drawer) to be opened.
            started = SharingStarted.Eagerly,
            initialValue = UiState.Loading,
        )

    init {
        // Periodic refresh while the process is alive. UpdateCheckWorker covers the case where
        // the app was killed and restarted by the system.
        appScope.launch {
            while (true) {
                delay(UPDATE_CHECK_INTERVAL_MS)
                refresh()
            }
        }
    }

    /** Forces a new update check. */
    fun refresh() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    /** One-shot check used by the background worker. Returns null when the check fails. */
    suspend fun checkForUpdate(): UpdateInfo? = runCatching { fetchLatestUpdate() }.getOrNull()

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(UiState.Success(fetchLatestUpdate()))
    }.catch {
        emit(UiState.Error(Exception("Failed to fetch update info", it)))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchLatestUpdate(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(releasesApi)
                .get()
                .addHeader(
                    "User-Agent",
                    "RikkaHub Arabic ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                )
                .addHeader("Accept", "application/vnd.github+json")
                .build()
        ).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch update info: HTTP ${response.code}")
        }
        // Reads the whole release list and picks the highest version, so a failed/delayed CI run
        // can never hide a newer Arabic release and ordering never depends on publish time.
        val releases = json.decodeFromString<List<GitHubRelease>>(response.body.string())
        val latest = UpdatePolicy.latestRelease(releases.mapNotNull { it.toCandidate() })
            ?: throw Exception("No published Arabic release found")
        return UpdateInfo(
            version = latest.version,
            publishedAt = latest.publishedAt,
            changelog = latest.changelog,
            downloads = latest.downloads,
        )
    }

}

object UpdateInstaller {
    /**
     * Checks if the file exists and is a valid, complete APK matching this application's package name.
     */
    fun isApkValid(context: Context, file: File): Boolean {
        return readPackageInfo(context, file)?.packageName == context.packageName
    }

    /**
     * True when [file] is a complete APK of this application **and** its version code is
     * strictly higher than the currently installed one.
     *
     * GitHub release asset names are stable across releases (`app-universal-release.apk`,
     * `app-arm64-v8a-release.apk`, ...), so a file cached from a previous update attempt would
     * otherwise be considered "valid" and get installed again, preventing every future update.
     * Requiring a strictly newer version code guarantees the freshest release is always used.
     */
    fun isInstallableUpdate(context: Context, file: File): Boolean {
        val info = readPackageInfo(context, file) ?: return false
        if (info.packageName != context.packageName) return false
        return isNewerVersionCode(versionCodeOf(info), installedVersionCode(context))
    }

    /**
     * Reads the manifest of an APK file without installing it. Returns `null` when the file is
     * missing, empty or not a readable APK archive.
     */
    private fun readPackageInfo(context: Context, file: File): PackageInfo? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull()
    }

    private fun installedVersionCode(context: Context): Long = runCatching {
        versionCodeOf(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    /**
     * Installs the downloaded APK via standard, official Android system Package Installer UI.
     * Respects Android security confirmations and does not attempt silent installation.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            Toast.makeText(context, context.getString(R.string.update_failed), Toast.LENGTH_SHORT).show()
            return
        }

        // content:// URI through FileProvider (required on Android 7+). Built up-front so an
        // invalid file is reported before touching the install permission screen.
        val contentUri: Uri = buildContentUri(context, apkFile) ?: run {
            Toast.makeText(context, context.getString(R.string.update_failed), Toast.LENGTH_LONG).show()
            return
        }

        // On Android 8.0+ (Oreo), verify unknown app install permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                context,
                context.getString(R.string.update_permission_required),
                Toast.LENGTH_LONG
            ).show()
            val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(manageIntent)
            return
        }

        try {
            context.startActivity(buildInstallIntent(context, contentUri))
        } catch (e: Exception) {
            Toast.makeText(context, "${context.getString(R.string.update_failed)}: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /** FileProvider content:// URI for [apkFile], or null when it cannot be built. */
    fun buildContentUri(context: Context, apkFile: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    }.getOrNull()

    /**
     * The official Android package-installer intent for a downloaded APK, or null when the file
     * cannot be exposed. Used by the in-app "install" button and by the ready notification action;
     * installation is always confirmed by the user, never silent.
     */
    fun buildInstallIntent(context: Context, apkFile: File): Intent? {
        val uri = buildContentUri(context, apkFile) ?: return null
        return buildInstallIntent(context, uri)
    }

    fun buildInstallIntent(context: Context, contentUri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** Maps to the pure policy type, or null for drafts/pre-releases that must be ignored. */
    fun toCandidate(): ReleaseCandidate? {
        if (draft || prerelease) return null
        return ReleaseCandidate(
            version = tagName.removePrefix("v"),
            publishedAt = publishedAt,
            changelog = body.orEmpty().ifBlank { name.orEmpty() },
            downloads = assets
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .sortedWith(compareBy({ apkAssetPriority(it.name) }, { it.name.lowercase() }))
                .map { UpdateDownload(it.name, it.browserDownloadUrl, formatBytes(it.size)) },
        )
    }
}

/** A published Arabic release reduced to what the update policy needs. */
internal data class ReleaseCandidate(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
)

/**
 * Pure update-decision logic, kept free of Android/network dependencies so it can be unit tested.
 */
internal object UpdatePolicy {
    /**
     * The newest released version (proper SemVer comparison), ignoring drafts/pre-releases.
     * Comparing versions — instead of trusting the order GitHub returns or a previous CI run —
     * is what keeps updates detectable after a failed workflow.
     */
    fun latestRelease(releases: List<ReleaseCandidate>): ReleaseCandidate? =
        releases.maxWithOrNull(
            compareBy<ReleaseCandidate> { Version(it.version) }.thenBy { it.publishedAt }
        )

    /** True only when [latestVersion] is strictly newer than [installedVersion]. */
    fun isUpdateAvailable(installedVersion: String, latestVersion: String): Boolean =
        Version(latestVersion) > Version(installedVersion)

    /** The APK to offer for a release: the universal build first, then the best ABI match. */
    fun preferredDownload(downloads: List<UpdateDownload>): UpdateDownload? =
        downloads.sortedWith(
            compareBy({ apkAssetPriority(it.name) }, { it.name.lowercase() })
        ).firstOrNull()
}

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * A downloaded/cached package is only installable when its version code is strictly greater than
 * the currently installed one. Because release asset names are shared between versions, a file
 * left on disk from a previous release must never be treated as the current update.
 */
internal fun isNewerVersionCode(remoteVersionCode: Long, installedVersionCode: Long): Boolean {
    return remoteVersionCode > installedVersionCode
}

/**
 * Ranks APK release assets so that the most broadly compatible one is offered first:
 * universal > arm64-v8a > armeabi-v7a/arm > x86_64 > x86 > anything else.
 *
 * The Arabic release publishes `app-universal-release.apk`, `app-arm64-v8a-release.apk` and
 * `app-x86_64-release.apk`; the universal build is the safe default on every device.
 */
internal fun apkAssetPriority(name: String): Int {
    val normalized = name.lowercase()
    return when {
        normalized.contains("universal") -> 0
        normalized.contains("arm64") || normalized.contains("aarch64") -> 1
        normalized.contains("armeabi") || normalized.contains("armv7") -> 2
        normalized.contains("x86_64") || normalized.contains("x64") -> 3
        normalized.contains("x86") -> 4
        else -> 5
    }
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
