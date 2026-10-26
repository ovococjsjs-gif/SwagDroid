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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/**
 * Dual-stack (IPv4 + IPv6) split-route DNS-only local VPN sinkhole (`Tier 1: No-Root`).
 *
 * Only DNS packets targeting virtual resolvers `10.215.173.2/32` and `fd00:57a6:d01d::2/128`
 * enter the TUN interface: 99.9% of regular TCP/HTTPS/video traffic flows directly over
 * hardware Wi-Fi/5G with zero throughput loss and zero IPv6 DNS leak.
 */
@AndroidEntryPoint
class SwagDnsVpnService : VpnService() {

    @Inject
    lateinit var blocklistRepo: BlocklistRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val upstreamSemaphore = Semaphore(MAX_CONCURRENT_DNS_UPSTREAMS)
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
            .addAddress(VIRTUAL_TUN_CLIENT_IPV4, 30)
            .addDnsServer(VIRTUAL_TUN_DNS_IPV4)
            .addRoute(VIRTUAL_TUN_DNS_IPV4, 32)
            // Dual-stack ULA IPv6 DNS sinkhole prevents LTE/5G IPv6 DNS bypasses
            .addAddress(VIRTUAL_TUN_CLIENT_IPV6, 126)
            .addDnsServer(VIRTUAL_TUN_DNS_IPV6)
            .addRoute(VIRTUAL_TUN_DNS_IPV6, 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

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

                val query = DnsPacketEngine.parseIpUdpDnsQuery(readBuffer, readLen) ?: continue
                val blocked = blocklistRepo.isBlocked(query.queriedDomain)
                blocklistRepo.recordDnsDecision(query.queriedDomain, blocked)

                if (blocked) {
                    val nxPacket = DnsPacketEngine.buildNxDomainResponse(query)
                    runCatching {
                        synchronized(output) {
                            output.write(nxPacket)
                            output.flush()
                        }
                    }
                } else if (upstreamAddr != null) {
                    launch {
                        upstreamSemaphore.withPermit {
                            forwardDnsQueryUpstream(query, upstreamAddr, output)
                        }
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
            val wrappedIpUdp = DnsPacketEngine.wrapDnsPayloadForQuery(query, answerPayload)
            synchronized(tunOut) {
                tunOut.write(wrappedIpUdp)
                tunOut.flush()
            }
        } catch (_: Exception) {
            // Timeout or transient network loss; Android resolver retries cleanly
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
            .setContentTitle("SwagDroid DNS-щит активен (IPv4 + IPv6)")
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
        private const val MAX_CONCURRENT_DNS_UPSTREAMS = 64
        private const val VIRTUAL_TUN_CLIENT_IPV4 = "10.215.173.1"
        private const val VIRTUAL_TUN_DNS_IPV4 = "10.215.173.2"
        private const val VIRTUAL_TUN_CLIENT_IPV6 = "fd00:57a6:d01d::1"
        private const val VIRTUAL_TUN_DNS_IPV6 = "fd00:57a6:d01d::2"

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
