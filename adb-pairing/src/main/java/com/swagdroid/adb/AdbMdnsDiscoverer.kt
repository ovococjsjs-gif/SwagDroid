package com.swagdroid.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress

/**
 * Тип mDNS-сервиса Беспроводной отладки Android 11+.
 */
enum class AdbMdnsServiceType(val serviceType: String) {
    /** Порт ввода 6-значного кода сопряжения (SPAKE2). */
    TLS_PAIRING("_adb-tls-pairing._tcp."),
    /** Динамический порт подключения уже сопряжённого клиента (меняется при каждом включении). */
    TLS_CONNECT("_adb-tls-connect._tcp."),
}

data class DiscoveredAdbEndpoint(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val type: AdbMdnsServiceType,
)

/**
 * Обнаружение локальных портов Беспроводной отладки (`_adb-tls-pairing._tcp` и `_adb-tls-connect._tcp`)
 * через системный `NsdManager`.
 */
class AdbMdnsDiscoverer(
    context: Context,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun observeEndpoints(
        targetType: AdbMdnsServiceType,
        onLog: (String) -> Unit = {},
    ): Flow<DiscoveredAdbEndpoint> = callbackFlow {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                onLog("[mDNS] Поиск сервиса $regType запущен...")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                onLog("[mDNS] Найден сервис ${service.serviceName}, разрешение адреса и порта...")
                nsdManager.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            onLog("[mDNS] Ошибка resolveService (${serviceInfo.serviceName}): код $errorCode")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val host: InetAddress? = resolvedInfo.host
                            val port: Int = resolvedInfo.port
                            if (host != null && port > 0) {
                                val endpoint = DiscoveredAdbEndpoint(
                                    serviceName = resolvedInfo.serviceName,
                                    hostAddress = host.hostAddress ?: "127.0.0.1",
                                    port = port,
                                    type = targetType,
                                )
                                onLog("[mDNS] Порт успешно определён: ${endpoint.hostAddress}:${endpoint.port}")
                                trySend(endpoint)
                            }
                        }
                    },
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                onLog("[mDNS] Сервис ${service.serviceName} закрыт")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onLog("[mDNS] Поиск $serviceType остановлен")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog("[mDNS] Не удалось запустить поиск $serviceType: код $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog("[mDNS] Ошибка остановки поиска $serviceType: код $errorCode")
            }
        }

        nsdManager.discoverServices(
            targetType.serviceType,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
