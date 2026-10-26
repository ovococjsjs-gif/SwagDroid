package com.swagdroid.core.vault

import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultExportBundle
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VaultMutationSpec {
    data class MutateSetting(
        val namespace: SettingNamespace,
        val key: String,
        val targetValue: String?,
    ) : VaultMutationSpec

    data class MutatePackageState(
        val packageName: String,
        val targetState: PackageStateTarget,
    ) : VaultMutationSpec

    data class MutateAppOp(
        val packageName: String,
        val opName: String,
        val targetMode: String,
    ) : VaultMutationSpec

    data class MutateCustomShell(
        val applyCommand: String,
        val revertCommand: String,
        val summaryRu: String,
    ) : VaultMutationSpec
}

sealed interface VaultTransactionResult {
    data class Success(val snapshotId: Long, val appliedCount: Int) : VaultTransactionResult
    data class Failure(val errorMessage: String, val autoReverted: Boolean) : VaultTransactionResult
}

@Singleton
class StateVaultManager @Inject constructor(
    private val store: VaultStore,
    private val executor: PrivilegedCommandExecutor,
    private val json: Json,
) {
    val snapshotsFlow: Flow<List<VaultSnapshot>> = store.observeSnapshots()

    /**
     * Captures the exact live before-state, persists a restore point in Room BEFORE touching
     * the system, executes all mutations, and unwinds in LIFO order if any step fails.
     */
    suspend fun applyTransaction(
        title: String,
        reason: String,
        source: FeatureSource,
        mutations: List<VaultMutationSpec>,
    ): VaultTransactionResult {
        if (mutations.isEmpty()) {
            return VaultTransactionResult.Failure("Список операций пуст", autoReverted = true)
        }

        // Phase 1: Capture exact current state before mutating anything
        val operations = ArrayList<VaultOperation>(mutations.size)
        for (spec in mutations) {
            val op = when (spec) {
                is VaultMutationSpec.MutateSetting -> {
                    val prev = executor.readSetting(spec.namespace, spec.key)
                    VaultOperation.SettingChange(
                        namespace = spec.namespace,
                        key = spec.key,
                        previousValue = prev,
                        newValue = spec.targetValue,
                    )
                }
                is VaultMutationSpec.MutatePackageState -> {
                    val prev = executor.readPackageState(spec.packageName)
                    VaultOperation.PackageStateChange(
                        packageName = spec.packageName,
                        previousState = prev,
                        newState = spec.targetState,
                    )
                }
                is VaultMutationSpec.MutateAppOp -> {
                    val prev = executor.readAppOpMode(spec.packageName, spec.opName)
                    VaultOperation.AppOpChange(
                        packageName = spec.packageName,
                        opName = spec.opName,
                        previousMode = prev,
                        newMode = spec.targetMode,
                    )
                }
                is VaultMutationSpec.MutateCustomShell -> {
                    VaultOperation.CustomShellChange(
                        applyCommand = spec.applyCommand,
                        revertCommand = spec.revertCommand,
                        summaryRu = spec.summaryRu,
                    )
                }
            }
            operations.add(op)
        }

        // Phase 2: Persist restore point atomically BEFORE executing mutations
        val now = System.currentTimeMillis()
        val snapshot = VaultSnapshot(
            title = title,
            reason = reason,
            source = source,
            createdAtEpochMs = now,
            privilegeTierUsed = executor.currentTier(),
            status = SnapshotStatus.ACTIVE,
            operations = operations,
        )
        val snapshotId = store.saveSnapshot(snapshot)

        // Phase 3: Apply forward operations; auto-unwind in reverse if any command fails
        val appliedSoFar = ArrayList<VaultOperation>()
        for (op in operations) {
            val res = executeForwardOperation(op)
            if (!res.isSuccess) {
                var allReverted = true
                for (doneOp in appliedSoFar.asReversed()) {
                    if (!executeInverseOperation(doneOp).isSuccess) {
                        allReverted = false
                    }
                }
                store.updateSnapshotStatus(
                    id = snapshotId,
                    status = if (allReverted) SnapshotStatus.ROLLED_BACK else SnapshotStatus.FAILED,
                    rolledBackAtEpochMs = System.currentTimeMillis(),
                )
                val errText = res.stderrText.ifEmpty { "Код возврата ${res.exitCode} (проверьте уровень прав ADB/Root)" }
                return VaultTransactionResult.Failure(
                    errorMessage = errText,
                    autoReverted = allReverted,
                )
            }
            appliedSoFar.add(op)
        }

        return VaultTransactionResult.Success(
            snapshotId = snapshotId,
            appliedCount = appliedSoFar.size,
        )
    }

    /**
     * Captures a read-only baseline checkpoint of key system parameters so the user can always
     * restore their original baseline settings in 1 tap.
     */
    suspend fun createBaselineCheckpoint(): Long {
        val baselineKeys = listOf(
            SettingNamespace.GLOBAL to "window_animation_scale",
            SettingNamespace.GLOBAL to "transition_animation_scale",
            SettingNamespace.GLOBAL to "animator_duration_scale",
            SettingNamespace.GLOBAL to "private_dns_mode",
            SettingNamespace.GLOBAL to "private_dns_specifier",
            SettingNamespace.GLOBAL to "captive_portal_https_url",
            SettingNamespace.GLOBAL to "wifi_scan_always_enabled",
            SettingNamespace.GLOBAL to "ble_scan_always_enabled",
        )

        val capturedOps = baselineKeys.map { (ns, key) ->
            val currentVal = executor.readSetting(ns, key)
            VaultOperation.SettingChange(
                namespace = ns,
                key = key,
                previousValue = currentVal,
                newValue = currentVal,
                summaryRu = "${ns.cliName}:$key = ${currentVal ?: "по умолчанию"}",
            )
        }

        val snapshot = VaultSnapshot(
            title = "Базовая точка восстановления системы",
            reason = "Слепок исходных сетевых, системных и графических параметров устройства",
            source = FeatureSource.MANUAL,
            createdAtEpochMs = System.currentTimeMillis(),
            privilegeTierUsed = executor.currentTier(),
            status = SnapshotStatus.ACTIVE,
            operations = capturedOps,
        )
        return store.saveSnapshot(snapshot)
    }

    /**
     * Rolls back a single snapshot in reverse (LIFO) operation order.
     */
    suspend fun rollbackSnapshot(snapshotId: Long): VaultTransactionResult {
        val snapshot = store.getSnapshotById(snapshotId)
            ?: return VaultTransactionResult.Failure("Точка восстановления #$snapshotId не найдена", false)

        if (snapshot.status == SnapshotStatus.ROLLED_BACK) {
            return VaultTransactionResult.Success(snapshotId, 0)
        }

        val errors = ArrayList<String>()
        for (op in snapshot.operations.asReversed()) {
            val res = executeInverseOperation(op)
            if (!res.isSuccess) {
                errors.add("${op.summaryRu}: ${res.stderrText.ifEmpty { "exit=${res.exitCode}" }}")
            }
        }

        return if (errors.isEmpty()) {
            store.updateSnapshotStatus(
                id = snapshotId,
                status = SnapshotStatus.ROLLED_BACK,
                rolledBackAtEpochMs = System.currentTimeMillis(),
            )
            VaultTransactionResult.Success(snapshotId, snapshot.operations.size)
        } else {
            store.updateSnapshotStatus(
                id = snapshotId,
                status = SnapshotStatus.FAILED,
                rolledBackAtEpochMs = null,
            )
            VaultTransactionResult.Failure(
                errorMessage = errors.joinToString("; "),
                autoReverted = false,
            )
        }
    }

    /**
     * Emergency Safe Reset: rolls back ALL active snapshots newest-first (true LIFO).
     */
    suspend fun rollbackAllActive(): Int {
        val activeList = store.getActiveSnapshotsNewestFirst()
        var rolledBackCount = 0
        for (snapshot in activeList) {
            val res = rollbackSnapshot(snapshot.id)
            if (res is VaultTransactionResult.Success) {
                rolledBackCount++
            }
        }
        return rolledBackCount
    }

    suspend fun deleteSnapshotRecord(snapshotId: Long) {
        store.deleteSnapshot(snapshotId)
    }

    suspend fun exportSnapshotsJson(deviceModel: String): String {
        val active = store.getActiveSnapshotsNewestFirst()
        val bundle = VaultExportBundle(
            exportedAtEpochMs = System.currentTimeMillis(),
            deviceModel = deviceModel,
            snapshots = active,
        )
        return json.encodeToString(bundle)
    }

    private suspend fun executeForwardOperation(op: VaultOperation) = when (op) {
        is VaultOperation.SettingChange ->
            executor.writeSetting(op.namespace, op.key, op.newValue)
        is VaultOperation.PackageStateChange ->
            executor.writePackageState(op.packageName, op.newState)
        is VaultOperation.AppOpChange ->
            executor.writeAppOpMode(op.packageName, op.opName, op.newMode)
        is VaultOperation.CustomShellChange ->
            executor.execute(op.applyCommand)
    }

    private suspend fun executeInverseOperation(op: VaultOperation) = when (op) {
        is VaultOperation.SettingChange ->
            executor.writeSetting(op.namespace, op.key, op.previousValue)
        is VaultOperation.PackageStateChange ->
            executor.writePackageState(op.packageName, op.previousState)
        is VaultOperation.AppOpChange ->
            executor.writeAppOpMode(op.packageName, op.opName, op.previousMode)
        is VaultOperation.CustomShellChange ->
            executor.execute(op.revertCommand)
    }
}
