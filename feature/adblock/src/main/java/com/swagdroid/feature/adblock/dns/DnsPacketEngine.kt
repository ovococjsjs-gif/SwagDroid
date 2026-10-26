package com.swagdroid.feature.adblock.dns

/**
 * Pure byte-level IPv4 + UDP + RFC-1035 DNS packet inspector and response builder
 * powering [com.swagdroid.feature.adblock.vpn.SwagDnsVpnService].
 */
object DnsPacketEngine {

    private const val IPV4_VERSION = 4
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53
    private const val DNS_HEADER_BYTES = 12

    data class ParsedUdpDnsQuery(
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
     * Parses a raw IPv4 TUN packet and returns [ParsedUdpDnsQuery] if it is an IPv4 UDP DNS query
     * targeting port 53 with at least one valid question `QNAME`.
     */
    fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int = packet.size): ParsedUdpDnsQuery? {
        if (length < 20 + 8 + DNS_HEADER_BYTES) return null

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != IPV4_VERSION) return null

        val ihlBytes = (packet[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes + 8 + DNS_HEADER_BYTES) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        val udpOffset = ihlBytes
        val srcPort = readUInt16(packet, udpOffset)
        val dstPort = readUInt16(packet, udpOffset + 2)
        val udpLen = readUInt16(packet, udpOffset + 4)
        if (dstPort != DNS_PORT || udpLen < 8 + DNS_HEADER_BYTES) return null

        val dnsOffset = udpOffset + 8
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
            ipHeaderLength = ihlBytes,
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
            // Reject compressed pointers in first QNAME or malformed >63-octet labels
            if ((labelLen and 0xC0) != 0 || labelLen > 63 || pos + 1 + labelLen > dnsPayload.size) {
                return null
            }
            val label = String(dnsPayload, pos + 1, labelLen, Charsets.US_ASCII).lowercase()
            labels.add(label)
            pos += 1 + labelLen
        }

        // Need QTYPE (2B) + QCLASS (2B) after QNAME terminator
        if (labels.isEmpty() || pos + 4 > dnsPayload.size) return null
        return labels.joinToString(".")
    }

    /**
     * Checks if [domain] or any of its parent domain suffixes matches [blockedSet] unless
     * explicitly exempted by [allowedSet].
     *
     * Example: `sub.ads.doubleclick.net` matches `doubleclick.net` in O(k) label hops.
     */
    fun shouldBlockDomain(
        domain: String,
        blockedSet: Set<String>,
        allowedSet: Set<String> = emptySet(),
    ): Boolean {
        val clean = domain.trim().trimEnd('.').lowercase()
        if (clean.isEmpty()) return false

        // Explicit allowlist (exact or parent suffix) always wins
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
     * Synthesizes a valid IPv4 + UDP + RFC-1035 `NXDOMAIN` (`RCODE = 3`) response packet
     * swapping source and destination endpoints so Android immediately drops the ad/tracker socket.
     */
    fun buildNxDomainIpv4UdpResponse(query: ParsedUdpDnsQuery): ByteArray {
        val qDns = query.dnsPayload
        // Find end of Question 1 (12B header + QNAME + 0x00 + 4B QTYPE/QCLASS)
        var pos = DNS_HEADER_BYTES
        while (pos < qDns.size && (qDns[pos].toInt() and 0xFF) != 0) {
            pos += 1 + (qDns[pos].toInt() and 0xFF)
        }
        val questionEnd = minOf(qDns.size, pos + 1 + 4)
        val dnsResp = ByteArray(questionEnd)
        System.arraycopy(qDns, 0, dnsResp, 0, questionEnd)

        // Flags: QR=1 (response), RD preserved, RA=1, RCODE=3 (NXDOMAIN) -> 0x8183
        val rdBit = readUInt16(qDns, 2) and 0x0100
        val respFlags = 0x8083 or rdBit
        writeUInt16(dnsResp, 2, respFlags)
        writeUInt16(dnsResp, 4, 1) // QDCOUNT = 1
        writeUInt16(dnsResp, 6, 0) // ANCOUNT = 0
        writeUInt16(dnsResp, 8, 0) // NSCOUNT = 0
        writeUInt16(dnsResp, 10, 0) // ARCOUNT = 0

        return wrapDnsPayloadInIpv4Udp(
            srcIp = query.dstIp,
            dstIp = query.srcIp,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            dnsPayload = dnsResp,
        )
    }

    /**
     * Wraps an upstream DNS answer payload back into an IPv4 + UDP packet addressed to the TUN client.
     */
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

        // IPv4 20-byte header
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[1] = 0x00
        writeUInt16(packet, 2, totalLength)
        writeUInt16(packet, 4, 0) // Identification
        writeUInt16(packet, 6, 0x4000) // Don't Fragment
        packet[8] = 64.toByte() // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        writeUInt16(packet, 10, 0) // Checksum placeholder
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = computeIpv4HeaderChecksum(packet, 20)
        writeUInt16(packet, 10, ipChecksum)

        // UDP 8-byte header (UDP checksum 0 is valid for IPv4 per RFC 768)
        writeUInt16(packet, 20, srcPort)
        writeUInt16(packet, 22, dstPort)
        writeUInt16(packet, 24, udpLength)
        writeUInt16(packet, 26, 0)

        System.arraycopy(dnsPayload, 0, packet, 28, dnsPayload.size)
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

    private fun readUInt16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}
