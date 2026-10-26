package com.swagdroid.feature.antivirus

import com.swagdroid.api.ActiveSocketEntry
import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.PackageSummary
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.feature.antivirus.layer1.ExactHashShardIndex
import com.swagdroid.feature.antivirus.layer1.ExactMalwareSignature
import com.swagdroid.feature.antivirus.layer1.Layer1SignatureScanner
import com.swagdroid.feature.antivirus.layer1.SignatureBloomFilter
import com.swagdroid.feature.antivirus.layer2.MinerHunterEngine
import com.swagdroid.feature.antivirus.layer2.ScreenOffTelemetrySample
import com.swagdroid.feature.antivirus.layer3.AppRuntimeRiskProfile
import com.swagdroid.feature.antivirus.layer3.GhostAndStalkerwareAuditor
import com.swagdroid.feature.antivirus.layer3.RecentForegroundEvent
import com.swagdroid.feature.antivirus.layer4.QuarantineManager
import com.swagdroid.feature.antivirus.model.Finding
import com.swagdroid.feature.antivirus.model.ScannedEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Матрица доступности возможностей антивируса на текущем уровне привилегий (L0 / L1 Shell / L2 Root).
 */
@Serializable
data class AntivirusCapabilityMatrix(
    val currentTier: PrivilegeTier,
    val canHashBaseAndSplitApks: Boolean,
    val canReadProcNetTcpSockets: Boolean,
    val canReadDumpsysCpuAndWakelocks: Boolean,
    val canInspectDataLocalTmp: Boolean,
    val canSuspendAndDisablePackages: Boolean,
) {
    companion object {
        fun forTier(tier: PrivilegeTier): AntivirusCapabilityMatrix = when (tier) {
            PrivilegeTier.NONE -> AntivirusCapabilityMatrix(
                currentTier = tier,
                canHashBaseAndSplitApks = true,
                canReadProcNetTcpSockets = false, // AOSP SELinux запрещает untrusted_app читать /proc/net/tcp
                canReadDumpsysCpuAndWakelocks = false,
                canInspectDataLocalTmp = false,
                canSuspendAndDisablePackages = false,
            )
            PrivilegeTier.ADB_SHELL,
            PrivilegeTier.ROOT -> AntivirusCapabilityMatrix(
                currentTier = tier,
                canHashBaseAndSplitApks = true,
                canReadProcNetTcpSockets = true, // Доступно через наш swagdroid_server (uid=2000 или 0)
                canReadDumpsysCpuAndWakelocks = true,
                canInspectDataLocalTmp = true,
                canSuspendAndDisablePackages = true,
            )
        }
    }
}

/**
 * Сводный отчёт полного сканирования с метриками производительности (время скана, количество пакетов, разбивка вердиктов).
 */
@Serializable
data class AntivirusScanReport(
    val scannedPackagesCount: Int,
    val scannedFilesCount: Int,
    val scanDurationMillis: Long,
    val capabilityMatrix: AntivirusCapabilityMatrix,
    val findings: List<Finding>,
)

/**
 * Единый координатор 4-эшелонированного антивируса и охотника на майнеров (`AntivirusCoordinator`).
 */
class AntivirusCoordinator(
    val layer1Scanner: Layer1SignatureScanner = createDefaultLayer1Scanner(),
    val minerHunter: MinerHunterEngine = MinerHunterEngine(),
    val ghostAuditor: GhostAndStalkerwareAuditor = GhostAndStalkerwareAuditor(),
    val quarantineManager: QuarantineManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Запускает полный эшелонированный проход по установленным приложениям и сэмплам `MinerWatch`.
     */
    fun runLayeredScan(
        packages: List<PackageSummary>,
        runtimeProfiles: Map<String, AppRuntimeRiskProfile>,
        screenOffSamples: List<ScreenOffTelemetrySample> = emptyList(),
        standaloneFilesToScan: List<File> = emptyList(),
        privilegeTier: PrivilegeTier = PrivilegeTier.NONE,
    ): AntivirusScanReport {
        val startMs = System.currentTimeMillis()
        val findings = mutableListOf<Finding>()

        for (pkg in packages) {
            val apkFile = pkg.sourceApkPath?.let { File(it) }

            // Слой 1: Двухфазная проверка хэшей APK + внутренних DEX/.so (BloomFilter -> ExactHashShardIndex)
            if (apkFile != null && apkFile.exists()) {
                val layer1Finding = layer1Scanner.scanInstalledAppApk(
                    packageName = pkg.packageName,
                    uid = pkg.uid,
                    versionName = pkg.versionName,
                    versionCode = pkg.versionCode,
                    installerPackageName = pkg.installerPackageName,
                    signingCertSha256 = pkg.signingCertSha256,
                    apkFile = apkFile,
                )
                if (layer1Finding != null) {
                    findings += layer1Finding
                }
            }

            // Слой 2: Статический поиск майнера в сторонних/новых APK + корреляция с ночным дозором MinerWatch
            val staticMiningResult = if (!pkg.isSystemApp && apkFile != null && apkFile.exists()) {
                minerHunter.scanApkForMiningCode(pkg.packageName, apkFile)
            } else {
                null
            }

            val appEntity = ScannedEntity.InstalledApp(
                packageName = pkg.packageName,
                uid = pkg.uid,
                versionName = pkg.versionName,
                versionCode = pkg.versionCode,
                installerPackageName = pkg.installerPackageName,
                signingCertSha256 = pkg.signingCertSha256,
                baseApkPath = pkg.sourceApkPath,
                baseApkSha256 = null,
            )

            val minerFinding = minerHunter.evaluatePackageMinerRisk(
                entity = appEntity,
                staticResult = staticMiningResult,
                screenOffSamples = screenOffSamples,
            )
            if (minerFinding != null) {
                findings += minerFinding
            }

            // Слой 3: Stalkerware IOC + Детерминированный аудит «Приложений-призраков» и опасных доступов
            val runtimeProfile = runtimeProfiles[pkg.packageName]
            if (runtimeProfile != null) {
                val auditFinding = ghostAuditor.auditAppProfile(runtimeProfile)
                if (auditFinding != null) {
                    findings += auditFinding
                }
            }
        }

        // Сканирование отдельных скачанных файлов в публичных папках (например, Download/*.apk)
        for (file in standaloneFilesToScan) {
            val fileFinding = layer1Scanner.scanStandaloneFile(file)
            if (fileFinding != null) {
                findings += fileFinding
            }
        }

        val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        return AntivirusScanReport(
            scannedPackagesCount = packages.size,
            scannedFilesCount = standaloneFilesToScan.size,
            scanDurationMillis = durationMs,
            capabilityMatrix = AntivirusCapabilityMatrix.forTier(privilegeTier),
            findings = findings,
        )
    }

    /**
     * Быстрый поиск виновника только что всплывшей поверх экрана рекламы («Реклама появилась только что!»).
     */
    fun detectRecentAdPopupCulprits(
        recentEvents: List<RecentForegroundEvent>,
        runtimeProfiles: Map<String, AppRuntimeRiskProfile>,
        currentDefaultLauncher: String?,
    ): List<Finding> = ghostAuditor.findRecentAdPopupCulprits(
        recentEvents = recentEvents,
        installedProfilesByPkg = runtimeProfiles,
        currentDefaultLauncher = currentDefaultLauncher,
    )

    /**
     * Снимает мгновенный сэмпл сетевых сокетов из привилегированного `ISwagDroidService` (на уровне L1/L2).
     */
    fun collectPrivilegedActiveSockets(service: ISwagDroidService): List<ActiveSocketEntry> =
        runCatching {
            json.decodeFromString<List<ActiveSocketEntry>>(service.activeNetworkSocketsJson)
        }.getOrDefault(emptyList())

    companion object {
        fun createDefaultLayer1Scanner(): Layer1SignatureScanner {
            val bloom = SignatureBloomFilter.createOptimal(expectedInsertions = 50_000, falsePositiveRate = 0.0001)
            val exactIndex = ExactHashShardIndex()
            val scanner = Layer1SignatureScanner(bloomFilter = bloom, exactIndex = exactIndex)

            // Базовые эталонные сигнатуры для самопроверки и EICAR-совместимого синтетического теста
            scanner.registerSignature(
                ExactMalwareSignature(
                    hashHex = "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f",
                    hashType = "sha256",
                    threatFamily = "EICAR-Test-Signature",
                    sourceFeed = "MaintainTeam/Hypatia Standard Test",
                )
            )
            scanner.registerSignature(
                ExactMalwareSignature(
                    hashHex = "44d88612fea8a8f36de82e1278abb02f",
                    hashType = "md5",
                    threatFamily = "EICAR-Test-Signature-MD5",
                    sourceFeed = "MaintainTeam/Hypatia Standard Test",
                )
            )
            return scanner
        }
    }
}
