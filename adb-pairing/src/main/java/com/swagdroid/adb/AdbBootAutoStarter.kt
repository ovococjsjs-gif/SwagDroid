package com.swagdroid.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Честный и надёжный автозапуск `swagdroid_server` после перезагрузки устройства.
 *
 * Как это работает технически:
 * 1. При первом запуске наш `swagdroid_server` (`uid=2000`) выдаёт приложению разрешение
 *    `android.permission.WRITE_SECURE_SETTINGS` (это разрешение сохраняется навсегда, даже после ребута!).
 * 2. При получении `ACTION_BOOT_COMPLETED` (или при появлении подключения к сети Wi-Fi)
 *    приложение проверяет наличие `WRITE_SECURE_SETTINGS`.
 * 3. Если устройство подключено к Wi-Fi:
 *    - Приложение записывает `Settings.Global.putInt(cr, "adb_wifi_enabled", 1)`
 *    - Через `AdbMdnsDiscoverer` находит новый динамический порт `_adb-tls-connect._tcp`
 *    - Подключается сохранённым RSA-ключом (`AdbKeyManager`) и поднимает `swagdroid_server`!
 * 4. Благодаря запуску через `setsid app_process`, после старта сервера пользователь может
 *    спокойно уйти из дома и выключить Wi-Fi — демон `swagdroid_server` продолжит работать до следующего ребута.
 */
class AdbBootAutoStarter : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        scheduleAutoBootstrapOnWifiAvailable(context.applicationContext)
    }

    companion object {
        private val autoStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun scheduleAutoBootstrapOnWifiAvailable(appContext: Context) {
            val hasWriteSecure = appContext.checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasWriteSecure) {
                AdbPairingForegroundService.appendStepLog(
                    "[AutoStart] Пропуск автостарта: разрешение WRITE_SECURE_SETTINGS ещё не выдано (требуется первый запуск)"
                )
                return
            }

            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.unregisterNetworkCallback(this)
                        autoStartScope.launch {
                            triggerSilentConnectAndBootstrap(appContext)
                        }
                    }
                },
            )
        }

        suspend fun triggerSilentConnectAndBootstrap(appContext: Context): Result<Unit> = runCatching {
            AdbPairingForegroundService.appendStepLog(
                "[AutoStart] Wi-Fi активен -> включаем Settings.Global.adb_wifi_enabled = 1..."
            )
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 1)

            val discoverer = AdbMdnsDiscoverer(appContext)
            val endpoint = withTimeout(15_000L) {
                discoverer.observeEndpoints(
                    targetType = AdbMdnsServiceType.TLS_CONNECT,
                    onLog = AdbPairingForegroundService::appendStepLog,
                ).first()
            }

            val keyManager = AdbKeyManager(appContext)
            val transport = AdbTransportClient(keyManager)
            transport.connectAndBootstrapServer(
                host = endpoint.hostAddress,
                port = endpoint.port,
                hostApkPath = appContext.applicationInfo.sourceDir,
                onStepLog = AdbPairingForegroundService::appendStepLog,
            ).getOrThrow()
        }
    }
}
