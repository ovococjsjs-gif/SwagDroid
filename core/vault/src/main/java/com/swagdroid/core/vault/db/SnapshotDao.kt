package com.swagdroid.core.vault.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {
    // IDs are the transaction sequence; wall-clock adjustments must not change undo order.
    @Query("SELECT * FROM vault_snapshots ORDER BY id DESC")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM vault_snapshots ORDER BY id DESC")
    suspend fun getAllNewestFirst(): List<SnapshotEntity>

    @Query("SELECT * FROM vault_snapshots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SnapshotEntity?

    @Insert
    suspend fun insert(entity: SnapshotEntity): Long

    @Update
    suspend fun update(entity: SnapshotEntity): Int

    @Query("DELETE FROM vault_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)
}
