package com.swagdroid.feature.adblock

import com.swagdroid.feature.adblock.data.BlocklistRepository
import com.swagdroid.feature.adblock.dns.DnsPacketEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DnsPacketEngineTest {

    @Test
    fun `parses raw IPv4 UDP DNS query and synthesizes valid NXDOMAIN response`() {
        val dnsPayload = buildSampleDnsQuery("sub.ads.doubleclick.net", txId = 0x4A2B)
        val rawIpv4Udp = DnsPacketEngine.wrapDnsPayloadInIpv4Udp(
            srcIp = byteArrayOf(10, 215.toByte(), 173.toByte(), 1),
            dstIp = byteArrayOf(10, 215.toByte(), 173.toByte(), 2),
            srcPort = 51423,
            dstPort = 53,
            dnsPayload = dnsPayload,
        )

        val parsed = DnsPacketEngine.parseIpv4UdpDnsQuery(rawIpv4Udp)
        assertNotNull(parsed)
        assertEquals("sub.ads.doubleclick.net", parsed!!.queriedDomain)
        assertEquals(0x4A2B, parsed.transactionId)

        val nxResponse = DnsPacketEngine.buildNxDomainIpv4UdpResponse(parsed)
        // Verify swapped endpoints: response srcIp == query dstIp, response dstPort == query srcPort
        assertArrayEquals(parsed.dstIp, nxResponse.copyOfRange(12, 16))
        assertArrayEquals(parsed.srcIp, nxResponse.copyOfRange(16, 20))

        // Verify IPv4 header checksum validates cleanly
        val expectedSum = DnsPacketEngine.computeIpv4HeaderChecksum(nxResponse, 20)
        val actualSum = ((nxResponse[10].toInt() and 0xFF) shl 8) or (nxResponse[11].toInt() and 0xFF)
        assertEquals(expectedSum, actualSum)

        // Verify DNS response QR=1 and RCODE=3 (NXDOMAIN)
        val flags = ((nxResponse[30].toInt() and 0xFF) shl 8) or (nxResponse[31].toInt() and 0xFF)
        assertTrue((flags and 0x8000) != 0)
        assertEquals(3, flags and 0x000F)
    }

    @Test
    fun `suffix domain matcher blocks subdomains and honors allowlist overrides`() {
        val blocked = setOf("doubleclick.net", "minexmr.com")
        val allowed = setOf("safe.doubleclick.net")

        assertTrue(DnsPacketEngine.shouldBlockDomain("doubleclick.net", blocked, allowed))
        assertTrue(DnsPacketEngine.shouldBlockDomain("banner.ads.doubleclick.net", blocked, allowed))
        assertTrue(DnsPacketEngine.shouldBlockDomain("pool.minexmr.com", blocked, allowed))
        assertFalse(DnsPacketEngine.shouldBlockDomain("safe.doubleclick.net", blocked, allowed))
        assertFalse(DnsPacketEngine.shouldBlockDomain("sub.safe.doubleclick.net", blocked, allowed))
        assertFalse(DnsPacketEngine.shouldBlockDomain("github.com", blocked, allowed))
    }

    @Test
    fun `seed blocklist asset parses cleanly and renders valid hosts file`() {
        val seedFile = listOf(
            File("src/main/assets/swag_blocklist_seed.txt"),
            File("feature/adblock/src/main/assets/swag_blocklist_seed.txt"),
        ).firstOrNull { it.exists() }
        assertNotNull(seedFile)

        val domains = BlocklistRepository.parseDomainRulesText(seedFile!!.readText())
        assertTrue("Expected > 80 root suffix domains, got ${domains.size}", domains.size > 80)
        assertTrue("doubleclick.net" in domains)
        assertTrue("pool.minexmr.com" in domains)
        assertTrue("ad.xiaomi.com" in domains)

        val hosts = BlocklistRepository.formatSystemHostsFile(setOf("ads.example.com"))
        assertTrue(hosts.contains("127.0.0.1 localhost"))
        assertTrue(hosts.contains("0.0.0.0 ads.example.com"))
    }

    private fun buildSampleDnsQuery(domain: String, txId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // 12-byte RFC-1035 Header
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // RD = 1
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        repeat(6) { out.write(0x00) }

        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // QNAME terminator
        out.write(0x00)
        out.write(0x01) // QTYPE = A
        out.write(0x00)
        out.write(0x01) // QCLASS = IN
        return out.toByteArray()
    }
}
