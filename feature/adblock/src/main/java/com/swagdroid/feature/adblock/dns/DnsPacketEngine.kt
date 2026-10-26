package com.swagdroid.feature.adblock.dns

/**
 * Dual-stack (IPv4 + IPv6) UDP + RFC-1035 DNS packet inspector and `NXDOMAIN` response builder
 * powering [com.swagdroid.feature.adblock.vpn.SwagDnsVpnService].
 *
 * Supporting both IPv4 and IPv6 eliminates dual-stack LTE/5G IPv6 DNS bypasses.
 */
object DnsPacketEngine {

    private const val IPV4_VERSION = 4
    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_BYTES = 40
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53
    private const val DNS_HEADER_BYTES = 12

    data class ParsedUdpDnsQuery(
        val ipVersion: Int,
        val ipHeaderLength: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray,
        val transactionId: Int,
        val queriedDomain: String,
    )

    /**
     * Parses a raw IPv4 or IPv6 TUN packet and returns [ParsedUdpDnsQuery] if it is a UDP DNS
     * query targeting port 53 with at least one valid question `QNAME`.
     */
    fun parseIpUdpDnsQuery(packet: ByteArray, length: Int = packet.size): ParsedUdpDnsQuery? {
        if (length < 20 + 8 + DNS_HEADER_BYTES) return null

        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            IPV4_VERSION -> parseIpv4Internal(packet, length)
            IPV6_VERSION -> parseIpv6Internal(packet, length)
            else -> null
        }
    }

    /**
     * Backwards-compatible alias for IPv4 callers and tests.
     */
    fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int = packet.size): ParsedUdpDnsQuery? =
        parseIpUdpDnsQuery(packet, length)

    private fun parseIpv4Internal(packet: ByteArray, length: Int): ParsedUdpDnsQuery? {
        val ihlBytes = (packet[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes + 8 + DNS_HEADER_BYTES) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        return parseUdpAndDns(IPV4_VERSION, ihlBytes, srcIp, dstIp, packet, length)
    }

    private fun parseIpv6Internal(packet: ByteArray, length: Int): ParsedUdpDnsQuery? {
        if (length < IPV6_HEADER_BYTES + 8 + DNS_HEADER_BYTES) return null
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != PROTOCOL_UDP) return null

        val srcIp = packet.copyOfRange(8, 24)
        val dstIp = packet.copyOfRange(24, 40)
        return parseUdpAndDns(IPV6_VERSION, IPV6_HEADER_BYTES, srcIp, dstIp, packet, length)
    }

    private fun parseUdpAndDns(
        ipVersion: Int,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        length: Int,
    ): ParsedUdpDnsQuery? {
        val srcPort = readUInt16(packet, ipHeaderLen)
        val dstPort = readUInt16(packet, ipHeaderLen + 2)
        val udpLen = readUInt16(packet, ipHeaderLen + 4)
        if (dstPort != DNS_PORT || udpLen < 8 + DNS_HEADER_BYTES) return null

        val dnsOffset = ipHeaderLen + 8
        val dnsLen = minOf(udpLen - 8, length - dnsOffset)
        if (dnsLen < DNS_HEADER_BYTES) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
        val txId = readUInt16(dnsPayload, 0)
        val flags = readUInt16(dnsPayload, 2)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = readUInt16(dnsPayload, 4)
        if (isResponse || qdCount < 1) return null

        val domain = decodeFirstQuestionDomain(dnsPayload) ?: return null
        return ParsedUdpDnsQuery(
            ipVersion = ipVersion,
            ipHeaderLength = ipHeaderLen,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload,
            transactionId = txId,
            queriedDomain = domain,
        )
    }

    /**
     * Decodes the first RFC-1035 `QNAME` starting at byte 12 of [dnsPayload].
     */
    fun decodeFirstQuestionDomain(dnsPayload: ByteArray): String? {
        if (dnsPayload.size <= DNS_HEADER_BYTES) return null
        val labels = ArrayList<String>(6)
        var pos = DNS_HEADER_BYTES

        while (pos < dnsPayload.size) {
            val labelLen = dnsPayload[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            if ((labelLen and 0xC0) != 0 || labelLen > 63 || pos + 1 + labelLen > dnsPayload.size) {
                return null
            }
            val label = String(dnsPayload, pos + 1, labelLen, Charsets.US_ASCII).lowercase()
            labels.add(label)
            pos += 1 + labelLen
        }

        if (labels.isEmpty() || pos + 4 > dnsPayload.size) return null
        return labels.joinToString(".")
    }

    /**
     * Checks if [domain] or any of its parent domain suffixes matches [blockedSet] unless
     * explicitly exempted by [allowedSet].
     */
    fun shouldBlockDomain(
        domain: String,
        blockedSet: Set<String>,
        allowedSet: Set<String> = emptySet(),
    ): Boolean {
        val clean = domain.trim().trimEnd('.').lowercase()
        if (clean.isEmpty()) return false

        var cursor = clean
        while (true) {
            if (cursor in allowedSet) return false
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            cursor = cursor.substring(dot + 1)
        }

        cursor = clean
        while (true) {
            if (cursor in blockedSet) return true
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            cursor = cursor.substring(dot + 1)
        }
        return false
    }

    /**
     * Synthesizes a valid IPv4 or IPv6 + UDP + RFC-1035 `NXDOMAIN` (`RCODE = 3`) response packet.
     */
    fun buildNxDomainResponse(query: ParsedUdpDnsQuery): ByteArray {
        val qDns = query.dnsPayload
        var pos = DNS_HEADER_BYTES
        while (pos < qDns.size && (qDns[pos].toInt() and 0xFF) != 0) {
            pos += 1 + (qDns[pos].toInt() and 0xFF)
        }
        val questionEnd = minOf(qDns.size, pos + 1 + 4)
        val dnsResp = ByteArray(questionEnd)
        System.arraycopy(qDns, 0, dnsResp, 0, questionEnd)

        val rdBit = readUInt16(qDns, 2) and 0x0100
        val respFlags = 0x8083 or rdBit
        writeUInt16(dnsResp, 2, respFlags)
        writeUInt16(dnsResp, 4, 1)
        writeUInt16(dnsResp, 6, 0)
        writeUInt16(dnsResp, 8, 0)
        writeUInt16(dnsResp, 10, 0)

        return wrapDnsPayloadForQuery(query, dnsResp)
    }

    fun buildNxDomainIpv4UdpResponse(query: ParsedUdpDnsQuery): ByteArray = buildNxDomainResponse(query)

    fun wrapDnsPayloadForQuery(
        query: ParsedUdpDnsQuery,
        dnsPayload: ByteArray,
    ): ByteArray = if (query.ipVersion == IPV6_VERSION) {
        wrapDnsPayloadInIpv6Udp(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            dnsPayload = dnsPayload,
        )
    } else {
        wrapDnsPayloadInIpv4Udp(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            dnsPayload = dnsPayload,
        )
    }

    fun wrapDnsPayloadInIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + dnsPayload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        writeUInt16(packet, 2, totalLength)
        writeUInt16(packet, 4, 0)
        writeUInt16(packet, 6, 0x4000)
        packet[8] = 64.toByte()
        packet[9] = PROTOCOL_UDP.toByte()
        writeUInt16(packet, 10, 0)
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = computeIpv4HeaderChecksum(packet, 20)
        writeUInt16(packet, 10, ipChecksum)

        writeUInt16(packet, 20, srcPort)
        writeUInt16(packet, 22, dstPort)
        writeUInt16(packet, 24, udpLength)
        writeUInt16(packet, 26, 0)

        System.arraycopy(dnsPayload, 0, packet, 28, dnsPayload.size)
        return packet
    }

    /**
     * Wraps a DNS payload into an IPv6 + UDP packet with mandatory RFC 2460 / RFC 8200 UDP pseudo-header checksum.
     */
    fun wrapDnsPayloadInIpv6Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + dnsPayload.size
        val packet = ByteArray(IPV6_HEADER_BYTES + udpLength)

        packet[0] = 0x60.toByte() // Version 6
        writeUInt16(packet, 4, udpLength) // Payload length
        packet[6] = PROTOCOL_UDP.toByte() // Next Header = UDP (17)
        packet[7] = 64.toByte() // Hop Limit
        System.arraycopy(srcIp, 0, packet, 8, 16)
        System.arraycopy(dstIp, 0, packet, 24, 16)

        writeUInt16(packet, 40, srcPort)
        writeUInt16(packet, 42, dstPort)
        writeUInt16(packet, 44, udpLength)
        writeUInt16(packet, 46, 0)
        System.arraycopy(dnsPayload, 0, packet, 48, dnsPayload.size)

        val udpChecksum = computeIpv6UdpChecksum(srcIp, dstIp, packet, 40, udpLength)
        writeUInt16(packet, 46, udpChecksum)
        return packet
    }

    fun computeIpv4HeaderChecksum(header: ByteArray, headerLength: Int): Int {
        var sum = 0
        var i = 0
        while (i < headerLength) {
            if (i != 10) {
                sum += readUInt16(header, i)
            }
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun computeIpv6UdpChecksum(
        srcIp16: ByteArray,
        dstIp16: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
    ): Int {
        var sum = 0
        for (i in 0 until 16 step 2) sum += readUInt16(srcIp16, i)
        for (i in 0 until 16 step 2) sum += readUInt16(dstIp16, i)
        sum += (udpLen ushr 16) and 0xFFFF
        sum += udpLen and 0xFFFF
        sum += PROTOCOL_UDP

        var i = 0
        while (i + 1 < udpLen) {
            if (i != 6) {
                sum += readUInt16(packet, udpOffset + i)
            }
            i += 2
        }
        if (i < udpLen) {
            sum += ((packet[udpOffset + i].toInt() and 0xFF) shl 8)
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val inverted = sum.inv() and 0xFFFF
        // Per RFC 8200, UDP checksum 0x0000 in IPv6 is transmitted as 0xFFFF
        return if (inverted == 0) 0xFFFF else inverted
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}
