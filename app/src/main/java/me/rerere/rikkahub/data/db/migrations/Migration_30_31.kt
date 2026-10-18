package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds explicit per-conversation overrides without dropping upstream's config snapshot. */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("ConversationEntity", "model_id", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("ConversationEntity", "workspace_id", "TEXT NOT NULL DEFAULT ''")
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
        while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return true
    }
    return false
}
