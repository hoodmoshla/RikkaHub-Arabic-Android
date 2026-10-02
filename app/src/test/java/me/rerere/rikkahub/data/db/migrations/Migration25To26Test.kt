package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the "Room cannot verify the data integrity" crash on update.
 *
 * Version 25 was released with three different schemas and the version was never bumped, so the
 * app must *converge* every installed v25 database on the v26 schema by adding only the columns
 * that are actually missing:
 *
 *  - Arabic fork v25 (897b): missing `workspaces.shell_compatibility_mode`
 *  - upstream v25 (049f):    missing `ConversationEntity.model_id` + `workspace_id`
 *  - merged v25 (ef68):      nothing missing
 */
class Migration25To26Test {

    @Test
    fun `adds the shell compatibility column to the arabic fork v25 schema`() {
        val executed = mutableListOf<String>()
        val db = fakeDatabase(
            mapOf(
                "workspaces" to setOf("id", "name", "root", "shell_status", "created_at", "updated_at", "last_access_at", "tool_approvals"),
                "ConversationEntity" to setOf("id", "model_id", "workspace_id"),
            ),
            executed,
        )

        Migration_25_26.migrate(db)

        assertEquals(1, executed.size)
        assertTrue(executed[0].contains("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun `adds the conversation columns to the upstream v25 schema`() {
        val executed = mutableListOf<String>()
        val db = fakeDatabase(
            mapOf(
                "workspaces" to setOf("id", "shell_compatibility_mode"),
                "ConversationEntity" to setOf("id", "assistant_id", "title"),
            ),
            executed,
        )

        Migration_25_26.migrate(db)

        assertEquals(2, executed.size)
        assertTrue(executed.any { it.contains("ALTER TABLE `ConversationEntity` ADD COLUMN `model_id` TEXT NOT NULL DEFAULT ''") })
        assertTrue(executed.any { it.contains("ALTER TABLE `ConversationEntity` ADD COLUMN `workspace_id` TEXT NOT NULL DEFAULT ''") })
    }

    @Test
    fun `is a no-op for the already complete merged v25 schema`() {
        val executed = mutableListOf<String>()
        val db = fakeDatabase(
            mapOf(
                "workspaces" to setOf("id", "shell_compatibility_mode"),
                "ConversationEntity" to setOf("id", "model_id", "workspace_id"),
            ),
            executed,
        )

        Migration_25_26.migrate(db)

        assertTrue("expected no ALTER statements, got $executed", executed.isEmpty())
    }

    // --- minimal reflection-based fakes so the migration can run on the JVM ---

    private fun fakeDatabase(columnsByTable: Map<String, Set<String>>, executed: MutableList<String>): SupportSQLiteDatabase {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "query" -> newCursor(columnsByTable[tableOf(args?.get(0) as String)].orEmpty().toList())
                "execSQL" -> { executed.add(args!![0] as String); null }
                "beginTransaction", "setTransactionSuccessful", "endTransaction" -> null
                else -> defaultReturn(method)
            }
        }
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    private fun newCursor(columns: List<String>): Any {
        var index = -1
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> if (args?.get(0) == "name") 1 else -1
                "moveToNext" -> { index++; index < columns.size }
                "getString" -> columns[index]
                "close" -> null
                else -> defaultReturn(method)
            }
        }
        return Proxy.newProxyInstance(
            Class.forName("android.database.Cursor").classLoader,
            arrayOf(Class.forName("android.database.Cursor")),
            handler,
        )
    }

    private fun tableOf(sql: String): String = Regex("`([^`]+)`").find(sql)?.groupValues?.get(1).orEmpty()

    private fun defaultReturn(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> ' '
        else -> null
    }
}
