package com.swagdroid.feature.antivirus

import com.swagdroid.api.ActiveSocketEntry
import com.swagdroid.feature.antivirus.layer1.ExactHashShardIndex
import com.swagdroid.feature.antivirus.layer1.ExactMalwareSignature
import com.swagdroid.feature.antivirus.layer1.Layer1SignatureScanner
import com.swagdroid.feature.antivirus.layer1.MultiHashDigest
import com.swagdroid.feature.antivirus.layer1.SignatureBloomFilter
import com.swagdroid.feature.antivirus.layer2.MinerHunterEngine
import com.swagdroid.feature.antivirus.layer2.ScreenOffTelemetrySample
import com.swagdroid.feature.antivirus.layer2.StaticMiningScanResult
import com.swagdroid.feature.antivirus.layer3.AppRuntimeRiskProfile
import com.swagdroid.feature.antivirus.layer3.GhostAndStalkerwareAuditor
import com.swagdroid.feature.antivirus.layer3.RecentForegroundEvent
import com.swagdroid.feature.antivirus.model.FindingConfidence
import com.swagdroid.feature.antivirus.model.RecommendedAction
import com.swagdroid.feature.antivirus.model.ScannedEntity
import com.swagdroid.feature.antivirus.model.VerdictLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntivirusLayeredSuiteTest {

    @Test
    fun `layer1 bloom filter hit without exact shard match does NOT produce false positive`() {
        val bloom = SignatureBloomFilter.createOptimal(expectedInsertions = 1000)
        val exactIndex = ExactHashShardIndex()
        val scanner = Layer1SignatureScanner(bloom, exactIndex)

        val unconfirmedHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        // Имитируем вероятностное попадание только в фильтр Блума (без подтверждения в точном шарде)
        bloom.put(unconfirmedHash)

        val digest = MultiHashDigest(
            md5Hex = "00000000000000000000000000000000",
            sha1Hex = "0000000000000000000000000000000000000000",
            sha256Hex = unconfirmedHash,
            byteCount = 1024L,
        )
        assertNull(scanner.matchMultiHash(digest))

        // Теперь регистрируем подтверждённую сигнатуру в оба слоя
        scanner.registerSignature(
            ExactMalwareSignature(
                hashHex = unconfirmedHash,
                hashType = "sha256",
                threatFamily = "Android.Trojan.Banker",
                sourceFeed = "TestFeed",
            )
        )
        val match = scanner.matchMultiHash(digest)
        assertNotNull(match)
        assertEquals("Android.Trojan.Banker", match?.threatFamily)
    }

    @Test
    fun `layer2 harmless app with single word randomx is not flagged, while static + screen-off anomaly escalates`() {
        val minerHunter = MinerHunterEngine()
        val harmlessStratum = mutableSetOf<String>()
        val harmlessRpc = mutableSetOf<String>()
        val harmlessNative = mutableSetOf<String>()
        val harmlessPools = mutableSetOf<String>()

        // Одиночное слово randomx в тексте документации или кошелька не должно триггерить детектор
        minerHunter.inspectRawTextForMiningMarkers(
            rawContent = "This crypto news reader discusses RandomX algorithm updates",
            foundStratum = harmlessStratum,
            foundRpc = harmlessRpc,
            foundNative = harmlessNative,
            foundPools = harmlessPools,
        )
        val harmlessScan = StaticMiningScanResult(
            packageName = "org.clean.wallet",
            matchedStratumUris = harmlessStratum.toList(),
            matchedRpcMethods = harmlessRpc.toList(),
            matchedNativeSymbols = harmlessNative.toList(),
            matchedPoolDomains = harmlessPools.toList(),
            inspectedEntriesCount = 1,
        )
        assertFalse(harmlessScan.hasStrongMiningCodeIndicators)

        // Приложение со статическими строками майнера без фоновой нагрузки -> CONTAINS_MINING_CODE (PROBABLE)
        val minerScan = StaticMiningScanResult(
            packageName = "com.stealth.xmrig",
            matchedStratumUris = listOf("stratum+tcp://"),
            matchedRpcMethods = listOf("mining.subscribe", "mining.submit"),
            matchedNativeSymbols = listOf("randomx_init_dataset", "randomx_calculate_hash"),
            matchedPoolDomains = listOf("supportxmr.com"),
            inspectedEntriesCount = 3,
        )
        assertTrue(minerScan.hasStrongMiningCodeIndicators)

        val appEntity = ScannedEntity.InstalledApp(
            packageName = "com.stealth.xmrig",
            uid = 10245,
            versionName = "1.0",
            versionCode = 1L,
            installerPackageName = null,
            signingCertSha256 = "deadbeef",
            baseApkPath = "/data/app/com.stealth.xmrig/base.apk",
            baseApkSha256 = "cafebabe",
        )

        val staticOnlyFinding = minerHunter.evaluatePackageMinerRisk(
            entity = appEntity,
            staticResult = minerScan,
            screenOffSamples = emptyList(),
        )
        assertNotNull(staticOnlyFinding)
        assertEquals(VerdictLabel.CONTAINS_MINING_CODE, staticOnlyFinding?.verdictLabel)
        assertEquals(FindingConfidence.PROBABLE, staticOnlyFinding?.confidence)
        assertEquals(RecommendedAction.REVIEW, staticOnlyFinding?.recommendedAction)

        // Добавляем 3 периода с выключенным экраном, где приложение берёт >50% CPU и держит сокет на порт 3333
        val samples = (1..3).map { idx ->
            ScreenOffTelemetrySample(
                timestampEpochMs = 1_700_000_000_000L + idx * 60_000L,
                isScreenOff = true,
                uidCpuJiffies = mapOf(10245 to 800L, 10100 to 100L),
                activeWakeLockPackages = setOf("com.stealth.xmrig"),
                activeMediaPlaybackPackages = emptySet(),
                activeSockets = listOf(
                    ActiveSocketEntry(
                        protocol = "tcp",
                        localAddress = "192.168.1.15",
                        localPort = 49152 + idx,
                        remoteAddress = "198.51.100.42",
                        remotePort = 3333,
                        stateCode = 1,
                        ownerUid = 10245,
                    )
                ),
            )
        }

        val confirmedMinerFinding = minerHunter.evaluatePackageMinerRisk(
            entity = appEntity,
            staticResult = minerScan,
            screenOffSamples = samples,
        )
        assertNotNull(confirmedMinerFinding)
        assertEquals(VerdictLabel.PROBABLE_HIDDEN_MINER, confirmedMinerFinding?.verdictLabel)
        assertEquals(FindingConfidence.CONFIRMED, confirmedMinerFinding?.confidence)
        assertEquals(RecommendedAction.QUARANTINE, confirmedMinerFinding?.recommendedAction)
    }

    @Test
    fun `layer3 catches ghost adware with intentionally disabled launcher icon and recent ad popup`() {
        val auditor = GhostAndStalkerwareAuditor()
        val ghostProfile = AppRuntimeRiskProfile(
            packageName = "com.fake.clean.flashlight",
            uid = 10311,
            versionName = "2.1",
            versionCode = 21L,
            isSystemApp = false,
            installerPackageName = "com.google.android.packageinstaller",
            signingCertSha256 = "11223344",
            baseApkPath = "/data/app/com.fake.clean.flashlight/base.apk",
            baseApkSha256 = "55667788",
            hasVisibleLauncherIcon = false,
            launcherComponentExplicitlyDisabled = true,
            isAccessibilityServiceEnabled = false,
            isDeviceAdminEnabled = false,
            isNotificationListenerEnabled = false,
            isOverlayAllowed = true,
            canRequestPackageInstalls = false,
        )

        val auditFinding = auditor.auditAppProfile(ghostProfile)
        assertNotNull(auditFinding)
        assertEquals(VerdictLabel.RISKY_ACCESS_CONFIGURATION, auditFinding?.verdictLabel)
        assertEquals(FindingConfidence.PROBABLE, auditFinding?.confidence)
        assertEquals(RecommendedAction.QUARANTINE, auditFinding?.recommendedAction)

        // Тестируем кнопку «Реклама появилась только что!»
        val now = 1_700_000_500_000L
        val culprits = auditor.findRecentAdPopupCulprits(
            recentEvents = listOf(
                RecentForegroundEvent(
                    packageName = "com.fake.clean.flashlight",
                    className = "com.fake.clean.flashlight.FullScreenInterstitialActivity",
                    lastForegroundTimestampEpochMs = now - 12_000L,
                )
            ),
            installedProfilesByPkg = mapOf(ghostProfile.packageName to ghostProfile),
            currentDefaultLauncher = "com.miui.home",
            nowEpochMs = now,
        )
        assertEquals(1, culprits.size)
        assertEquals(FindingConfidence.PROBABLE, culprits.first().confidence)
    }
}
