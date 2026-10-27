package com.swagdroid.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.core.vault.StateVaultManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    vaultManager: StateVaultManager,
    private val pipeline: SecurityPipelineEngine,
    private val adblockController: AdblockController,
    private val blocklistRepo: BlocklistRepository,
) : ViewModel() {

    private val internal = MutableStateFlow(InternalSecState())

    /**
     * Rehydrates active Quarantine items directly from Room-backed StateVault snapshots so
     * isolated packages survive app restarts and phone reboots with zero state loss!
     */
    private val persistedQuarantineFlow = vaultManager.snapshotsFlow.map { snapshots ->
        snapshots.mapNotNull(::snapshotToQuarantineEntry)
    }

    private val adblockExtrasFlow = combine(
        SwagDnsVpnService.isRunningFlow,
        blocklistRepo.recentLog,
        blocklistRepo.customBlocked,
        blocklistRepo.customAllowed,
    ) { vpnRunning, dnsLog, customBlk, customAlw ->
        AdblockDynamicSlice(vpnRunning, dnsLog, customBlk, customAlw)
    }

    val uiState: StateFlow<SecurityCenterUiState> = combine(
        privilegeManager.state,
        internal,
        persistedQuarantineFlow,
        adblockExtrasFlow,
    ) { priv, st, quarantined, adSlice ->
        SecurityCenterUiState(
            privilegeState = priv,
            activeTab = st.activeTab,
            threats = st.threats,
            quarantinedItems = quarantined,
            trackerReports = st.trackerReports,
            hasScannedThreatsOnce = st.hasScannedThreatsOnce,
            hasScannedTrackersOnce = st.hasScannedTrackersOnce,
            isScanning = st.isScanning,
            scanStep = st.scanStep,
            scanTotal = st.scanTotal,
            selectedAdblockMode = st.selectedAdblockMode,
            selectedResolver = st.selectedResolver,
            isLocalVpnRunning = adSlice.vpnRunning,
            isPrivateDnsActive = st.isPrivateDnsActive,
            activePrivateDnsHost = st.activePrivateDnsHost,
            isRootHostsActive = st.isRootHostsActive,
            totalBlockRulesCount = blocklistRepo.totalRulesCount,
            customBlockedDomains = adSlice.customBlk,
            customAllowedDomains = adSlice.customAlw,
            recentDnsLog = adSlice.dnsLog,
            bannerMessage = st.bannerMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SecurityCenterUiState(),
    )

    init {
        launchSafely {
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

    fun runThreatScan() {
        if (internal.value.isScanning) return
        launchSafely {
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
                        "В проверенной части данных совпадений не найдено. Это не подтверждает безопасность всего устройства."
                    } else {
                        "Найдено угроз и подозрительных объектов: ${decisions.size}"
                    },
                )
            }
        }
    }

    fun quarantineDecision(decision: AggregatedThreatDecision) {
        val pkg = decision.packageName ?: return
        launchSafely {
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
        launchSafely {
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
            launchSafely {
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
        launchSafely {
            val ok = pipeline.restoreFromQuarantine(entry)
            if (ok) {
                internal.update { cur ->
                    cur.copy(
                        bannerMessage = "Пакет «${entry.appLabel}» восстановлен из карантина",
                    )
                }
            }
        }
    }

    fun buildUninstallIntent(packageName: String) = pipeline.buildSystemUninstallIntent(packageName)

    fun runTrackerRadarScan() {
        if (internal.value.isScanning) return
        launchSafely {
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
        launchSafely {
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

    fun selectAdblockMode(mode: AdblockEngineMode) {
        internal.update { it.copy(selectedAdblockMode = mode) }
    }

    fun selectUpstreamResolver(resolver: UpstreamDnsResolver) {
        internal.update { it.copy(selectedResolver = resolver) }
    }

    fun onLocalVpnPermissionGranted() {
        adblockController.startLocalDnsVpn(internal.value.selectedResolver)
        internal.update { it.copy(bannerMessage = "Локальный DNS-щит запущен (фильтруются IPv4 + IPv6 UDP:53 запросы)") }
    }

    fun stopLocalDnsVpn() {
        adblockController.stopLocalDnsVpn()
        internal.update { it.copy(bannerMessage = "Локальный DNS-щит остановлен") }
    }

    fun enableAdbPrivateDns() {
        launchSafely {
            when (val res = adblockController.enableAdbPrivateDns(internal.value.selectedResolver)) {
                is VaultTransactionResult.Success -> {
                    refreshAdblockStatus()
                    internal.update {
                        it.copy(bannerMessage = "Параметры Private DNS записаны и прочитаны обратно (#${res.snapshotId}). Работу и фильтрацию внешнего резолвера нужно проверить отдельно.")
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    fun disableAdbPrivateDns() {
        launchSafely {
            when (val result = adblockController.disableAdbPrivateDns()) {
                is VaultTransactionResult.Success -> {
                    refreshAdblockStatus()
                    internal.update { it.copy(bannerMessage = "Параметр Private DNS переведён в авто-режим и прочитан обратно") }
                }
                is VaultTransactionResult.Failure -> internal.update { it.copy(bannerMessage = result.errorMessage) }
            }
        }
    }

    fun applyRootHosts() {
        launchSafely {
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
                    "Домен сохранён в ${if (block) "чёрный" else "белый"} список"
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

    private data class AdblockDynamicSlice(
        val vpnRunning: Boolean,
        val dnsLog: List<DnsQueryLogEntry>,
        val customBlk: Set<String>,
        val customAlw: Set<String>,
    )

    companion object {
        internal fun snapshotToQuarantineEntry(snapshot: VaultSnapshot): QuarantineEntry? {
            if (snapshot.source != FeatureSource.SECURITY || snapshot.status != SnapshotStatus.ACTIVE) {
                return null
            }
            val pkgOp = snapshot.operations.filterIsInstance<VaultOperation.PackageStateChange>()
                .firstOrNull { it.newState == PackageStateTarget.SUSPENDED }
                ?: return null
            val cleanTitle = snapshot.title.removePrefix("Карантин угрозы:").trim().ifEmpty { pkgOp.packageName }
            return QuarantineEntry(
                packageName = pkgOp.packageName,
                appLabel = cleanTitle,
                quarantinedAtEpochMs = snapshot.createdAtEpochMs,
                reasonSummaryRu = snapshot.reason,
                vaultSnapshotId = snapshot.id,
            )
        }
    }
    private fun launchSafely(action: suspend () -> Unit) = viewModelScope.launch {
        try {
            action()
        } catch (cancelled: CancellationException) {
            internal.update { it.copy(isScanning = false) }
            throw cancelled
        } catch (error: Exception) {
            internal.update { it.copy(isScanning = false, hasScannedThreatsOnce = false, hasScannedTrackersOnce = false, bannerMessage = "Проверка/операция не завершена: ${error.message}. Отсутствие угроз или успех изменения не подтверждены.") }
        }
    }

}
