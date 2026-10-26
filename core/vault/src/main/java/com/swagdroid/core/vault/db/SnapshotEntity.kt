package com.swagdroid.core.vault.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "vault_snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val reason: String,
    val sourceName: String,
    val createdAtEpochMs: Long,
    val rolledBackAtEpochMs: Long?,
    val privilegeTierName: String,
    val statusName: String,
    val operationsJson: String,
) {
    fun toDomain(json: Json): VaultSnapshot {
        val decodedOps = runCatching {
            json.decodeFromString<List<VaultOperation>>(operationsJson)
        }.getOrDefault(emptyList())

        val source = runCatching { FeatureSource.valueOf(sourceName) }
            .getOrDefault(FeatureSource.MANUAL)
        val tier = runCatching { PrivilegeTier.valueOf(privilegeTierName) }
            .getOrDefault(PrivilegeTier.BASIC)
        val status = runCatching { SnapshotStatus.valueOf(statusName) }
            .getOrDefault(SnapshotStatus.ACTIVE)

        return VaultSnapshot(
            id = id,
            title = title,
            reason = reason,
            source = source,
            createdAtEpochMs = createdAtEpochMs,
            rolledBackAtEpochMs = rolledBackAtEpochMs,
            privilegeTierUsed = tier,
            status = status,
            operations = decodedOps,
        )
    }

    companion object {
        fun fromDomain(snapshot: VaultSnapshot, json: Json): SnapshotEntity = SnapshotEntity(
            id = snapshot.id,
            title = snapshot.title,
            reason = snapshot.reason,
            sourceName = snapshot.source.name,
            createdAtEpochMs = snapshot.createdAtEpochMs,
            rolledBackAtEpochMs = snapshot.rolledBackAtEpochMs,
            privilegeTierName = snapshot.privilegeTierUsed.name,
            statusName = snapshot.status.name,
            operationsJson = json.encodeToString(snapshot.operations),
        )
    }
}
