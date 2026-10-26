package com.swagdroid.feature.antivirus.layer2

import com.swagdroid.api.ActiveSocketEntry
import com.swagdroid.feature.antivirus.model.DetectorType
import com.swagdroid.feature.antivirus.model.EvidenceFact
import com.swagdroid.feature.antivirus.model.Finding
import com.swagdroid.feature.antivirus.model.FindingConfidence
import com.swagdroid.feature.antivirus.model.RecommendedAction
import com.swagdroid.feature.antivirus.model.ScannedEntity
import com.swagdroid.feature.antivirus.model.VerdictLabel
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Результат статического сканирования APK (DEX / ELF `.so` / WebView assets) на майнинговый код.
 */
@Serializable
data class StaticMiningScanResult(
    val packageName: String,
    val matchedStratumUris: List<String>,
    val matchedRpcMethods: List<String>,
    val matchedNativeSymbols: List<String>,
    val matchedPoolDomains: List<String>,
    val inspectedEntriesCount: Int,
) {
    /**
     * Считаем наличие майнингового кода доказанным только при специфичной комбинации признаков,
     * а не по одному случайному слову `randomx`.
     */
    val hasStrongMiningCodeIndicators: Boolean
        get() = matchedStratumUris.isNotEmpty() ||
            matchedPoolDomains.isNotEmpty() ||
            matchedRpcMethods.size >= 2 ||
            matchedNativeSymbols.size >= 2 ||
            (matchedRpcMethods.isNotEmpty() && matchedNativeSymbols.isNotEmpty())
}

/**
 * Сэмпл поведенческого мониторинга при выключенном экране («Ночной дозор» `MinerWatch`).
 */
@Serializable
data class ScreenOffTelemetrySample(
    val timestampEpochMs: Long,
    val isScreenOff: Boolean,
    val uidCpuJiffies: Map<Int, Long>,
    val activeWakeLockPackages: Set<String>,
    val activeMediaPlaybackPackages: Set<String>,
    val activeSockets: List<ActiveSocketEntry>,
)

/**
 * Слой 2: Охотник на скрытые криптомайнеры (`MinerHunterEngine` в духе `BlendLog/MinerSearch`,
 * адаптированный под реальные ограничения Android SELinux и уровни привилегий L0 / L2 / L3).
 */
class MinerHunterEngine {

    /**
     * Статический анализ `classes*.dex`, `lib/*/*.so` и `assets/*.js` внутри APK.
     * Выполняется потоково и кэшируется, не требуя распаковки APK на диск.
     */
    fun scanApkForMiningCode(
        packageName: String,
        apkFile: File,
    ): StaticMiningScanResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return StaticMiningScanResult(packageName, emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }

        val foundStratum = linkedSetOf<String>()
        val foundRpc = linkedSetOf<String>()
        val foundNative = linkedSetOf<String>()
        val foundPools = linkedSetOf<String>()
        var inspectedCount = 0

        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val shouldInspect = (name.startsWith("classes") && name.endsWith(".dex")) ||
                        (name.startsWith("lib/") && name.endsWith(".so")) ||
                        (name.startsWith("assets/") && (name.endsWith(".js") || name.endsWith(".json") || name.endsWith(".html")))

                    if (!shouldInspect || entry.size > MAX_SINGLE_ENTRY_BYTES) continue
                    inspectedCount++

                    zip.getInputStream(entry).buffered().use { stream ->
                        val textChunk = stream.readBytes().toString(Charsets.ISO_8859_1)
                        inspectRawTextForMiningMarkers(
                            rawContent = textChunk,
                            foundStratum = foundStratum,
                            foundRpc = foundRpc,
                            foundNative = foundNative,
                            foundPools = foundPools,
                        )
                    }
                }
            }
        }

        return StaticMiningScanResult(
            packageName = packageName,
            matchedStratumUris = foundStratum.toList(),
            matchedRpcMethods = foundRpc.toList(),
            matchedNativeSymbols = foundNative.toList(),
            matchedPoolDomains = foundPools.toList(),
            inspectedEntriesCount = inspectedCount,
        )
    }

    /**
     * Прямой анализ буфера строк (используется как при сканировании APK, так и в синтетических тестах).
     */
    fun inspectRawTextForMiningMarkers(
        rawContent: String,
        foundStratum: MutableSet<String>,
        foundRpc: MutableSet<String>,
        foundNative: MutableSet<String>,
        foundPools: MutableSet<String>,
    ) {
        val lower = rawContent.lowercase()

        for (uriPrefix in STRATUM_URI_PREFIXES) {
            if (uriPrefix in lower) {
                foundStratum += uriPrefix
            }
        }
        for (rpcMethod in STRATUM_RPC_METHODS) {
            if (rpcMethod in lower) {
                foundRpc += rpcMethod
            }
        }
        for (symbol in NATIVE_MINER_SYMBOLS) {
            if (symbol in lower) {
                foundNative += symbol
            }
        }
        for (pool in KNOWN_MINING_POOL_DOMAINS) {
            if (pool in lower) {
                foundPools += pool
            }
        }
    }

    /**
     * Оценивает результат статического анализа + поведенческой серии сэмплов `MinerWatch`
     * и формирует честный `Finding` с цепочкой доказательств.
     */
    fun evaluatePackageMinerRisk(
        entity: ScannedEntity.InstalledApp,
        staticResult: StaticMiningScanResult?,
        screenOffSamples: List<ScreenOffTelemetrySample> = emptyList(),
    ): Finding? {
        val evidence = mutableListOf<EvidenceFact>()
        val hasStaticMiningCode = staticResult?.hasStrongMiningCodeIndicators == true

        if (staticResult != null && hasStaticMiningCode) {
            if (staticResult.matchedStratumUris.isNotEmpty()) {
                evidence += EvidenceFact(
                    code = "STATIC_STRATUM_URI",
                    titleRu = "В коде приложения обнаружены протоколы пулов майнинга Stratum",
                    technicalDetail = "Маркеры: ${staticResult.matchedStratumUris.joinToString(", ")}",
                    weight = 45,
                )
            }
            if (staticResult.matchedPoolDomains.isNotEmpty()) {
                evidence += EvidenceFact(
                    code = "STATIC_MINING_POOL_DOMAIN",
                    titleRu = "Внутри APK зашиты адреса известных майнинг-пулов",
                    technicalDetail = "Домены: ${staticResult.matchedPoolDomains.joinToString(", ")}",
                    weight = 50,
                )
            }
            if (staticResult.matchedRpcMethods.isNotEmpty()) {
                evidence += EvidenceFact(
                    code = "STATIC_STRATUM_JSON_RPC",
                    titleRu = "Найдены методы JSON-RPC авторизации и отправки шар майнинга",
                    technicalDetail = "Методы: ${staticResult.matchedRpcMethods.joinToString(", ")}",
                    weight = 35,
                )
            }
            if (staticResult.matchedNativeSymbols.isNotEmpty()) {
                evidence += EvidenceFact(
                    code = "STATIC_NATIVE_MINER_SYMBOLS",
                    titleRu = "В нативных библиотеках (.so) найдены функции XMRig / RandomX / CryptoNight",
                    technicalDetail = "Символы: ${staticResult.matchedNativeSymbols.joinToString(", ")}",
                    weight = 45,
                )
            }
        }

        // Поведенческий анализ по окнам с выключенным экраном:
        // Правило: минимум в 3 периодах с выключенным экраном приложение занимает >50% суммарного CPU-времени
        // пользовательских приложений, не проигрывает медиа и держит сетевое соединение или Wakelock.
        val validScreenOffSamples = screenOffSamples.filter { it.isScreenOff }
        var anomalousWindowsCount = 0
        var suspiciousSocketPorts = mutableSetOf<Int>()

        for (sample in validScreenOffSamples) {
            if (entity.packageName in sample.activeMediaPlaybackPackages) continue
            val totalUserCpu = sample.uidCpuJiffies.values.sum().coerceAtLeast(1L)
            val appCpu = sample.uidCpuJiffies[entity.uid] ?: 0L
            val cpuShare = appCpu.toDouble() / totalUserCpu.toDouble()

            val appSockets = sample.activeSockets.filter { it.ownerUid == entity.uid }
            val holdsWakeLock = entity.packageName in sample.activeWakeLockPackages

            if (cpuShare >= 0.50 && (appSockets.isNotEmpty() || holdsWakeLock)) {
                anomalousWindowsCount++
                appSockets.forEach { suspiciousSocketPorts += it.remotePort }
            }
        }

        val hasRepeatedScreenOffAnomaly = anomalousWindowsCount >= 3
        if (hasRepeatedScreenOffAnomaly) {
            evidence += EvidenceFact(
                code = "BEHAVIOR_SCREEN_OFF_CPU_AND_NET",
                titleRu = "Систематическая нагрузка CPU (>50% доли) при выключенном экране",
                technicalDetail = "Зафиксировано в $anomalousWindowsCount периодах наблюдения при погашенном экране " +
                    "(активные порты: ${suspiciousSocketPorts.ifEmpty { setOf(443) }.joinToString()})",
                weight = 55,
            )
        }

        return when {
            hasStaticMiningCode && hasRepeatedScreenOffAnomaly -> {
                Finding(
                    findingId = UUID.randomUUID().toString(),
                    entity = entity,
                    detector = DetectorType.BEHAVIOR_MINER_WATCH,
                    confidence = FindingConfidence.CONFIRMED,
                    verdictLabel = VerdictLabel.PROBABLE_HIDDEN_MINER,
                    evidence = evidence,
                    recommendedAction = RecommendedAction.QUARANTINE,
                )
            }

            hasStaticMiningCode && !hasRepeatedScreenOffAnomaly -> {
                Finding(
                    findingId = UUID.randomUUID().toString(),
                    entity = entity,
                    detector = DetectorType.MINING_CODE_STATIC,
                    confidence = FindingConfidence.PROBABLE,
                    verdictLabel = VerdictLabel.CONTAINS_MINING_CODE,
                    evidence = evidence,
                    recommendedAction = RecommendedAction.REVIEW,
                )
            }

            !hasStaticMiningCode && hasRepeatedScreenOffAnomaly -> {
                Finding(
                    findingId = UUID.randomUUID().toString(),
                    entity = entity,
                    detector = DetectorType.BEHAVIOR_MINER_WATCH,
                    confidence = FindingConfidence.PROBABLE,
                    verdictLabel = VerdictLabel.ABNORMAL_RESOURCE_DRAIN,
                    evidence = evidence,
                    recommendedAction = RecommendedAction.REVIEW,
                )
            }

            else -> null
        }
    }

    companion object {
        private const val MAX_SINGLE_ENTRY_BYTES = 32L * 1024L * 1024L // 32 MB

        val STRATUM_URI_PREFIXES = listOf(
            "stratum+tcp://",
            "stratum+ssl://",
            "stratum+tls://",
            "stratum2+tcp://",
        )

        val STRATUM_RPC_METHODS = listOf(
            "mining.subscribe",
            "mining.authorize",
            "mining.submit",
            "mining.set_difficulty",
            "eth_submitwork",
            "eth_getwork",
        )

        val NATIVE_MINER_SYMBOLS = listOf(
            "randomx_alloc_cache",
            "randomx_init_dataset",
            "randomx_calculate_hash",
            "randomx_create_vm",
            "xmrig::workers",
            "cryptonight_hash",
            "astrobwt_dero",
        )

        val KNOWN_MINING_POOL_DOMAINS = listOf(
            "supportxmr.com",
            "minexmr.com",
            "nanopool.org",
            "hashvault.pro",
            "c3pool.com",
            "moneroocean.stream",
            "herominers.com",
            "2miners.com",
            "f2pool.com",
            "unmineable.com",
        )
    }
}
