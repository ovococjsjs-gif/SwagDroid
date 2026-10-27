package com.swagdroid.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swagdroid.core.designsystem.theme.StatusDanger
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.core.designsystem.theme.TierRootColor
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.RootAdvisorReport
import com.swagdroid.core.model.ShizukuStatus
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessAdbWizardSheet(
    privilegeState: PrivilegeState,
    onDismiss: () -> Unit,
    onOpenAboutPhone: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenOrDownloadShizuku: () -> Unit,
    onGrantShizukuPermission: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Суперправа без ПК и без потери банков (45 сек)",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Беспроводная отладка Android 11+ вместе с Shizuku даёт SwagDroid системные права UID 2000 (shell): заморозка системного мусора, отключение телеметрии, очистка кэшей и Фиксер проблем — без разблокировки загрузчика и без удаления данных.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WizardStepCard(
                stepNumber = "Шаг 1",
                title = if (privilegeState.isDeveloperOptionsEnabled) {
                    "Режим разработчика уже включён ✓"
                } else {
                    "Включите меню «Для разработчиков»"
                },
                body = "Откройте «О телефоне» и 7 раз нажмите на «Номер сборки» (или «Версия OS»).",
                buttonLabel = "Открыть «О телефоне»",
                onAction = onOpenAboutPhone,
            )

            WizardStepCard(
                stepNumber = "Шаг 2",
                title = "Установите и откройте бесплатный мост Shizuku",
                body = when (privilegeState.shizukuStatus) {
                    ShizukuStatus.NOT_INSTALLED ->
                        "Статус: Shizuku не установлен. Скачайте официальный open-source APK с GitHub."
                    ShizukuStatus.INSTALLED_NOT_RUNNING ->
                        "Статус: Shizuku установлен, осталось нажать «Запуск через Беспроводную отладку»."
                    ShizukuStatus.PERMISSION_REQUIRED ->
                        "Статус: Shizuku запущен! Осталось выдать разрешение SwagDroid (Шаг 4)."
                    ShizukuStatus.READY ->
                        "Статус: Shizuku активен и подключён (UID ${privilegeState.shizukuUid ?: 2000}) ✓"
                },
                buttonLabel = if (privilegeState.shizukuStatus == ShizukuStatus.NOT_INSTALLED) {
                    "Скачать Shizuku (GitHub)"
                } else {
                    "Открыть Shizuku"
                },
                onAction = onOpenOrDownloadShizuku,
            )

            WizardStepCard(
                stepNumber = "Шаг 3",
                title = "Сопряжение по 6-значному коду на самом экране",
                body = "В «Для разработчиков» включите «Беспроводная отладка» → нажмите «Подключение устройства с помощью кода» и введите 6 цифр прямо во всплывающее уведомление.",
                buttonLabel = "Открыть «Для разработчиков»",
                onAction = onOpenDeveloperOptions,
            )

            Button(
                onClick = onGrantShizukuPermission,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TierAdbColor),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Шаг 4 · Подключить SwagDroid к Shizuku",
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WizardStepCard(
    stepNumber: String,
    title: String,
    body: String,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stepNumber.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = TierAdbColor,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = buttonLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootAdvisorSheet(
    advisor: RootAdvisorReport,
    onDismiss: () -> Unit,
    onRequestRoot: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Интерактивный Root-советник",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = advisor.recommendedStrategyTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TierRootColor,
            )
            Text(
                text = advisor.recommendedStrategyBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AdvisorFactRow("Устройство", "${advisor.brand} ${advisor.deviceModel} (${advisor.codename})")
                    AdvisorFactRow("Процессор / SoC", advisor.socHardware)
                    AdvisorFactRow("Android / Патч", "Android ${advisor.androidRelease} (SDK ${advisor.sdkInt}) · ${advisor.securityPatch}")
                    AdvisorFactRow("Ядро Linux", advisor.kernelVersion)
                    AdvisorFactRow(
                        "GKI 2.0 (KernelSU)",
                        if (advisor.isGki2Compatible) "Да (${advisor.gkiBranch})" else "Нет (рекомендуется Magisk)",
                    )
                    AdvisorFactRow(
                        "Схема разделов",
                        if (advisor.isAbSeamlessDevice) "A/B Seamless ${advisor.activeSlot ?: ""}".trim() else "Классическая (Only-A)",
                    )
                    AdvisorFactRow("Verified Boot", advisor.verifiedBootState)
                }
            }

            Text(
                text = "Важные предупреждения",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = StatusWarning,
            )
            advisor.cautionNotes.forEach { caution ->
                Text(
                    text = "• $caution",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                text = "Безопасный порядок действий",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            advisor.guidedSteps.forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Button(
                onClick = onRequestRoot,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TierRootColor),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Уже есть Magisk / KernelSU / APatch → Запросить Root",
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AdvisorFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StateVaultHistorySheet(
    snapshots: List<VaultSnapshot>,
    onDismiss: () -> Unit,
    onCreateBaseline: () -> Unit,
    onExportJson: () -> Unit,
    onRollbackSingle: (Long) -> Unit,
    onRollbackAll: () -> Unit,
) {
    val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "StateVault · Точки восстановления и Откат",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Для поддерживаемых изменений сохраняются исходные значения и журнал шагов. Откат проверяется чтением; при конфликте, неизвестном завершении команды или повреждённой записи система не перезаписывается вслепую.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCreateBaseline,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Снимок")
                }
                OutlinedButton(
                    onClick = onExportJson,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Экспорт JSON")
                }
                if (snapshots.any { it.status.canAttemptRollback }) {
                    Button(
                        onClick = onRollbackAll,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusDanger),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Откатить доступные")
                    }
                }
            }

            if (snapshots.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        text = "Журнал пока пуст. Нажмите «Создать слепок», чтобы зафиксировать исходные настройки вашего смартфона.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                snapshots.forEach { snap ->
                    val canRestore = snap.status.canAttemptRollback
                    val badgeColor = when {
                        snap.status == SnapshotStatus.ACTIVE -> StatusSafe
                        snap.status in setOf(SnapshotStatus.ROLLED_BACK, SnapshotStatus.CHECKPOINT) -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> StatusDanger
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${snap.id} · ${snap.title}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = snap.status.labelRu,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = badgeColor,
                                )
                            }
                            Text(
                                text = "${snap.source.titleRu} · ${formatter.format(Date(snap.createdAtEpochMs))} · ${snap.privilegeTierUsed.shortBadge}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = snap.reason,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            snap.operations.take(4).forEach { op ->
                                Text(
                                    text = "• ${op.summaryRu}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (snap.operations.size > 4) {
                                Text(
                                    text = "…и ещё параметров: ${snap.operations.size - 4}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            snap.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusDanger) }
                            if (snap.steps.isNotEmpty()) {
                                Text("Шаги: ${snap.steps.joinToString { "${it.index + 1}:${it.status.name}" }}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (canRestore) {
                                OutlinedButton(
                                    onClick = { onRollbackSingle(snap.id) },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Проверить и восстановить")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
