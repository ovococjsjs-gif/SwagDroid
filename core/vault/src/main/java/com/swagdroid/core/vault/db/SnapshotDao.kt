package com.swagdroid.core.vault.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {

    @Query("SELECT * FROM vault_snapshots ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM vault_snapshots WHERE statusName = :status ORDER BY createdAtEpochMs DESC")
    suspend fun getByStatus(status: String): List<SnapshotEntity>

    @Query("SELECT * FROM vault_snapshots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SnapshotEntity): Long

    @Query(
        "UPDATE vault_snapshots SET statusName = :status, rolledBackAtEpochMs = :rolledBackAt WHERE id = :id"
    )
    suspend fun updateStatus(id: Long, status: String, rolledBackAt: Long?)

    @Query("DELETE FROM vault_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)
}
