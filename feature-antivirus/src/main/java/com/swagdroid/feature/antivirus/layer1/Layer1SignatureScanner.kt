package com.swagdroid.feature.antivirus.layer1

import com.swagdroid.feature.antivirus.model.DetectorType
import com.swagdroid.feature.antivirus.model.EvidenceFact
import com.swagdroid.feature.antivirus.model.Finding
import com.swagdroid.feature.antivirus.model.FindingConfidence
import com.swagdroid.feature.antivirus.model.RecommendedAction
import com.swagdroid.feature.antivirus.model.ScannedEntity
import com.swagdroid.feature.antivirus.model.VerdictLabel
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Двухэтапный сигнатурный сканер Слоя 1:
 * 1. Быстрый отсев через `SignatureBloomFilter` (совместимый с подходом `MaintainTeam/Hypatia`).
 * 2. Обязательное подтверждение попадания через `ExactHashShardIndex` (нулевая вероятность ложного `CONFIRMED`).
 * 3. Сканирует в приоритетном порядке:
 *    - целиком `base.apk` / `split_*.apk`
 *    - внутренние исполняемые `classes*.dex` и нативные библиотеки `lib/*/*.so` внутри APK
 *    - отдельно лежащие скачанные `.apk`, `.dex`, `.so`, `.zip` в публичных папках (`Download/`).
 */
class Layer1SignatureScanner(
    private val bloomFilter: SignatureBloomFilter,
    private val exactIndex: ExactHashShardIndex,
    private val scanCache: ApkScanCache = ApkScanCache(),
    val signatureDbVersion: Int = 20261026,
) {
    fun registerSignature(signature: ExactMalwareSignature) {
        bloomFilter.put(signature.hashHex)
        exactIndex.insert(signature)
    }

    /**
     * Проверяет тройку хэшей (`SHA-256`, `MD5`, `SHA-1`) через двухфазный конвейер:
     * Фаза 1: BloomFilter -> Фаза 2: ExactHashShardIndex.
     */
    fun matchMultiHash(digest: MultiHashDigest): ExactMalwareSignature? {
        val candidates = listOf(digest.sha256Hex, digest.md5Hex, digest.sha1Hex)
        for (hash in candidates) {
            if (bloomFilter.mightContain(hash)) {
                val confirmed = exactIndex.verifyExactMatch(hash)
                if (confirmed != null) {
                    return confirmed
                }
            }
        }
        return null
    }

    /**
     * Сканирует установленное приложение: сначала весь APK, затем внутренние `classes*.dex` и `lib/*.so`.
     */
    fun scanInstalledAppApk(
        packageName: String,
        uid: Int,
        versionName: String,
        versionCode: Long,
        installerPackageName: String?,
        signingCertSha256: String?,
        apkFile: File,
    ): Finding? {
        if (!apkFile.exists() || !apkFile.canRead()) return null

        val cached = scanCache.getIfValid(
            packageName = packageName,
            versionCode = versionCode,
            lastModifiedEpochMs = apkFile.lastModified(),
            signatureDbVersion = signatureDbVersion,
        )
        if (cached != null && cached.isCleanInLayer1) {
            return null
        }

        // 1. Хэшируем сам APK за 1 проход
        val apkDigest = SinglePassMultiHasher.hashFile(apkFile)
        val wholeApkMatch = matchMultiHash(apkDigest)
        if (wholeApkMatch != null) {
            return buildConfirmedSignatureFinding(
                packageName = packageName,
                uid = uid,
                versionName = versionName,
                versionCode = versionCode,
                installerPackageName = installerPackageName,
                signingCertSha256 = signingCertSha256,
                apkPath = apkFile.absolutePath,
                apkSha256 = apkDigest.sha256Hex,
                matchedLocation = "base.apk",
                signature = wholeApkMatch,
            )
        }

        // 2. Хэшируем внутренние classes*.dex и нативные библиотеки lib/*/*.so
        runCatching {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val isExecutableEntry = (name.startsWith("classes") && name.endsWith(".dex")) ||
                        (name.startsWith("lib/") && name.endsWith(".so")) ||
                        (name.startsWith("assets/") && (name.endsWith(".dex") || name.endsWith(".so") || name.endsWith(".apk")))

                    if (isExecutableEntry) {
                        zip.getInputStream(entry).use { entryStream ->
                            val entryDigest = SinglePassMultiHasher.hashStream(entryStream)
                            val entryMatch = matchMultiHash(entryDigest)
                            if (entryMatch != null) {
                                return buildConfirmedSignatureFinding(
                                    packageName = packageName,
                                    uid = uid,
                                    versionName = versionName,
                                    versionCode = versionCode,
                                    installerPackageName = installerPackageName,
                                    signingCertSha256 = signingCertSha256,
                                    apkPath = apkFile.absolutePath,
                                    apkSha256 = apkDigest.sha256Hex,
                                    matchedLocation = "Внутренний модуль APK: $name",
                                    signature = entryMatch,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Сохраняем чистый результат в кэш
        scanCache.put(
            CachedApkScanEntry(
                packageName = packageName,
                versionCode = versionCode,
                lastModifiedEpochMs = apkFile.lastModified(),
                signatureDbVersion = signatureDbVersion,
                baseApkSha256 = apkDigest.sha256Hex,
                isCleanInLayer1 = true,
            )
        )
        return null
    }

    /**
     * Сканирует отдельный скачанный файл в хранилище (например, в `/sdcard/Download`).
     */
    fun scanStandaloneFile(file: File): Finding? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val digest = SinglePassMultiHasher.hashFile(file)
        val match = matchMultiHash(digest) ?: return null

        val entity = ScannedEntity.StandaloneFile(
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = digest.sha256Hex,
            md5 = digest.md5Hex,
        )
        return Finding(
            findingId = UUID.randomUUID().toString(),
            entity = entity,
            detector = DetectorType.EXACT_HASH_IOC,
            confidence = FindingConfidence.CONFIRMED,
            verdictLabel = VerdictLabel.KNOWN_THREAT,
            evidence = listOf(
                EvidenceFact(
                    code = "EXACT_FILE_HASH_MATCH",
                    titleRu = "Точное совпадение криптографического хэша (${match.hashType.uppercase()})",
                    technicalDetail = "Семейство: ${match.threatFamily} • Хэш: ${match.hashHex} • База: ${match.sourceFeed}",
                    weight = 100,
                )
            ),
            recommendedAction = RecommendedAction.QUARANTINE,
        )
    }

    private fun buildConfirmedSignatureFinding(
        packageName: String,
        uid: Int,
        versionName: String,
        versionCode: Long,
        installerPackageName: String?,
        signingCertSha256: String?,
        apkPath: String,
        apkSha256: String,
        matchedLocation: String,
        signature: ExactMalwareSignature,
    ): Finding {
        val entity = ScannedEntity.InstalledApp(
            packageName = packageName,
            uid = uid,
            versionName = versionName,
            versionCode = versionCode,
            installerPackageName = installerPackageName,
            signingCertSha256 = signingCertSha256,
            baseApkPath = apkPath,
            baseApkSha256 = apkSha256,
        )
        return Finding(
            findingId = UUID.randomUUID().toString(),
            entity = entity,
            detector = DetectorType.EXACT_HASH_IOC,
            confidence = FindingConfidence.CONFIRMED,
            verdictLabel = VerdictLabel.KNOWN_THREAT,
            evidence = listOf(
                EvidenceFact(
                    code = "TWO_STAGE_HASH_CONFIRMED",
                    titleRu = "Подтверждённая сигнатура вредоносного ПО: ${signature.threatFamily}",
                    technicalDetail = "Объект: $matchedLocation • ${signature.hashType.uppercase()}: ${signature.hashHex} • Источник: ${signature.sourceFeed}",
                    weight = 100,
                )
            ),
            recommendedAction = RecommendedAction.QUARANTINE,
        )
    }
}
