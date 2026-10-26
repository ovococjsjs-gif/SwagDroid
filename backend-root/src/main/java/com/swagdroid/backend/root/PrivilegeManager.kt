package com.swagdroid.backend.root

import android.content.Context
import android.os.IBinder
import com.swagdroid.adb.AdbBootAutoStarter
import com.swagdroid.adb.AdbPairingForegroundService
import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.api.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Текущее состояние подключения к привилегированному серверу `swagdroid_server`.
 */
sealed class PrivilegeConnectionState {
    data object DisconnectedTier0 : PrivilegeConnectionState()
    data class Connecting(val stepMessageRu: String) : PrivilegeConnectionState()
    data class Connected(
        val service: ISwagDroidService,
        val serverInfo: ServerInfo,
    ) : PrivilegeConnectionState()
    data class Failed(
        val reasonRu: String,
        val requiresUserPairingOnboarding: Boolean,
    ) : PrivilegeConnectionState()
}

/**
 * Центральный менеджер «Лестницы привилегий» (`PrivilegeManager`).
 *
 * Архитектурный принцип:
 * Все фичи приложения (Антивирус, Клинер, Оптимизатор, AdBlock, Фиксер) обращаются ТОЛЬКО к `PrivilegeManager`.
 * Менеджер автоматически поднимает и удерживает связь с единым демоном `swagdroid_server` в следующем порядке приоритета:
 * 1. Уже запущенный `swagdroid_server` (если Binder уже передан в `BinderContainerProvider`).
 * 2. Root-доступ (`su` через Magisk / KernelSU / APatch) -> даёт `PrivilegeTier.ROOT (uid=0)`.
 * 3. Мост с уже запущенным Shizuku (`ShizukuBridgeLauncher`) -> даёт `PrivilegeTier.ADB_SHELL (uid=2000)`.
 * 4. Бесшовное тихое переподключение по Беспроводной отладке (если уже есть `WRITE_SECURE_SETTINGS` и RSA-ключ).
 * 5. Интерактивный мастер первого сопряжения в 1 клик (с уведомлением `RemoteInput`).
 */
object PrivilegeManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow<PrivilegeConnectionState>(
        PrivilegeConnectionState.DisconnectedTier0
    )
    val connectionState: StateFlow<PrivilegeConnectionState> = _connectionState.asStateFlow()

    val currentTier: PrivilegeTier
        get() = when (val state = _connectionState.value) {
            is PrivilegeConnectionState.Connected -> state.serverInfo.privilegeTier
            else -> PrivilegeTier.NONE
        }

    /**
     * Вызывается из `BinderContainerProvider`, когда запущенный демон `swagdroid_server`
     * присылает свой живой `IBinder`.
     */
    fun onServerBinderReceived(rawBinder: IBinder) {
        if (!rawBinder.isBinderAlive) return

        val service = ISwagDroidService.Stub.asInterface(rawBinder)
        runCatching {
            rawBinder.linkToDeath(
                {
                    AdbPairingForegroundService.appendStepLog(
                        "[Binder] Соединение с swagdroid_server разорвано (процесс завершён)"
                    )
                    _connectionState.value = PrivilegeConnectionState.DisconnectedTier0
                },
                0,
            )

            val infoJson = service.serverInfoJson
            val info = json.decodeFromString<ServerInfo>(infoJson)
            AdbPairingForegroundService.appendStepLog(
                "[Binder] Подключён к swagdroid_server: uid=${info.uid}, pid=${info.pid}, " +
                    "tier=${info.privilegeTier}, backend=${info.launchBackend}"
            )
            _connectionState.value = PrivilegeConnectionState.Connected(
                service = service,
                serverInfo = info,
            )
        }.onFailure { err ->
            _connectionState.value = PrivilegeConnectionState.Failed(
                reasonRu = "Ошибка рукопожатия с сервером: ${err.message}",
                requiresUserPairingOnboarding = false,
            )
        }
    }

    /**
     * Автоматическое прохождение «Лестницы привилегий»:
     * Пробует Root -> Мост Shizuku -> Тихий автостарт по Wi-Fi ADB (если уже сопряжён).
     */
    fun autoEscalatePrivileges(context: Context) {
        val appContext = context.applicationContext
        if (_connectionState.value is PrivilegeConnectionState.Connected) return

        scope.launch {
            // Шаг 1: Проверяем Root (Magisk / KernelSU / APatch)
            _connectionState.value = PrivilegeConnectionState.Connecting("Проверка наличия Root (Magisk / KernelSU / APatch)...")
            val rootLauncher = RootServerLauncher(appContext)
            val rootResult = rootLauncher.launchServerViaSu(onLog = AdbPairingForegroundService::appendStepLog)
            if (rootResult.isSuccess && waitForBinderConnection(timeoutMs = 3_500L)) {
                return@launch
            }

            // Шаг 2: Проверяем, запущен ли уже Shizuku на устройстве
            val shizukuBridge = ShizukuBridgeLauncher(appContext)
            if (shizukuBridge.isShizukuRunning() && shizukuBridge.isShizukuPermissionGranted()) {
                _connectionState.value = PrivilegeConnectionState.Connecting("Запуск swagdroid_server через мост Shizuku...")
                val bridgeRes = shizukuBridge.bootstrapServerViaShizuku(onLog = AdbPairingForegroundService::appendStepLog)
                if (bridgeRes.isSuccess && waitForBinderConnection(timeoutMs = 3_500L)) {
                    return@launch
                }
            }

            // Шаг 3: Пробуем тихое переподключение через Беспроводную отладку (если уже есть сохранённый ключ)
            _connectionState.value = PrivilegeConnectionState.Connecting("Попытка тихого подключения к Беспроводной отладке...")
            val silentRes = AdbBootAutoStarter.triggerSilentConnectAndBootstrap(appContext)
            if (silentRes.isSuccess && waitForBinderConnection(timeoutMs = 4_000L)) {
                return@launch
            }

            // Шаг 4: Переходим к интерактивному мастеру сопряжения в 1 клик
            _connectionState.value = PrivilegeConnectionState.Failed(
                reasonRu = "Требуется первичное сопряжение по Беспроводной отладке (займёт 30 секунд без ПК) или выдача прав Root.",
                requiresUserPairingOnboarding = true,
            )
        }
    }

    private suspend fun waitForBinderConnection(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_connectionState.value is PrivilegeConnectionState.Connected) return true
            delay(150L)
        }
        return _connectionState.value is PrivilegeConnectionState.Connected
    }
}
