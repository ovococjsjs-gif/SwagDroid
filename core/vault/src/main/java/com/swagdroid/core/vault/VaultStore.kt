package com.swagdroid.core.vault

import com.swagdroid.core.model.VaultSnapshot
import kotlinx.coroutines.flow.Flow

interface VaultStore {
    fun observeSnapshots(): Flow<List<VaultSnapshot>>
    suspend fun getSnapshotById(id: Long): VaultSnapshot?
    suspend fun getSnapshotsNewestFirst(): List<VaultSnapshot>
    suspend fun insertSnapshot(snapshot: VaultSnapshot): Long
    /** Atomic update of both status and the per-step journal, or throw before any next OS command. */
    suspend fun updateSnapshot(snapshot: VaultSnapshot)
    suspend fun deleteSnapshot(id: Long)
}
