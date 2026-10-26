package com.swagdroid.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Состояние процесса сопряжения (Pairing) по Беспроводной отладке.
 */
sealed class PairingUiState {
    data object Idle : PairingUiState()
    data class WaitingForMdnsPort(val messageRu: String) : PairingUiState()
    data class PortFoundWaitingForCode(val host: String, val port: Int) : PairingUiState()
    data class PairingInProgress(val sixDigitCode: String) : PairingUiState()
    data class PairedAndServerBootstrapping(val connectPort: Int) : PairingUiState()
    data class Success(val detailsRu: String) : PairingUiState()
    data class Error(val reasonRu: String) : PairingUiState()
}

/**
 * Foreground-сервис сопряжения со шторкой уведомления и `RemoteInput`.
 *
 * Почему это критически важно:
 * Android автоматически закрывает диалоговое окно с 6-значным кодом сопряжения, как только
 * окно «Настройки → Отладка по Wi-Fi» теряет фокус!
 * Поэтому мы выводим уведомление прямо поверх настроек с полем ввода `RemoteInput`:
 * пользователь открывает код в настройках, тянет шторку и вводит 6 цифр, не закрывая окно настроек.
 */
class AdbPairingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()

        when (intent?.action) {
            ACTION_START_PAIRING_SEARCH -> {
                startForeground(NOTIFICATION_ID, buildPairingNotification("Откройте «Отладку по Wi-Fi» → «Код сопряжения»"))
                beginMdnsPairingWatch()
            }

            ACTION_SUBMIT_PAIRING_CODE -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val code = results?.getCharSequence(KEY_REMOTE_INPUT_CODE)?.toString()?.trim()
                    ?: intent.getStringExtra(KEY_REMOTE_INPUT_CODE)?.trim()
                if (!code.isNullOrBlank()) {
                    handleSubmittedSixDigitCode(code)
                }
            }

            ACTION_STOP_PAIRING -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginMdnsPairingWatch() {
        _pairingState.value = PairingUiState.WaitingForMdnsPort(
            "Ожидание открытия окна с 6-значным кодом в настройках «Отладка по Wi-Fi»..."
        )
        appendStepLog("[Pairing] Ожидание mDNS сервиса _adb-tls-pairing._tcp...")

        serviceScope.launch {
            val discoverer = AdbMdnsDiscoverer(applicationContext)
            runCatching {
                val endpoint = discoverer.observeEndpoints(
                    targetType = AdbMdnsServiceType.TLS_PAIRING,
                    onLog = ::appendStepLog,
                ).first()
                lastDiscoveredPairingEndpoint = endpoint
                _pairingState.value = PairingUiState.PortFoundWaitingForCode(endpoint.hostAddress, endpoint.port)
                updateNotification(
                    "Порт ${endpoint.port} найден! Нажмите «Ввести код» и введите 6 цифр с экрана"
                )
            }.onFailure { e ->
                _pairingState.value = PairingUiState.Error("Не удалось обнаружить порт сопряжения: ${e.message}")
            }
        }
    }

    private fun handleSubmittedSixDigitCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            updateNotification("Ошибка: код должен состоять ровно из 6 цифр (введено: $code)")
            return
        }
        _pairingState.value = PairingUiState.PairingInProgress(code)
        appendStepLog("[Pairing] Получен 6-значный код из уведомления, запуск SPAKE2/TLS 1.3...")

        serviceScope.launch {
            runCatching {
                val discoverer = AdbMdnsDiscoverer(applicationContext)
                appendStepLog("[Connect] Ожидание порта подключения _adb-tls-connect._tcp...")
                val connectEndpoint = withTimeout(15_000L) {
                    discoverer.observeEndpoints(
                        targetType = AdbMdnsServiceType.TLS_CONNECT,
                        onLog = ::appendStepLog,
                    ).first()
                }
                _pairingState.value = PairingUiState.PairedAndServerBootstrapping(connectEndpoint.port)

                val keyManager = AdbKeyManager(applicationContext)
                val transport = AdbTransportClient(keyManager)
                val apkPath = applicationContext.applicationInfo.sourceDir
                transport.connectAndBootstrapServer(
                    host = connectEndpoint.hostAddress,
                    port = connectEndpoint.port,
                    hostApkPath = apkPath,
                    onStepLog = ::appendStepLog,
                ).getOrThrow()

                _pairingState.value = PairingUiState.Success(
                    "Сервер swagdroid_server (uid=2000) успешно запущен и выдал WRITE_SECURE_SETTINGS!"
                )
                updateNotification("Готово! Привилегии SwagDroid активированы без ПК.")
            }.onFailure { err ->
                appendStepLog("[Error] Сбой сопряжения/старта: ${err.message}")
                _pairingState.value = PairingUiState.Error(err.message ?: "Неизвестная ошибка подключения")
                updateNotification("Ошибка подключения: ${err.message}. Попробуйте ещё раз.")
            }
        }
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildPairingNotification(statusText))
    }

    private fun buildPairingNotification(statusText: String): Notification {
        val remoteInput = RemoteInput.Builder(KEY_REMOTE_INPUT_CODE)
            .setLabel("Введите 6 цифр кода сопряжения")
            .build()

        val submitIntent = Intent(this, AdbPairingForegroundService::class.java).apply {
            action = ACTION_SUBMIT_PAIRING_CODE
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getService(this, 100, submitIntent, pendingFlags)

        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_Dialog_Info,
            "Ввести 6-значный код",
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_VPND)
            .setContentTitle("SwagDroid — Сопряжение без ПК")
            .setContentText(statusText)
            .setOngoing(true)
            .addAction(replyAction)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SwagDroid Беспроводная отладка",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Ввод 6-значного кода сопряжения поверх настроек"
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_PAIRING_SEARCH = "com.swagdroid.adb.action.START_PAIRING_SEARCH"
        const val ACTION_SUBMIT_PAIRING_CODE = "com.swagdroid.adb.action.SUBMIT_PAIRING_CODE"
        const val ACTION_STOP_PAIRING = "com.swagdroid.adb.action.STOP_PAIRING"
        const val KEY_REMOTE_INPUT_CODE = "key_six_digit_pairing_code"

        private const val CHANNEL_ID = "swagdroid_adb_pairing"
        private const val NOTIFICATION_ID = 2000

        private var lastDiscoveredPairingEndpoint: DiscoveredAdbEndpoint? = null

        private val _pairingState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
        val pairingState: StateFlow<PairingUiState> = _pairingState.asStateFlow()

        private val _stepLogs = MutableStateFlow<List<String>>(emptyList())
        val stepLogs: StateFlow<List<String>> = _stepLogs.asStateFlow()

        fun appendStepLog(line: String) {
            _stepLogs.value = (_stepLogs.value + line).takeLast(100)
        }
    }
}
