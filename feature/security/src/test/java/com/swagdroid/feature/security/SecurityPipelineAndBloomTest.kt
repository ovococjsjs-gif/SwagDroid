package com.swagdroid.feature.security

import com.swagdroid.feature.security.engine.SecurityPipelineEngine
import com.swagdroid.feature.security.model.RawThreatSignal
import com.swagdroid.feature.security.model.ThreatActionType
import com.swagdroid.feature.security.model.ThreatObjectKind
import com.swagdroid.feature.security.model.ThreatSeverity
import com.swagdroid.feature.security.scanner.SecuritySignaturesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPipelineAndBloomTest {

    @Test
    fun `bloom signature filter guarantees zero false negatives on malware catalog`() {
        val bloom = SecuritySignaturesCatalog.malwareBloomFilter
        SecuritySignaturesCatalog.KNOWN_MALWARE_SHA256.keys.forEach { sha ->
            assertTrue("Bloom missed known SHA-256 $sha", bloom.mightContain(sha))
        }
        SecuritySignaturesCatalog.KNOWN_TROJAN_PACKAGE_IDS.keys.forEach { pkg ->
            assertTrue("Bloom missed known Trojan pkg $pkg", bloom.mightContain(pkg))
        }
        assertFalse(bloom.mightContain("org.telegram.messenger"))
    }

    @Test
    fun `miner byte scanner detects XMRig and Stratum pool strings`() {
        val fakeNativePayload = "ELF...config: stratum+ssl://pool.minexmr.com:443 algo=randomx...".toByteArray()
        val hits = SecurityPipelineEngine.detectMinerMarkersInBytes(fakeNativePayload)
        assertTrue(hits.size >= 3)
        assertTrue(hits.any { it.contains("stratum+ssl://") })
        assertTrue(hits.any { it.contains("randomx") })
    }

    @Test
    fun `hosts hijack analyzer distinguishes AdBlock 0_0_0_0 from phishing redirects`() {
        val cleanAdblockHosts = """
            127.0.0.1 localhost
            ::1 ip6-localhost
            0.0.0.0 doubleclick.net
            0.0.0.0 ad.xiaomi.com
        """.trimIndent()
        assertTrue(SecurityPipelineEngine.analyzeHostsHijacks(cleanAdblockHosts).isEmpty())

        val hijackedHosts = """
            127.0.0.1 localhost
            185.199.108.153 online.sberbank.ru
            0.0.0.0 ads.google.com
        """.trimIndent()
        val hijacks = SecurityPipelineEngine.analyzeHostsHijacks(hijackedHosts)
        assertEquals(1, hijacks.size)
        assertEquals("185.199.108.153 → online.sberbank.ru", hijacks.first())
    }

    @Test
    fun `decision aggregator elevates highest priority action per MinerSearch ThreatManager`() {
        val signals = listOf(
            RawThreatSignal(
                targetKey = "pkg:com.evil.miner",
                displayTitle = "Fake Updater",
                packageName = "com.evil.miner",
                kind = ThreatObjectKind.PRIVILEGE_ABUSE,
                severity = ThreatSeverity.MEDIUM,
                recommendedAction = ThreatActionType.REVIEW_MANUAL,
                reasonRu = "Reads notifications",
                technicalEvidence = "NotificationListener",
            ),
            RawThreatSignal(
                targetKey = "pkg:com.evil.miner",
                displayTitle = "Fake Updater",
                packageName = "com.evil.miner",
                kind = ThreatObjectKind.CRYPTO_MINER,
                severity = ThreatSeverity.CRITICAL,
                recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                reasonRu = "Embedded XMRig",
                technicalEvidence = "stratum+tcp://",
            ),
        )

        val aggregated = SecurityPipelineEngine.aggregateDecisions(signals)
        assertEquals(1, aggregated.size)
        assertEquals(ThreatActionType.QUARANTINE_SUSPEND, aggregated.first().action)
        assertEquals(100, aggregated.first().riskScore)
        assertEquals(2, aggregated.first().reasonsRu.size)
    }

    @Test
    fun `exodus dex scanner identifies embedded tracker descriptors`() {
        val fakeDexBytes = "dex\n035\u0000Lcom/google/android/gms/ads/AdView;Lio/appmetrica/analytics/AppMetrica;Lcom/appsflyer/AppsFlyerLib;".toByteArray()
        val found = SecurityPipelineEngine.detectTrackersInDexBytes(fakeDexBytes)
        val ids = found.map { it.id }.toSet()
        assertEquals(setOf("admob", "appmetrica_new", "appsflyer"), ids)
    }
}
