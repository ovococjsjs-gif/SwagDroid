package com.swagdroid.core.vault

import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.vault.db.SnapshotDao
import com.swagdroid.core.vault.db.SnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface VaultStore {
    fun observeSnapshots(): Flow<List<VaultSnapshot>>
    suspend fun getSnapshotById(id: Long): VaultSnapshot?
    suspend fun getActiveSnapshotsNewestFirst(): List<VaultSnapshot>
    suspend fun saveSnapshot(snapshot: VaultSnapshot): Long
    suspend fun updateSnapshotStatus(id: Long, status: SnapshotStatus, rolledBackAtEpochMs: Long?)
    suspend fun deleteSnapshot(id: Long)
}

@Singleton
class RoomVaultStore @Inject constructor(
    private val dao: SnapshotDao,
    private val json: Json,
) : VaultStore {

    override fun observeSnapshots(): Flow<List<VaultSnapshot>> {
        return dao.observeAll().map { entities ->
            entities.map { it.toDomain(json) }
        }
    }

    override suspend fun getSnapshotById(id: Long): VaultSnapshot? {
        return dao.getById(id)?.toDomain(json)
    }

    override suspend fun getActiveSnapshotsNewestFirst(): List<VaultSnapshot> {
        return dao.getByStatus(SnapshotStatus.ACTIVE.name).map { it.toDomain(json) }
    }

    override suspend fun saveSnapshot(snapshot: VaultSnapshot): Long {
        return dao.insert(SnapshotEntity.fromDomain(snapshot, json))
    }

    override suspend fun updateSnapshotStatus(
        id: Long,
        status: SnapshotStatus,
        rolledBackAtEpochMs: Long?,
    ) {
        dao.updateStatus(id, status.name, rolledBackAtEpochMs)
    }

    override suspend fun deleteSnapshot(id: Long) {
        dao.deleteById(id)
    }
}
