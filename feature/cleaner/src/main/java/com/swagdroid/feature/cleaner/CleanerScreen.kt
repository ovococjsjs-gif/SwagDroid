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

    if (pendingReducedShield != null) {
        AlertDialog(
            onDismissRequest = { pendingReducedShield = null },
            title = { Text("Ослабить защиту кэша браузеров / менеджеров?") },
            text = {
                Text(
                    "По умолчанию SwagDroid полностью обходит стороной все менеджеры паролей, 2FA-генераторы и браузеры.\n\n" +
                        "Даже при ослаблении этого фильтра аппаратный Контур 1 всё равно НИКОГДА не удалит базы паролей, Cookies, формы автозаполнения (Web Data), tgnet.dat и pm clear.",
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
                    Text("Оставить 100% защиту")
                }
            },
        )
    }

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
                        text = "Умная очистка",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Архитектура SD Maid SE · Сейф мессенджеров · 0% риска для аккаунтов",
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
                                "Броня паролей, автозаполнения и аккаунтов: ВКЛ (по умолчанию)"
                            } else {
                                "Броня паролей: Контур 1 активен (пользовательский фильтр изменён)"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = shieldAccent,
                        )
                        Text(
                            text = "Логины, Cookies, 2FA, формы автозаполнения и чаты неприкосновенны",
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
                        text = "На Android 11–16 для сканирования папок удалённых игр, Telegram/WhatsApp и дубликатов выдайте штатное разрешение управления файлами.",
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
                            text = "К безопасному освобождению:",
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

                if (state.isScanning || state.isCleaning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::runScan,
                        enabled = !state.isScanning && !state.isCleaning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(SwagIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isScanning) "Сканирование…" else "Сканировать")
                    }
                    Button(
                        onClick = viewModel::cleanAllSelected,
                        enabled = !state.isScanning && !state.isCleaning && state.hasScannedOnce,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(SwagIcons.Cleaner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (state.isCleaning) "Очистка…" else "Очистить")
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
                        EmptyHintCard("Нажмите «Сканировать», чтобы проверить кэши приложений, логи, временные файлы и пустые папки без затрагивания аккаунтов.")
                    }
                } else if (state.junkGroups.isEmpty()) {
                    item {
                        EmptyHintCard("Идеально чисто! Лишних временных кэшей и системного мусора не обнаружено.")
                    }
                } else {
                    items(state.junkGroups, key = { it.category.name }) { grp ->
                        SwagCard(onClick = { viewModel.toggleJunkCategory(grp.category) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = grp.isSelected,
                                    onCheckedChange = { viewModel.toggleJunkCategory(grp.category) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(grp.category.titleRu, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        grp.category.subtitleRu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
                        Text("Бережный Сейф Мессенджеров", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Чистит ТОЛЬКО просмотренный медиа-кэш (кружочки, видеоленту, стикеры, дубли Sent). Ключи входа (tgnet.dat), переписки и базы чатов защищены на уровне ядра.",
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
                                    label = { Text(af.labelRu) },
                                )
                            }
                        }
                    }
                }

                if (state.messengerShelves.isEmpty()) {
                    item {
                        EmptyHintCard("Тяжёлых медиа-кэшей мессенджеров по выбранному фильтру возраста не найдено.")
                    }
                } else {
                    items(state.messengerShelves, key = { it.id }) { shelf ->
                        SwagCard(onClick = { viewModel.toggleMessengerShelf(shelf.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = shelf.isSelected,
                                    onCheckedChange = { viewModel.toggleMessengerShelf(shelf.id) },
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
                if (state.corpses.isEmpty()) {
                    item {
                        EmptyHintCard("Сиротских папок от давно удалённых игр и программ в Android/data, obb и media не обнаружено.")
                    }
                } else {
                    items(state.corpses, key = { it.id }) { corpse ->
                        SwagCard(onClick = { viewModel.toggleCorpse(corpse.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = corpse.isSelected,
                                    onCheckedChange = { viewModel.toggleCorpse(corpse.id) },
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
                if (state.duplicateClusters.isEmpty()) {
                    item {
                        EmptyHintCard("Дублирующихся файлов (SHA-256) и лишних серийных кадров (dHash) не найдено.")
                    }
                } else {
                    items(state.duplicateClusters, key = { it.id }) { cluster ->
                        SwagCard(onClick = { viewModel.toggleDuplicateCluster(cluster.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = cluster.isSelected,
                                    onCheckedChange = { viewModel.toggleDuplicateCluster(cluster.id) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cluster.kind.labelRu, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Останется оригинал: ${cluster.keptOriginalPath.substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusSafe,
                                    )
                                    Text(
                                        text = "К удалению копий (${cluster.duplicatePaths.size}): ${cluster.duplicatePaths.joinToString { it.substringAfterLast('/') }}",
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
                        subtitle = "Включена по умолчанию, чтобы очистка никогда не приводила к выходу из аккаунтов",
                        icon = SwagIcons.Shield,
                    ) {
                        Text(
                            text = "Контур 1 (Неотключаемый инвариант ядра): SwagDroid физически запрещает вызов pm clear и удаление баз accounts_ce.db, Keystore, shared_prefs, databases, Cookies, Login Data, форм автозаполнения Web Data и ключей сессий tgnet.dat / msgstore.db.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusSafe,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Защищать системное автозаполнение и учётные записи",
                            subtitle = "Обходит активный сервис Autofill, KeyChain и CredentialManager (по умолчанию ВКЛ)",
                            checked = state.shieldConfig.protectAccountsAndAutofill,
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
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectPasswordManagersAnd2Fa = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Не трогать кэши браузеров и WebView-сессий",
                            subtitle = "Защищено браузеров: ${AuthAndCredentialsGuard.BROWSER_AND_WEBVIEW_PACKAGES.size} (Chrome, Яндекс Браузер, Firefox, Brave, Opera, Samsung Internet…) — сайты и формы не разлогинятся",
                            checked = state.shieldConfig.protectBrowsersAndWebViewSessions,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectBrowsersAndWebViewSessions = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        ShieldToggleRow(
                            title = "Защищать файлы сейфов и резервных кодов (.kdbx, .2fas, .aegis)",
                            subtitle = "Исключает любые базы паролей, сертификаты .p12/.pfx и конфиги VPN в Загрузках",
                            checked = state.shieldConfig.protectVaultAnd2FaBackupFiles,
                            onToggle = { next ->
                                val updated = state.shieldConfig.copy(protectVaultAnd2FaBackupFiles = next)
                                if (!next) pendingReducedShield = updated else viewModel.updateShieldConfig(updated)
                            },
                        )

                        if (!state.isFullShieldActive) {
                            Button(
                                onClick = viewModel::resetShieldToFullDefault,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Вернуть 100% защиту по умолчанию")
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
                            text = "Принудительное убийство фоновых процессов выгружает службу автозаполнения паролей, сбивает пуши мессенджеров и заставляет процессор тратить в 3 раза больше заряда батареи на повторный холодный запуск библиотек. Вместо этого используйте честный ядерный «Cached Apps Freezer» на вкладке Оптимизатор.",
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
        Switch(checked = checked, onCheckedChange = onToggle)
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
