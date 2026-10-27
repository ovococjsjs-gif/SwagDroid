package com.swagdroid.core.vault

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageEnabledSetting
import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.StateReadResult
import com.swagdroid.core.model.VaultExportBundle
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.model.VaultStepJournal
import com.swagdroid.core.model.VaultStepStatus
import com.swagdroid.core.model.isDifferentBoot
import com.swagdroid.core.model.isValidBootId
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.privilege.SystemStateParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VaultMutationSpec {
    data class MutateSetting(val namespace: SettingNamespace, val key: String, val targetValue: String?) : VaultMutationSpec
    data class MutatePackageState(val packageName: String, val targetState: PackageStateTarget) : VaultMutationSpec
    data class MutateAppOp(val packageName: String, val opName: String, val targetMode: String) : VaultMutationSpec
    /** Retained so callers get an explicit refusal instead of silently losing a part of a preset. */
    data class MutateCustomShell(val applyCommand: String, val revertCommand: String, val summaryRu: String) : VaultMutationSpec
}

enum class VaultRecovery { NO_CHANGES, ORIGINAL_CONFIRMED, NEEDS_RECOVERY }

sealed interface VaultTransactionResult {
    /** Success means all supported postconditions were read back, not just exitCode == 0. */
    data class Success(val snapshotId: Long, val appliedCount: Int) : VaultTransactionResult
    data class Failure(
        val errorMessage: String,
        val recovery: VaultRecovery = VaultRecovery.NO_CHANGES,
        val snapshotId: Long? = null,
    ) : VaultTransactionResult
}

data class VaultBatchRollbackResult(
    val eligibleCount: Int,
    val restoredCount: Int,
    val failures: Map<Long, String>,
    val notAttemptedCount: Int,
    val unverifiedCount: Int,
)

@Singleton
class StateVaultManager @Inject constructor(
    private val store: VaultStore,
    private val executor: PrivilegedCommandExecutor,
    private val exporter: VaultExporter,
) {
    private val txMutex = Mutex()
    val snapshotsFlow: Flow<List<VaultSnapshot>> = store.observeSnapshots()

    suspend fun applyTransaction(
        title: String,
        reason: String,
        source: FeatureSource,
        mutations: List<VaultMutationSpec>,
    ): VaultTransactionResult = txMutex.withLock {
        var current: VaultSnapshot? = null
        try {
            val request = mutations.toList()
            if (request.isEmpty()) return@withLock noChanges("Список операций пуст")
            // Reject unsupported requests before dispatching even their first mutation.
            if (request.any { it is VaultMutationSpec.MutateCustomShell }) {
                return@withLock noChanges("Операция временно недоступна: произвольная shell-команда не имеет проверяемого исходного состояния и точного отката. Ничего не применено.")
            }
            if (request.filterIsInstance<VaultMutationSpec.MutateSetting>().any {
                    !SystemStateParsers.settingKeyPattern.matches(it.key) || !SystemStateParsers.canRoundTripSettingValue(it.targetValue)
                }) return@withLock noChanges("Неподдерживаемое имя или размер значения Settings. Ничего не применено.")
            if (request.filterIsInstance<VaultMutationSpec.MutateAppOp>().any { it.targetMode !in SystemStateParsers.appOpModes }) {
                return@withLock noChanges("Неподдерживаемый режим AppOps. Ничего не применено.")
            }
            if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) return@withLock noChanges("Для системных изменений нужны ADB / Shizuku или Root")
            executor.executionBlockReason()?.let { return@withLock noChanges(it) }
            val pending = store.getSnapshotsNewestFirst().firstOrNull {
                it.status == SnapshotStatus.CORRUPTED || (it.journalVersion == JOURNAL_VERSION &&
                    (it.status in setOf(SnapshotStatus.PREPARED, SnapshotStatus.APPLYING, SnapshotStatus.ROLLING_BACK, SnapshotStatus.RECOVERY_REQUIRED) ||
                        it.steps.any { step -> step.status in uncertainSteps }))
            }
            if (pending != null) return@withLock noChanges("Сначала проверьте незавершённую запись #${pending.id} в StateVault: ${pending.status.labelRu}")
            for (spec in request.filterIsInstance<VaultMutationSpec.MutatePackageState>()) {
                SystemBrickGuard.validatePackageMutation(spec.packageName, spec.targetState)?.let { return@withLock noChanges(it) }
            }
            val user = executor.currentUserId()
            val boot = executor.currentBootId() ?: return@withLock noChanges("Не удалось определить загрузку устройства; безопасное восстановление после сбоя недоступно")
            if (!isValidBootId(boot)) return@withLock noChanges("Нет подтверждённого идентификатора загрузки ядра")
            val operations = request.map { capture(it) }
            for (op in operations.filterIsInstance<VaultOperation.PackageStateChange>()) {
                val before = checkNotNull(op.previousDetails)
                val after = checkNotNull(op.newDetails)
                if (before != after) {
                    val inverseTarget = if (before.enabledSetting == PackageEnabledSetting.ENABLED && !before.suspended) PackageStateTarget.ENABLED else PackageStateTarget.DISABLED_USER
                    SystemBrickGuard.validatePackageMutation(op.packageName, inverseTarget)?.let {
                        return@withLock noChanges("Исходное состояние критического пакета нельзя безопасно вернуть. Нужно отдельное действие без обещания отката; ничего не применено.")
                    }
                    executor.validatePackageWrite(op.packageName, after).requireValue()
                    executor.validatePackageWrite(op.packageName, before).requireValue()
                }
            }
            if (operations.filterIsInstance<VaultOperation.SettingChange>().any { !SystemStateParsers.canRoundTripSettingValue(it.previousValue) }) {
                return@withLock noChanges("Исходное значение Settings нельзя точно вернуть этим транспортом (размер/NUL). Изменение не применено.")
            }
            if (operations.map(::resourceKey).distinct().size != operations.size) return@withLock noChanges("Один запрос меняет один ресурс несколько раз. Сначала объедините изменения в однозначный план.")
            val prepared = VaultSnapshot(
                title = title, reason = reason, source = source, createdAtEpochMs = System.currentTimeMillis(),
                privilegeTierUsed = executor.currentTier(), status = SnapshotStatus.PREPARED, operations = operations,
                journalVersion = JOURNAL_VERSION, userId = user, bootId = boot,
                steps = operations.indices.map { VaultStepJournal(it) },
            )
            val id = store.insertSnapshot(prepared)
            current = prepared.copy(id = id)

            for ((index, operation) in operations.withIndex()) {
                val startedFrom = checkNotNull(current)
                if (executor.currentUserId() != user || executor.currentBootId() != boot) {
                    return@withLock recoverAfterFailure(startedFrom, "Изменился пользователь или сеанс загрузки устройства")
                }
                val before = observe(operation)
                if (before !is Observation.Known || before.value != beforeValue(operation)) {
                    return@withLock recoverAfterFailure(startedFrom, "Исходное состояние изменилось или недоступно: ${operation.summaryRu}. ${before.errorText}")
                }
                if (beforeValue(operation) == afterValue(operation)) {
                    current = step(startedFrom, index, VaultStepStatus.APPLIED, SnapshotStatus.APPLYING)
                    store.updateSnapshot(current)
                    continue // Desired state already confirmed; do not force DEFAULT -> ENABLED etc.
                }
                current = step(startedFrom, index, VaultStepStatus.STARTED, SnapshotStatus.APPLYING)
                store.updateSnapshot(current) // Durable write-ahead journal; failure here must prevent dispatch.
                val result = forward(operation)
                if (result.completion == CommandCompletion.UNKNOWN) {
                    current = step(current, index, VaultStepStatus.OUTCOME_UNKNOWN, SnapshotStatus.RECOVERY_REQUIRED, result.stderrText)
                    store.updateSnapshot(current)
                    return@withLock needsRecovery(current, "Результат команды неизвестен. Автоматический откат не запускался. ${result.stderrText}")
                }
                val after = observe(operation)
                if (!result.isSuccess || after !is Observation.Known || after.value != afterValue(operation)) {
                    val error = result.stderrText.ifBlank { "Изменение не подтверждено чтением (${result.exitCode}). ${after.errorText}" }
                    current = step(current, index, VaultStepStatus.APPLY_FAILED, SnapshotStatus.APPLYING, error)
                    store.updateSnapshot(current)
                    // Includes the FAILED step: it may already have partially changed the resource.
                    return@withLock recoverAfterFailure(current, error)
                }
                current = step(current, index, VaultStepStatus.APPLIED, SnapshotStatus.APPLYING)
                store.updateSnapshot(current)
            }
            current = current.copy(status = SnapshotStatus.ACTIVE, lastError = null)
            store.updateSnapshot(current)
            VaultTransactionResult.Success(current.id, operations.count { beforeValue(it) != afterValue(it) })
        } catch (cancelled: CancellationException) {
            current?.let { markInterrupted(it, "Применение прервано. Автоматический откат не выполнялся.") }
            throw cancelled
        } catch (error: Exception) {
            if (current == null) noChanges("Не удалось подготовить изменение: ${error.message}") else {
                markInterrupted(current, "Ошибка выполнения/журнала: ${error.message}")
                needsRecovery(current, "Не удалось завершить изменение или записать его результат: ${error.message}. Дальнейшие команды не запускались.")
            }
        }
    }

    /** Nine readable Settings keys only. A reference capture is NOT an automatic undo or full backup. */
    suspend fun createBaselineCheckpoint(): VaultTransactionResult = txMutex.withLock {
        try {
            val keys = listOf(
                SettingNamespace.GLOBAL to "window_animation_scale",
                SettingNamespace.GLOBAL to "transition_animation_scale",
                SettingNamespace.GLOBAL to "animator_duration_scale",
                SettingNamespace.GLOBAL to "private_dns_mode",
                SettingNamespace.GLOBAL to "private_dns_specifier",
                SettingNamespace.GLOBAL to "captive_portal_https_url",
                SettingNamespace.GLOBAL to "wifi_scan_always_enabled",
                SettingNamespace.GLOBAL to "ble_scan_always_enabled",
                SettingNamespace.SECURE to "icon_blacklist",
            )
            val ops = keys.map { (namespace, key) ->
                val value = executor.readSettingState(namespace, key).valueOrNullIfMissing()
                VaultOperation.SettingChange(namespace, key, value, value)
            }
            val id = store.insertSnapshot(VaultSnapshot(
                title = "Справочный снимок 9 параметров", reason = "Только чтение. Не является полным бэкапом или автоматическим откатом.",
                source = FeatureSource.MANUAL, createdAtEpochMs = System.currentTimeMillis(), privilegeTierUsed = executor.currentTier(),
                status = SnapshotStatus.CHECKPOINT, operations = ops, journalVersion = JOURNAL_VERSION,
                userId = executor.currentUserId(), bootId = executor.currentBootId(), steps = ops.indices.map { VaultStepJournal(it) },
            ))
            VaultTransactionResult.Success(id, 0)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { noChanges("Снимок не создан: ${error.message}") }
    }

    suspend fun rollbackSnapshot(snapshotId: Long): VaultTransactionResult = txMutex.withLock {
        try {
            val snapshot = store.getSnapshotById(snapshotId) ?: return@withLock noChanges("Запись #$snapshotId не найдена")
            rollbackLocked(snapshot)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { VaultTransactionResult.Failure("Не удалось прочитать журнал: ${error.message}", VaultRecovery.NEEDS_RECOVERY, snapshotId) }
    }

    /** Includes FAILED/interrupted records, stops on the first failure, never claims a partial batch is complete. */
    suspend fun rollbackAllActive(): VaultBatchRollbackResult = txMutex.withLock {
        val all = store.getSnapshotsNewestFirst()
        val eligible = all.filter { it.journalVersion == JOURNAL_VERSION && it.status.canAttemptRollback }
        val failures = linkedMapOf<Long, String>()
        var restored = 0
        for (snapshot in eligible) {
            val result = rollbackLocked(snapshot)
            if (result is VaultTransactionResult.Failure) {
                failures[snapshot.id] = result.errorMessage
                break
            }
            restored++
        }
        VaultBatchRollbackResult(
            eligible.size, restored, failures, eligible.size - restored - failures.size,
            all.count { it.status in setOf(SnapshotStatus.CORRUPTED, SnapshotStatus.LEGACY_UNVERIFIED) },
        )
    }

    suspend fun deleteSnapshotRecord(snapshotId: Long) = txMutex.withLock {
        val snapshot = store.getSnapshotById(snapshotId) ?: return@withLock
        check(snapshot.status in setOf(SnapshotStatus.ROLLED_BACK, SnapshotStatus.CHECKPOINT)) { "Нельзя удалять журнал активного или непроверенного изменения" }
        store.deleteSnapshot(snapshotId)
    }

    suspend fun exportSnapshotsJson(deviceModel: String): String = txMutex.withLock {
        exporter.export(VaultExportBundle(exportedAtEpochMs = System.currentTimeMillis(), deviceModel = deviceModel, snapshots = store.getSnapshotsNewestFirst()))
    }

    private suspend fun recoverAfterFailure(snapshot: VaultSnapshot, error: String): VaultTransactionResult.Failure {
        if (snapshot.steps.all { it.status == VaultStepStatus.PLANNED }) {
            store.updateSnapshot(snapshot.copy(status = SnapshotStatus.ROLLED_BACK, rolledBackAtEpochMs = System.currentTimeMillis(), lastError = error))
            return VaultTransactionResult.Failure(error, VaultRecovery.NO_CHANGES, snapshot.id)
        }
        val recovery = rollbackLocked(snapshot)
        return if (recovery is VaultTransactionResult.Success) {
            VaultTransactionResult.Failure(
                "$error · Все выполненные изменения проверены: исходные значения восстановлены или уже совпадают.",
                VaultRecovery.ORIGINAL_CONFIRMED, snapshot.id,
            )
        } else {
            val failed = recovery as VaultTransactionResult.Failure
            VaultTransactionResult.Failure("$error · Восстановление не завершено: ${failed.errorMessage}", VaultRecovery.NEEDS_RECOVERY, snapshot.id)
        }
    }

    private suspend fun rollbackLocked(snapshot: VaultSnapshot): VaultTransactionResult {
        var current = snapshot
        try {
            if (current.journalVersion != JOURNAL_VERSION || current.status in setOf(SnapshotStatus.CORRUPTED, SnapshotStatus.LEGACY_UNVERIFIED, SnapshotStatus.CHECKPOINT) ||
                current.operations.isEmpty() || current.steps.map { it.index } != current.operations.indices.toList() ||
                current.operations.any { it is VaultOperation.CustomShellChange }
            ) return needsRecovery(current, "Эта запись не содержит проверяемого журнала. Автоматический откат запрещён; доступен экспорт.")
            if (current.userId != executor.currentUserId()) return needsRecovery(current, "Запись принадлежит другому Android-пользователю")
            if (current.status == SnapshotStatus.ROLLED_BACK) return VaultTransactionResult.Success(current.id, 0)
            executor.executionBlockReason()?.let { return needsRecovery(current, it) }
            if (current.steps.any { it.status in uncertainSteps } && !isDifferentBoot(current.bootId, executor.currentBootId())) {
                return needsRecovery(current, "Нельзя подтвердить завершение прерванной команды. Перед восстановлением нужна подтверждённая перезагрузка устройства.")
            }
            val ownKeys = affectedKeys(current)
            val newer = store.getSnapshotsNewestFirst().firstOrNull { other ->
                other.id > current.id && other.status !in setOf(SnapshotStatus.ROLLED_BACK, SnapshotStatus.CHECKPOINT) &&
                    (other.status == SnapshotStatus.CORRUPTED || affectedKeys(other).any { it == "*" || it in ownKeys })
            }
            if (newer != null) return needsRecovery(current, "Конфликт с более новой записью #${newer.id}. Сначала восстановите её; старые значения не перезаписаны.")

            current = current.copy(status = SnapshotStatus.ROLLING_BACK, lastError = null)
            store.updateSnapshot(current)
            var checked = 0
            for (index in current.operations.indices.reversed()) {
                val journal = current.steps[index]
                if (journal.status == VaultStepStatus.PLANNED || journal.status == VaultStepStatus.REVERTED) continue
                val operation = current.operations[index]
                if (beforeValue(operation) == afterValue(operation)) {
                    current = step(current, index, VaultStepStatus.REVERTED, SnapshotStatus.ROLLING_BACK)
                    store.updateSnapshot(current)
                    continue // No-op at apply time owns no external state to restore.
                }
                val observed = observe(operation)
                if (observed is Observation.Known && observed.value == beforeValue(operation)) {
                    current = step(current, index, VaultStepStatus.REVERTED, SnapshotStatus.ROLLING_BACK)
                    store.updateSnapshot(current)
                    checked++
                    continue
                }
                val intermediate = observed is Observation.Known && journal.status in setOf(VaultStepStatus.APPLY_FAILED, VaultStepStatus.STARTED, VaultStepStatus.OUTCOME_UNKNOWN, VaultStepStatus.REVERTING, VaultStepStatus.REVERT_FAILED) &&
                    isPackageIntermediate(operation, observed.value)
                if (observed !is Observation.Known || (observed.value != afterValue(operation) && !intermediate)) {
                    val error = if (observed is Observation.Known) "Конфликт: ${operation.summaryRu} изменён вне этой операции" else "Нет достоверного состояния: ${observed.errorText}"
                    current = step(current, index, VaultStepStatus.REVERT_FAILED, SnapshotStatus.FAILED, error)
                    store.updateSnapshot(current)
                    return needsRecovery(current, "$error. Значение не перезаписано.")
                }
                current = step(current, index, VaultStepStatus.REVERTING, SnapshotStatus.ROLLING_BACK)
                store.updateSnapshot(current)
                val result = inverse(operation)
                if (result.completion == CommandCompletion.UNKNOWN) {
                    current = step(current, index, VaultStepStatus.OUTCOME_UNKNOWN, SnapshotStatus.RECOVERY_REQUIRED, result.stderrText)
                    store.updateSnapshot(current)
                    return needsRecovery(current, "Завершение команды отката не подтверждено. ${result.stderrText}")
                }
                val restored = observe(operation)
                if (!result.isSuccess || restored !is Observation.Known || restored.value != beforeValue(operation)) {
                    val error = "Исходное состояние не подтверждено: ${operation.summaryRu}. ${result.stderrText} ${restored.errorText}"
                    current = step(current, index, VaultStepStatus.REVERT_FAILED, SnapshotStatus.FAILED, error)
                    store.updateSnapshot(current)
                    return needsRecovery(current, error)
                }
                current = step(current, index, VaultStepStatus.REVERTED, SnapshotStatus.ROLLING_BACK)
                store.updateSnapshot(current)
                checked++
            }
            current = current.copy(status = SnapshotStatus.ROLLED_BACK, rolledBackAtEpochMs = System.currentTimeMillis(), lastError = null)
            store.updateSnapshot(current)
            return VaultTransactionResult.Success(current.id, checked)
        } catch (cancelled: CancellationException) {
            markInterrupted(current, "Откат прерван. Автоматическое продолжение не выполняется.")
            throw cancelled
        } catch (error: Exception) {
            markInterrupted(current, "Ошибка восстановления/журнала: ${error.message}")
            return needsRecovery(current, "Не удалось завершить или записать откат: ${error.message}")
        }
    }

    private suspend fun markInterrupted(snapshot: VaultSnapshot, error: String) = withContext(NonCancellable) {
        // The last successful write-ahead record remains authoritative even if this best-effort write fails.
        runCatching {
            withTimeoutOrNull(2_000) {
                // Nested recovery may already have advanced the journal. Never overwrite it with
                // the outer apply loop's stale copy, especially after cancellation during undo.
                val latest = store.getSnapshotById(snapshot.id) ?: return@withTimeoutOrNull
                if (latest.status in setOf(SnapshotStatus.CORRUPTED, SnapshotStatus.LEGACY_UNVERIFIED) || latest.unreadablePayload != null) return@withTimeoutOrNull
                store.updateSnapshot(latest.copy(
                    status = SnapshotStatus.RECOVERY_REQUIRED, lastError = error,
                    steps = latest.steps.map { if (it.status in uncertainSteps) it.copy(status = VaultStepStatus.OUTCOME_UNKNOWN, error = error) else it },
                ))
            }
        }
        Unit
    }

    private suspend fun capture(spec: VaultMutationSpec): VaultOperation = when (spec) {
        is VaultMutationSpec.MutateSetting -> VaultOperation.SettingChange(spec.namespace, spec.key, executor.readSettingState(spec.namespace, spec.key).valueOrNullIfMissing(), spec.targetValue)
        is VaultMutationSpec.MutateAppOp -> VaultOperation.AppOpChange(spec.packageName, spec.opName, executor.readAppOpState(spec.packageName, spec.opName).requireValue(), spec.targetMode)
        is VaultMutationSpec.MutatePackageState -> {
            val before = executor.readPackageSnapshot(spec.packageName).requireValue()
            check(before.isConsistent) { "Противоречивый снимок пакета" }
            VaultOperation.PackageStateChange(spec.packageName, before.coarseState, spec.targetState, before, before.forTarget(spec.targetState))
        }
        is VaultMutationSpec.MutateCustomShell -> error("Произвольные shell-операции не поддерживаются")
    }

    private sealed interface Observation {
        data class Known(val value: Any?) : Observation
        data class Unavailable(val reason: String) : Observation
        val errorText: String get() = if (this is Unavailable) reason else ""
    }

    private suspend fun observe(operation: VaultOperation): Observation = try {
        val result: StateReadResult<*> = when (operation) {
            is VaultOperation.SettingChange -> executor.readSettingState(operation.namespace, operation.key)
            is VaultOperation.PackageStateChange -> executor.readPackageSnapshot(operation.packageName)
            is VaultOperation.AppOpChange -> executor.readAppOpState(operation.packageName, operation.opName)
            is VaultOperation.CustomShellChange -> StateReadResult.Unavailable("Shell-операция не проверяема")
        }
        when (result) {
            is StateReadResult.Value -> Observation.Known(result.value)
            StateReadResult.Missing -> if (operation is VaultOperation.SettingChange) Observation.Known(null) else Observation.Unavailable("Объект отсутствует")
            is StateReadResult.Unavailable -> Observation.Unavailable(result.reason)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Observation.Unavailable(error.message ?: "Ошибка чтения") }

    private suspend fun forward(op: VaultOperation) = when (op) {
        is VaultOperation.SettingChange -> executor.writeSetting(op.namespace, op.key, op.newValue)
        is VaultOperation.PackageStateChange -> executor.writePackageSnapshot(op.packageName, checkNotNull(op.newDetails))
        is VaultOperation.AppOpChange -> executor.writeAppOpMode(op.packageName, op.opName, op.newMode)
        is VaultOperation.CustomShellChange -> error("Shell-операция запрещена")
    }

    private suspend fun inverse(op: VaultOperation) = when (op) {
        is VaultOperation.SettingChange -> executor.writeSetting(op.namespace, op.key, op.previousValue)
        is VaultOperation.PackageStateChange -> {
            val previous = checkNotNull(op.previousDetails)
            val inverseTarget = if (previous.enabledSetting == PackageEnabledSetting.ENABLED && !previous.suspended) PackageStateTarget.ENABLED else PackageStateTarget.DISABLED_USER
            val denial = SystemBrickGuard.validatePackageMutation(op.packageName, inverseTarget)
            if (denial != null) ShellExecResult("", -1, emptyList(), listOf(denial), executor.currentTier(), CommandCompletion.NOT_STARTED)
            else executor.writePackageSnapshot(op.packageName, previous)
        }
        is VaultOperation.AppOpChange -> executor.writeAppOpMode(op.packageName, op.opName, op.previousMode)
        is VaultOperation.CustomShellChange -> error("Непроверяемый откат запрещён")
    }

    private fun beforeValue(op: VaultOperation): Any? = when (op) {
        is VaultOperation.SettingChange -> op.previousValue
        is VaultOperation.PackageStateChange -> checkNotNull(op.previousDetails)
        is VaultOperation.AppOpChange -> op.previousMode
        is VaultOperation.CustomShellChange -> error("Непроверяемое состояние")
    }
    private fun afterValue(op: VaultOperation): Any? = when (op) {
        is VaultOperation.SettingChange -> op.newValue
        is VaultOperation.PackageStateChange -> checkNotNull(op.newDetails)
        is VaultOperation.AppOpChange -> op.newMode
        is VaultOperation.CustomShellChange -> error("Непроверяемое состояние")
    }
    private fun resourceKey(op: VaultOperation): String = when (op) {
        is VaultOperation.SettingChange -> "setting:${op.namespace.cliName}:${op.key}"
        is VaultOperation.PackageStateChange -> "package:${op.packageName}"
        is VaultOperation.AppOpChange -> "appop:${op.packageName}:${op.opName}"
        is VaultOperation.CustomShellChange -> "*"
    }
    private fun affectedKeys(snapshot: VaultSnapshot): Set<String> = snapshot.operations.mapIndexedNotNull { index, operation ->
        if (snapshot.journalVersion == JOURNAL_VERSION && (snapshot.steps.getOrNull(index)?.status in setOf(VaultStepStatus.PLANNED, VaultStepStatus.REVERTED) || beforeValue(operation) == afterValue(operation))) null else resourceKey(operation)
    }.toSet()

    private fun isPackageIntermediate(op: VaultOperation, value: Any?): Boolean {
        if (op !is VaultOperation.PackageStateChange || value !is PackageStateSnapshot) return false
        val before = op.previousDetails ?: return false
        val after = op.newDetails ?: return false
        return value.uid == before.uid && value.lastUpdateTime == before.lastUpdateTime && value.isConsistent &&
            value.enabledSetting in setOf(before.enabledSetting, after.enabledSetting) &&
            value.suspended in setOf(before.suspended, after.suspended) && value.effectivelyEnabled in setOf(before.effectivelyEnabled, after.effectivelyEnabled)
    }
    private fun step(snapshot: VaultSnapshot, index: Int, status: VaultStepStatus, overall: SnapshotStatus, error: String? = null) = snapshot.copy(
        status = overall, lastError = error,
        steps = snapshot.steps.map { if (it.index == index) it.copy(status = status, error = error) else it },
    )
    private fun noChanges(message: String) = VaultTransactionResult.Failure(message, VaultRecovery.NO_CHANGES)
    private fun needsRecovery(snapshot: VaultSnapshot, message: String) = VaultTransactionResult.Failure("#${snapshot.id}: $message", VaultRecovery.NEEDS_RECOVERY, snapshot.id)

    companion object {
        const val JOURNAL_VERSION = 1
        private val uncertainSteps = setOf(VaultStepStatus.STARTED, VaultStepStatus.REVERTING, VaultStepStatus.OUTCOME_UNKNOWN)
    }
}
