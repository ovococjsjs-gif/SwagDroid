package com.swagdroid.core.rollback

import com.swagdroid.api.ISwagDroidService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Write-Ahead Журнал состояний и откатов (`RollbackJournal`).
 *
 * Реализует жизненный цикл `PREPARED -> APPLYING -> APPLIED -> VERIFIED` и проверку конфликтов
 * перед откатом (`detectConflictsBeforeRollback`), чтобы не затирать изменения, сделанные
 * пользователем или системой уже после применения фикса.
 */
class RollbackJournal(
    private val storageDir: File,
) {
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val journalFile: File
        get() = File(storageDir.apply { mkdirs() }, "rollback_journal_v1.json")

    private val _transactions = MutableStateFlow<List<RollbackTransaction>>(emptyList())
    val transactions: StateFlow<List<RollbackTransaction>> = _transactions.asStateFlow()

    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!journalFile.exists()) {
                _transactions.value = emptyList()
                return@withLock
            }
            runCatching {
                val raw = journalFile.readText()
                _transactions.value = json.decodeFromString<List<RollbackTransaction>>(raw)
            }.onFailure {
                _transactions.value = emptyList()
            }
        }
    }

    /**
     * Шаг 1 WAL: Записывает снимок `PREPARED` на диск ДО первого изменения системы.
     */
    suspend fun recordPreMutationSnapshot(
        featureId: String,
        titleRu: String,
        descriptionRu: String,
        operations: List<ReversibleSystemOp>,
        reversibility: ReversibilityType = ReversibilityType.EXACT_REVERSIBLE,
        initialState: WalEntryState = WalEntryState.APPLIED,
    ): RollbackTransaction = withContext(Dispatchers.IO) {
        require(operations.isNotEmpty()) { "RollbackTransaction must contain at least 1 operation" }
        mutex.withLock {
            val tx = RollbackTransaction(
                transactionId = UUID.randomUUID().toString(),
                featureId = featureId,
                titleRu = titleRu,
                descriptionRu = descriptionRu,
                createdAtEpochMs = System.currentTimeMillis(),
                operations = operations,
                reversibility = reversibility,
                walState = initialState,
            )
            val updated = listOf(tx) + _transactions.value
            persistLocked(updated)
            _transactions.value = updated
            tx
        }
    }

    /**
     * Обновляет статус записи в Write-Ahead журнале (`APPLYING` -> `APPLIED` -> `VERIFIED`).
     */
    suspend fun updateWalState(
        transactionId: String,
        newState: WalEntryState,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _transactions.value.map { tx ->
                if (tx.transactionId == transactionId) tx.copy(walState = newState) else tx
            }
            persistLocked(updated)
            _transactions.value = updated
        }
    }

    /**
     * Проверяет, не изменил ли пользователь или система настройку ПОСЛЕ нашего фикса.
     * Если текущее значение в системе не совпадает с `newValue`, которое мы записывали,
     * возвращает `ConflictDetected`, чтобы UI предупредил пользователя перед откатом.
     */
    suspend fun detectConflictsBeforeRollback(
        transactionId: String,
        service: ISwagDroidService,
    ): List<RollbackConflictCheck.ConflictDetected> = withContext(Dispatchers.IO) {
        val target = _transactions.value.firstOrNull { it.transactionId == transactionId }
            ?: return@withContext emptyList()

        val conflicts = mutableListOf<RollbackConflictCheck.ConflictDetected>()
        for (op in target.operations) {
            when (op) {
                is ReversibleSystemOp.SystemSettingChange -> {
                    val currentActual = service.getSystemSetting(op.namespace.wireName, op.key)
                    if (currentActual != op.newValue && !(currentActual.isNullOrEmpty() && op.newValue.isNullOrEmpty())) {
                        conflicts += RollbackConflictCheck.ConflictDetected(
                            opDescription = "${op.namespace.wireName}:${op.key}",
                            expectedValueAfterOurFix = op.newValue,
                            actualCurrentSystemValue = currentActual,
                            originalValueBeforeOurFix = op.previousValue,
                        )
                    }
                }

                is ReversibleSystemOp.AppOpChange -> {
                    val currentMode = service.getAppOpMode(op.opCode, op.uid, op.packageName)
                    if (currentMode != op.newMode) {
                        conflicts += RollbackConflictCheck.ConflictDetected(
                            opDescription = "AppOp(${op.opCode}) для ${op.packageName}",
                            expectedValueAfterOurFix = op.newMode.toString(),
                            actualCurrentSystemValue = currentMode.toString(),
                            originalValueBeforeOurFix = op.previousMode.toString(),
                        )
                    }
                }

                is ReversibleSystemOp.DeviceIdleWhitelistChange -> {
                    val currentlyWhitelisted = service.isDeviceIdleWhitelisted(op.packageName)
                    if (currentlyWhitelisted != op.whitelistedAfter) {
                        conflicts += RollbackConflictCheck.ConflictDetected(
                            opDescription = "Doze Whitelist (${op.packageName})",
                            expectedValueAfterOurFix = op.whitelistedAfter.toString(),
                            actualCurrentSystemValue = currentlyWhitelisted.toString(),
                            originalValueBeforeOurFix = op.wasWhitelistedBefore.toString(),
                        )
                    }
                }

                is ReversibleSystemOp.PackageStateChange -> Unit
            }
        }
        conflicts
    }

    /**
     * Откатывает транзакцию в обратном порядке (LIFO) с проверкой конфликтов (если `forceOverwriteConflicts == false`).
     */
    suspend fun rollbackTransaction(
        transactionId: String,
        service: ISwagDroidService,
        forceOverwriteConflicts: Boolean = false,
    ): Result<RollbackTransaction> = withContext(Dispatchers.IO) {
        if (!forceOverwriteConflicts) {
            val conflicts = detectConflictsBeforeRollback(transactionId, service)
            if (conflicts.isNotEmpty()) {
                val first = conflicts.first()
                return@withContext Result.failure(
                    IllegalStateException(
                        "Обнаружен конфликт перед откатом '${first.opDescription}': " +
                            "ожидалось '${first.expectedValueAfterOurFix}', но сейчас в системе '${first.actualCurrentSystemValue}'. " +
                            "Подтвердите принудительный откат к '${first.originalValueBeforeOurFix}'."
                    )
                )
            }
        }

        mutex.withLock {
            val currentList = _transactions.value
            val target = currentList.firstOrNull { it.transactionId == transactionId }
                ?: return@withLock Result.failure(
                    NoSuchElementException("Транзакция $transactionId не найдена в журнале откатов")
                )

            if (target.isRolledBack) {
                return@withLock Result.success(target)
            }

            return@withLock runCatching {
                for (op in target.operations.asReversed()) {
                    revertSingleOperation(op, service)
                }

                val rolledBackTx = target.copy(
                    walState = WalEntryState.ROLLED_BACK,
                    isRolledBack = true,
                    rolledBackAtEpochMs = System.currentTimeMillis(),
                )
                val updated = currentList.map {
                    if (it.transactionId == transactionId) rolledBackTx else it
                }
                persistLocked(updated)
                _transactions.value = updated
                rolledBackTx
            }
        }
    }

    /**
     * Откатывает все точно обратимые транзакции и возвращает список пропущенных необратимых операций.
     */
    suspend fun rollbackAllActive(
        service: ISwagDroidService,
        forceOverwriteConflicts: Boolean = false,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val activeIds = _transactions.value
            .filter { !it.isRolledBack && it.reversibility != ReversibilityType.IRREVERSIBLE_MAINTENANCE }
            .map { it.transactionId }
        var rolledBackCount = 0
        for (id in activeIds) {
            rollbackTransaction(id, service, forceOverwriteConflicts).getOrElse {
                return@withContext Result.failure(it)
            }
            rolledBackCount++
        }
        Result.success(rolledBackCount)
    }

    private fun revertSingleOperation(op: ReversibleSystemOp, service: ISwagDroidService) {
        when (op) {
            is ReversibleSystemOp.SystemSettingChange -> {
                val restored = service.putSystemSetting(
                    op.namespace.wireName,
                    op.key,
                    op.previousValue ?: "",
                )
                check(restored) {
                    "Не удалось откатить системную настройку ${op.namespace.wireName}:${op.key}"
                }
            }

            is ReversibleSystemOp.PackageStateChange -> {
                if (!op.wasInstalledForUser) {
                    service.uninstallPackageForUser(op.packageName, op.userId, true)
                } else {
                    if (op.targetAction == PackageMutationType.UNINSTALL_FOR_USER) {
                        val restored = service.restoreExistingPackageForUser(op.packageName, op.userId)
                        check(restored) {
                            "Не удалось восстановить системный пакет ${op.packageName}"
                        }
                    }
                    val stateRestored = service.setApplicationEnabledState(
                        op.packageName,
                        op.previousEnabledState,
                        0,
                        op.userId,
                    )
                    check(stateRestored) {
                        "Не удалось вернуть прежнее состояние пакета ${op.packageName}"
                    }
                }
            }

            is ReversibleSystemOp.AppOpChange -> {
                val ok = service.setAppOpMode(op.opCode, op.uid, op.packageName, op.previousMode)
                check(ok) {
                    "Не удалось откатить AppOp(${op.opCode}) для пакета ${op.packageName}"
                }
            }

            is ReversibleSystemOp.DeviceIdleWhitelistChange -> {
                val ok = service.setDeviceIdleWhitelist(op.packageName, op.wasWhitelistedBefore)
                check(ok) {
                    "Не удалось откатить статус Doze Whitelist для ${op.packageName}"
                }
            }
        }
    }

    private fun persistLocked(list: List<RollbackTransaction>) {
        val tempFile = File(storageDir, "rollback_journal_v1.json.tmp")
        tempFile.writeText(json.encodeToString(list))
        if (!tempFile.renameTo(journalFile)) {
            journalFile.writeText(json.encodeToString(list))
            tempFile.delete()
        }
    }
}
