package com.swagdroid.feature.fixer

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swagdroid.core.designsystem.component.PrivilegeTierBadge
import com.swagdroid.core.designsystem.component.RiskBadge
import com.swagdroid.core.designsystem.component.SwagCard
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusDanger
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.feature.fixer.model.AppRescueAuditResult
import com.swagdroid.feature.fixer.model.DiagnosticVerdict
import com.swagdroid.feature.fixer.model.InstalledAppBrief
import com.swagdroid.feature.fixer.model.PhoneSubsystem
import com.swagdroid.feature.fixer.model.PlaybookRuntimeState
import com.swagdroid.feature.fixer.safety.FixerSafetyPolicy

@Composable
fun FixerRoute(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: FixerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    FixerScreen(
        state = state,
        contentPadding = contentPadding,
        onRunFullDiagnostics = viewModel::runFullDiagnostics,
        onSearchChange = viewModel::updateSearchQuery,
        onSelectSubsystem = viewModel::selectSubsystem,
        onToggleOnlyIssues = viewModel::toggleOnlyIssuesFilter,
        onDiagnoseSingle = viewModel::diagnoseSingle,
        onApplySingleFix = viewModel::applySingleFix,
        onRollbackSingleFix = viewModel::rollbackSingleFix,
        onOpenAppRescuer = viewModel::openAppRescuerSheet,
        onDismissBanner = viewModel::dismissBanner,
    )

    if (state.showAppRescuerSheet) {
        AppNotificationRescuerSheet(
            apps = state.userApps,
            selectedAudit = state.selectedAppAudit,
            isBusy = state.isAppRescueBusy,
            onSelectApp = viewModel::inspectAppForRescue,
            onExecuteRescue = viewModel::executeAppRescue,
            onDismiss = viewModel::closeAppRescuerSheet,
        )
    }
}

@Composable
fun FixerScreen(
    state: FixerScreenState,
    contentPadding: PaddingValues,
    onRunFullDiagnostics: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectSubsystem: (PhoneSubsystem?) -> Unit,
    onToggleOnlyIssues: () -> Unit,
    onDiagnoseSingle: (String) -> Unit,
    onApplySingleFix: (String) -> Unit,
    onRollbackSingleFix: (String, Long) -> Unit,
    onOpenAppRescuer: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag("fixer_list")
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Доктор системы",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "12 подсистем смартфона · ${state.totalPlaybooksCount} точечных диагностик с откатом",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivilegeTierBadge(
                    tier = state.privilegeState.activeTier,
                )
            }
        }

        // Status feedback banner
        state.bannerMessage?.let { msg ->
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
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

        // Full Phone Diagnostics Hero Card
        item {
            SwagCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Полная автопроверка всех узлов",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Безопасное чтение параметров сети, экрана, звука, тача, батареи, пакетов и сертификатов без изменений",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.isFullScanRunning) {
                    val progress = (state.scannedProgressCount.toFloat() / state.totalPlaybooksCount.coerceAtLeast(1))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Проверено ${state.scannedProgressCount} из ${state.totalPlaybooksCount} узлов…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatMiniPill("Отклонений", state.issuesCount.toString(), StatusWarning, Modifier.weight(1f))
                    StatMiniPill("В норме", state.healthyCount.toString(), StatusSafe, Modifier.weight(1f))
                    StatMiniPill("Исправлено", state.fixedCount.toString(), TierAdbColor, Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRunFullDiagnostics,
                        enabled = !state.isFullScanRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(SwagIcons.Pulse, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isFullScanRunning) "Идёт проверка…" else "Проверить весь телефон")
                    }
                }
            }
        }

        // Interactive App Notification Rescuer Card
        item {
            SwagCard(onClick = onOpenAppRescuer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = SwagIcons.Bell,
                        contentDescription = null,
                        tint = TierAdbColor,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Реаниматор пуш-уведомлений приложения",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Выберите Telegram, WhatsApp, банк или будильник — проверим и разблокируем все 5 звеньев доставки пушей",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = SwagIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Symptom search bar +subsystem chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth().testTag("fixer_search"),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(SwagIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    placeholder = {
                        Text("Симптом: без интернета, тихий звук, серый экран, не ставится APK…")
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedSubsystem == null && !state.onlyIssuesFilter,
                        onClick = { onSelectSubsystem(null) },
                        label = { Text("Все (${state.items.size})") },
                    )
                    FilterChip(
                        selected = state.onlyIssuesFilter,
                        onClick = onToggleOnlyIssues,
                        label = { Text("Отклонения (${state.issuesCount})") },
                    )
                    PhoneSubsystem.entries.forEach { sub ->
                        val countInSub = state.items.count { it.playbook.subsystem == sub }
                        val issuesInSub = state.items.count {
                            it.playbook.subsystem == sub && it.verdict == DiagnosticVerdict.ISSUE_FOUND
                        }
                        val label = if (issuesInSub > 0) {
                            "${sub.shortTitleRu} · !$issuesInSub"
                        } else {
                            "${sub.shortTitleRu} ($countInSub)"
                        }
                        FilterChip(
                            selected = state.selectedSubsystem == sub,
                            onClick = {
                                onSelectSubsystem(if (state.selectedSubsystem == sub) null else sub)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        //Selected subsystem description banner
        state.selectedSubsystem?.let { sub ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(sub.fullTitleRu, style = MaterialTheme.typography.titleMedium)
                        Text(
                            sub.descriptionRu,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Playbook cards
        items(state.filteredItems, key = { it.playbook.id }) { item ->
            PlaybookDiagnosticCard(
                item = item,
                onDiagnose = { onDiagnoseSingle(item.playbook.id) },
                onFix = { onApplySingleFix(item.playbook.id) },
                onRollback = { snapId -> onRollbackSingleFix(item.playbook.id, snapId) },
            )
        }
    }
}

@Composable
private fun StatMiniPill(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaybookDiagnosticCard(
    item: PlaybookRuntimeState,
    onDiagnose: () -> Unit,
    onFix: () -> Unit,
    onRollback: (Long) -> Unit,
) {
    var expanded by rememberSaveable(item.playbook.id) { mutableStateOf(false) }
    val playbook = item.playbook
    val blockedReason = FixerSafetyPolicy.automaticFixBlockReason(playbook.id)
    val context = LocalContext.current

    val verdictColor = when (item.verdict) {
        DiagnosticVerdict.HEALTHY -> StatusSafe
        DiagnosticVerdict.ISSUE_FOUND, DiagnosticVerdict.REVIEW_REQUIRED -> StatusWarning
        DiagnosticVerdict.FIXED_ACTIVE -> TierAdbColor
        DiagnosticVerdict.NEEDS_PRIVILEGE -> StatusDanger
        DiagnosticVerdict.CHECKING -> TierAdbColor
        DiagnosticVerdict.NOT_CHECKED, DiagnosticVerdict.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SwagCard(onClick = { expanded = !expanded }) {
        // Top metadata row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = playbook.subsystem.shortTitleRu.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = verdictColor.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = item.verdict.labelRu,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = verdictColor,
                    )
                }
                RiskBadge(risk = playbook.risk)
            }
        }

        // Symptom title
        Text(
            text = playbook.symptomTitle,
            style = MaterialTheme.typography.titleMedium,
        )

        // Live device readout
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = item.liveEvidence,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Expandable root-cause & technical details
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Что известно: ${playbook.rootCauseExplanation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Действие: ${playbook.fixActionSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Требуемый уровень: ${playbook.requiredTier.titleRu} · " + when {
                        blockedReason != null -> "Только проверка, без автоматических изменений"
                        playbook.supportsVaultRollback -> "Изменения записываются в StateVault"
                        else -> "Действие без автоматического отката"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        blockedReason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (playbook.id in setOf(FixerSafetyPolicy.ACCESSIBILITY_SERVICES, FixerSafetyPolicy.USER_CERTIFICATES)) {
                val accessibility = playbook.id == FixerSafetyPolicy.ACCESSIBILITY_SERVICES
                OutlinedButton(onClick = {
                    val action = if (accessibility) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_SECURITY_SETTINGS
                    runCatching { context.startActivity(Intent(action)) }
                        .onFailure { Toast.makeText(context, "Откройте соответствующий раздел настроек телефона вручную", Toast.LENGTH_LONG).show() }
                }) {
                    Text(if (accessibility) "Открыть спецвозможности" else "Открыть настройки безопасности")
                }
            }
        }

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDiagnose,
                enabled = !item.isBusy,
                modifier = Modifier.testTag("diagnose_${playbook.id}"),
            ) {
                Text("Проверить")
            }

            if (item.linkedSnapshotId != null) {
                FilledTonalButton(
                    onClick = { onRollback(item.linkedSnapshotId) },
                    enabled = !item.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SwagIcons.HistoryUndo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Откатить (#${item.linkedSnapshotId})")
                }
            } else {
                Button(
                    onClick = onFix,
                    enabled = !item.isBusy && blockedReason == null && item.verdict != DiagnosticVerdict.NOT_APPLICABLE,
                    modifier = Modifier.weight(1f).testTag("fix_${playbook.id}"),
                ) {
                    Icon(SwagIcons.Fixer, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (blockedReason != null) "Только проверка" else if (playbook.supportsVaultRollback) "Исправить" else "Выполнить")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNotificationRescuerSheet(
    apps: List<InstalledAppBrief>,
    selectedAudit: AppRescueAuditResult?,
    isBusy: Boolean,
    onSelectApp: (InstalledAppBrief) -> Unit,
    onExecuteRescue: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filterText by rememberSaveable { mutableStateOf("") }
    val filteredApps = apps.filter {
        filterText.isBlank() ||
            it.label.contains(filterText, ignoreCase = true) ||
            it.packageName.contains(filterText, ignoreCase = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Реаниматор пуш-уведомлений",
                style = MaterialTheme.typography.headlineMedium,
            )

            if (selectedAudit != null) {
                SwagCard {
                    Text(
                        text = "${selectedAudit.appLabel} (${selectedAudit.packageName})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = buildString {
                            appendLine("• Исключение из сна Doze: ${if (selectedAudit.inDozeWhitelist) "Да (ОК)" else "НЕТ (усыпляется!)"}")
                            appendLine("• Корзина активности: ${selectedAudit.standbyBucket}")
                            appendLine("• AppOps RUN_IN_BACKGROUND: ${selectedAudit.runInBackgroundMode}")
                            appendLine("• AppOps RUN_ANY_IN_BACKGROUND: ${selectedAudit.runAnyInBackgroundMode}")
                            appendLine("• AppOps POST_NOTIFICATION: ${selectedAudit.postNotificationMode}")
                            append("• Пакет заморожен/отключён: ${if (selectedAudit.isSuspendedOrDisabled) "ДА!" else "Нет"}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Button(
                        onClick = onExecuteRescue,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Разблокировать все 5 уровней фона (с точкой отката)")
                    }
                }
            }

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск приложения (Telegram, Банк, Почта…)") },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Surface(
                        onClick = { onSelectApp(app) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "Проверить →",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
