package com.swagdroid.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.adblock.AdblockController
import com.swagdroid.feature.adblock.data.BlocklistRepository
import com.swagdroid.feature.adblock.model.AdblockEngineMode
import com.swagdroid.feature.adblock.model.DnsQueryLogEntry
import com.swagdroid.feature.adblock.model.UpstreamDnsResolver
import com.swagdroid.feature.adblock.vpn.SwagDnsVpnService
import com.swagdroid.feature.security.engine.SecurityPipelineEngine
import com.swagdroid.feature.security.model.AggregatedThreatDecision
import com.swagdroid.feature.security.model.AppTrackerScanItem
import com.swagdroid.feature.security.model.QuarantineEntry
import com.swagdroid.feature.security.model.SecuritySubTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SecurityCenterUiState(
    val privilegeState: PrivilegeState = PrivilegeState(),
    val activeTab: SecuritySubTab = SecuritySubTab.THREAT_SCANNER,
    val threats: List<AggregatedThreatDecision> = emptyList(),
    val quarantinedItems: List<QuarantineEntry> = emptyList(),
    val trackerReports: List<AppTrackerScanItem> = emptyList(),
    val hasScannedThreatsOnce: Boolean = false,
    val hasScannedTrackersOnce: Boolean = false,
    val isScanning: Boolean = false,
    val scanStep: Int = 0,
    val scanTotal: Int = 0,
    // AdBlock 3-in-1 state
    val selectedAdblockMode: AdblockEngineMode = AdblockEngineMode.LOCAL_DNS_VPN,
    val selectedResolver: UpstreamDnsResolver = UpstreamDnsResolver.ADGUARD,
    val isLocalVpnRunning: Boolean = false,
    val isPrivateDnsActive: Boolean = false,
    val activePrivateDnsHost: String? = null,
    val isRootHostsActive: Boolean = false,
    val totalBlockRulesCount: Int = 0,
    val customBlockedDomains: Set<String> = emptySet(),
    val customAllowedDomains: Set<String> = emptySet(),
    val recentDnsLog: List<DnsQueryLogEntry> = emptyList(),
    val bannerMessage: String? = null,
)

private data class InternalSecState(
    val activeTab: SecuritySubTab = SecuritySubTab.THREAT_SCANNER,
    val threats: List<AggregatedThreatDecision> = emptyList(),
    val quarantinedItems: List<QuarantineEntry> = emptyList(),
    val trackerReports: List<AppTrackerScanItem> = emptyList(),
    val hasScannedThreatsOnce: Boolean = false,
    val hasScannedTrackersOnce: Boolean = false,
    val isScanning: Boolean = false,
    val scanStep: Int = 0,
    val scanTotal: Int = 0,
    val selectedAdblockMode: AdblockEngineMode = AdblockEngineMode.LOCAL_DNS_VPN,
    val selectedResolver: UpstreamDnsResolver = UpstreamDnsResolver.ADGUARD,
    val isPrivateDnsActive: Boolean = false,
    val activePrivateDnsHost: String? = null,
    val isRootHostsActive: Boolean = false,
    val bannerMessage: String? = null,
)

@HiltViewModel
class SecurityCenterViewModel @Inject constructor(
    privilegeManager: PrivilegeManager,
    private val pipeline: SecurityPipelineEngine,
    private val adblockController: AdblockController,
    private val blocklistRepo: BlocklistRepository,
) : ViewModel() {

    private val internal = MutableStateFlow(InternalSecState())

    val uiState: StateFlow<SecurityCenterUiState> = combine(
        privilegeManager.state,
        internal,
        SwagDnsVpnService.isRunningFlow,
        blocklistRepo.recentLog,
        combine(blocklistRepo.customBlocked, blocklistRepo.customAllowed) { b, a -> b to a },
    ) { priv, st, vpnRunning, dnsLog, (customBlk, customAlw) ->
        SecurityCenterUiState(
            privilegeState = priv,
            activeTab = st.activeTab,
            threats = st.threats,
            quarantinedItems = st.quarantinedItems,
            trackerReports = st.trackerReports,
            hasScannedThreatsOnce = st.hasScannedThreatsOnce,
            hasScannedTrackersOnce = st.hasScannedTrackersOnce,
            isScanning = st.isScanning,
            scanStep = st.scanStep,
            scanTotal = st.scanTotal,
            selectedAdblockMode = st.selectedAdblockMode,
            selectedResolver = st.selectedResolver,
            isLocalVpnRunning = vpnRunning,
            isPrivateDnsActive = st.isPrivateDnsActive,
            activePrivateDnsHost = st.activePrivateDnsHost,
            isRootHostsActive = st.isRootHostsActive,
            totalBlockRulesCount = blocklistRepo.totalRulesCount,
            customBlockedDomains = customBlk,
            customAllowedDomains = customAlw,
            recentDnsLog = dnsLog,
            bannerMessage = st.bannerMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SecurityCenterUiState(),
    )

    init {
        viewModelScope.launch {
            blocklistRepo.ensureLoaded()
            refreshAdblockStatus()
        }
    }

    fun selectTab(tab: SecuritySubTab) {
        internal.update { it.copy(activeTab = tab) }
    }

    fun dismissBanner() {
        internal.update { it.copy(bannerMessage = null) }
    }

    // =========================================================================
    // Threat Scanner & Quarantine
    // =========================================================================

    fun runThreatScan() {
        if (internal.value.isScanning) return
        viewModelScope.launch {
            internal.update { it.copy(isScanning = true, scanStep = 0, scanTotal = 1, bannerMessage = null) }
            val decisions = pipeline.runFullThreatScan { step, total ->
                internal.update { it.copy(scanStep = step, scanTotal = total) }
            }
            internal.update {
                it.copy(
                    threats = decisions,
                    hasScannedThreatsOnce = true,
                    isScanning = false,
                    bannerMessage = if (decisions.isEmpty()) {
                        "Сканирование завершено: угроз, майнеров и скрытого Adware не обнаружено!"
                    } else {
                        "Найдено угроз и подозрительных объектов: ${decisions.size}"
                    },
                )
            }
        }
    }

    fun quarantineDecision(decision: AggregatedThreatDecision) {
        val pkg = decision.packageName ?: return
        viewModelScope.launch {
            val res = pipeline.quarantinePackage(
                pkg = pkg,
                appLabel = decision.displayTitle,
                reasonRu = decision.reasonsRu.firstOrNull() ?: "Изоляция подозрительного пакета",
            )
            res.fold(
                onSuccess = { entry ->
                    internal.update { cur ->
                        cur.copy(
                            threats = cur.threats.filterNot { it.targetKey == decision.targetKey },
                            quarantinedItems = listOf(entry) + cur.quarantinedItems,
                            bannerMessage = "Пакет «${entry.appLabel}» остановлен, лишён оверлеев и помещён в Карантин (#${entry.vaultSnapshotId})",
                        )
                    }
                },
                onFailure = { err ->
                    internal.update { it.copy(bannerMessage = err.message ?: "Ошибка помещения в карантин") }
                },
            )
        }
    }

    fun deleteLooseMaliciousFile(decision: AggregatedThreatDecision) {
        val path = decision.apkFilePath ?: return
        if (decision.packageName != null) return
        viewModelScope.launch {
            val deleted = runCatching { File(path).delete() }.getOrDefault(false)
            if (deleted) {
                internal.update { cur ->
                    cur.copy(
                        threats = cur.threats.filterNot { it.targetKey == decision.targetKey },
                        bannerMessage = "Вредоносный файл ${path.substringAfterLast('/')} удалён!",
                    )
                }
            }
        }
    }

    fun cureSystemThreat(decision: AggregatedThreatDecision) {
        if (decision.targetKey == "setting:global:http_proxy") {
            viewModelScope.launch {
                if (pipeline.cureHijackedProxy()) {
                    internal.update { cur ->
                        cur.copy(
                            threats = cur.threats.filterNot { it.targetKey == decision.targetKey },
                            bannerMessage = "Перехватчик системного прокси успешно очищен (создана точка отката StateVault)",
                        )
                    }
                }
            }
        }
    }

    fun restoreFromQuarantine(entry: QuarantineEntry) {
        viewModelScope.launch {
            val ok = pipeline.restoreFromQuarantine(entry)
            if (ok) {
                internal.update { cur ->
                    cur.copy(
                        quarantinedItems = cur.quarantinedItems.filterNot { it.vaultSnapshotId == entry.vaultSnapshotId },
                        bannerMessage = "Пакет «${entry.appLabel}» восстановлен из карантина",
                    )
                }
            }
        }
    }

    fun buildUninstallIntent(packageName: String) = pipeline.buildSystemUninstallIntent(packageName)

    // =========================================================================
    // Exodus Tracker Radar
    // =========================================================================

    fun runTrackerRadarScan() {
        if (internal.value.isScanning) return
        viewModelScope.launch {
            internal.update { it.copy(isScanning = true, bannerMessage = null) }
            val reports = pipeline.scanInstalledAppsForTrackers()
            internal.update {
                it.copy(
                    trackerReports = reports,
                    hasScannedTrackersOnce = true,
                    isScanning = false,
                    bannerMessage = "Проверено ${reports.size} приложений по базе 32 SDK Exodus Privacy",
                )
            }
        }
    }

    fun isolateAppBackgroundTraffic(pkg: String, label: String) {
        viewModelScope.launch {
            when (val res = adblockController.setAppBackgroundNetworkBlocked(pkg, label, blockBackground = true)) {
                is VaultTransactionResult.Success -> {
                    internal.update {
                        it.copy(bannerMessage = "Фоновый трафик трекеров «$label» заблокирован (точка отката #${res.snapshotId})")
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    // =========================================================================
    // Adaptive 3-Mode AdBlock & Firewall
    // =========================================================================

    fun selectAdblockMode(mode: AdblockEngineMode) {
        internal.update { it.copy(selectedAdblockMode = mode) }
    }

    fun selectUpstreamResolver(resolver: UpstreamDnsResolver) {
        internal.update { it.copy(selectedResolver = resolver) }
    }

    fun onLocalVpnPermissionGranted() {
        adblockController.startLocalDnsVpn(internal.value.selectedResolver)
        internal.update { it.copy(bannerMessage = "Локальный DNS-щит запущен (фильтруются только UDP:53 запросы)") }
    }

    fun stopLocalDnsVpn() {
        adblockController.stopLocalDnsVpn()
        internal.update { it.copy(bannerMessage = "Локальный DNS-щит остановлен") }
    }

    fun enableAdbPrivateDns() {
        viewModelScope.launch {
            when (val res = adblockController.enableAdbPrivateDns(internal.value.selectedResolver)) {
                is VaultTransactionResult.Success -> {
                    refreshAdblockStatus()
                    internal.update {
                        it.copy(bannerMessage = "Системный DoT AdBlock активирован без слота VPN (точка отката #${res.snapshotId})")
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    fun disableAdbPrivateDns() {
        viewModelScope.launch {
            adblockController.disableAdbPrivateDns()
            refreshAdblockStatus()
            internal.update { it.copy(bannerMessage = "Системный Private DNS переведён в авто-режим") }
        }
    }

    fun applyRootHosts() {
        viewModelScope.launch {
            when (val res = adblockController.applyRootSystemlessHosts()) {
                is VaultTransactionResult.Success -> {
                    refreshAdblockStatus()
                    internal.update {
                        it.copy(bannerMessage = "Systemless /etc/hosts установлен (точка отката #${res.snapshotId})")
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    fun addCustomDomainRule(domain: String, block: Boolean) {
        val ok = blocklistRepo.addCustomRule(domain, block)
        internal.update {
            it.copy(
                bannerMessage = if (ok) {
                    "Домен добавлен в ${if (block) "чёрный" else "белый"} список"
                } else {
                    "Введите корректное доменное имя (например, ads.example.com)"
                },
            )
        }
    }

    fun removeCustomDomainRule(domain: String) {
        blocklistRepo.removeCustomRule(domain)
    }

    private suspend fun refreshAdblockStatus() {
        val (dotActive, dotHost, rootHosts) = adblockController.readCurrentStatus()
        internal.update {
            it.copy(
                isPrivateDnsActive = dotActive,
                activePrivateDnsHost = dotHost,
                isRootHostsActive = rootHosts,
            )
        }
    }
}
