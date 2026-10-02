package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * Fixes the "Room cannot verify the data integrity ... Expected identity hash: ef68..., found:
 * 897b..." crash that appeared right after updating the app.
 *
 * Database version 25 was shipped with three *different* schemas and the version number was never
 * bumped:
 *  - the Arabic fork's build had `ConversationEntity.model_id` / `ConversationEntity.workspace_id`
 *    but not `workspaces.shell_compatibility_mode` (identity hash 897b...);
 *  - the official upstream build had `workspaces.shell_compatibility_mode` but not the two
 *    conversation columns (identity hash 049f...);
 *  - the merged build has all three (identity hash ef68...).
 *
 * Because Room only runs migrations when the stored version is lower than the requested one, an
 * already-installed v25 database was never migrated, so opening it failed the identity-hash check.
 *
 * Bumping to v26 and adding every missing column - guarded by `PRAGMA table_info` so the statement
 * is a no-op for schemas that already contain it - converges all three v25 databases on the v26
 * schema without losing any data.
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(25, 26)
        db.beginTransaction()
        try {
            db.addColumnIfMissing("ConversationEntity", "model_id", "TEXT NOT NULL DEFAULT ''")
            db.addColumnIfMissing("ConversationEntity", "workspace_id", "TEXT NOT NULL DEFAULT ''")
            db.addColumnIfMissing("workspaces", "shell_compatibility_mode", "INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
