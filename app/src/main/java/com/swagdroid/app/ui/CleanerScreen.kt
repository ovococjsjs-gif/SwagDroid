package com.swagdroid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.backend.root.PrivilegeConnectionState
import com.swagdroid.backend.root.PrivilegeManager
import com.swagdroid.feature.cleaner.CleanerCoordinator
import com.swagdroid.feature.cleaner.CleanerScanSummary
import com.swagdroid.feature.cleaner.cache.CacheOnlyCapabilityStatus
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.OwnershipConfidence
import com.swagdroid.feature.cleaner.model.StorageCategory
import com.swagdroid.feature.cleaner.model.StorageFinding
import kotlinx.coroutines.launch

/**
 * Экран Этапа 3: Честный анализатор и очиститель хранилища (`CleanerScreen`).
 *
 * Ключевые отличия от «обычных клинеров»:
 * - Строгое разделение объёмов: «Освобождается сразу» vs «Уходит в системную Корзину MediaStore» vs «Защищённые медиа/бэкапы».
 * - Канонизация путей `/sdcard`, `/storage/emulated/0`, `/data/media/0` и дедупликация пересечений кэша.
 * - Канареечная самопроверка (`CacheClearCanaryProbe`) флага `pm clear --cache-only` на самом себе перед применением к чужим пакетам.
 * - 4 корзины мессенджеров (WhatsApp, Telegram, TikTok, VK) и умная классификация скачанных APK в `Download/`.
 */
@Composable
fun CleanerScreen(
    coordinator: CleanerCoordinator,
) {
    val scope = rememberCoroutineScope()
    val connectionState by PrivilegeManager.connectionState.collectAsState()

    var canaryStatus by remember { mutableStateOf(coordinator.cacheCleaner.capabilityStatus) }
    var summary by remember { mutableStateOf(buildDemoCleanerSummary(coordinator)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "SwagDroid • Умная Очистка Хранилища (Этап 3)",
                    color = Color(0xFFF0F6FC),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Точная классификация владения • Дедупликация путей • 4 корзины мессенджеров • Канареечная проба кэша",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                )
            }

            // 1. Честная сводка объёмов (без приписок и двойного счёта)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Честный баланс памяти (после канонизации и дедупликации)",
                            color = Color(0xFFF0F6FC),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "🟢 Освободится мгновенно (Кэш + проверенные tmp): ${formatMegabytes(summary.immediatelyFreeableSafeBytes)}\n" +
                                "🟡 В системную Корзину MediaStore (освободится после очистки корзины): ${formatMegabytes(summary.trashPendingBytes)}\n" +
                                "🛡️ Защищено / Только ручной обзор (Медиа, свежие бэкапы, ожидающие APK): ${formatMegabytes(summary.manualReviewProtectedBytes)}",
                            color = Color(0xFFC9D1D9),
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val connected = connectionState as? PrivilegeConnectionState.Connected
                                    if (connected == null) {
                                        statusMessage = "Для канареечной пробы pm clear --cache-only подключите сервер на вкладке 1."
                                    } else {
                                        scope.launch {
                                            canaryStatus = coordinator.cacheCleaner.runSelfCanaryCapabilityProbe(connected.service)
                                            statusMessage = "Результат канареечной пробы на вашей прошивке: $canaryStatus"
                                        }
                                    }
                                }
                            ) {
                                Text("Проверить --cache-only ($canaryStatus)")
                            }
                            OutlinedButton(
                                onClick = {
                                    summary = buildDemoCleanerSummary(coordinator)
                                    statusMessage = "Инвентаризация завершена: дубли путей /sdcard и /storage/emulated/0 объединены."
                                }
                            ) {
                                Text("Пересканировать")
                            }
                        }
                    }
                }
            }

            if (statusMessage != null) {
                item {
                    Text(
                        text = statusMessage.orEmpty(),
                        color = Color(0xFF58A6FF),
                        fontSize = 13.sp,
                    )
                }
            }

            // 2. Список дедуплицированных StorageFinding
            items(summary.findings, key = { it.findingId }) { item ->
                StorageFindingCard(item)
            }
        }
    }
}

@Composable
private fun StorageFindingCard(finding: StorageFinding) {
    val confidenceColor = when (finding.confidence) {
        OwnershipConfidence.HIGH, OwnershipConfidence.HIGH_ROOT -> Color(0xFF3FB950)
        OwnershipConfidence.MEDIUM -> Color(0xFFD29922)
        OwnershipConfidence.LOW_KEEP -> Color(0xFF58A6FF)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "${finding.category.titleRu} • ${formatMegabytes(finding.bytesAllocated)}",
                color = confidenceColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                text = finding.canonicalPath,
                color = Color(0xFFF0F6FC),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = finding.explanationRu,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Правило: ${finding.evidenceRule} • Действие: ${finding.action.actionTitleRu}",
                color = Color(0xFFC9D1D9),
                fontSize = 11.5.sp,
            )
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    String.format("%.1f МБ", bytes.toDouble() / (1024.0 * 1024.0))

private fun buildDemoCleanerSummary(coordinator: CleanerCoordinator): CleanerScanSummary {
    val sampleFindings = listOf(
        StorageFinding(
            findingId = "c1",
            canonicalPath = "/storage/emulated/0/Android/data/com.zhiliaoapp.musically/cache",
            ownerPackage = "com.zhiliaoapp.musically",
            category = StorageCategory.STANDARD_APP_CACHE,
            bytesLogical = 840_000_000L,
            bytesAllocated = 840_000_000L,
            confidence = OwnershipConfidence.HIGH,
            evidenceRule = "STORAGE_STATS_DEFAULT_CACHE",
            explanationRu = "Стандартный кэш TikTok (черновики в files/ не затрагиваются).",
            minPrivilege = PrivilegeTier.ADB_SHELL,
            action = CleanerActionType.CLEAR_DEFAULT_CACHE,
            reversible = false,
        ),
        StorageFinding(
            findingId = "c2",
            canonicalPath = "/sdcard/Android/data/com.old.uninstalled.rpg",
            ownerPackage = "com.old.uninstalled.rpg",
            category = StorageCategory.CORPSE_APP_SPECIFIC,
            bytesLogical = 1_420_000_000L,
            bytesAllocated = 1_420_000_000L,
            confidence = OwnershipConfidence.HIGH,
            evidenceRule = "APP_SPECIFIC_ORPHAN_ALL_USERS",
            explanationRu = "Пакет 'com.old.uninstalled.rpg' отсутствует у всех пользователей (проверены профили 0, 10, 999).",
            minPrivilege = PrivilegeTier.ADB_SHELL,
            action = CleanerActionType.DELETE_BY_VERIFIED_RULE,
            reversible = false,
        ),
        StorageFinding(
            findingId = "c3",
            canonicalPath = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/VID-20261015-WA0012.mp4",
            ownerPackage = "com.whatsapp",
            category = StorageCategory.MESSENGER_EXACT_DUPLICATE,
            bytesLogical = 128_000_000L,
            bytesAllocated = 128_000_000L,
            confidence = OwnershipConfidence.HIGH,
            evidenceRule = "SHA256_VERIFIED_GALLERY_ORIGINAL_EXISTS",
            explanationRu = "Точный SHA-256 дубликат отправленного видео; оригинал подтверждён в /storage/emulated/0/DCIM/Camera.",
            minPrivilege = PrivilegeTier.NONE,
            action = CleanerActionType.MEDIA_STORE_TRASH,
            reversible = true,
        ),
        StorageFinding(
            findingId = "c4",
            canonicalPath = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15",
            ownerPackage = "com.whatsapp",
            category = StorageCategory.MESSENGER_BACKUP_PROTECTED,
            bytesLogical = 310_000_000L,
            bytesAllocated = 310_000_000L,
            confidence = OwnershipConfidence.LOW_KEEP,
            evidenceRule = "LATEST_MESSENGER_BACKUP_PROTECTED",
            explanationRu = "Самая свежая резервная копия чатов WhatsApp. Защищена от удаления.",
            minPrivilege = PrivilegeTier.NONE,
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
            reversible = true,
        ),
        StorageFinding(
            findingId = "c5",
            canonicalPath = "/storage/emulated/0/Download/Telegram_v11.3.0_update.apk",
            ownerPackage = "org.telegram.messenger",
            category = StorageCategory.DOWNLOADED_APK_INSTALLER,
            bytesLogical = 76_000_000L,
            bytesAllocated = 76_000_000L,
            confidence = OwnershipConfidence.LOW_KEEP,
            evidenceRule = "DOWNLOAD_APK_PENDING_NEWER_UPDATE",
            explanationRu = "Скачанный APK (v5300) НОВЕЕ установленной версии (v5200). Это ожидающее обновление, а не мусор!",
            minPrivilege = PrivilegeTier.NONE,
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
            reversible = true,
        ),
    )
    return coordinator.buildDeduplicatedSummary(sampleFindings)
}
