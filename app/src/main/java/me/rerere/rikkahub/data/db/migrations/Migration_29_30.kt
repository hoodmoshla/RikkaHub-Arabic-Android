package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds translation history as in the published upstream schema 30. */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `translation_history` (
                `id` TEXT NOT NULL,
                `source_text` TEXT NOT NULL,
                `translated_text` TEXT NOT NULL,
                `target_language` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)")
    }
}
