package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash reports must always expose the real failure.
 *
 * Koin wraps dependency failures in `InstanceCreationException` ("Could not create instance for
 * 'X'") and the meaningful cause is the innermost one. The previous report kept only the first
 * 8_000 characters of `stackTraceToString()`, i.e. it truncated the tail where the root cause
 * lives, which is exactly why the ChatVM startup crash could not be diagnosed.
 */
class CrashHandlerReportTest {

    @Test
    fun `deepest cause walks the whole chain`() {
        val root = IllegalStateException("Room cannot verify the data integrity")
        val middle = RuntimeException("koin wrapper", root)
        val outer = RuntimeException("Could not create instance for ChatVM", middle)

        assertEquals(root, deepestCause(outer))
        assertEquals(root, deepestCause(root))
    }

    @Test
    fun `the report puts the root cause first`() {
        val root = IllegalStateException("the real failure")
        val outer = RuntimeException("Could not create instance for 'ChatVM'", root)

        val report = buildCrashReport(Thread.currentThread(), outer)

        val rootIndex = report.indexOf("=== ROOT CAUSE ===")
        val chainIndex = report.indexOf("=== FULL CHAIN")
        assertTrue("the root cause section must exist", rootIndex >= 0)
        assertTrue("the root cause must come before the outer chain", rootIndex < chainIndex)
        assertTrue(
            "the root cause message must be visible",
            report.substring(rootIndex, chainIndex).contains("the real failure"),
        )
        // The outer message is still kept for context.
        assertTrue(report.contains("Could not create instance for 'ChatVM'"))
    }

    @Test
    fun `a report without a nested cause still contains the exception`() {
        val report = buildCrashReport(Thread.currentThread(), IllegalArgumentException("boom"))
        assertTrue(report.contains("java.lang.IllegalArgumentException: boom"))
        // No duplicated chain section when there is nothing to unwrap.
        assertTrue(!report.contains("=== FULL CHAIN"))
    }

    @Test
    fun `a self referencing cause cannot loop forever`() {
        val loop = object : RuntimeException("self") {
            override val cause: Throwable get() = this
        }
        assertEquals(loop, deepestCause(loop))
    }

    @Test
    fun `the report is not truncated before the root cause`() {
        // A deep Compose/Koin style stack must not push the root cause out of the report.
        var throwable: Throwable = IllegalStateException("deep root cause")
        repeat(60) { index ->
            throwable = RuntimeException("layer $index", throwable)
        }
        val report = buildCrashReport(Thread.currentThread(), throwable)
        assertTrue(report.contains("deep root cause"))
        assertTrue(report.indexOf("=== ROOT CAUSE ===") < report.indexOf("=== FULL CHAIN"))
    }
}
