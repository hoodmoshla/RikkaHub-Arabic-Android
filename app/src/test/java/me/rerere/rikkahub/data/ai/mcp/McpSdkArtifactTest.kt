package me.rerere.rikkahub.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Direct experiment on the MCP SDK artifact itself (no Android, no R8).
 *
 * The Arabic release that crashed on launch was built with the MCP SDK switched to the
 * `com.github.rikkahub.mcp-kotlin-sdk:...:0.15.0-rikka.2` JitPack artifact, and that was the only
 * dependency change compared to the previous (working) release. This test performs exactly the
 * constructions `McpSessionRegistry` performs (`createSdkClient` / `createTransport`) with the very
 * same Ktor client `McpManager` builds. If the artifact is broken (class initialization failure,
 * missing/renamed member, serialization setup problem), it fails **here** with the real exception
 * instead of at app startup inside the Koin dependency graph.
 */
class McpSdkArtifactTest {

    private fun appHttpClient() = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; isLenient = true })
        }
        install(SSE)
    }

    @Test
    fun `the mcp sdk client used by the app can be created`() {
        val client = Client(clientInfo = Implementation(name = "RikkaHub Arabic test", version = "1.0"))
        assertNotNull(client)
    }

    @Test
    fun `the streamable http transport used by the app can be created`() {
        val transport = StreamableHttpClientTransport(
            url = "https://example.com/mcp",
            client = appHttpClient(),
            requestBuilder = {},
        )
        assertNotNull(transport)
    }

    @Test
    fun `the sse transport used by the app can be created`() {
        val transport = SseClientTransport(
            urlString = "https://example.com/sse",
            client = appHttpClient(),
            requestBuilder = {},
        )
        assertNotNull(transport)
    }
}
