package com.swagdroid.feature.optimizer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.swagdroid.core.designsystem.component.RiskBadge
import com.swagdroid.core.designsystem.component.SwagCard
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.feature.optimizer.model.AndroidGameModeChoice
import com.swagdroid.feature.optimizer.model.AnimationScaleChoice
import com.swagdroid.feature.optimizer.model.ArtCompileMode
import com.swagdroid.feature.optimizer.model.DebloatAppItem
import com.swagdroid.feature.optimizer.model.DebloatApplyMode
import com.swagdroid.feature.optimizer.model.ImmersiveModeOption
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.OptimizerSubTab
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset
import com.swagdroid.feature.optimizer.model.RefreshRateProfile
import com.swagdroid.feature.optimizer.model.StatusBarIconSlot

@Composable
fun OptimizerRoute(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: OptimizerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    OptimizerScreen(
        state = state,
        contentPadding = contentPadding,
        viewModel = viewModel,
    )

    if (state.showJsonSheet) {
        PortableJsonProfileSheet(
            exportedJson = state.exportedJsonPreview.orEmpty(),
            onImportJson = viewModel::importPortableJson,
            onDismiss = viewModel::closePortableJsonSheet,
        )
    }
}

@Composable
private fun OptimizerScreen(
    state: OptimizerUiState,
    contentPadding: PaddingValues,
    viewModel: OptimizerViewModel,
) {
    var customDnsInput by rememberSaveable { mutableStateOf("") }
    var boosterSearch by rememberSaveable { mutableStateOf("") }

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
                        text = "Оптимизатор PRO",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "7 пресетов · 24 твика · Тюнер UI · Game Mode · База UAD-ng (${state.allDebloatItems.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivilegeTierBadge(tier = state.privilegeState.activeTier)
            }
        }

        // Scrollable 5-subtab selector
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(label) },
                    )
                }
            }
        }

        // Feedback Banner
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
                        TextButton(onClick = viewModel::dismissBanner) {
                            Text("ОК")
                        }
                    }
                }
            }
        }

        if (state.activeTab in setOf(OptimizerSubTab.TWEAKS, OptimizerSubTab.UI_TUNER) && !state.hasLiveState) {
            item {
                SwagCard {
                    Text("Состояние параметров не получено", style = MaterialTheme.typography.titleMedium)
                    Text("Неизвестные значения не показываются как выключенные или стандартные. Проверьте доступ и повторите чтение.")
                    OutlinedButton(onClick = viewModel::refreshAll, enabled = !state.isBusy) { Text("Повторить чтение") }
                }
            }
        } else when (state.activeTab) {
            // =================================================================
            // TAB 1: PRESETS + PORTABLE JSON PROFILES
            // =================================================================
            OptimizerSubTab.PRESETS -> {
                item {
                    SwagCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(SwagIcons.Sparkles, contentDescription = null, tint = TierAdbColor, modifier = Modifier.size(26.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Переносимые JSON-профили", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Сохраните свои твики и список замороженной блоатвари в JSON или перенесите готовый шаблон на второй телефон",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = viewModel::openPortableJsonSheet,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Экспорт / Импорт JSON-конфигурации")
                        }
                    }
                }

                items(state.presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        isBusy = state.isBusy,
                        onApply = { viewModel.applyPreset(preset.id) },
                        onRollback = { snapId -> viewModel.rollbackPreset(preset.id, snapId) },
                    )
                }
            }

            // =================================================================
            // TAB 2: PRO SYSTEM, PRIVACY & NETWORK TWEAKS
            // =================================================================
            OptimizerSubTab.TWEAKS -> {
                // Graphics, Animations & Refresh Rate
                item {
                    SwagSectionCard(
                        title = "Графика, Герцовка и Плавность",
                        subtitle = "Текущий масштаб: ${state.tweaks.animatorScale}x · Частота: ${state.tweaks.peakRefreshRate ?: "Авто"} Гц",
                        icon = SwagIcons.Sparkles,
                    ) {
                        Text("Дробная шкала системных анимаций:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AnimationScaleChoice.entries.forEach { choice ->
                                FilterChip(
                                    selected = state.tweaks.animatorScale == choice.scaleText,
                                    onClick = { viewModel.setAnimationScale(choice.scaleText) },
                                    enabled = !state.isBusy,
                                    label = { Text(choice.labelRu) },
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Профиль частоты обновления матрицы:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RefreshRateProfile.entries.forEach { prof ->
                                FilterChip(
                                    selected = state.tweaks.peakRefreshRate == prof.peakHz && state.tweaks.minRefreshRate == prof.minHz,
                                    onClick = { viewModel.applyRefreshProfile(prof) },
                                    enabled = !state.isBusy,
                                    label = { Text(prof.titleRu) },
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Отключить тяжёлое размытие фона окон (Window Blurs)",
                            subtitle = "Снимает 20–30% нагрузки с GPU при открытии шторки уведомлений и папок",
                            checked = state.tweaks.disableWindowBlurs,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Отключение размытия окон",
                                    SettingNamespace.GLOBAL,
                                    "disable_window_blurs",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                    }
                }

                // Kernel, Memory & Background Limits
                item {
                    SwagSectionCard(
                        title = "Ядро, ОЗУ и Фоновые процессы",
                        subtitle = "Честное управление процессами Android без фейковых «убийц ОЗУ»",
                        icon = SwagIcons.Memory,
                    ) {
                        TweakSwitchRow(
                            title = "Ядерный замораживатель кэша (Cached Apps Freezer)",
                            subtitle = "Свёрнутые программы остаются в ОЗУ для мгновенного старта, но тратят 0.0% CPU",
                            checked = state.tweaks.cachedAppsFreezerEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Cached Apps Freezer",
                                    SettingNamespace.GLOBAL,
                                    "cached_apps_freezer",
                                    if (it) "enabled" else "device_default",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Отключить убийцу фоновых процессов (Phantom Killer)",
                            subtitle = "Запрещает Android 12–16 убивать мессенджеры, SSH, Termux и вкладки браузера",
                            checked = state.tweaks.phantomKillerDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Phantom Process Killer",
                                    SettingNamespace.GLOBAL,
                                    "settings_enable_monitor_phantom_procs",
                                    if (it) "false" else "true",
                                )
                            },
                        )

                        if (state.privilegeState.activeTier == PrivilegeTier.ROOT || state.tweaks.swappinessKernel != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "ZRAM / VM Swappiness ядра (сейчас: ${state.tweaks.swappinessKernel ?: "60"}) [Root]:",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(20 to "20 · Отклик", 60 to "60 · Баланс", 100 to "100 · Макс. ZRAM").forEach { (v, title) ->
                                    FilterChip(
                                        selected = state.tweaks.swappinessKernel == v.toString(),
                                        onClick = { viewModel.setKernelSwappiness(v) },
                                        enabled = !state.isBusy && state.privilegeState.activeTier == PrivilegeTier.ROOT,
                                        label = { Text(title) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Privacy, Passwords & Clipboard Shield
                item {
                    SwagSectionCard(
                        title = "Приватность, Пароли и Буфер обмена",
                        subtitle = "Защита от скрытой слежки и подглядывания за экраном",
                        icon = SwagIcons.Shield,
                    ) {
                        TweakSwitchRow(
                            title = "Скрывать вводимые символы паролей сразу точками",
                            subtitle = "Отключает показ последней введённой буквы пароля (защита в транспорте и на записи экрана)",
                            checked = state.tweaks.hidePasswordChars,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Скрытие символов паролей",
                                    SettingNamespace.SYSTEM,
                                    "show_password",
                                    if (it) "0" else "1",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Мгновенная блокировка кнопкой питания (0 сек задержки)",
                            subtitle = "Убирает 5-секундное окно, когда погашенный экран ещё можно открыть без ПИН/отпечатка",
                            checked = state.tweaks.instantPowerButtonLock,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Мгновенный замок кнопкой питания",
                                    SettingNamespace.SECURE,
                                    "lock_screen_lock_after_timeout",
                                    if (it) "0" else "5000",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Запретить скрытый радиопоиск Wi-Fi и Bluetooth",
                            subtitle = "Выключает геолокационное сканирование эфира при выключенных тумблерах",
                            checked = state.tweaks.hiddenRadioScanDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = viewModel::toggleHiddenRadioScanDisabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Отключить фоновую отправку отчётов о сбоях",
                            subtitle = "Блокирует упаковку и пересылку диагностических дампов приложений",
                            checked = state.tweaks.errorTelemetryDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Отключение отчётов об ошибках",
                                    SettingNamespace.GLOBAL,
                                    "send_action_app_error",
                                    if (it) "0" else "1",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilledTonalButton(
                            onClick = viewModel::applyClipboardGuard,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Включить Clipboard Guard для всех сторонних APK")
                        }
                    }
                }

                // Network, Ping & Encrypted Private DNS
                item {
                    SwagSectionCard(
                        title = "Сеть, Пинг и Шифрованный Private DNS (DoT)",
                        subtitle = "Блокировка рекламы на уровне ОС без занятия слота VPN",
                        icon = SwagIcons.Wifi,
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
                                    onClick = { viewModel.selectPrivateDns(dns) },
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = customDnsInput,
                                onValueChange = { customDnsInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Свой сервер (напр. xxxx.dns.nextdns.io)") },
                            )
                            Button(
                                onClick = { viewModel.applyCustomDnsHost(customDnsInput) },
                                enabled = !state.isBusy && customDnsInput.isNotBlank(),
                            ) {
                                Text("Задать")
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Троттлинг сканов Wi-Fi против скачков пинга в играх",
                            subtitle = "Запрещает частое переключение радиоканала во время активной сессии",
                            checked = state.tweaks.wifiScanThrottleEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Троттлинг фонового скана Wi-Fi",
                                    SettingNamespace.GLOBAL,
                                    "wifi_scan_throttle_enabled",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Бесшовный переход Wi-Fi ↔ LTE/5G без обрыва связи",
                            subtitle = "Держит сотовый канал прогретым для мгновенного подхвата звонков",
                            checked = state.tweaks.mobileDataAlwaysOn,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Бесшовный переход Wi-Fi/LTE",
                                    SettingNamespace.GLOBAL,
                                    "mobile_data_always_on",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                    }
                }
            }

            // =================================================================
            // TAB 3: SYSTEMUI TUNER (STATUS BAR, IMMERSIVE, SOUNDS)
            // =================================================================
            OptimizerSubTab.UI_TUNER -> {
                item {
                    SwagSectionCard(
                        title = "Конструктор значков статус-бара",
                        subtitle = "Уберите лишние системные значки сверху экрана (Secure:icon_blacklist)",
                        icon = SwagIcons.Optimizer,
                    ) {
                        StatusBarIconSlot.entries.forEach { slot ->
                            val isHidden = slot.token in state.tweaks.hiddenStatusBarTokens
                            TweakSwitchRow(
                                title = "Скрыть: ${slot.labelRu}",
                                subtitle = "Системный слот «${slot.token}»",
                                checked = isHidden,
                                enabled = !state.isBusy,
                                onCheckedChange = { hide -> viewModel.toggleStatusBarIcon(slot.token, hide) },
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::resetStatusBarIcons,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Показать все значки статус-бара")
                        }
                    }
                }

                item {
                    SwagSectionCard(
                        title = "Часы, Заряд и Полноэкранный режим",
                        subtitle = "Точные индикаторы и защита экрана OLED от выгорания полосок",
                        icon = SwagIcons.Battery,
                    ) {
                        TweakSwitchRow(
                            title = "Процент заряда внутри значка батареи",
                            subtitle = "System:status_bar_show_battery_percent",
                            checked = state.tweaks.batteryPercentInStatusBar,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Процент батареи",
                                    SettingNamespace.SYSTEM,
                                    "status_bar_show_battery_percent",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Секунды в системных часах (ЧЧ:ММ:СС)",
                            subtitle = "Secure:clock_seconds",
                            checked = state.tweaks.clockSecondsInStatusBar,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Секунды в часах",
                                    SettingNamespace.SECURE,
                                    "clock_seconds",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Иммерсивный режим экрана:", style = MaterialTheme.typography.labelMedium)
                        ImmersiveModeOption.entries.forEach { opt ->
                            val selected = (state.tweaks.immersivePolicy ?: "") == (opt.policyValue ?: "")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { viewModel.applyImmersiveMode(opt) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(opt.titleRu, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text(opt.subtitleRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                item {
                    SwagSectionCard(
                        title = "Аудиомикшер, Системные звуки и Виброотклик",
                        subtitle = "Тонкая настройка акустического комфорта",
                        icon = SwagIcons.Bell,
                    ) {
                        TweakSwitchRow(
                            title = "Отключить назойливое предупреждение о громкости в наушниках",
                            subtitle = "Убирает сброс громкости каждые 20 часов прослушивания",
                            checked = state.tweaks.safeAudioWarningDisabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Лимитер громкости наушников",
                                    SettingNamespace.GLOBAL,
                                    "audio_safe_volume_state",
                                    if (it) "1" else "3",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Звук подключения зарядного кабеля",
                            subtitle = "Global:charging_sounds_enabled",
                            checked = state.tweaks.chargingSoundsEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Звук зарядки",
                                    SettingNamespace.GLOBAL,
                                    "charging_sounds_enabled",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Звук блокировки и разблокировки экрана",
                            subtitle = "System:lockscreen_sounds_enabled",
                            checked = state.tweaks.lockscreenSoundsEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Звук блокировки",
                                    SettingNamespace.SYSTEM,
                                    "lockscreen_sounds_enabled",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TweakSwitchRow(
                            title = "Тактильный виброотклик системных жестов",
                            subtitle = "System:haptic_feedback_enabled",
                            checked = state.tweaks.hapticFeedbackEnabled,
                            enabled = !state.isBusy,
                            onCheckedChange = {
                                viewModel.toggleSettingFlag(
                                    "Тактильный виброотклик",
                                    SettingNamespace.SYSTEM,
                                    "haptic_feedback_enabled",
                                    if (it) "1" else "0",
                                )
                            },
                        )
                    }
                }
            }

            // =================================================================
            // TAB 4: APP BOOSTER (ART COMPILER + ANDROID GAME MODE API)
            // =================================================================
            OptimizerSubTab.BOOSTER -> {
                item {
                    SwagSectionCard(
                        title = "Глобальный компилятор ART (dex2oat) + UFS TRIM",
                        subtitle = "Реальное ускорение запуска программ за счёт AOT-компиляции байткода",
                        icon = SwagIcons.Memory,
                    ) {
                        ArtCompileMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.selectedArtMode == mode,
                                    onClick = { viewModel.selectArtCompileMode(mode) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.titleRu, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text(mode.subtitleRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Button(
                            onClick = viewModel::runGlobalArtBooster,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.isBusy) "Идёт компиляция…" else "Оптимизировать все приложения + UFS TRIM")
                        }
                    }
                }

                item {
                    SwagCard {
                        Text(
                            text = "Индивидуальный форсаж и Game Mode API (Android 12+)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Задайте системный игровой профиль (Производительность / Батарея) или скомпилируйте избранное тяжёлое приложение в режиме ${state.selectedArtMode.cliMode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = boosterSearch,
                            onValueChange = { boosterSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Поиск установленной игры или приложения…") },
                        )
                    }
                }

                val filteredBoosterApps = state.boosterApps.filter {
                    boosterSearch.isBlank() ||
                        it.label.contains(boosterSearch, ignoreCase = true) ||
                        it.packageName.contains(boosterSearch, ignoreCase = true)
                }.take(40)

                items(filteredBoosterApps, key = { it.packageName }) { app ->
                    SwagCard {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AndroidGameModeChoice.entries.forEach { gm ->
                                OutlinedButton(
                                    onClick = { viewModel.setAppGameMode(app.packageName, gm) },
                                    enabled = !state.isBusy,
                                ) {
                                    Text(gm.labelRu)
                                }
                            }
                            FilledTonalButton(
                                onClick = { viewModel.compileSingleApp(app.packageName) },
                                enabled = !state.isBusy,
                            ) {
                                Text("ART (${state.selectedArtMode.cliMode})")
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 5: DEBLOATER PRO (UAD-NG 4,123 PACKAGES)
            // =================================================================
            OptimizerSubTab.DEBLOATER -> {
                item {
                    SwagCard {
                        Text(
                            text = "Debloater Pro · База UAD-ng (${state.allDebloatItems.size} пакетов)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Найдено на устройстве: ${state.installedBloatCount} · Заморожено: ${state.frozenBloatCount}. Пакеты класса Unsafe исключены для 100% защиты от бутлупа.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Apply mode selector (Suspend vs Disable)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DebloatApplyMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.debloatApplyMode == mode,
                                    onClick = { viewModel.selectDebloatApplyMode(mode) },
                                    label = { Text(mode.titleRu) },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = state.debloatQuery,
                            onValueChange = viewModel::updateDebloatQuery,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            leadingIcon = { Icon(SwagIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            placeholder = { Text("Поиск: msa, gos, facebook, analytics, heytap…") },
                        )

                        // Installed + safety tier chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.showOnlyInstalledDebloat,
                                onClick = { viewModel.toggleInstalledOnlyFilter(true) },
                                label = { Text("На этом телефоне (${state.installedBloatCount})") },
                            )
                            FilterChip(
                                selected = !state.showOnlyInstalledDebloat,
                                onClick = { viewModel.toggleInstalledOnlyFilter(false) },
                                label = { Text("Вся база (${state.allDebloatItems.size})") },
                            )
                            listOf("Recommended" to "Рекомендуется", "Advanced" to "Продвинуто", "Expert" to "Эксперт").forEach { (key, title) ->
                                FilterChip(
                                    selected = state.selectedRemovalFilter == key,
                                    onClick = {
                                        viewModel.selectRemovalFilter(if (state.selectedRemovalFilter == key) null else key)
                                    },
                                    label = { Text(title) },
                                )
                            }
                        }

                        // Source filter chips (OEM / Google / Misc / Carrier / AOSP)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.selectedListSourceFilter == null,
                                onClick = { viewModel.selectListSourceFilter(null) },
                                label = { Text("Все источники") },
                            )
                            listOf(
                                "Oem" to "OEM Прошивка",
                                "Google" to "Google",
                                "Misc" to "Meta / Стороннее",
                                "Carrier" to "Оператор",
                                "Aosp" to "AOSP",
                            ).forEach { (srcKey, srcLabel) ->
                                FilterChip(
                                    selected = state.selectedListSourceFilter == srcKey,
                                    onClick = {
                                        viewModel.selectListSourceFilter(
                                            if (state.selectedListSourceFilter == srcKey) null else srcKey,
                                        )
                                    },
                                    label = { Text(srcLabel) },
                                )
                            }
                        }

                        // Batch helper buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = viewModel::selectAllRecommendedOnDevice,
                                enabled = !state.isBusy,
                            ) {
                                Text("Выбрать все Recommended")
                            }
                            if (state.selectedIds.isNotEmpty()) {
                                TextButton(onClick = viewModel::clearAllDebloatSelections) {
                                    Text("Снять выбор (${state.selectedIds.size})")
                                }
                            }
                            if (state.frozenBloatCount > 0) {
                                OutlinedButton(
                                    onClick = viewModel::restoreAllFrozenPackages,
                                    enabled = !state.isBusy,
                                ) {
                                    Text("Разморозить все (${state.frozenBloatCount})")
                                }
                            }
                        }

                        if (state.selectedIds.isNotEmpty()) {
                            Button(
                                onClick = viewModel::freezeSelectedBatch,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(SwagIcons.Snowflake, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Применить к выбранным (${state.selectedIds.size}) · StateVault")
                            }
                        }
                    }
                }

                items(state.visibleDebloatItems, key = { it.entry.id }) { item ->
                    DebloatPackageCard(
                        item = item,
                        isBusy = state.isBusy,
                        onToggleSelect = { viewModel.togglePackageSelection(item.entry.id) },
                        onFreeze = { viewModel.freezeSinglePackage(item.entry.id) },
                        onRestore = { viewModel.restoreSinglePackage(item.entry.id) },
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
                Text(text = "• $bullet", style = MaterialTheme.typography.bodySmall)
            }
        }
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
                Text(item.appLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.entry.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RiskBadge(risk = item.entry.riskLevel)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = item.entry.listLabelRu,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Surface(shape = RoundedCornerShape(6.dp), color = stateColor.copy(alpha = 0.14f)) {
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortableJsonProfileSheet(
    exportedJson: String,
    onImportJson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var textValue by rememberSaveable(exportedJson) { mutableStateOf(exportedJson) }
    var copiedNotice by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Экспорт и Импорт JSON-профиля", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Скопируйте текущую конфигурацию или вставьте чужой JSON-шаблон — при импорте автоматически создаётся точка отката StateVault.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            if (copiedNotice) {
                Text(
                    "JSON скопирован в буфер обмена!",
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusSafe,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("SwagDroidProfile", textValue))
                        copiedNotice = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Копировать JSON")
                }
                Button(
                    onClick = { onImportJson(textValue) },
                    enabled = textValue.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Импортировать")
                }
            }
        }
    }
}
