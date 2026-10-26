package com.swagdroid.core.vault.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SwagVaultDatabase : RoomDatabase() {
    abstract fun snapshotDao(): SnapshotDao
}
