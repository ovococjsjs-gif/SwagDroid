package com.swagdroid.feature.fixer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.fixer.catalog.FixerCatalog
import com.swagdroid.feature.fixer.engine.FixExecutionOutcome
import com.swagdroid.feature.fixer.engine.FixerDiagnosticEngine
import com.swagdroid.feature.fixer.model.AppRescueAuditResult
import com.swagdroid.feature.fixer.model.DiagnosticVerdict
import com.swagdroid.feature.fixer.model.InstalledAppBrief
import com.swagdroid.feature.fixer.model.PhoneSubsystem
import com.swagdroid.feature.fixer.model.PlaybookRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FixerScreenState(
    val privilegeState: PrivilegeState = PrivilegeState(),
    val items: List<PlaybookRuntimeState> = emptyList(),
    val selectedSubsystem: PhoneSubsystem? = null,
    val searchQuery: String = "",
    val onlyIssuesFilter: Boolean = false,
    val isFullScanRunning: Boolean = false,
    val scannedProgressCount: Int = 0,
    val totalPlaybooksCount: Int = FixerCatalog.allPlaybooks.size,
    val bannerMessage: String? = null,
    val showAppRescuerSheet: Boolean = false,
    val userApps: List<InstalledAppBrief> = emptyList(),
    val selectedAppAudit: AppRescueAuditResult? = null,
    val isAppRescueBusy: Boolean = false,
) {
    val issuesCount: Int
        get() = items.count { it.verdict == DiagnosticVerdict.ISSUE_FOUND }
    val healthyCount: Int
        get() = items.count { it.verdict == DiagnosticVerdict.HEALTHY }
    val fixedCount: Int
        get() = items.count { it.verdict == DiagnosticVerdict.FIXED_ACTIVE }

    val filteredItems: List<PlaybookRuntimeState>
        get() = items.filter { item ->
            val subsystemMatch = selectedSubsystem == null || item.playbook.subsystem == selectedSubsystem
            val queryMatch = item.playbook.matchesQuery(searchQuery)
            val issueMatch = !onlyIssuesFilter || item.verdict == DiagnosticVerdict.ISSUE_FOUND
            subsystemMatch && queryMatch && issueMatch
        }
}

private data class InternalFixerState(
    val runtimeMap: Map<String, PlaybookRuntimeState> = FixerCatalog.allPlaybooks.associate {
        it.id to PlaybookRuntimeState(playbook = it)
    },
    val selectedSubsystem: PhoneSubsystem? = null,
    val searchQuery: String = "",
    val onlyIssuesFilter: Boolean = false,
    val isFullScanRunning: Boolean = false,
    val scannedProgressCount: Int = 0,
    val bannerMessage: String? = null,
    val showAppRescuerSheet: Boolean = false,
    val userApps: List<InstalledAppBrief> = emptyList(),
    val selectedAppAudit: AppRescueAuditResult? = null,
    val isAppRescueBusy: Boolean = false,
)

@HiltViewModel
class FixerViewModel @Inject constructor(
    privilegeManager: PrivilegeManager,
    private val engine: FixerDiagnosticEngine,
    private val vaultManager: StateVaultManager,
) : ViewModel() {

    private val internal = MutableStateFlow(InternalFixerState())

    val uiState: StateFlow<FixerScreenState> = combine(
        privilegeManager.state,
        internal,
    ) { priv, st ->
        FixerScreenState(
            privilegeState = priv,
            items = FixerCatalog.allPlaybooks.mapNotNull { st.runtimeMap[it.id] },
            selectedSubsystem = st.selectedSubsystem,
            searchQuery = st.searchQuery,
            onlyIssuesFilter = st.onlyIssuesFilter,
            isFullScanRunning = st.isFullScanRunning,
            scannedProgressCount = st.scannedProgressCount,
            totalPlaybooksCount = FixerCatalog.allPlaybooks.size,
            bannerMessage = st.bannerMessage,
            showAppRescuerSheet = st.showAppRescuerSheet,
            userApps = st.userApps,
            selectedAppAudit = st.selectedAppAudit,
            isAppRescueBusy = st.isAppRescueBusy,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FixerScreenState(
            items = FixerCatalog.allPlaybooks.map { PlaybookRuntimeState(playbook = it) },
        ),
    )

    fun updateSearchQuery(query: String) {
        internal.update { it.copy(searchQuery = query) }
    }

    fun selectSubsystem(subsystem: PhoneSubsystem?) {
        internal.update { it.copy(selectedSubsystem = subsystem) }
    }

    fun toggleOnlyIssuesFilter() {
        internal.update { it.copy(onlyIssuesFilter = !it.onlyIssuesFilter) }
    }

    fun dismissBanner() {
        internal.update { it.copy(bannerMessage = null) }
    }

    fun runFullDiagnostics() {
        if (internal.value.isFullScanRunning) return
        viewModelScope.launch {
            val all = FixerCatalog.allPlaybooks
            internal.update {
                it.copy(
                    isFullScanRunning = true,
                    scannedProgressCount = 0,
                    bannerMessage = null,
                )
            }
            var foundIssues = 0
            all.forEachIndexed { idx, playbook ->
                val (verdict, evidence) = engine.diagnose(playbook)
                if (verdict == DiagnosticVerdict.ISSUE_FOUND) foundIssues++
                internal.update { cur ->
                    val existing = cur.runtimeMap[playbook.id] ?: PlaybookRuntimeState(playbook)
                    val preservedVerdict = if (existing.linkedSnapshotId != null && verdict == DiagnosticVerdict.HEALTHY) {
                        DiagnosticVerdict.FIXED_ACTIVE
                    } else {
                        verdict
                    }
                    cur.copy(
                        scannedProgressCount = idx + 1,
                        runtimeMap = cur.runtimeMap + (
                            playbook.id to existing.copy(
                                verdict = preservedVerdict,
                                liveEvidence = evidence,
                                isBusy = false,
                            )
                        ),
                    )
                }
            }
            internal.update {
                it.copy(
                    isFullScanRunning = false,
                    bannerMessage = "Проверено все ${all.size} узлов по 12 подсистемам · Найдено отклонений: $foundIssues",
                )
            }
        }
    }

    fun diagnoseSingle(playbookId: String) {
        val playbook = FixerCatalog.byId(playbookId) ?: return
        viewModelScope.launch {
            updatePlaybookState(playbookId) { it.copy(verdict = DiagnosticVerdict.CHECKING, isBusy = true) }
            val (verdict, evidence) = engine.diagnose(playbook)
            updatePlaybookState(playbookId) { prev ->
                prev.copy(
                    verdict = verdict,
                    liveEvidence = evidence,
                    isBusy = false,
                )
            }
        }
    }

    fun applySingleFix(playbookId: String) {
        val playbook = FixerCatalog.byId(playbookId) ?: return
        viewModelScope.launch {
            updatePlaybookState(playbookId) { it.copy(isBusy = true) }
            when (val outcome = engine.applyFix(playbook)) {
                is FixExecutionOutcome.AppliedWithVault -> {
                    val (_, freshEvidence) = engine.diagnose(playbook)
                    updatePlaybookState(playbookId) { prev ->
                        prev.copy(
                            verdict = DiagnosticVerdict.FIXED_ACTIVE,
                            liveEvidence = freshEvidence,
                            linkedSnapshotId = outcome.snapshotId,
                            isBusy = false,
                        )
                    }
                    internal.update { it.copy(bannerMessage = outcome.messageRu) }
                }
                is FixExecutionOutcome.AppliedMaintenance -> {
                    val (freshVerdict, freshEvidence) = engine.diagnose(playbook)
                    updatePlaybookState(playbookId) { prev ->
                        prev.copy(
                            verdict = freshVerdict,
                            liveEvidence = freshEvidence,
                            isBusy = false,
                        )
                    }
                    internal.update { it.copy(bannerMessage = outcome.messageRu) }
                }
                is FixExecutionOutcome.Failed -> {
                    updatePlaybookState(playbookId) { it.copy(isBusy = false) }
                    internal.update { it.copy(bannerMessage = outcome.errorRu) }
                }
            }
        }
    }

    fun rollbackSingleFix(playbookId: String, snapshotId: Long) {
        val playbook = FixerCatalog.byId(playbookId) ?: return
        viewModelScope.launch {
            updatePlaybookState(playbookId) { it.copy(isBusy = true) }
            when (val res = vaultManager.rollbackSnapshot(snapshotId)) {
                is VaultTransactionResult.Success -> {
                    val (freshVerdict, freshEvidence) = engine.diagnose(playbook)
                    updatePlaybookState(playbookId) { prev ->
                        prev.copy(
                            verdict = freshVerdict,
                            liveEvidence = freshEvidence,
                            linkedSnapshotId = null,
                            isBusy = false,
                        )
                    }
                    internal.update { it.copy(bannerMessage = "Фикс откачен к исходным настройкам (#$snapshotId)") }
                }
                is VaultTransactionResult.Failure -> {
                    updatePlaybookState(playbookId) { it.copy(isBusy = false) }
                    internal.update { it.copy(bannerMessage = "Ошибка отката: ${res.errorMessage}") }
                }
            }
        }
    }

    // --- Interactive App Notification Rescuer ---

    fun openAppRescuerSheet() {
        internal.update { it.copy(showAppRescuerSheet = true, isAppRescueBusy = true) }
        viewModelScope.launch {
            val apps = engine.loadUserAppsForRescue()
            internal.update { it.copy(userApps = apps, isAppRescueBusy = false) }
        }
    }

    fun closeAppRescuerSheet() {
        internal.update { it.copy(showAppRescuerSheet = false, selectedAppAudit = null) }
    }

    fun inspectAppForRescue(app: InstalledAppBrief) {
        internal.update { it.copy(isAppRescueBusy = true) }
        viewModelScope.launch {
            val audit = engine.inspectAppNotifications(app)
            internal.update { it.copy(selectedAppAudit = audit, isAppRescueBusy = false) }
        }
    }

    fun executeAppRescue() {
        val audit = internal.value.selectedAppAudit ?: return
        internal.update { it.copy(isAppRescueBusy = true) }
        viewModelScope.launch {
            when (val outcome = engine.rescueAppNotifications(audit)) {
                is FixExecutionOutcome.AppliedWithVault -> {
                    val refreshed = engine.inspectAppNotifications(
                        InstalledAppBrief(audit.packageName, audit.appLabel, false),
                    )
                    internal.update {
                        it.copy(
                            selectedAppAudit = refreshed,
                            isAppRescueBusy = false,
                            bannerMessage = outcome.messageRu,
                        )
                    }
                }
                is FixExecutionOutcome.AppliedMaintenance -> {
                    internal.update { it.copy(isAppRescueBusy = false, bannerMessage = outcome.messageRu) }
                }
                is FixExecutionOutcome.Failed -> {
                    internal.update { it.copy(isAppRescueBusy = false, bannerMessage = outcome.errorRu) }
                }
            }
        }
    }

    private fun updatePlaybookState(
        id: String,
        transform: (PlaybookRuntimeState) -> PlaybookRuntimeState,
    ) {
        internal.update { cur ->
            val current = cur.runtimeMap[id] ?: return@update cur
            cur.copy(runtimeMap = cur.runtimeMap + (id to transform(current)))
        }
    }
}
