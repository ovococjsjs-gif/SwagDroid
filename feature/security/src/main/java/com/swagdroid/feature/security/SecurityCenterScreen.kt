package com.swagdroid.feature.security

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swagdroid.core.designsystem.component.PrivilegeTierBadge
import com.swagdroid.core.designsystem.component.SwagCard
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusDanger
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.feature.adblock.model.AdblockEngineMode
import com.swagdroid.feature.adblock.model.UpstreamDnsResolver
import com.swagdroid.feature.security.model.AggregatedThreatDecision
import com.swagdroid.feature.security.model.AppTrackerScanItem
import com.swagdroid.feature.security.model.QuarantineEntry
import com.swagdroid.feature.security.model.SecuritySubTab
import com.swagdroid.feature.security.model.ThreatActionType
import com.swagdroid.feature.security.model.ThreatSeverity

@Composable
fun SecurityCenterRoute(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenVault: () -> Unit = {},
    viewModel: SecurityCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            viewModel.onLocalVpnPermissionGranted()
        }
    }

    SecurityCenterScreen(
        state = uiState,
        contentPadding = contentPadding,
        onSelectTab = viewModel::selectTab,
        onDismissBanner = viewModel::dismissBanner,
        onRunThreatScan = viewModel::runThreatScan,
        onQuarantineThreat = viewModel::quarantineDecision,
        onDeleteLooseFile = viewModel::deleteLooseMaliciousFile,
        onCureSystemThreat = viewModel::cureSystemThreat,
        onSystemUninstall = { pkg ->
            runCatching { context.startActivity(viewModel.buildUninstallIntent(pkg)) }
        },
        onRestoreQuarantine = viewModel::restoreFromQuarantine,
        onRunTrackerScan = viewModel::runTrackerRadarScan,
        onBlockAppBackgroundTraffic = viewModel::isolateAppBackgroundTraffic,
        onSelectAdblockMode = viewModel::selectAdblockMode,
        onSelectResolver = viewModel::selectUpstreamResolver,
        onStartLocalVpn = {
            val prepIntent = VpnService.prepare(context)
            if (prepIntent != null) {
                vpnPermissionLauncher.launch(prepIntent)
            } else {
                viewModel.onLocalVpnPermissionGranted()
            }
        },
        onStopLocalVpn = viewModel::stopLocalDnsVpn,
        onEnablePrivateDns = viewModel::enableAdbPrivateDns,
        onDisablePrivateDns = viewModel::disableAdbPrivateDns,
        onApplyRootHosts = viewModel::applyRootHosts,
        onAddCustomDomain = viewModel::addCustomDomainRule,
        onRemoveCustomDomain = viewModel::removeCustomDomainRule,
        onOpenVault = onOpenVault,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityCenterScreen(
    state: SecurityCenterUiState,
    contentPadding: PaddingValues,
    onSelectTab: (SecuritySubTab) -> Unit,
    onDismissBanner: () -> Unit,
    onRunThreatScan: () -> Unit,
    onQuarantineThreat: (AggregatedThreatDecision) -> Unit,
    onDeleteLooseFile: (AggregatedThreatDecision) -> Unit,
    onCureSystemThreat: (AggregatedThreatDecision) -> Unit,
    onSystemUninstall: (String) -> Unit,
    onRestoreQuarantine: (QuarantineEntry) -> Unit,
    onRunTrackerScan: () -> Unit,
    onBlockAppBackgroundTraffic: (String, String) -> Unit,
    onSelectAdblockMode: (AdblockEngineMode) -> Unit,
    onSelectResolver: (UpstreamDnsResolver) -> Unit,
    onStartLocalVpn: () -> Unit,
    onStopLocalVpn: () -> Unit,
    onEnablePrivateDns: () -> Unit,
    onDisablePrivateDns: () -> Unit,
    onApplyRootHosts: () -> Unit,
    onAddCustomDomain: (String, Boolean) -> Unit,
    onRemoveCustomDomain: (String) -> Unit,
    onOpenVault: () -> Unit,
) {
    var customDomainDraft by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        ScrollableTabRow(
            selectedTabIndex = state.activeTab.ordinal,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SecuritySubTab.entries.forEach { tab ->
                Tab(
                    selected = state.activeTab == tab,
                    onClick = { onSelectTab(tab) },
                    text = {
                        val badge = when (tab) {
                            SecuritySubTab.THREAT_SCANNER -> state.threats.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
                            SecuritySubTab.QUARANTINE -> state.quarantinedItems.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
                            else -> ""
                        }
                        Text(
                            text = tab.titleRu + badge,
                            fontWeight = if (state.activeTab == tab) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
            }
        }

        if (state.isScanning) {
            val progress = if (state.scanTotal > 0) {
                (state.scanStep.toFloat() / state.scanTotal.toFloat()).coerceIn(0.05f, 1f)
            } else {
                0.5f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.bannerMessage != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.bannerMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissBanner) { Text("ОК") }
                        }
                    }
                }
            }

            when (state.activeTab) {
                SecuritySubTab.THREAT_SCANNER -> {
                    item {
                        SwagSectionCard(
                            title = "Антивирус & Анти-Майнер",
                            icon = SwagIcons.Shield,
                            subtitle = "5 сканеров: Bloom SHA-256 · Поиск XMRig/Stratum в APK · Охотник за HiddenAds без иконок · Аудит Accessibility · Защита сети и hosts",
                            trailingHeader = {
                                TextButton(onClick = onOpenVault) { Text("Откаты") }
                            },
                        ) {
                            Button(
                                onClick = onRunThreatScan,
                                enabled = !state.isScanning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(SwagIcons.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (state.isScanning) {
                                        "Проверка пакетов и сети (${state.scanStep}/${state.scanTotal})..."
                                    } else {
                                        "Запустить глубокую проверку угроз"
                                    },
                                )
                            }
                        }
                    }

                    if (state.hasScannedThreatsOnce && state.threats.isEmpty() && !state.isScanning) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                color = StatusSafe.copy(alpha = 0.14f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(SwagIcons.CheckCircle, contentDescription = null, tint = StatusSafe)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "В проверенных данных совпадений нет",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusSafe,
                                        )
                                        Text(
                                            text = "Это ограниченная локальная проверка сигнатур и эвристик, а не подтверждение отсутствия всех угроз. Закрытые и неподдерживаемые данные могли не проверяться.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(state.threats, key = { it.targetKey }) { decision ->
                        ThreatDecisionCard(
                            decision = decision,
                            currentTier = state.privilegeState.activeTier,
                            onQuarantine = { onQuarantineThreat(decision) },
                            onDeleteLoose = { onDeleteLooseFile(decision) },
                            onCureSystem = { onCureSystemThreat(decision) },
                            onUninstall = { decision.packageName?.let(onSystemUninstall) },
                        )
                    }
                }

                SecuritySubTab.QUARANTINE -> {
                    item {
                        SwagSectionCard(
                            title = "Изолятор угроз (Карантин)",
                            icon = SwagIcons.Snowflake,
                            subtitle = "Изолированные пакеты заморожены (SUSPENDED), лишены фоновой работы и права рисовать поверх окон. Каждая изоляция обратима через StateVault.",
                        ) {
                            Text(
                                text = "Объектов в карантине: ${state.quarantinedItems.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (state.quarantinedItems.isEmpty()) {
                        item {
                            SwagCard {
                                Text(
                                    text = "Карантин пуст. Подозрительные пакеты, отправленные в изоляцию из вкладки «Антивирус», появятся здесь.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    items(state.quarantinedItems, key = { it.vaultSnapshotId }) { entry ->
                        SwagCard {
                            Text(
                                text = entry.appLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = entry.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = entry.reasonSummaryRu,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onRestoreQuarantine(entry) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Восстановить")
                                }
                                Button(
                                    onClick = { onSystemUninstall(entry.packageName) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Удалить пакет")
                                }
                            }
                        }
                    }
                }

                SecuritySubTab.TRACKER_RADAR -> {
                    item {
                        SwagSectionCard(
                            title = "Радар трекеров (Exodus DEX)",
                            icon = SwagIcons.Search,
                            subtitle = "Прямой байт-анализ classes.dex внутри установленных приложений по 32 реальным рекламным и аналитическим SDK без отправки данных в облако",
                        ) {
                            Button(
                                onClick = onRunTrackerScan,
                                enabled = !state.isScanning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (state.isScanning) "Сканирование classes.dex..." else "Проверить приложения на вшитые трекеры",
                                )
                            }
                        }
                    }

                    items(state.trackerReports, key = { it.packageName }) { item ->
                        TrackerReportCard(
                            item = item,
                            canIsolateNetwork = state.privilegeState.activeTier.satisfies(PrivilegeTier.ADB_SHIZUKU),
                            onBlockBackground = { onBlockAppBackgroundTraffic(item.packageName, item.appLabel) },
                        )
                    }
                }

                SecuritySubTab.ADBLOCK_FIREWALL -> {
                    item {
                        SwagSectionCard(
                            title = "Адаптивный AdBlock 3-в-1",
                            icon = SwagIcons.Wifi,
                            subtitle = "Загружено суффиксных правил: ${state.totalBlockRulesCount} (покрывают тысячи рекламных и майнинг-поддоменов)",
                        ) {
                            AdblockEngineMode.entries.forEach { mode ->
                                val selected = state.selectedAdblockMode == mode
                                Card(
                                    onClick = { onSelectAdblockMode(mode) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        },
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = mode.titleRu,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f),
                                            )
                                            PrivilegeTierBadge(tier = mode.requiredTier)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = mode.subtitleRu,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "DNS-резолвер:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                UpstreamDnsResolver.entries.forEach { resolver ->
                                    FilterChip(
                                        selected = state.selectedResolver == resolver,
                                        onClick = { onSelectResolver(resolver) },
                                        label = { Text(resolver.titleRu) },
                                    )
                                }
                            }

                            when (state.selectedAdblockMode) {
                                AdblockEngineMode.LOCAL_DNS_VPN -> {
                                    if (state.isLocalVpnRunning) {
                                        OutlinedButton(
                                            onClick = onStopLocalVpn,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Остановить локальный DNS-щит (Активен)")
                                        }
                                    } else {
                                        Button(
                                            onClick = onStartLocalVpn,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Включить локальный DNS-щит (Без Root)")
                                        }
                                    }
                                }

                                AdblockEngineMode.ADB_PRIVATE_DNS -> {
                                    if (state.isPrivateDnsActive) {
                                        Text(
                                            text = "Активен DoT: ${state.activePrivateDnsHost} (Слот VPN свободен!)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = StatusSafe,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        OutlinedButton(
                                            onClick = onDisablePrivateDns,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Вернуть системный DNS в авто-режим")
                                        }
                                    } else {
                                        Button(
                                            onClick = onEnablePrivateDns,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Включить системный DoT без занятия VPN")
                                        }
                                    }
                                }

                                AdblockEngineMode.ROOT_SYSTEMLESS_HOSTS -> {
                                    Button(
                                        onClick = onApplyRootHosts,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (state.isRootHostsActive) {
                                                "Обновить Systemless /etc/hosts"
                                            } else {
                                                "Установить Systemless /etc/hosts (Root)"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SwagSectionCard(
                            title = "Свои правила доменов",
                            icon = SwagIcons.Shield,
                            subtitle = "Мгновенная блокировка любого домена и его поддоменов либо исключение (белый список)",
                        ) {
                            OutlinedTextField(
                                value = customDomainDraft,
                                onValueChange = { customDomainDraft = it },
                                singleLine = true,
                                label = { Text("Домен (например, ads.example.com)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onAddCustomDomain(customDomainDraft, true)
                                        customDomainDraft = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Блокировать")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onAddCustomDomain(customDomainDraft, false)
                                        customDomainDraft = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("В белый список")
                                }
                            }

                            val allCustom = state.customBlockedDomains.map { it to true } +
                                state.customAllowedDomains.map { it to false }
                            if (allCustom.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    allCustom.forEach { (dom, isBlk) ->
                                        FilterChip(
                                            selected = true,
                                            onClick = { onRemoveCustomDomain(dom) },
                                            label = { Text("${if (isBlk) "Блок:" else "Разр:"} $dom ✕") },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.recentDnsLog.isNotEmpty()) {
                        item {
                            SwagSectionCard(
                                title = "Живой журнал DNS-щита",
                                icon = SwagIcons.Pulse,
                                subtitle = "Последние запросы приложений в режиме реального времени",
                            ) {
                                state.recentDnsLog.take(15).forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = entry.domain,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = if (entry.wasBlocked) "NXDOMAIN" else "OK",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (entry.wasBlocked) StatusDanger else StatusSafe,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreatDecisionCard(
    decision: AggregatedThreatDecision,
    currentTier: PrivilegeTier,
    onQuarantine: () -> Unit,
    onDeleteLoose: () -> Unit,
    onCureSystem: () -> Unit,
    onUninstall: () -> Unit,
) {
    val badgeColor = when (decision.maxSeverity) {
        ThreatSeverity.CRITICAL -> StatusDanger
        ThreatSeverity.HIGH -> StatusDanger
        ThreatSeverity.MEDIUM -> StatusWarning
        ThreatSeverity.LOW -> StatusWarning
    }

    SwagCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = decision.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = decision.primaryKind.labelRu,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = badgeColor.copy(alpha = 0.16f),
            ) {
                Text(
                    text = "Риск ${decision.riskScore}/100",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                )
            }
        }

        decision.reasonsRu.forEach { reason ->
            Text(
                text = "• $reason",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        decision.evidenceLines.firstOrNull()?.let { ev ->
            Text(
                text = ev,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (decision.packageName != null) {
                if (currentTier.satisfies(PrivilegeTier.ADB_SHIZUKU)) {
                    OutlinedButton(
                        onClick = onQuarantine,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("В карантин")
                    }
                }
                Button(
                    onClick = onUninstall,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Удалить APK")
                }
            } else if (decision.action == ThreatActionType.CURE_SYSTEM_SETTING) {
                Button(
                    onClick = onCureSystem,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(decision.action.buttonLabelRu)
                }
            } else if (decision.apkFilePath != null) {
                Button(
                    onClick = onDeleteLoose,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить заражённый файл")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackerReportCard(
    item: AppTrackerScanItem,
    canIsolateNetwork: Boolean,
    onBlockBackground: () -> Unit,
) {
    val countColor = when {
        item.detectedTrackers.isEmpty() -> StatusSafe
        item.detectedTrackers.size <= 3 -> StatusWarning
        else -> StatusDanger
    }

    SwagCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = countColor.copy(alpha = 0.16f),
            ) {
                Text(
                    text = "${item.detectedTrackers.size} SDK",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = countColor,
                )
            }
        }

        if (item.detectedTrackers.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.detectedTrackers.forEach { sig ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = sig.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (canIsolateNetwork) {
                OutlinedButton(
                    onClick = onBlockBackground,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отрубить фоновый трафик трекеров (Фаервол)")
                }
            }
        }
    }
}
