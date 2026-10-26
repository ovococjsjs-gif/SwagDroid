package com.swagdroid.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swagdroid.adb.AdbPairingForegroundService
import com.swagdroid.adb.OemDiagnosticAdvisor
import com.swagdroid.api.PackageSummary
import com.swagdroid.backend.root.PrivilegeConnectionState
import com.swagdroid.backend.root.PrivilegeManager
import com.swagdroid.core.rollback.RollbackJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Главный экран Этапа 1:
 * - Панель статуса единого сервера `swagdroid_server` (UID, PID, SELinux, Backend, WRITE_SECURE_SETTINGS)
 * - Живая OEM-диагностика (Xiaomi HyperOS / Samsung OneUI / ColorOS / Pixel) с переходом в нужный раздел настроек
 * - Запуск сопряжения в 1 клик через уведомление `RemoteInput` или авто-эскалация через Root / Shizuku
 * - Интерактивный самотест Binder IPC (`getInstalledPackages`, `trimAllCaches`)
 * - Пошаговый лог подключения для прозрачной отладки
 */
@Composable
fun PrivilegeCenterScreen(
    rollbackJournal: RollbackJournal,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by PrivilegeManager.connectionState.collectAsState()
    val stepLogs by AdbPairingForegroundService.stepLogs.collectAsState()
    val transactions by rollbackJournal.transactions.collectAsState()

    var oemReport by remember {
        mutableStateOf(OemDiagnosticAdvisor(context).evaluateReadiness())
    }
    var quickTestSummary by remember { mutableStateOf<String?>(null) }

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
                    text = "SwagDroid • Ядро Привилегий (Этап 1)",
                    color = Color(0xFFF0F6FC),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Единый сервер swagdroid_server (Wireless ADB / Shizuku / Root) + Журнал откатов",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                )
            }

            // 1. Карточка статуса сервера
            item {
                ServerStatusCard(
                    state = connectionState,
                    onAutoConnect = {
                        oemReport = OemDiagnosticAdvisor(context).evaluateReadiness()
                        PrivilegeManager.autoEscalatePrivileges(context)
                    },
                    onStartWirelessPairing = {
                        oemReport = OemDiagnosticAdvisor(context).evaluateReadiness()
                        val intent = Intent(context, AdbPairingForegroundService::class.java).apply {
                            action = AdbPairingForegroundService.ACTION_START_PAIRING_SEARCH
                        }
                        context.startForegroundService(intent)
                    },
                    onRunBinderSelfTest = { service ->
                        scope.launch {
                            val resultText = withContext(Dispatchers.IO) {
                                runCatching {
                                    val json = Json { ignoreUnknownKeys = true }
                                    val pkgs = json.decodeFromString<List<PackageSummary>>(
                                        service.getInstalledPackagesJson(0, 0)
                                    )
                                    val systemCount = pkgs.count { it.isSystemApp }
                                    val userCount = pkgs.size - systemCount
                                    val protectedCount = pkgs.count { it.isCriticalProtected }
                                    "Самотест Binder IPC успешен: получено ${pkgs.size} пакетов " +
                                        "(пользовательских: $userCount, системных: $systemCount, " +
                                        "под защитой CriticalPackageGuard: $protectedCount)"
                                }.getOrElse { "Ошибка самотеста: ${it.message}" }
                            }
                            quickTestSummary = resultText
                        }
                    },
                )
            }

            if (quickTestSummary != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF132F20)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = quickTestSummary.orEmpty(),
                            color = Color(0xFF56D364),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // 2. Карточка OEM-диагностики и подготовки к сопряжению в 1 клик
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Диагностика устройства и OEM-профиль",
                                    color = Color(0xFFF0F6FC),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                )
                                Text(
                                    text = "${oemReport.vendorProfile.displayName} • Android SDK ${oemReport.sdkInt}",
                                    color = Color(0xFF58A6FF),
                                    fontSize = 12.sp,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    oemReport = OemDiagnosticAdvisor(context).evaluateReadiness()
                                }
                            ) {
                                Text("Обновить", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        oemReport.checklist.forEach { step ->
                            val badgeColor = when {
                                step.isPassed && step.isBlocking -> Color(0xFF3FB950)
                                !step.isPassed && step.isBlocking -> Color(0xFFF85149)
                                else -> Color(0xFFD29922)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = step.titleRu,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = step.descriptionRu,
                                    color = Color(0xFF8B949E),
                                    fontSize = 12.sp,
                                )
                                if (step.actionSettingsIntent != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(step.actionSettingsIntent).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        Text("Открыть этот раздел Настроек", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Карточка транзакционного журнала откатов (RollbackJournal)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Журнал транзакций и Откат (RollbackJournal)",
                            color = Color(0xFFF0F6FC),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        val activeCount = transactions.count { !it.isRolledBack }
                        Text(
                            text = "Активных снимков состояния: $activeCount (всего записей: ${transactions.size})",
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 4. Живой лог шагов подключения (для быстрой диагностики на любом устройстве)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Журнал шагов привилегированного движка",
                            color = Color(0xFFF0F6FC),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF090C10), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            if (stepLogs.isEmpty()) {
                                Text(
                                    text = "Лог пуст. Нажмите «Подключить привилегии» или «Сопряжение по коду».",
                                    color = Color(0xFF6E7681),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    stepLogs.takeLast(20).forEach { line ->
                                        Text(
                                            text = line,
                                            color = Color(0xFFA5D6FF),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
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
private fun ServerStatusCard(
    state: PrivilegeConnectionState,
    onAutoConnect: () -> Unit,
    onStartWirelessPairing: () -> Unit,
    onRunBinderSelfTest: (com.swagdroid.api.ISwagDroidService) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is PrivilegeConnectionState.Connected -> {
                    val info = state.serverInfo
                    Text(
                        text = "✅ Сервер активен: ${info.privilegeTier.displayNameRu}",
                        color = Color(0xFF3FB950),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "UID: ${info.uid} • PID: ${info.pid} • Backend: ${info.launchBackend}\n" +
                            "SELinux: ${info.selinuxContext}\n" +
                            "Автозапуск после ребута (WRITE_SECURE_SETTINGS): ${if (info.writeSecureSettingsGranted) "Готов" else "Не выдан"}",
                        color = Color(0xFFC9D1D9),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { onRunBinderSelfTest(state.service) }) {
                        Text("Запустить тест Binder IPC (getInstalledPackages)")
                    }
                }

                is PrivilegeConnectionState.Connecting -> {
                    Text(
                        text = "⏳ Подключение к серверу привилегий...",
                        color = Color(0xFFD29922),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = state.stepMessageRu,
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp,
                    )
                }

                is PrivilegeConnectionState.Failed,
                PrivilegeConnectionState.DisconnectedTier0 -> {
                    Text(
                        text = "⚡ Статус: Tier 0 (Сервер swagdroid_server ещё не запущен)",
                        color = Color(0xFFF0883E),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    if (state is PrivilegeConnectionState.Failed) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.reasonRu,
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onAutoConnect) {
                            Text("Авто-старт (Root / Shizuku / ADB)")
                        }
                        OutlinedButton(onClick = onStartWirelessPairing) {
                            Text("Сопряжение по 6 цифрам (Без ПК)")
                        }
                    }
                }
            }
        }
    }
}
