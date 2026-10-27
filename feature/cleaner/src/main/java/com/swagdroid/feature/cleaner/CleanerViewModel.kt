package com.swagdroid.feature.cleaner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.feature.cleaner.engine.CleanerScanAndPurgeEngine
import com.swagdroid.feature.cleaner.model.CleanerSubTab
import com.swagdroid.feature.cleaner.model.CleanupExecutionResult
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CorpseFinding
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.model.DuplicateCluster
import com.swagdroid.feature.cleaner.model.MediaAgeFilter
import com.swagdroid.feature.cleaner.model.MessengerShelfItem
import com.swagdroid.feature.cleaner.model.SystemJunkCategory
import com.swagdroid.feature.cleaner.model.SystemJunkGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CleanerUiState(
    val privilegeState: PrivilegeState = PrivilegeState(),
    val activeTab: CleanerSubTab = CleanerSubTab.SYSTEM_CACHE,
    val shieldConfig: CredentialShieldConfig = CredentialShieldConfig(),
    val ageFilter: MediaAgeFilter = MediaAgeFilter.ANY_AGE,
    val junkGroups: List<SystemJunkGroup> = emptyList(),
    val messengerShelves: List<MessengerShelfItem> = emptyList(),
    val corpses: List<CorpseFinding> = emptyList(),
    val duplicateClusters: List<DuplicateCluster> = emptyList(),
    val hasAllFilesAccess: Boolean = false,
    val hasScannedOnce: Boolean = false,
    val isScanning: Boolean = false,
    val isPreparingCleanup: Boolean = false,
    val isCleaning: Boolean = false,
    val cleanupReview: CleanupReview = CleanupReview.None,
    val lastCleanupResult: CleanupExecutionResult? = null,
    val bannerMessage: String? = null,
) {
    val isBusy: Boolean get() = isScanning || isPreparingCleanup || isCleaning

    val totalSelectedReclaimableBytes: Long
        get() = junkGroups.filter { it.isSelected && it.category.cleanupBlockReason == null }.sumOf { it.bytesCount } +
            messengerShelves.filter { it.isSelected }.sumOf { it.bytesCount } +
            corpses.filter { it.isSelected }.sumOf { it.bytesCount } +
            duplicateClusters.filter { it.isSelected }.sumOf { it.reclaimableBytes }

    // Zero-byte files and empty directories still require confirmation.
    val hasSelectedTargets: Boolean
        get() = junkGroups.any { it.isSelected && it.category.cleanupBlockReason == null && it.targetPaths.isNotEmpty() } ||
            messengerShelves.any { it.isSelected && it.filePaths.isNotEmpty() } ||
            corpses.any { it.isSelected } ||
            duplicateClusters.any { it.isSelected && it.duplicatePaths.isNotEmpty() }

    val isFullShieldActive: Boolean
        get() = shieldConfig.protectAccountsAndAutofill &&
            shieldConfig.protectPasswordManagersAnd2Fa &&
            shieldConfig.protectBrowsersAndWebViewSessions &&
            shieldConfig.protectVaultAnd2FaBackupFiles
}

@HiltViewModel
class CleanerViewModel @Inject constructor(
    privilegeManager: PrivilegeManager,
    private val engine: CleanerScanAndPurgeEngine,
) : ViewModel() {
    private val internal = MutableStateFlow(
        CleanerUiState(hasAllFilesAccess = engine.hasFullStoragePermission()),
    )
    private var preparationJob: Job? = null

    val uiState: StateFlow<CleanerUiState> = combine(privilegeManager.state, internal) { priv, st ->
        st.copy(privilegeState = priv)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = internal.value,
    )

    fun selectTab(tab: CleanerSubTab) {
        internal.update { it.copy(activeTab = tab, hasAllFilesAccess = engine.hasFullStoragePermission()) }
    }

    fun dismissBanner() {
        internal.update { it.copy(bannerMessage = null) }
    }

    fun runScan() {
        if (internal.value.isBusy) return
        // Set the busy flag before launching: two rapid taps cannot enqueue two scans.
        internal.update {
            it.copy(
                isScanning = true,
                hasScannedOnce = false,
                cleanupReview = CleanupReview.None,
                lastCleanupResult = null,
                bannerMessage = null,
                hasAllFilesAccess = engine.hasFullStoragePermission(),
            )
        }
        val input = internal.value
        viewModelScope.launch {
            try {
                val report = engine.runFullScan(input.shieldConfig, input.ageFilter)
                internal.update {
                    it.copy(
                        junkGroups = report.junkGroups,
                        messengerShelves = report.messengerShelves,
                        corpses = report.corpses,
                        duplicateClusters = report.duplicateClusters,
                        hasAllFilesAccess = report.hasAllFilesAccess,
                        hasScannedOnce = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                internal.update { it.copy(bannerMessage = "Сканирование не завершено: ${error.message ?: "ошибка чтения"}. Повторите проверку.") }
            } finally {
                internal.update { it.copy(isScanning = false) }
            }
        }
    }

    fun setMediaAgeFilter(filter: MediaAgeFilter) {
        if (internal.value.isBusy) return
        internal.update { it.copy(ageFilter = filter, cleanupReview = CleanupReview.None) }
        if (internal.value.hasScannedOnce) runScan()
    }

    fun updateShieldConfig(newConfig: CredentialShieldConfig) {
        if (internal.value.isBusy) return
        internal.update { it.copy(shieldConfig = newConfig, cleanupReview = CleanupReview.None) }
        if (internal.value.hasScannedOnce) runScan()
    }

    fun resetShieldToFullDefault() {
        updateShieldConfig(CredentialShieldConfig())
    }

    private fun changeSelection(transform: (CleanerUiState) -> CleanerUiState) {
        internal.update { current ->
            if (current.isBusy) current else transform(current).copy(cleanupReview = CleanupReview.None)
        }
    }

    fun toggleJunkCategory(category: SystemJunkCategory) {
        if (category.cleanupBlockReason != null) return
        changeSelection { st ->
            st.copy(junkGroups = st.junkGroups.map { if (it.category == category) it.copy(isSelected = !it.isSelected) else it })
        }
    }

    fun toggleMessengerShelf(id: String) = changeSelection { st ->
        st.copy(messengerShelves = st.messengerShelves.map { if (it.id == id) it.copy(isSelected = !it.isSelected) else it })
    }

    fun toggleCorpse(id: String) = changeSelection { st ->
        st.copy(corpses = st.corpses.map { if (it.id == id) it.copy(isSelected = !it.isSelected) else it })
    }

    fun toggleDuplicateCluster(id: String) = changeSelection { st ->
        st.copy(duplicateClusters = st.duplicateClusters.map { if (it.id == id) it.copy(isSelected = !it.isSelected) else it })
    }

    fun requestCleanupReview() {
        val st = internal.value
        if (st.isBusy || !st.hasScannedOnce || st.cleanupReview is CleanupReview.Awaiting) return
        val selection = CleanupSelection.from(st.junkGroups, st.messengerShelves, st.corpses, st.duplicateClusters)
        if (selection.groups.isEmpty()) {
            internal.update { it.copy(bannerMessage = "Не выбрано доступных объектов для очистки") }
            return
        }
        internal.update { it.copy(isPreparingCleanup = true, cleanupReview = CleanupReview.None, bannerMessage = null) }
        preparationJob = viewModelScope.launch {
            try {
                val plan = engine.prepareCleanup(selection, st.shieldConfig)
                // Even an empty plan is reviewable: it explains unavailable/protected files.
                internal.update { it.copy(cleanupReview = CleanupReview.Awaiting(plan)) }
            } catch (cancelled: CancellationException) {
                internal.update { it.copy(bannerMessage = "Подготовка отменена. Файлы не удалялись.") }
                throw cancelled
            } catch (error: Exception) {
                internal.update { it.copy(bannerMessage = "План не подготовлен, ничего не удалено: ${error.message ?: "ошибка чтения"}") }
            } finally {
                internal.update { it.copy(isPreparingCleanup = false) }
                preparationJob = null
            }
        }
    }

    fun cancelCleanupPreparation() {
        preparationJob?.cancel()
    }

    fun dismissCleanupReview() {
        internal.update { it.copy(cleanupReview = CleanupReview.None) }
    }

    fun confirmCleanup(planId: String, acceptsPermanentDeletion: Boolean) {
        val st = internal.value
        if (st.isBusy) return
        val approval = st.cleanupReview.approve(planId, acceptsPermanentDeletion) ?: return
        // Consume this exact confirmation synchronously. A double tap or stale dialog cannot reuse it.
        if (!internal.compareAndSet(
                st,
                st.copy(
                    cleanupReview = CleanupReview.None,
                    isCleaning = true,
                    hasScannedOnce = false,
                    junkGroups = emptyList(),
                    messengerShelves = emptyList(),
                    corpses = emptyList(),
                    duplicateClusters = emptyList(),
                    bannerMessage = null,
                ),
            )
        ) return

        viewModelScope.launch {
            try {
                val result = engine.executeCleanup(approval, st.shieldConfig)
                val status = when {
                    result.failed.isNotEmpty() -> "Очистка завершилась с ошибками"
                    result.skipped.isNotEmpty() -> "Очистка завершена с пропусками"
                    else -> "Обработка плана завершена"
                }
                internal.update {
                    it.copy(lastCleanupResult = result, bannerMessage = "$status. Для актуального списка выполните новое сканирование.")
                }
            } catch (cancelled: CancellationException) {
                internal.update { it.copy(bannerMessage = "Очистка прервана. Часть файлов могла быть удалена; выполните новое сканирование.") }
                throw cancelled
            } catch (error: Exception) {
                internal.update {
                    it.copy(bannerMessage = "Очистка не завершена: ${error.message ?: "ошибка"}. Часть файлов могла быть удалена. Нужно новое сканирование.")
                }
            } finally {
                internal.update { it.copy(isCleaning = false) }
            }
        }
    }

    fun dismissCleanupResult() {
        internal.update { it.copy(lastCleanupResult = null) }
    }
}
