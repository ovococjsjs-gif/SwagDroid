package com.swagdroid.feature.cleaner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.swagdroid.core.designsystem.component.SwagSectionCard
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.designsystem.theme.StatusSafe
import com.swagdroid.core.designsystem.theme.StatusWarning
import com.swagdroid.core.designsystem.theme.TierAdbColor
import com.swagdroid.feature.cleaner.model.CleanerSubTab
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.model.MediaAgeFilter
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard

@Composable
fun CleanerRoute(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CleanerScreen(
        state = state,
        contentPadding = contentPadding,
        viewModel = viewModel,
    )
}

@Composable
private fun CleanerScreen(
    state: CleanerUiState,
    contentPadding: PaddingValues,
    viewModel: CleanerViewModel,
) {
    val context = LocalContext.current
    var pendingReducedShield by remember { mutableStateOf<CredentialShieldConfig?>(null) }

    (state.cleanupReview as? CleanupReview.Awaiting)?.let { review ->
        CleanupReviewDialog(review.plan, viewModel::dismissCleanupReview, viewModel::confirmCleanup)
    }
    state.lastCleanupResult?.let { result ->
        CleanupResultDialog(result, viewModel::dismissCleanupResult)
    }

    if (pendingReducedShield != null) {
        AlertDialog(
            onDismissRequest = { pendingReducedShield = null },
            title = { Text("Ослабить защиту кэша браузеров / менеджеров?") },
            text = {
                Text(
                    "По умолчанию включены исключения известных менеджеров паролей, 2FA-приложений и браузеров.\n\n" +
                        "Базовые запреты путей останутся, но фильтры не распознают все важные данные. Ослабление исключений увеличивает риск потери файлов.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReducedShield?.let { viewModel.updateShieldConfig(it) }
                        pendingReducedShield = null
                    },
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = {
                Button(onClick = { pendingReducedShield = null }) {
                    Text("Оставить исключения")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .testTag("cleaner_list")
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
                        text = "Умная очистка",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Сначала проверка списка, затем удаление без корзины",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivilegeTierBadge(tier = state.privilegeState.activeTier)
            }
        }

        // Permanent Default-ON Credential Shield Strip
        item {
            val shieldAccent = if (state.isFullShieldActive) StatusSafe else StatusWarning
            Surface(
                onClick = { viewModel.selectTab(CleanerSubTab.AUTH_SHIELD) },
                shape = RoundedCornerShape(16.dp),
                color = shieldAccent.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = SwagIcons.Shield,
                        contentDescription = null,
                        tint = shieldAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isFullShieldActive) {
                                "Исключения для важных данных: включены"
                            } else {
                                "Часть исключений для важных данных отключена"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = shieldAccent,
                        )
                        Text(
                            text = "Фильтры известных путей не заменяют резервную копию",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = SwagIcons.ChevronRight,
                        contentDescription = null,
                        tint = shieldAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Storage permission card if needed on Android 11+
        if (!state.hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item {
                SwagCard {
                    Text(
                        text = "Требуется доступ к хранилищу для поиска хвостов и кэша мессенджеров",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Разрешение расширяет доступ к общим файлам, но не открывает все чужие Android/data. Shizuku не меняет права этого файлового сканера.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть разрешение доступа к файлам")
                    }
                }
            }
        }

        // Summary & Scan/Clean Hero Card
        item {
            SwagCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Выбрано во всех разделах (оценка):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatBytesRu(state.totalSelectedReclaimableBytes),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = if (state.totalSelectedReclaimableBytes > 0L) StatusSafe else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.isPreparingCleanup) {
                    Text("Подготовка списка и проверка содержимого файлов… Ничего не удаляется.")
                    TextButton(onClick = viewModel::cancelCleanupPreparation) { Text("Отменить подготовку") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::runScan,
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f).testTag("cleaner_scan"),
                    ) {
                        Icon(SwagIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isScanning) "Сканирование…" else "Сканировать")
                    }
                    Button(
                        onClick = viewModel::requestCleanupReview,
                        enabled = !state.isBusy && state.hasScannedOnce && state.hasSelectedTargets,
                        modifier = Modifier.weight(1f).testTag("cleaner_review"),
                    ) {
                        Icon(SwagIcons.Cleaner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isCleaning) "Удаление…" else "Просмотреть план")
                    }
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

        // 5 Sub-Tabs
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CleanerSubTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.titleRu) },
                    )
                }
            }
        }

        when (state.activeTab) {
            // =================================================================
            // TAB 1: SYSTEM & APP CACHES
            // =================================================================
            CleanerSubTab.SYSTEM_CACHE -> {
                if (!state.hasScannedOnce) {
                    item {
                        EmptyHintCard("Нажмите «Сканировать», чтобы найти доступные файлы. Удаление потребует отдельного просмотра и подтверждения плана.")
                    }
                } else if (state.junkGroups.isEmpty()) {
                    item {
                        EmptyHintCard("В доступных для сканирования папках кандидаты не найдены. Это не проверка всего хранилища.")
                    }
                } else {
                    items(state.junkGroups, key = { it.category.name }) { grp ->
                        SwagCard(onClick = if (!state.isBusy && grp.category.cleanupBlockReason == null) ({ viewModel.toggleJunkCategory(grp.category) }) else null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = grp.isSelected,
                                    onCheckedChange = { viewModel.toggleJunkCategory(grp.category) },
                                    enabled = !state.isBusy && grp.category.cleanupBlockReason == null,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(grp.category.titleRu, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        grp.category.subtitleRu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    grp.category.cleanupBlockReason?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (grp.bytesCount > 0L) formatBytesRu(grp.bytesCount) else "${grp.itemsCount} шт.",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TierAdbColor,
                                    )
                                    RiskBadge(risk = grp.category.risk)
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 2: MESSENGER VAULT (Telegram, WhatsApp, TikTok, VK, Viber)
            // =================================================================
            CleanerSubTab.MESSENGERS -> {
                item {
                    SwagCard {
                        Text("Файлы мессенджеров", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Включает сохранённые фото, видео, аудио и файлы Sent — не только кэш. Ничего не выбрано автоматически. Проверьте файлы и резервную копию перед удалением.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MediaAgeFilter.entries.forEach { af ->
                                FilterChip(
                                    selected = state.ageFilter == af,
                                    onClick = { viewModel.setMediaAgeFilter(af) },
                                    enabled = !state.isBusy,
                                    label = { Text(af.labelRu) },
                                )
                            }
                        }
                    }
                }

                if (state.messengerShelves.isEmpty()) {
                    item {
                        EmptyHintCard("В доступных папках по выбранному возрасту файлы не найдены. Закрытые Android/data могли быть пропущены.")
                    }
                } else {
                    items(state.messengerShelves, key = { it.id }) { shelf ->
                        SwagCard(onClick = if (!state.isBusy) ({ viewModel.toggleMessengerShelf(shelf.id) }) else null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = shelf.isSelected,
                                    onCheckedChange = { viewModel.toggleMessengerShelf(shelf.id) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${shelf.brand.titleRu} · ${shelf.shelf.titleRu}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = "${shelf.shelf.subtitleRu} (${shelf.filesCount} ф.)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatBytesRu(shelf.bytesCount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TierAdbColor,
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 3: CORPSEFINDER (ORPHANED UNINSTALLED APP FOLDERS)
            // =================================================================
            CleanerSubTab.CORPSES -> {
                item { EmptyHintCard("Отсутствие приложения не доказывает, что папка не нужна: в ней могут остаться ваши файлы. Ничего не выбрано автоматически.") }
                if (state.corpses.isEmpty()) {
                    item {
                        EmptyHintCard("Среди доступных папок кандидаты в остатки приложений не найдены.")
                    }
                } else {
                    items(state.corpses, key = { it.id }) { corpse ->
                        SwagCard(onClick = if (!state.isBusy) ({ viewModel.toggleCorpse(corpse.id) }) else null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = corpse.isSelected,
                                    onCheckedChange = { viewModel.toggleCorpse(corpse.id) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(corpse.packageName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${corpse.locationLabelRu} · ${corpse.filesCount} ф.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        corpse.absolutePath,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatBytesRu(corpse.bytesCount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = StatusWarning,
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 4: DEDUPLICATOR (SHA-256 EXACT + 64-BIT PERCEPTUAL dHASH)
            // =================================================================
            CleanerSubTab.DUPLICATES -> {
                item { EmptyHintCard("Похожие кадры не равны точным копиям. Перед удалением проверьте список; сохраняемые оригиналы исключаются из очистки всех разделов.") }
                if (state.duplicateClusters.isEmpty()) {
                    item {
                        EmptyHintCard("В проверенной части доступных файлов дубликаты и похожие кадры не найдены.")
                    }
                } else {
                    items(state.duplicateClusters, key = { it.id }) { cluster ->
                        SwagCard(onClick = if (!state.isBusy) ({ viewModel.toggleDuplicateCluster(cluster.id) }) else null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = cluster.isSelected,
                                    onCheckedChange = { viewModel.toggleDuplicateCluster(cluster.id) },
                                    enabled = !state.isBusy,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cluster.kind.labelRu, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Сохраняемый файл: ${cluster.keptOriginalPath.substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusSafe,
                                    )
                                    Text(
                                        text = "Кандидаты на удаление (${cluster.duplicatePaths.size}): ${cluster.duplicatePaths.joinToString { it.substringAfterLast('/') }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatBytesRu(cluster.reclaimableBytes),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TierAdbColor,
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // TAB 5: DEFAULT-ON CREDENTIAL, PASSWORD & AUTOFILL SHIELD
            // =================================================================
            CleanerSubTab.AUTH_SHIELD -> {
                item {
                    SwagSectionCard(
                        title = "Двухконтурная Броня Паролей и Аккаунтов",
                        subtitle = "Исключения известных путей и приложений. Не гарантируют сохранность всех важных данных",
                        icon = SwagIcons.Shield,
                    ) {
                        Text(
                            text = "Базовый фильтр исключает известные пути accounts_ce.db, Keystore, shared_prefs, databases, Cookies, Login Data, Web Data, tgnet.dat и msgstore.db. Неизвестные форматы и расположения он может не распознать.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusSafe,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Защищать системное автозаполнение и учётные записи",
                            subtitle = "Обходит активный сервис Autofill, KeyChain и CredentialManager (по умолчанию ВКЛ)",
                            checked = state.shieldConfig.protectAccountsAndAutofill,
                            enabled = !state.isBusy,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectAccountsAndAutofill = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Не трогать менеджеры паролей, 2FA и платёжные кошельки",
                            subtitle = "Защищено пакетов по умолчанию: ${AuthAndCredentialsGuard.PASSWORD_AND_2FA_PACKAGES.size} (Google Password Manager, Bitwarden, KeePass, Proton Pass, Google/Yandex Authenticator, Mir Pay…)",
                            checked = state.shieldConfig.protectPasswordManagersAnd2Fa,
                            enabled = !state.isBusy,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectPasswordManagersAnd2Fa = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Не трогать кэши браузеров и WebView-сессий",
                            subtitle = "Защищено браузеров: ${AuthAndCredentialsGuard.BROWSER_AND_WEBVIEW_PACKAGES.size} (Chrome, Яндекс Браузер, Firefox, Brave, Opera, Samsung Internet…) — их известные каталоги исключаются",
                            checked = state.shieldConfig.protectBrowsersAndWebViewSessions,
                            enabled = !state.isBusy,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectBrowsersAndWebViewSessions = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Защищать файлы сейфов и резервных кодов (.kdbx, .2fas, .aegis)",
                            subtitle = "Исключает известные расширения баз паролей, сертификатов и конфигураций VPN",
                            checked = state.shieldConfig.protectVaultAnd2FaBackupFiles,
                            enabled = !state.isBusy,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectVaultAnd2FaBackupFiles = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )

                        if (!state.isFullShieldActive) {
                            Button(
                                onClick = viewModel::resetShieldToFullDefault,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Включить все исключения")
                            }
                        }
                    }
                }

                // Honest No-RAM-Killer Manifesto
                item {
                    SwagCard {
                        Text(
                            text = "Почему здесь нет кнопки «Очистить ОЗУ и ускорить на 90%»?",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Принудительная остановка фоновых процессов может нарушить уведомления и работу автозаполнения. Повторный запуск приложений тоже расходует ресурсы; универсального ускорения такая очистка не гарантирует.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShieldToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun EmptyHintCard(text: String) {
    SwagCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
