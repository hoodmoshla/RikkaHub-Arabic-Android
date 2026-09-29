package me.rerere.rikkahub.utils

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end check of the update flow: a real [UpdateChecker] talking HTTP (to a local server that
 * speaks the GitHub releases API) and producing the update the user would see and download.
 *
 * This is the scenario that matters in production:
 *
 *   installed = old version  ->  GitHub has a newer Arabic release  ->  app detects it
 *   ->  offers app-universal-release.apk of the NEW release  ->  never the old cached APK
 *
 * The asset name is identical (`app-universal-release.apk`) in every release, which is exactly the
 * situation that used to make the app re-install an old APK.
 */
class UpdateCheckerIntegrationTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var responseBody: String = "[]"
    private var responseCode: Int = 200
    private var requestCount: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/releases") { exchange ->
            requestCount++
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/releases?per_page=30"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun checker() = UpdateChecker(
        client = OkHttpClient(),
        appScope = scope,
        releasesApi = baseUrl,
    )

    private fun apkAsset(url: String, size: Long = 50_000_000) = """
        {"name":"app-universal-release.apk","size":$size,"browser_download_url":"$url"}
    """.trimIndent()

    private fun release(tag: String, publishedAt: String, apkUrl: String, body: String = "") = """
        {
          "tag_name": "$tag",
          "name": "RikkaHub Arabic $tag",
          "body": "$body",
          "published_at": "$publishedAt",
          "draft": false,
          "prerelease": false,
          "assets": [${apkAsset(apkUrl)}]
        }
    """.trimIndent()

    @Test
    fun `installed old version sees the new Arabic release and gets the new APK url`() = runBlocking {
        val installed = "2.5.4.86"
        // Older release listed FIRST on purpose: the newest must be picked by version, not order.
        responseBody = "[${release("v2.5.4.86", "2026-09-27T11:50:21Z", "https://example.com/old/app-universal-release.apk")}," +
            "${release("v2.5.5.87", "2026-09-28T09:00:00Z", "https://example.com/new/app-universal-release.apk")}]"

        val info = checker().checkForUpdate()
        assertNotNull("a newer Arabic release must be detected", info)
        assertEquals("2.5.5.87", info!!.version)

        assertTrue(UpdatePolicy.isUpdateAvailable(installed, info.version))

        val download = UpdatePolicy.preferredDownload(info.downloads)
        assertNotNull(download)
        assertEquals("app-universal-release.apk", download!!.name)
        // The URL must belong to the NEW release, never the old one with the same file name.
        assertEquals("https://example.com/new/app-universal-release.apk", download.url)
        assertFalse(download.url.contains("/old/"))
    }

    @Test
    fun `no update is reported while the installed version is the newest`() = runBlocking {
        responseBody = "[${release("v2.5.5.87", "2026-09-28T09:00:00Z", "https://example.com/u.apk")}]"

        val info = checker().checkForUpdate()
        assertNotNull(info)
        assertEquals("2.5.5.87", info!!.version)
        assertFalse(
            "the installed version must not be offered as an update",
            UpdatePolicy.isUpdateAvailable("2.5.5.87", info.version),
        )
    }

    @Test
    fun `detection keeps working after a previously failed release job`() = runBlocking {
        // The CI failed, so 2.5.5 was never published; later a retried run publishes it.
        responseBody = "[${release("v2.5.4.86", "2026-09-27T11:50:21Z", "https://example.com/old/u.apk")}]"
        val before = checker().checkForUpdate()
        assertEquals("2.5.4.86", before!!.version)
        assertFalse(UpdatePolicy.isUpdateAvailable("2.5.4.86", before.version))

        // ... workflow retried and published the new version.
        responseBody = "[${release("v2.5.4.86", "2026-09-27T11:50:21Z", "https://example.com/old/u.apk")}," +
            "${release("v2.5.5.95", "2026-10-01T08:00:00Z", "https://example.com/new/u.apk")}]"
        val after = checker().checkForUpdate()
        assertEquals("2.5.5.95", after!!.version)
        assertTrue(UpdatePolicy.isUpdateAvailable("2.5.4.86", after.version))
    }

    @Test
    fun `api failure never produces a fake update`() = runBlocking {
        responseCode = 500
        responseBody = """{"message":"Internal Server Error"}"""

        val info = checker().checkForUpdate()
        assertNull("a failed check must not report any update", info)
    }

    @Test
    fun `drafts and prereleases are never offered`() = runBlocking {
        responseBody = """
        [
          {"tag_name":"v2.6.0.99","name":"draft","body":"","published_at":"2026-10-02T00:00:00Z","draft":true,"prerelease":false,"assets":[${apkAsset("https://example.com/draft/u.apk")}]},
          {"tag_name":"v2.6.0.98","name":"rc","body":"","published_at":"2026-10-02T00:00:00Z","draft":false,"prerelease":true,"assets":[${apkAsset("https://example.com/rc/u.apk")}]},
          {"tag_name":"v2.5.5.87","name":"stable","body":"","published_at":"2026-09-28T09:00:00Z","draft":false,"prerelease":false,"assets":[${apkAsset("https://example.com/stable/u.apk")}]}
        ]
        """.trimIndent()

        val info = checker().checkForUpdate()
        assertEquals("2.5.5.87", info!!.version)
    }

    @Test
    fun `the checker really performs the HTTP request`() = runBlocking {
        responseBody = "[${release("v2.5.5.87", "2026-09-28T09:00:00Z", "https://example.com/u.apk")}]"
        checker().checkForUpdate()
        assertTrue("the update check must hit the network endpoint", requestCount > 0)
    }
}
