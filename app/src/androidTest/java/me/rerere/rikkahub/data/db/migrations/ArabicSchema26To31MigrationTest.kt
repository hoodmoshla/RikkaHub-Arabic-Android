package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArabicSchema26To31MigrationTest {
    private val testDb = "arabic-schema-26-to-31"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(Migration_28_29()),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesConversationOverridesMemoryAndMessages() {
        val conversationId = "conversation-26"
        val modelId = "model-override-26"
        val workspaceId = "workspace-override-26"
        helper.createDatabase(testDb, 26).apply {
            execSQL(
                """INSERT INTO ConversationEntity (
                    id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned,
                    custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd,
                    model_id, workspace_id, folder_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(conversationId, "assistant-1", "title", "[]", 10L, 20L, "[]", 0,
                    "", "[]", "[]", "", modelId, workspaceId, ""),
            )
            execSQL(
                "INSERT INTO MemoryEntity (id, assistant_id, content) VALUES (?, ?, ?)",
                arrayOf<Any?>("memory-1", "assistant-1", "keep this memory"),
            )
            execSQL(
                """INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
                    VALUES (?, ?, ?, ?, ?)""",
                arrayOf<Any?>("node-1", conversationId, 0, "[]", 0),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDb,
            31,
            true,
            Migration_26_27,
            Migration_27_28,
            Migration_29_30,
            Migration_30_31,
        )
        db.query(
            "SELECT model_id, workspace_id, config FROM ConversationEntity WHERE id = ?",
            arrayOf<Any?>(conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(modelId, cursor.getString(0))
            assertEquals(workspaceId, cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        db.query("SELECT path, content FROM memory_file WHERE assistant_id = ?", arrayOf<Any?>("assistant-1")).use { cursor ->
            var foundMemory = false
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == "/profile.md") {
                    assertTrue(cursor.getString(1).contains("keep this memory"))
                    foundMemory = true
                }
            }
            assertTrue("legacy memory should be migrated into the profile file", foundMemory)
        }
        db.query("SELECT messages FROM message_node WHERE id = ?", arrayOf<Any?>("node-1")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.close()
    }
}
