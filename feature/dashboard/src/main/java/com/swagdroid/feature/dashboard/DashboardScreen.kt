package com.swagdroid.feature.dashboard

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swagdroid.core.designsystem.R as DesignR
import com.swagdroid.core.designsystem.component.HealthScoreGauge
import com.swagdroid.core.designsystem.component.PrivilegeTierBadge
import com.swagdroid.core.designsystem.component.RiskBadge
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.component.UsageProgressBar
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusDanger
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.core.designsystem.theme.TierRootColor
import com.swagdroid.core.model.HealthSignal
import com.swagdroid.core.model.RiskLevel
import com.swagdroid.core.model.RootProvider
import com.swagdroid.core.model.SignalSeverity
import com.swagdroid.core.model.SwagTab

@Composable
fun DashboardRoute(
    onNavigateToTab: (SwagTab) -> Unit,
    openVaultTrigger: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(openVaultTrigger) {
        if (openVaultTrigger > 0) {
            viewModel.openSheet(DashboardSheet.StateVaultHistory)
        }
    }

    DashboardScreen(
        uiState = uiState,
        onRefresh = viewModel::refreshAll,
        onDismissBanner = viewModel::dismissBanner,
        onOpenWirelessWizard = { viewModel.openSheet(DashboardSheet.WirelessAdbWizard) },
        onOpenRootAdvisor = { viewModel.openSheet(DashboardSheet.RootAdvisor) },
        onOpenVaultHistory = { viewModel.openSheet(DashboardSheet.StateVaultHistory) },
        onCreateBaseline = viewModel::createBaselineCheckpoint,
        onApplySnappyAnimations = viewModel::applySnappyAnimationsTweak,
        onNavigateToTab = onNavigateToTab,
        modifier = modifier,
    )

    when (uiState.activeSheet) {
        DashboardSheet.None -> Unit
        DashboardSheet.WirelessAdbWizard -> {
            WirelessAdbWizardSheet(
                privilegeState = uiState.privilege,
                onDismiss = viewModel::closeSheet,
                onOpenAboutPhone = {
                    runCatching { context.startActivity(viewModel.aboutPhoneIntent()) }
                },
                onOpenDeveloperOptions = {
                    runCatching { context.startActivity(viewModel.developerOptionsIntent()) }
                },
                onOpenOrDownloadShizuku = {
                    runCatching { context.startActivity(viewModel.shizukuIntent()) }
                },
                onGrantShizukuPermission = viewModel::requestShizukuPermission,
            )
        }
        DashboardSheet.RootAdvisor -> {
            uiState.passport?.advisor?.let { advisor ->
                RootAdvisorSheet(
                    advisor = advisor,
                    onDismiss = viewModel::closeSheet,
                    onRequestRoot = viewModel::requestRootAccess,
                )
            }
        }
        DashboardSheet.StateVaultHistory -> {
            StateVaultHistorySheet(
                snapshots = uiState.snapshots,
                onDismiss = viewModel::closeSheet,
                onCreateBaseline = viewModel::createBaselineCheckpoint,
                onExportJson = {
                    viewModel.exportVaultJson { exportedJson ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "SwagDroid StateVault Backup")
                            putExtra(Intent.EXTRA_TEXT, exportedJson)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Экспорт точек отката StateVault"))
                        }
                    }
                },
                onRollbackSingle = viewModel::rollbackSnapshot,
                onRollbackAll = viewModel::rollbackAllActive,
            )
        }
    }
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onDismissBanner: () -> Unit,
    onOpenWirelessWizard: () -> Unit,
    onOpenRootAdvisor: () -> Unit,
    onOpenVaultHistory: () -> Unit,
    onCreateBaseline: () -> Unit,
    onApplySnappyAnimations: () -> Unit,
    onNavigateToTab: (SwagTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Header Row with Official SwagDroid Emblem
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.swag_brand_logo),
                    contentDescription = "SwagDroid Emblem",
                    modifier = Modifier.size(46.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SwagDroid · v1.0",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Здоровье, защита и полный контроль",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivilegeTierBadge(tier = uiState.privilege.activeTier)
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = SwagIcons.Refresh,
                        contentDescription = "Обновить телеметрию",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Optional status toast/banner
        uiState.bannerMessage?.let { message ->
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissBanner) {
                            Text("ОК")
                        }
                    }
                }
            }
        }

        if (uiState.isLoading || uiState.passport == null || uiState.health == null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Считывание паспорта железа и системных служб…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        val passport = uiState.passport
        val health = uiState.health

        // 2. Hero System Health Pulse Card
        item {
            SwagSectionCard(
                title = "${passport.advisor.brand} ${passport.advisor.deviceModel}",
                subtitle = "Android ${passport.advisor.androidRelease} · ${passport.advisor.socHardware} · ${passport.advisor.codename}",
                icon = SwagIcons.Pulse,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HealthScoreGauge(
                        score = health.score,
                        statusLabel = health.statusLabel,
                        size = 152.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = health.subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Патч безопасности: ${passport.advisor.securityPatch}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = onOpenVaultHistory,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = SwagIcons.HistoryUndo,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("StateVault (${uiState.activeSnapshotsCount})")
                        }
                    }
                }

                if (health.signals.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        health.signals.forEach { signal ->
                            HealthSignalRow(
                                signal = signal,
                                onClick = {
                                    if (signal.id == "privilege_basic") {
                                        onOpenWirelessWizard()
                                    } else if (signal.id == "no_checkpoint") {
                                        onCreateBaseline()
                                    } else {
                                        onNavigateToTab(signal.targetTab)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 3. 3-Tier Privilege Escalation Card (Zero-PC ADB + Root Advisor)
        item {
            SwagSectionCard(
                title = "Ядро привилегий (3 уровня)",
                subtitle = uiState.privilege.activeTier.titleRu,
                icon = SwagIcons.TerminalAdb,
                accentColor = TierAdbColor,
            ) {
                val rootDetail = if (uiState.privilege.rootProvider != RootProvider.NONE) {
                    "Активен провайдер: ${uiState.privilege.rootProvider.displayName} (${uiState.privilege.rootVersion ?: "UID 0"})"
                } else {
                    "Получите 90% системных возможностей за 45 сек прямо на экране смартфона (без ПК и с рабочими банками) или откройте Root-советник для ${passport.advisor.deviceModel}."
                }
                Text(
                    text = rootDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onOpenWirelessWizard,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = TierAdbColor),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("ADB без ПК", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onOpenRootAdvisor,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = SwagIcons.RootBolt,
                            contentDescription = null,
                            tint = TierRootColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Root-советник")
                    }
                }
            }
        }

        // 4. StateVault & Safe Quick Action Card
        item {
            SwagSectionCard(
                title = "StateVault · Безопасные твики с откатом",
                subtitle = "Активных точек восстановления: ${uiState.activeSnapshotsCount}",
                icon = SwagIcons.HistoryUndo,
                accentColor = StatusSafe,
                trailingHeader = { RiskBadge(RiskLevel.SAFE) },
            ) {
                Text(
                    text = "Любое вмешательство записывает точные предыдущие настройки в локальную БД Room перед выполнением и поддерживает мгновенный LIFO-откат.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onCreateBaseline,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Слепок системы")
                    }
                    Button(
                        onClick = onApplySnappyAnimations,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Анимации 0.5x")
                    }
                }
            }
        }

        // 5. Honest Battery, Storage & Memory Passport Card
        item {
            SwagSectionCard(
                title = "Честный паспорт батареи и памяти",
                subtitle = "Прямые показания контроллера питания и ядра",
                icon = SwagIcons.Battery,
            ) {
                val bat = passport.battery
                val capacityLine = buildString {
                    append("${bat.levelPercent}%")
                    if (bat.isCharging) append(" ⚡ Заряжается")
                    bat.estimatedWearHealthPercent?.let { append(" · Ресурс ~$it%") }
                    bat.cycleCount?.let { append(" · Циклов: $it") }
                }
                val detailLine = buildString {
                    append("Температура: ${bat.temperatureCelsius}°C · ${bat.voltageMv} мВ · ${bat.technology}")
                    if (bat.fullChargeEstimatedMah != null && bat.designCapacityMah != null) {
                        append("\nОценка ёмкости: ~${bat.fullChargeEstimatedMah} из ${bat.designCapacityMah} мА·ч (${bat.healthStatusLabel})")
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = capacityLine,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = detailLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                UsageProgressBar(
                    label = "Накопитель (/data)",
                    valueRight = "${formatBytesRu(passport.storage.usedBytes)} / ${formatBytesRu(passport.storage.totalBytes)} (своб. ${formatBytesRu(passport.storage.freeBytes)})",
                    fraction = passport.storage.usedFraction,
                )

                UsageProgressBar(
                    label = "Оперативная память (RAM)",
                    valueRight = "${formatBytesRu(passport.memory.usedRamBytes)} / ${formatBytesRu(passport.memory.totalRamBytes)}",
                    fraction = passport.memory.usedRamFraction,
                )

                if (passport.memory.zramTotalBytes > 0L) {
                    val zramFrac = (passport.memory.zramUsedBytes.toFloat() / passport.memory.zramTotalBytes.toFloat())
                        .coerceIn(0f, 1f)
                    UsageProgressBar(
                        label = "Сжатый раздел подкачки (ZRAM)",
                        valueRight = "${formatBytesRu(passport.memory.zramUsedBytes)} / ${formatBytesRu(passport.memory.zramTotalBytes)}",
                        fraction = zramFrac,
                    )
                }

                Text(
                    text = "Ядро: ${passport.advisor.kernelVersion} · SELinux: ${when (passport.advisor.selinuxEnforcing) { true -> "Enforcing"; false -> "Permissive"; null -> "Auto" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthSignalRow(
    signal: HealthSignal,
    onClick: () -> Unit,
) {
    val tint = when (signal.severity) {
        SignalSeverity.CRITICAL -> StatusDanger
        SignalSeverity.ATTENTION -> StatusWarning
        SignalSeverity.GOOD -> StatusSafe
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = SwagIcons.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = signal.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = signal.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = SwagIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
