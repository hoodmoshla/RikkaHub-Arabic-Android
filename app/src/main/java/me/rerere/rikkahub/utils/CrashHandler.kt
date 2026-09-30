package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 200_000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildCrashReport(thread, throwable).take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }
}

/**
 * Walks the `cause` chain down to the deepest throwable.
 *
 * Koin wraps dependency failures in an [org.koin.core.error.InstanceCreationException] whose
 * message only names the class that could not be created; the meaningful failure is always the
 * innermost cause.
 */
internal fun deepestCause(throwable: Throwable): Throwable {
    var current = throwable
    val seen = mutableSetOf<Throwable>()
    while (true) {
        val cause = current.cause ?: return current
        if (cause === current || !seen.add(cause)) return current
        current = cause
    }
}

/**
 * Builds the crash report with the **root cause first**.
 *
 * `Throwable.stackTraceToString()` prints the outermost exception first and the real cause last,
 * and a long Compose/Koin/navigation stack easily exceeds any length limit. Reporting the deepest
 * cause first guarantees the actual failure is never lost when the report is truncated.
 */
internal fun buildCrashReport(thread: Thread, throwable: Throwable): String = buildString {
    val root = deepestCause(throwable)
    appendLine("Thread: ${thread.name}")
    appendLine()
    appendLine("=== ROOT CAUSE ===")
    appendLine("${root.javaClass.name}: ${root.message}")
    appendLine(root.stackTraceToString())
    if (root !== throwable) {
        appendLine()
        appendLine("=== FULL CHAIN (outermost first) ===")
        appendLine(throwable.stackTraceToString())
    }
}
