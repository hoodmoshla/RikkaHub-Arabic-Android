package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression check on the MCP SDK artifact itself, kept free of any Android/OkHttp/Ktor engine so
 * that it is valid in a plain JVM unit test (constructing an OkHttp client in a unit test pulls
 * okhttp-android, whose platform detection calls android.util.Log and fails with "Stub!").
 *
 * `Client(...)` itself is deliberately NOT constructed here: the SDK initialises an
 * Android-dependent logger in its class initializer, which cannot run in a plain JVM unit test
 * (it fails with ExceptionInInitializerError while working fine on device). The artifact was
 * verified with a complete classpath in a standalone JVM run; what stays checked here is that the
 * classes the app loads are present and initialise.
 *
 * The Arabic release that crashed on launch switched this dependency to
 * `com.github.rikkahub.mcp-kotlin-sdk:...:0.15.0-rikka.2`; initialising and constructing the SDK
 * entry points the app uses fails here instead of at runtime if the artifact is broken.
 */
class McpSdkArtifactTest {

    @Test
    fun `the sdk implementation type keeps its values`() {
        val implementation = Implementation(name = "probe", version = "1.0")
        assertEquals("probe", implementation.name)
        assertEquals("1.0", implementation.version)
    }

    @Test
    fun `the transport classes used by the app are present and initialize`() {
        // Class.forName initialises the class, which is where a broken artifact fails.
        assertNotNull(Class.forName("io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport"))
        assertNotNull(Class.forName("io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport"))
        assertNotNull(Class.forName("io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport"))
    }
}
