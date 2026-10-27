package com.swagdroid.feature.cleaner.engine

import com.swagdroid.feature.cleaner.data.CleanupReceiptStore
import com.swagdroid.feature.cleaner.model.CleanupExecutionResult
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One app-wide lane includes both the receipt and deletion, even across multiple activities. */
@Singleton
class RecordedCleanupExecutor @Inject constructor(private val receipts: CleanupReceiptStore) {
    private val mutex = Mutex()

    suspend fun run(
        approval: CleanupReview.Approved,
        work: suspend () -> CleanupExecutionResult,
    ): CleanupRunOutcome = mutex.withLock {
        if (!approval.reserveRun()) return@withLock CleanupRunOutcome.NotStarted("Это подтверждение уже использовано. Подготовьте новый план.")
        val running = CleanupReceipt(approval.planId, System.currentTimeMillis(), approval.targetCount, CleanupReceipt.Status.RUNNING)
        try {
            receipts.write(running)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withLock CleanupRunOutcome.NotStarted("Не удалось сохранить начало операции. Файлы не удалялись: ${error.message}")
        }
        try {
            val result = work()
            val completed = running.copy(
                status = CleanupReceipt.Status.COMPLETE, completedAtMs = System.currentTimeMillis(),
                deletedFiles = result.deletedFiles, deletedDirectories = result.deletedEmptyDirectories,
                deletedBytes = result.deletedFileBytes, skipped = result.skipped.size, failed = result.failed.size,
            )
            val saved = saveAfterwards(completed)
            CleanupRunOutcome.Completed(result, completed, saved)
        } catch (cancelled: CancellationException) {
            saveAfterwards(running.copy(status = CleanupReceipt.Status.INTERRUPTED))
            throw cancelled
        } catch (error: Exception) {
            saveAfterwards(running.copy(status = CleanupReceipt.Status.INTERRUPTED))
            CleanupRunOutcome.Interrupted("Операция не завершена. Часть файлов могла быть удалена; точный итог неизвестен. ${error.message.orEmpty()}")
        }
    }

    private suspend fun saveAfterwards(receipt: CleanupReceipt): Boolean = withContext(NonCancellable) {
        try {
            withTimeoutOrNull(2_000) { receipts.write(receipt); true } == true
        } catch (_: Exception) {
            false // The old RUNNING marker is intentionally retained; never claim a persisted success.
        }
    }
}
