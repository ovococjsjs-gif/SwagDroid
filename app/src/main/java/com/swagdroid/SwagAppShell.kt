package com.swagdroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swagdroid.core.designsystem.component.RiskBadge
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.model.RiskLevel
import com.swagdroid.core.model.SwagTab
import com.swagdroid.feature.dashboard.DashboardRoute
import com.swagdroid.feature.fixer.FixerRoute
import com.swagdroid.feature.optimizer.OptimizerRoute

@Composable
fun SwagAppShell() {
    var currentTab by rememberSaveable { mutableStateOf(SwagTab.DASHBOARD) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                SwagTab.entries.forEach { tab ->
                    val selected = tab == currentTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.iconVector(),
                                contentDescription = tab.titleRu,
                            )
                        },
                        label = {
                            Text(
                                text = tab.titleRu,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (currentTab) {
            SwagTab.DASHBOARD -> {
                DashboardRoute(
                    onNavigateToTab = { target -> currentTab = target },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            SwagTab.FIXER -> {
                FixerRoute(contentPadding = innerPadding)
            }
            SwagTab.OPTIMIZER -> {
                OptimizerRoute(contentPadding = innerPadding)
            }
            SwagTab.CLEANER -> {
                ModuleBlueprintScreen(
                    title = "Умная очистка системы (Этап 3)",
                    subtitle = "Честная зачистка без фейковых «ускорителей ОЗУ»",
                    icon = SwagIcons.Cleaner,
                    plannedFeatures = listOf(
                        "Сейф мессенджеров (Telegram, WhatsApp, TikTok, VK): кружочки, видео, стикеры" to RiskLevel.SAFE,
                        "CorpseFinder: поиск сиротских папок давно удалённых игр и программ" to RiskLevel.SAFE,
                        "Очистка всех кэшей одной командой через ADB / Shizuku / Root" to RiskLevel.SAFE,
                        "Удаление зависших сессий установщика APK (staging-sessions) и логов" to RiskLevel.SAFE,
                        "Поиск дубликатов и серий похожих снимков (SHA-256 + pHash)" to RiskLevel.SAFE,
                    ),
                    onBackToDashboard = { currentTab = SwagTab.DASHBOARD },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            SwagTab.SECURITY -> {
                ModuleBlueprintScreen(
                    title = "Антивирус, Анти-майнер и AdBlock (Этап 4)",
                    subtitle = "Архитектура MinerSearch + Hypatia Bloom-сканер + 3-режимный AdBlock",
                    icon = SwagIcons.Shield,
                    plannedFeatures = listOf(
                        "Офлайн-сканер вредоносных APK/DEX по фильтрам Блума + карантин" to RiskLevel.SAFE,
                        "Детектор скрытых криптомайнеров (ELF/Stratum/фоновый нагрев при выкл. экране)" to RiskLevel.SAFE,
                        "Охотник на призрачный Adware (пустые иконки, скрытый Launcher, оверлеи)" to RiskLevel.SAFE,
                        "Аудит опасных прав Accessibility / DeviceAdmin и встроенных трекеров (Exodus)" to RiskLevel.SAFE,
                        "3-режимный AdBlock (Systemless Hosts / Private DNS DoT без слота VPN / Фаервол)" to RiskLevel.SAFE,
                    ),
                    onBackToDashboard = { currentTab = SwagTab.DASHBOARD },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun ModuleBlueprintScreen(
    title: String,
    subtitle: String,
    icon: ImageVector,
    plannedFeatures: List<Pair<String, RiskLevel>>,
    onBackToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SwagSectionCard(
                title = title,
                subtitle = subtitle,
                icon = icon,
            ) {
                Text(
                    text = "Ядро привилегий (:core:privilege) и транзакционный движок откатов (:core:vault) уже подключены и готовы к работе этого модуля.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        plannedFeatures.forEach { (featureText, risk) ->
            item {
                SwagSectionCard(
                    title = featureText,
                    icon = SwagIcons.CheckCircle,
                    trailingHeader = { RiskBadge(risk) },
                ) {
                    Text(
                        text = "Интегрируется с журналом восстановления StateVault.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onBackToDashboard,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Вернуться на экран «Пульс»", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun SwagTab.iconVector(): ImageVector = when (this) {
    SwagTab.DASHBOARD -> SwagIcons.Pulse
    SwagTab.SECURITY -> SwagIcons.Shield
    SwagTab.CLEANER -> SwagIcons.Cleaner
    SwagTab.OPTIMIZER -> SwagIcons.Optimizer
    SwagTab.FIXER -> SwagIcons.Fixer
}
