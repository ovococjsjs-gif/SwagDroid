package com.swagdroid.feature.cleaner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.feature.cleaner.data.CleanupReceiptStore
import com.swagdroid.feature.cleaner.engine.CleanerOperations
import com.swagdroid.feature.cleaner.engine.CleanupFilePreview
import com.swagdroid.feature.cleaner.model.CleanupBucket
import com.swagdroid.feature.cleaner.model.CleanupCandidate
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.CleanupScanProgress
import com.swagdroid.feature.cleaner.model.CleanupStep
import com.swagdroid.feature.cleaner.model.StorageAccess
import com.swagdroid.feature.cleaner.model.StorageCleanupScan
import com.swagdroid.feature.cleaner.model.StorageSpace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CleanerUiState(
    val step: CleanupStep = CleanupStep.ACCESS,
    val access: StorageAccess = StorageAccess.NEEDS_ALL_FILES,
    val space: StorageSpace? = null,
    val scan: StorageCleanupScan? = null,
    val selectedPaths: Set<String> = emptySet(),
    val bucket: CleanupBucket? = null,
    val query: String = "",
    val isRestoringReceipt: Boolean = true,
    val isScanning: Boolean = false,
    val isPreparingCleanup: Boolean = false,
    val isCleaning: Boolean = false,
    val progress: CleanupScanProgress = CleanupScanProgress("", 0),
    val cleanupReview: CleanupReview = CleanupReview.None,
    val outcome: CleanupRunOutcome? = null,
    val lastReceipt: CleanupReceipt? = null,
    val lastReceiptPersisted: Boolean = true,
    val bannerMessage: String? = null,
) {
    val isBusy: Boolean get() = isRestoringReceipt || isScanning || isPreparingCleanup || isCleaning
    val selected: List<CleanupCandidate> get() = scan?.selected(selectedPaths).orEmpty()
    val selectedBytes: Long get() = selected.sumOf { it.file.bytes }
    val visibleCandidates: List<CleanupCandidate>
        get() = scan?.candidates.orEmpty().filter {
            (bucket == null || it.bucket == bucket) && (query.isBlank() || it.file.path.contains(query.trim(), ignoreCase = true))
        }
}

@HiltViewModel
class CleanerViewModel @Inject constructor(
    private val engine: CleanerOperations,
    private val receipts: CleanupReceiptStore,
) : ViewModel() {
    private val internal = MutableStateFlow(CleanerUiState(access = engine.storageAccess(), space = engine.storageSpace()))
    val uiState: StateFlow<CleanerUiState> = internal.asStateFlow()
    private var scanJob: Job? = null
    private var preparationJob: Job? = null
    private var cleanupJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val receipt = receipts.readLast()
                internal.update { current ->
                    current.copy(
                        lastReceipt = receipt,
                        step = if (receipt != null && receipt.status != CleanupReceipt.Status.COMPLETE) CleanupStep.RESULT else current.step,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                internal.update { it.copy(bannerMessage = "Не удалось прочитать последний итог. Завершение предыдущей очистки не подтверждено.") }
            } finally { internal.update { it.copy(isRestoringReceipt = false) } }
        }
    }

    fun refreshAccess() {
        val access = engine.storageAccess()
        val old = internal.value
        if (old.access != access && !access.canScan && !old.isCleaning) {
            scanJob?.cancel()
            preparationJob?.cancel()
            internal.update {
                it.copy(access = access, space = engine.storageSpace(), step = CleanupStep.ACCESS,
                    scan = null, selectedPaths = emptySet(), cleanupReview = CleanupReview.None,
                    bannerMessage = "Доступ к файлам не выдан или отозван. Старый выбор сброшен; удаление не запускалось.")
            }
        } else {
            internal.update { it.copy(access = access, space = engine.storageSpace()) }
        }
    }

    fun showMessage(message: String) { internal.update { it.copy(bannerMessage = message) } }
    fun dismissBanner() { internal.update { it.copy(bannerMessage = null) } }

    fun runScan() {
        refreshAccess()
        val current = internal.value
        if (current.isBusy || !current.access.canScan) return
        if (!internal.compareAndSet(current, current.copy(
                step = CleanupStep.CHOOSE, isScanning = true, scan = null, selectedPaths = emptySet(),
                bucket = null, query = "", cleanupReview = CleanupReview.None, outcome = null,
                bannerMessage = null, progress = CleanupScanProgress("Подготовка", 0),
            ))) return
        scanJob = viewModelScope.launch {
            try {
                val result = engine.scan { progress -> internal.update { it.copy(progress = progress) } }
                internal.update { it.copy(scan = result, selectedPaths = emptySet(), space = engine.storageSpace()) }
            } catch (cancelled: CancellationException) {
                internal.update { it.copy(step = CleanupStep.ACCESS, bannerMessage = "Поиск отменён. Файлы не удалялись.") }
                throw cancelled
            } catch (error: Exception) {
                internal.update { it.copy(step = CleanupStep.ACCESS, bannerMessage = "Поиск не завершён: ${error.message ?: "ошибка чтения"}. Отсутствие файлов не подтверждено.") }
            } finally {
                internal.update { it.copy(isScanning = false) }
                scanJob = null
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }
    fun resumeSelection() {
        if (!internal.value.isBusy && internal.value.scan != null && internal.value.access.canScan) {
            internal.update { it.copy(step = CleanupStep.CHOOSE) }
        }
    }

    fun setBucket(bucket: CleanupBucket?) { internal.update { it.copy(bucket = bucket) } }
    fun setQuery(query: String) { internal.update { it.copy(query = query) } }

    fun toggleFile(path: String) {
        internal.update { current ->
            if (current.isBusy || current.step != CleanupStep.CHOOSE || current.scan?.candidates?.none { it.file.path == path } != false) current
            else current.copy(
                selectedPaths = if (path in current.selectedPaths) current.selectedPaths - path else current.selectedPaths + path,
                cleanupReview = CleanupReview.None,
            )
        }
    }

    fun selectFilteredFiles() {
        internal.update { current ->
            if (current.isBusy || current.step != CleanupStep.CHOOSE || current.bucket !in setOf(CleanupBucket.TEMPORARY, CleanupBucket.EMPTY_DIRECTORIES)) current else current.copy(
                selectedPaths = current.selectedPaths + current.visibleCandidates.map { it.file.path }, cleanupReview = CleanupReview.None,
            )
        }
    }

    fun clearSelection() {
        internal.update { if (it.isBusy) it else it.copy(selectedPaths = emptySet(), cleanupReview = CleanupReview.None) }
    }

    fun requestCleanupReview() {
        refreshAccess()
        val current = internal.value
        val scan = current.scan ?: return
        if (current.isBusy || !current.access.canScan || current.step != CleanupStep.CHOOSE || current.selected.isEmpty()) return
        if (!internal.compareAndSet(current, current.copy(isPreparingCleanup = true, bannerMessage = null, cleanupReview = CleanupReview.None))) return
        val selection = scan.selection(current.selectedPaths)
        preparationJob = viewModelScope.launch {
            try {
                val plan = engine.prepare(selection)
                internal.update { it.copy(step = CleanupStep.REVIEW, cleanupReview = CleanupReview.Awaiting(plan)) }
            } catch (cancelled: CancellationException) {
                internal.update { it.copy(bannerMessage = "Проверка выбора отменена. Ничего не удалено.") }
                throw cancelled
            } catch (error: Exception) {
                internal.update { it.copy(bannerMessage = "План не подготовлен, ничего не удалено: ${error.message}") }
            } finally {
                internal.update { it.copy(isPreparingCleanup = false) }
                preparationJob = null
            }
        }
    }

    fun cancelCleanupPreparation() { preparationJob?.cancel() }

    fun editSelection() {
        if (internal.value.isBusy) return
        internal.update { it.copy(step = CleanupStep.CHOOSE, cleanupReview = CleanupReview.None) }
    }

    fun confirmCleanup(planId: String, acceptsPermanentDeletion: Boolean) {
        refreshAccess()
        val current = internal.value
        if (current.isBusy || !current.access.canScan || current.step != CleanupStep.REVIEW) return
        val approval = current.cleanupReview.approve(planId, acceptsPermanentDeletion) ?: return
        val running = CleanupReceipt(approval.planId, System.currentTimeMillis(), approval.targetCount, CleanupReceipt.Status.RUNNING)
        if (!internal.compareAndSet(current, current.copy(
                lastReceipt = running, lastReceiptPersisted = false,
                step = CleanupStep.RESULT, isCleaning = true, cleanupReview = CleanupReview.None,
                scan = null, selectedPaths = emptySet(), outcome = null, bannerMessage = null,
            ))) return
        cleanupJob = viewModelScope.launch {
            try {
                val outcome = engine.execute(approval)
                internal.update {
                    it.copy(outcome = outcome, space = engine.storageSpace(),
                        lastReceiptPersisted = when (outcome) {
                            is CleanupRunOutcome.Completed -> outcome.receiptSaved
                            is CleanupRunOutcome.NotStarted -> current.lastReceiptPersisted
                            is CleanupRunOutcome.Interrupted -> false
                        },
                        lastReceipt = when (outcome) {
                            is CleanupRunOutcome.Completed -> outcome.receipt
                            is CleanupRunOutcome.NotStarted -> current.lastReceipt
                            is CleanupRunOutcome.Interrupted -> running.copy(status = CleanupReceipt.Status.INTERRUPTED)
                        })
                }
            } catch (cancelled: CancellationException) {
                internal.update { it.copy(lastReceipt = running.copy(status = CleanupReceipt.Status.INTERRUPTED), outcome = CleanupRunOutcome.Interrupted("Удаление остановлено. Часть файлов могла быть удалена. Точный итог неизвестен; корзины нет.")) }
                throw cancelled
            } catch (error: Exception) {
                internal.update { it.copy(lastReceipt = running.copy(status = CleanupReceipt.Status.INTERRUPTED), outcome = CleanupRunOutcome.Interrupted("Завершение не подтверждено: ${error.message}. Часть файлов могла быть удалена.")) }
            } finally {
                internal.update { it.copy(isCleaning = false) }
                cleanupJob = null
            }
        }
    }

    fun stopCleanup() { cleanupJob?.cancel() }
    fun showLastResult() {
        if (!internal.value.isBusy && internal.value.lastReceipt != null) internal.update { it.copy(step = CleanupStep.RESULT, outcome = null) }
    }
    fun finish() {
        if (!internal.value.isBusy) internal.update {
            it.copy(step = CleanupStep.ACCESS, outcome = null, cleanupReview = CleanupReview.None, scan = null, selectedPaths = emptySet(), bannerMessage = null)
        }
    }
    fun back() {
        when {
            internal.value.isCleaning -> showMessage("Чтобы остановить дальнейшее удаление, нажмите «Остановить». Уже удалённые файлы не вернутся.")
            internal.value.isScanning -> cancelScan()
            internal.value.isPreparingCleanup -> cancelCleanupPreparation()
            internal.value.step == CleanupStep.REVIEW -> editSelection()
            internal.value.step == CleanupStep.RESULT -> finish()
            internal.value.step == CleanupStep.CHOOSE -> internal.update { it.copy(step = CleanupStep.ACCESS) }
        }
    }

    fun previewFile(file: CleanupFileSnapshot, onReady: (CleanupFilePreview) -> Unit) {
        val scan = internal.value.scan ?: return
        if (internal.value.isBusy || scan.candidates.none { it.file == file || it.keptOriginal == file }) return
        viewModelScope.launch {
            try { onReady(engine.preview(file)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage("Не удалось открыть файл: ${error.message}") }
        }
    }
}
