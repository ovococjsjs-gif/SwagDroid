package com.swagdroid.feature.cleaner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.feature.cleaner.engine.CleanerScanAndPurgeEngine
import com.swagdroid.feature.cleaner.model.CleanerSubTab
import com.swagdroid.feature.cleaner.model.CorpseFinding
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.model.DuplicateCluster
import com.swagdroid.feature.cleaner.model.MediaAgeFilter
import com.swagdroid.feature.cleaner.model.MessengerShelfItem
import com.swagdroid.feature.cleaner.model.SystemJunkCategory
import com.swagdroid.feature.cleaner.model.SystemJunkGroup
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** Enabled (`true`) by default on all 4 axes! */
    val shieldConfig: CredentialShieldConfig = CredentialShieldConfig(),
    val ageFilter: MediaAgeFilter = MediaAgeFilter.ANY_AGE,
    val junkGroups: List<SystemJunkGroup> = emptyList(),
    val messengerShelves: List<MessengerShelfItem> = emptyList(),
    val corpses: List<CorpseFinding> = emptyList(),
    val duplicateClusters: List<DuplicateCluster> = emptyList(),
    val hasAllFilesAccess: Boolean = true,
    val hasScannedOnce: Boolean = false,
    val isScanning: Boolean = false,
    val isCleaning: Boolean = false,
    val bannerMessage: String? = null,
) {
    val selectedJunkBytes: Long
        get() = junkGroups.filter { it.isSelected }.sumOf { it.bytesCount }
    val selectedMessengerBytes: Long
        get() = messengerShelves.filter { it.isSelected }.sumOf { it.bytesCount }
    val selectedCorpseBytes: Long
        get() = corpses.filter { it.isSelected }.sumOf { it.bytesCount }
    val selectedDuplicateBytes: Long
        get() = duplicateClusters.filter { it.isSelected }.sumOf { it.reclaimableBytes }

    val totalSelectedReclaimableBytes: Long
        get() = selectedJunkBytes + selectedMessengerBytes + selectedCorpseBytes + selectedDuplicateBytes

    val isFullShieldActive: Boolean
        get() = shieldConfig.protectAccountsAndAutofill &&
            shieldConfig.protectPasswordManagersAnd2Fa &&
            shieldConfig.protectBrowsersAndWebViewSessions &&
            shieldConfig.protectVaultAnd2FaBackupFiles
}

private data class InternalCleanerState(
    val activeTab: CleanerSubTab = CleanerSubTab.SYSTEM_CACHE,
    val shieldConfig: CredentialShieldConfig = CredentialShieldConfig(),
    val ageFilter: MediaAgeFilter = MediaAgeFilter.ANY_AGE,
    val junkGroups: List<SystemJunkGroup> = emptyList(),
    val messengerShelves: List<MessengerShelfItem> = emptyList(),
    val corpses: List<CorpseFinding> = emptyList(),
    val duplicateClusters: List<DuplicateCluster> = emptyList(),
    val hasAllFilesAccess: Boolean = true,
    val hasScannedOnce: Boolean = false,
    val isScanning: Boolean = false,
    val isCleaning: Boolean = false,
    val bannerMessage: String? = null,
)

@HiltViewModel
class CleanerViewModel @Inject constructor(
    privilegeManager: PrivilegeManager,
    private val engine: CleanerScanAndPurgeEngine,
) : ViewModel() {

    private val internal = MutableStateFlow(
        InternalCleanerState(hasAllFilesAccess = engine.hasFullStoragePermission()),
    )

    val uiState: StateFlow<CleanerUiState> = combine(
        privilegeManager.state,
        internal,
    ) { priv, st ->
        CleanerUiState(
            privilegeState = priv,
            activeTab = st.activeTab,
            shieldConfig = st.shieldConfig,
            ageFilter = st.ageFilter,
            junkGroups = st.junkGroups,
            messengerShelves = st.messengerShelves,
            corpses = st.corpses,
            duplicateClusters = st.duplicateClusters,
            hasAllFilesAccess = st.hasAllFilesAccess,
            hasScannedOnce = st.hasScannedOnce,
            isScanning = st.isScanning,
            isCleaning = st.isCleaning,
            bannerMessage = st.bannerMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CleanerUiState(hasAllFilesAccess = engine.hasFullStoragePermission()),
    )

    fun selectTab(tab: CleanerSubTab) {
        internal.update {
            it.copy(
                activeTab = tab,
                hasAllFilesAccess = engine.hasFullStoragePermission(),
            )
        }
    }

    fun dismissBanner() {
        internal.update { it.copy(bannerMessage = null) }
    }

    fun runScan() {
        if (internal.value.isScanning) return
        viewModelScope.launch {
            internal.update {
                it.copy(
                    isScanning = true,
                    bannerMessage = null,
                    hasAllFilesAccess = engine.hasFullStoragePermission(),
                )
            }
            val cur = internal.value
            val report = engine.runFullScan(cur.shieldConfig, cur.ageFilter)
            internal.update {
                it.copy(
                    junkGroups = report.junkGroups,
                    messengerShelves = report.messengerShelves,
                    corpses = report.corpses,
                    duplicateClusters = report.duplicateClusters,
                    hasAllFilesAccess = report.hasAllFilesAccess,
                    hasScannedOnce = true,
                    isScanning = false,
                )
            }
        }
    }

    fun setMediaAgeFilter(filter: MediaAgeFilter) {
        internal.update { it.copy(ageFilter = filter) }
        if (internal.value.hasScannedOnce) {
            runScan()
        }
    }

    fun updateShieldConfig(newConfig: CredentialShieldConfig) {
        internal.update { it.copy(shieldConfig = newConfig) }
        if (internal.value.hasScannedOnce) {
            runScan()
        }
    }

    fun resetShieldToFullDefault() {
        updateShieldConfig(CredentialShieldConfig())
        internal.update {
            it.copy(bannerMessage = "Максимальная Броня паролей, автозаполнения и 2FA включена на 100%")
        }
    }

    // --- Item selection toggles ---

    fun toggleJunkCategory(cat: SystemJunkCategory) {
        internal.update { cur ->
            cur.copy(
                junkGroups = cur.junkGroups.map {
                    if (it.category == cat) it.copy(isSelected = !it.isSelected) else it
                },
            )
        }
    }

    fun toggleMessengerShelf(id: String) {
        internal.update { cur ->
            cur.copy(
                messengerShelves = cur.messengerShelves.map {
                    if (it.id == id) it.copy(isSelected = !it.isSelected) else it
                },
            )
        }
    }

    fun toggleCorpse(id: String) {
        internal.update { cur ->
            cur.copy(
                corpses = cur.corpses.map {
                    if (it.id == id) it.copy(isSelected = !it.isSelected) else it
                },
            )
        }
    }

    fun toggleDuplicateCluster(id: String) {
        internal.update { cur ->
            cur.copy(
                duplicateClusters = cur.duplicateClusters.map {
                    if (it.id == id) it.copy(isSelected = !it.isSelected) else it
                },
            )
        }
    }

    // --- Safe Double-Gated Clean Execution ---

    fun cleanAllSelected() {
        val st = internal.value
        if (st.isCleaning) return

        val allTargets = buildList {
            st.junkGroups.filter { it.isSelected }.forEach { addAll(it.targetPaths) }
            st.messengerShelves.filter { it.isSelected }.forEach { addAll(it.filePaths) }
            st.corpses.filter { it.isSelected }.forEach { add(it.absolutePath) }
            st.duplicateClusters.filter { it.isSelected }.forEach { addAll(it.duplicatePaths) }
        }

        if (allTargets.isEmpty()) {
            internal.update { it.copy(bannerMessage = "Не отмечено ни одного элемента для очистки") }
            return
        }

        viewModelScope.launch {
            internal.update { it.copy(isCleaning = true) }
            val (deletedCount, freedBytes) = engine.purgeTargets(allTargets, st.shieldConfig)
            val refreshed = engine.runFullScan(st.shieldConfig, st.ageFilter)
            internal.update {
                it.copy(
                    junkGroups = refreshed.junkGroups,
                    messengerShelves = refreshed.messengerShelves,
                    corpses = refreshed.corpses,
                    duplicateClusters = refreshed.duplicateClusters,
                    isCleaning = false,
                    bannerMessage = "Очищено безопасно: освобождено ${formatBytesRu(freedBytes)} ($deletedCount объ.) · Аккаунты и пароли не затронуты!",
                )
            }
        }
    }
}
