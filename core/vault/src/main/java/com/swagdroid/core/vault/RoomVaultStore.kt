package com.swagdroid.core.vault

import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.vault.db.SnapshotDao
import com.swagdroid.core.vault.db.SnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomVaultStore @Inject constructor(
    private val dao: SnapshotDao,
    private val json: Json,
) : VaultStore {
    override fun observeSnapshots(): Flow<List<VaultSnapshot>> = dao.observeAll().map { list -> list.map { it.toDomain(json) } }
    override suspend fun getSnapshotById(id: Long): VaultSnapshot? = dao.getById(id)?.toDomain(json)
    override suspend fun getSnapshotsNewestFirst(): List<VaultSnapshot> = dao.getAllNewestFirst().map { it.toDomain(json) }
    override suspend fun insertSnapshot(snapshot: VaultSnapshot): Long = dao.insert(SnapshotEntity.fromDomain(snapshot, json))
    override suspend fun updateSnapshot(snapshot: VaultSnapshot) {
        check(dao.update(SnapshotEntity.fromDomain(snapshot, json)) == 1) { "Запись журнала #${snapshot.id} исчезла" }
    }
    override suspend fun deleteSnapshot(id: Long) { dao.deleteById(id) }
}
