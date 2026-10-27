package com.swagdroid.core.vault.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.model.VaultStepJournal
import com.swagdroid.core.model.VaultStepStatus
import com.swagdroid.core.model.isValidBootId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "vault_snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val reason: String,
    val sourceName: String,
    val createdAtEpochMs: Long,
    val rolledBackAtEpochMs: Long?,
    val privilegeTierName: String,
    val statusName: String,
    val operationsJson: String,
    @ColumnInfo(defaultValue = "0") val journalVersion: Int = 0,
    val userId: Int? = null,
    val bootId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val stepsJson: String = "[]",
    val lastError: String? = null,
) {
    fun toDomain(json: Json): VaultSnapshot {
        try {
            val operations = json.decodeFromString<List<VaultOperation>>(operationsJson)
            require(operations.isNotEmpty()) { "Пустой список операций не является точкой отката" }
            val source = FeatureSource.valueOf(sourceName)
            val tier = PrivilegeTier.valueOf(privilegeTierName)
            val storedStatus = SnapshotStatus.valueOf(statusName)
            val steps = json.decodeFromString<List<VaultStepJournal>>(stepsJson)
            require(journalVersion in 0..1) { "Неподдерживаемая версия журнала $journalVersion" }
            if (journalVersion == 1) {
                require(userId != null && userId >= 0) { "Не указан Android-пользователь" }
                require(isValidBootId(bootId) || (bootId == null && storedStatus == SnapshotStatus.CHECKPOINT)) { "Не указана загрузка устройства" }
                require(steps.map { it.index } == operations.indices.toList()) { "Неполный или повреждённый журнал шагов" }
                require(operations.none { it is VaultOperation.CustomShellChange }) { "Произвольная shell-операция не поддерживает проверяемый откат" }
                require(operations.filterIsInstance<VaultOperation.PackageStateChange>().all { it.previousDetails != null && it.newDetails != null }) {
                    "Отсутствует точный снимок состояния пакета"
                }
                require(operations.filterIsInstance<VaultOperation.PackageStateChange>().all {
                    val before = checkNotNull(it.previousDetails)
                    before.isConsistent && before.uid / 100_000 == userId && checkNotNull(it.newDetails).isConsistent && before.coarseState == it.previousState && before.forTarget(it.newState) == it.newDetails
                }) { "Противоречивые флаги или идентификаторы пакета" }
                if (storedStatus == SnapshotStatus.ACTIVE) require(steps.all { it.status == VaultStepStatus.APPLIED }) { "Применение не подтверждено для всех шагов" }
                if (storedStatus == SnapshotStatus.ROLLED_BACK) require(steps.all { it.status in setOf(VaultStepStatus.PLANNED, VaultStepStatus.REVERTED) }) { "Откат не подтвержден для всех шагов" }
            }
            val status = if (journalVersion == 0) SnapshotStatus.LEGACY_UNVERIFIED else storedStatus
            return VaultSnapshot(
                id, title, reason, source, createdAtEpochMs, rolledBackAtEpochMs, tier, status, operations,
                journalVersion, userId, bootId, steps,
                if (status == SnapshotStatus.LEGACY_UNVERIFIED) "Старая запись (статус $statusName) сохранена для просмотра/экспорта. Достоверность before-state и завершение команд не подтверждены; автоматический откат отключён." else lastError,
                if (status == SnapshotStatus.LEGACY_UNVERIFIED) "status=$statusName\noperations=$operationsJson\nsteps=$stepsJson" else null,
            )
        } catch (error: Exception) {
            return VaultSnapshot(
                id, title, reason, FeatureSource.MANUAL, createdAtEpochMs, rolledBackAtEpochMs,
                PrivilegeTier.BASIC, SnapshotStatus.CORRUPTED, emptyList(),
                journalVersion = journalVersion, userId = userId, bootId = bootId,
                lastError = "Нельзя автоматически восстановить запись: ${error.message}",
                unreadablePayload = "status=$statusName\nsource=$sourceName\ntier=$privilegeTierName\noperations=$operationsJson\nsteps=$stepsJson",
            )
        }
    }

    companion object {
        fun fromDomain(snapshot: VaultSnapshot, json: Json): SnapshotEntity {
            require(snapshot.journalVersion == 1 && snapshot.unreadablePayload == null && snapshot.status != SnapshotStatus.CORRUPTED && snapshot.status != SnapshotStatus.LEGACY_UNVERIFIED) {
                "Нельзя перезаписать исходные данные непроверенной записи"
            }
            return SnapshotEntity(
                id = snapshot.id,
                title = snapshot.title,
                reason = snapshot.reason,
                sourceName = snapshot.source.name,
                createdAtEpochMs = snapshot.createdAtEpochMs,
                rolledBackAtEpochMs = snapshot.rolledBackAtEpochMs,
                privilegeTierName = snapshot.privilegeTierUsed.name,
                statusName = snapshot.status.name,
                operationsJson = json.encodeToString(snapshot.operations),
                journalVersion = snapshot.journalVersion,
                userId = snapshot.userId,
                bootId = snapshot.bootId,
                stepsJson = json.encodeToString(snapshot.steps),
                lastError = snapshot.lastError,
            )
        }
    }
}
