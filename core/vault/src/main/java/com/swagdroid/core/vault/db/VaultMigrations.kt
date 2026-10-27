package com.swagdroid.core.vault.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: old operation JSON and IDs are preserved, never silently reclassified as verified. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_snapshots ADD COLUMN journalVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vault_snapshots ADD COLUMN userId INTEGER")
        db.execSQL("ALTER TABLE vault_snapshots ADD COLUMN bootId TEXT")
        db.execSQL("ALTER TABLE vault_snapshots ADD COLUMN stepsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE vault_snapshots ADD COLUMN lastError TEXT")
    }
}
