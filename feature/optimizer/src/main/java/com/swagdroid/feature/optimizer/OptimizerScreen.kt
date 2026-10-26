package com.swagdroid.feature.optimizer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swagdroid.core.designsystem.component.PrivilegeTierBadge
import com.swagdroid.core.designsystem.component.RiskBadge
import com.swagdroid.core.designsystem.component.SwagCard
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.feature.optimizer.model.AnimationScaleChoice
import com.swagdroid.feature.optimizer.model.DebloatAppItem
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.OptimizerSubTab
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset

@Composable
fun OptimizerRoute(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: OptimizerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OptimizerScreen(
        state = state,
        contentPadding = contentPadding,
        onSelectTab = viewModel::selectTab,
        onDismissBanner = viewModel::dismissBanner,
        onApplyPreset = viewModel::applyPreset,
        onRollbackPreset = viewModel::rollbackPreset,
        onSetAnimationScale = viewModel::setAnimationScale,
        onSelectPrivateDns = viewModel::selectPrivateDns,
        onToggleCachedFreezer = viewModel::toggleCachedAppsFreezer,
        onTogglePhantomKiller = viewModel::togglePhantomKillerDisabled,
        onToggleHiddenRadioScan = viewModel::toggleHiddenRadioScanDisabled,
        onToggleBatteryPercent = viewModel::toggleBatteryPercent,
        onToggleErrorTelemetry = viewModel::toggleErrorTelemetryDisabled,
        onRunArtBooster = viewModel::runArtBooster,
        onDebloatQueryChange = viewModel::updateDebloatQuery,
        onToggleInstalledOnly = viewModel::toggleInstalledOnlyFilter,
        onSelectRemovalFilter = viewModel::selectRemovalFilter,
        onTogglePkgSelect = viewModel::togglePackageSelection,
        onFreezeSingle = viewModel::freezeSinglePackage,
        onRestoreSingle = viewModel::restoreSinglePackage,
        onFreezeSelectedBatch = viewModel::freezeSelectedBatch,
    )
}

@Composable
fun OptimizerScreen(
    state: OptimizerUiState,
    contentPadding: PaddingValues,
    onSelectTab: (OptimizerSubTab) -> Unit,
    onDismissBanner: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onRollbackPreset: (String, Long) -> Unit,
    onSetAnimationScale: (String) -> Unit,
    onSelectPrivateDns: (PrivateDnsPreset) -> Unit,
    onToggleCachedFreezer: (Boolean) -> Unit,
    onTogglePhantomKiller: (Boolean) -> Unit,
    onToggleHiddenRadioScan: (Boolean) -> Unit,
    onToggleBatteryPercent: (Boolean) -> Unit,
    onToggleErrorTelemetry: (Boolean) -> Unit,
    onRunArtBooster: () -> Unit,
    onDebloatQueryChange: (String) -> Unit,
    onToggleInstalledOnly: (Boolean) -> Unit,
    onSelectRemovalFilter: (String?) -> Unit,
    onTogglePkgSelect: (String) -> Unit,
    onFreezeSingle: (String) -> Unit,
    onRestoreSingle: (String) -> Unit,
    onFreezeSelectedBatch: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
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
                        text = "Оптимизатор",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Пресеты в 1 клик · Твики ядра и приватности · База UAD-ng (${state.allDebloatItems.size} пакетов)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivilegeTierBadge(tier = state.privilegeState.activeTier)
            }
        }

        // Sub-tab selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptimizerSubTab.entries.forEach { tab ->
                    val label = if (tab == OptimizerSubTab.DEBLOATER && state.installedBloatCount > 0) {
                        "${tab.titleRu} (${state.installedBloatCount})"
                    } else {
                        tab.titleRu
                    }
                    FilterChip(
                        selected = state.activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Banner
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

        when (state.activeTab) {
            OptimizerSubTab.PRESETS -> {
                items(state.presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        isBusy = state.isBusy,
                        onApply = { onApplyPreset(preset.id) },
                        onRollback = { snapId -> onRollbackPreset(preset.id, snapId) },
                    )
                }
            }

            OptimizerSubTab.TWEAKS -> {
                // Animation scale card
                item {
                    SwagSectionCard(
                        title = "Скорость системных анимаций",
                        subtitle = "Текущий масштаб: ${state.tweaks.animationScale}x (дробные шаги без поломки банков)",
                        icon = SwagIcons.Sparkles,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AnimationScaleChoice.entries.forEach { choice ->
                                FilterChip(
                                    selected = state.tweaks.animationScale == choice.scaleText,
                                    onClick = { onSetAnimationScale(choice.scaleText) },
                                    enabled = !state.isBusy,
                                    label = { Text(choice.labelRu) },
                                )
                            }
                        }
                    }
                }

                // Private DNS DoT card
                item {
                    SwagSectionCard(
                        title = "Системный Частный DNS (DoT)",
                        subtitle = "Зашифрованный DNS и блокировка рекламы без расхода батареи и БЕЗ занятия слота VPN",
                        icon = SwagIcons.Shield,
                    ) {
                        PrivateDnsPreset.entries.forEach { dns ->
                            val isSelected = if (dns.hostnameValue == null) {
                                state.tweaks.privateDnsMode != "hostname"
                            } else {
                                state.tweaks.privateDnsMode == "hostname" &&
                                    state.tweaks.privateDnsHost.equals(dns.hostnameValue, ignoreCase = true)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectPrivateDns(dns) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dns.titleRu, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text(
                                        dns.subtitleRu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Core system switches
                item {
                    SwagSectionCard(
                        title = "Тумблеры ядра и приватности",
                        subtitle = "Каждое переключение сохраняет точку отката в StateVault",
                        icon = SwagIcons.Optimizer,
                    ) {
                        TweakSwitchRow(
                            title = "Заморозка кэшированных приложений (Cached Freezer)",
                            subtitle = "Свёрнутые программы остаются в ОЗУ, но потребляют ровно 0% CPU",
                            checked = state.tweaks.cachedAppsFreezerEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = onToggleCachedFreezer,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Отключить убийцу фоновых процессов (Phantom Killer)",
                            subtitle = "Android 12–16 перестанет закрывать мессенджеры, SSH, Termux и тяжёлые вкладки",
                            checked = state.tweaks.phantomKillerDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = onTogglePhantomKiller,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Запретить скрытый радиопоиск Wi-Fi и Bluetooth",
                            subtitle = "Выключает круглосуточное сканирование эфира при отключённых тумблерах",
                            checked = state.tweaks.hiddenRadioScanDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = onToggleHiddenRadioScan,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Блокировать фоновую отправку дампов ошибок",
                            subtitle = "Отключает упаковку и пересылку диагностических отчётов падений",
                            checked = state.tweaks.errorTelemetryDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = onToggleErrorTelemetry,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Процент заряда внутри иконки батареи",
                            subtitle = "Системный ключ status_bar_show_battery_percent",
                            checked = state.tweaks.batteryPercentInStatusBar,
                            enabled = !state.isBusy,
                            onCheckedChange = onToggleBatteryPercent,
                        )
                    }
                }

                // ART Booster + FSTRIM
                item {
                    SwagSectionCard(
                        title = "Ускоритель ART speed-profile + UFS TRIM",
                        subtitle = "Профильная AOT-компиляция байткода приложений и аппаратный TRIM флеш-памяти (реальных прирост скорости без фейковой «очистки ОЗУ»)",
                        icon = SwagIcons.Memory,
                    ) {
                        Button(
                            onClick = onRunArtBooster,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.isBusy) "Выполняется оптимизация…" else "Запустить ART-компиляцию и TRIM")
                        }
                    }
                }
            }

            OptimizerSubTab.DEBLOATER -> {
                item {
                    SwagCard {
                        Text(
                            text = "Безопасная заморозка блоатвари (UAD-ng)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Вместо необратимого удаления системных разделов SwagDroid использует обратимую заморозку (Suspend) со страховкой StateVault. Пакеты класса Unsafe исключены из каталога ради 100% защиты от бутлупа.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = state.debloatQuery,
                            onValueChange = onDebloatQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            leadingIcon = { Icon(SwagIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            placeholder = { Text("Поиск: msa, gos, facebook, analytics, heytap…") },
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.showOnlyInstalledDebloat,
                                onClick = { onToggleInstalledOnly(true) },
                                label = { Text("На этом телефоне (${state.installedBloatCount})") },
                            )
                            FilterChip(
                                selected = !state.showOnlyInstalledDebloat,
                                onClick = { onToggleInstalledOnly(false) },
                                label = { Text("Вся база (${state.allDebloatItems.size})") },
                            )
                            FilterChip(
                                selected = state.selectedRemovalFilter == "Recommended",
                                onClick = {
                                    onSelectRemovalFilter(
                                        if (state.selectedRemovalFilter == "Recommended") null else "Recommended",
                                    )
                                },
                                label = { Text("Рекомендуется") },
                            )
                            FilterChip(
                                selected = state.selectedRemovalFilter == "Advanced",
                                onClick = {
                                    onSelectRemovalFilter(
                                        if (state.selectedRemovalFilter == "Advanced") null else "Advanced",
                                    )
                                },
                                label = { Text("Продвинуто") },
                            )
                            FilterChip(
                                selected = state.selectedRemovalFilter == "Expert",
                                onClick = {
                                    onSelectRemovalFilter(
                                        if (state.selectedRemovalFilter == "Expert") null else "Expert",
                                    )
                                },
                                label = { Text("Эксперт") },
                            )
                        }

                        if (state.selectedIds.isNotEmpty()) {
                            Button(
                                onClick = onFreezeSelectedBatch,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(SwagIcons.Snowflake, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Заморозить выбранные (${state.selectedIds.size}) с точкой отката")
                            }
                        }
                    }
                }

                items(state.visibleDebloatItems, key = { it.entry.id }) { item ->
                    DebloatPackageCard(
                        item = item,
                        isBusy = state.isBusy,
                        onToggleSelect = { onTogglePkgSelect(item.entry.id) },
                        onFreeze = { onFreezeSingle(item.entry.id) },
                        onRestore = { onRestoreSingle(item.entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: OptimizerPresetTemplate,
    isBusy: Boolean,
    onApply: () -> Unit,
    onRollback: (Long) -> Unit,
) {
    SwagCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = preset.titleRu,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            RiskBadge(risk = preset.risk)
        }
        Text(
            text = preset.subtitleRu,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            preset.bulletPoints.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (preset.activeSnapshotId != null) {
                FilledTonalButton(
                    onClick = { onRollback(preset.activeSnapshotId) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(SwagIcons.HistoryUndo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Откатить пресет (#${preset.activeSnapshotId})")
                }
            } else {
                Button(
                    onClick = onApply,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(SwagIcons.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Применить пресет")
                }
            }
        }
    }
}

@Composable
private fun TweakSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun DebloatPackageCard(
    item: DebloatAppItem,
    isBusy: Boolean,
    onToggleSelect: () -> Unit,
    onFreeze: () -> Unit,
    onRestore: () -> Unit,
) {
    val isFrozen = item.stateTarget != PackageStateTarget.ENABLED
    val stateColor = if (isFrozen) TierAdbColor else if (item.isInstalledOnDevice) StatusWarning else StatusSafe

    SwagCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.isInstalledOnDevice) {
                Checkbox(
                    checked = item.isSelected,
                    onCheckedChange = { onToggleSelect() },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.appLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.entry.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RiskBadge(risk = item.entry.riskLevel)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = item.entry.listLabelRu,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = stateColor.copy(alpha = 0.14f),
            ) {
                Text(
                    text = if (!item.isInstalledOnDevice) "Нет на устройстве" else item.stateTarget.labelRu,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor,
                )
            }
        }

        Text(
            text = item.entry.desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (item.isInstalledOnDevice) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (isFrozen) {
                    OutlinedButton(onClick = onRestore, enabled = !isBusy) {
                        Text("Разморозить")
                    }
                } else {
                    FilledTonalButton(onClick = onFreeze, enabled = !isBusy) {
                        Icon(SwagIcons.Snowflake, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Заморозить")
                    }
                }
            }
        }
    }
}
