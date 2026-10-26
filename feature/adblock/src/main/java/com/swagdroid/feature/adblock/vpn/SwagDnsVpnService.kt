package com.swagdroid.feature.adblock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.swagdroid.feature.adblock.data.BlocklistRepository
import com.swagdroid.feature.adblock.dns.DnsPacketEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/**
 * Ultra-lightweight split-route DNS-only local VPN sinkhole (`Tier 1: No-Root`).
 *
 * Only packets addressed to virtual DNS IP `10.215.173.2/32` enter the TUN interface:
 * 99.9% of regular TCP/HTTPS/video traffic flows directly over hardware Wi-Fi/LTE with
 * zero throughput loss and negligible battery usage.
 */
@AndroidEntryPoint
class SwagDnsVpnService : VpnService() {

    @Inject
    lateinit var blocklistRepo: BlocklistRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var workerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val upstreamIp = intent?.getStringExtra(EXTRA_UPSTREAM_IP) ?: "94.140.14.14"
        val bypassedApps = intent?.getStringArrayListExtra(EXTRA_BYPASSED_PACKAGES).orEmpty()

        startForegroundWithChannel()
        startTunnelLoop(upstreamIp, bypassedApps)
        return START_STICKY
    }

    private fun startTunnelLoop(upstreamIpv4: String, bypassedPackages: List<String>) {
        workerJob?.cancel()
        runCatching { tunInterface?.close() }

        val builder = Builder()
            .setSession("SwagDroid DNS Shield")
            .setMtu(1500)
            .addAddress(VIRTUAL_TUN_CLIENT_IP, 30)
            .addDnsServer(VIRTUAL_TUN_DNS_IP)
            .addRoute(VIRTUAL_TUN_DNS_IP, 32)

        runCatching { builder.addDisallowedApplication(packageName) }
        for (pkg in bypassedPackages) {
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        val established = runCatching { builder.establish() }.getOrNull() ?: run {
            _isRunning.value = false
            stopSelf()
            return
        }

        tunInterface = established
        _isRunning.value = true

        workerJob = serviceScope.launch {
            blocklistRepo.ensureLoaded()
            val input = FileInputStream(established.fileDescriptor)
            val output = FileOutputStream(established.fileDescriptor)
            val readBuffer = ByteArray(4096)
            val upstreamAddr = runCatching { InetAddress.getByName(upstreamIpv4) }.getOrNull()

            while (isActive) {
                val readLen = runCatching { input.read(readBuffer) }.getOrDefault(-1)
                if (readLen <= 0) continue

                val query = DnsPacketEngine.parseIpv4UdpDnsQuery(readBuffer, readLen) ?: continue
                val blocked = blocklistRepo.isBlocked(query.queriedDomain)
                blocklistRepo.recordDnsDecision(query.queriedDomain, blocked)

                if (blocked) {
                    val nxPacket = DnsPacketEngine.buildNxDomainIpv4UdpResponse(query)
                    runCatching {
                        synchronized(output) {
                            output.write(nxPacket)
                            output.flush()
                        }
                    }
                } else if (upstreamAddr != null) {
                    // Forward allowed query over protected UDP socket on a child coroutine
                    launch {
                        forwardDnsQueryUpstream(query, upstreamAddr, output)
                    }
                }
            }
        }
    }

    private fun forwardDnsQueryUpstream(
        query: DnsPacketEngine.ParsedUdpDnsQuery,
        upstreamAddr: InetAddress,
        tunOut: FileOutputStream,
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 3500

            val reqPacket = DatagramPacket(query.dnsPayload, query.dnsPayload.size, upstreamAddr, 53)
            socket.send(reqPacket)

            val respBuf = ByteArray(2048)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)

            val answerPayload = respBuf.copyOfRange(0, respPacket.length)
            val wrappedIpUdp = DnsPacketEngine.wrapDnsPayloadInIpv4Udp(
                srcIp = query.dstIp,
                dstIp = query.srcIp,
                srcPort = query.dstPort,
                dstPort = query.srcPort,
                dnsPayload = answerPayload,
            )
            synchronized(tunOut) {
                tunOut.write(wrappedIpUdp)
                tunOut.flush()
            }
        } catch (_: Exception) {
            // Timeout or unreachable network; client resolver will retry cleanly
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun stopTunnel() {
        workerJob?.cancel()
        workerJob = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithChannel() {
        val channelId = "swag_dns_shield"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val channel = NotificationChannel(
                channelId,
                "SwagDroid Локальный DNS-щит",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("SwagDroid DNS-щит активен")
            .setContentText("Фильтрация рекламы, трекеров и майнинг-пулов без потери скорости")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "com.swagdroid.action.START_DNS_VPN"
        const val ACTION_STOP = "com.swagdroid.action.STOP_DNS_VPN"
        const val EXTRA_UPSTREAM_IP = "extra_upstream_ip"
        const val EXTRA_BYPASSED_PACKAGES = "extra_bypassed_packages"

        private const val NOTIFICATION_ID = 42053
        private const val VIRTUAL_TUN_CLIENT_IP = "10.215.173.1"
        private const val VIRTUAL_TUN_DNS_IP = "10.215.173.2"

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
