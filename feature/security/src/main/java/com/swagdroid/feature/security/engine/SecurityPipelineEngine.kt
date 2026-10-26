package com.swagdroid.feature.security.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.security.model.AggregatedThreatDecision
import com.swagdroid.feature.security.model.AppTrackerScanItem
import com.swagdroid.feature.security.model.QuarantineEntry
import com.swagdroid.feature.security.model.RawThreatSignal
import com.swagdroid.feature.security.model.ThreatActionType
import com.swagdroid.feature.security.model.ThreatObjectKind
import com.swagdroid.feature.security.model.ThreatSeverity
import com.swagdroid.feature.security.model.TrackerSdkSignature
import com.swagdroid.feature.security.scanner.SecuritySignaturesCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityPipelineEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
    private val vaultManager: StateVaultManager,
) {

    /**
     * Executes all 5 security scanners across installed user apps, `/sdcard/Download` APKs,
     * active privileged services, and network/hosts settings, then aggregates raw signals
     * into prioritized [AggregatedThreatDecision] records (MinerSearch ThreatManager pattern).
     */
    suspend fun runFullThreatScan(
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AggregatedThreatDecision> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val userPackages = pm.getInstalledPackages(
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
        ).filter { pkgInfo ->
            val app = pkgInfo.applicationInfo ?: return@filter false
            val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            !isSys && pkgInfo.packageName != context.packageName
        }

        val enabledAccessibility = executor.readSetting(SettingNamespace.SECURE, "enabled_accessibility_services").orEmpty()
        val enabledNotifListeners = executor.readSetting(SettingNamespace.SECURE, "enabled_notification_listeners").orEmpty()

        val signals = ArrayList<RawThreatSignal>()
        val totalSteps = userPackages.size + 2
        var step = 0

        for (pkgInfo in userPackages) {
            step++
            onProgress(step, totalSteps)
            val appInfo = pkgInfo.applicationInfo ?: continue
            val pkg = pkgInfo.packageName
            val rawLabel = pm.getApplicationLabel(appInfo).toString()
            val label = rawLabel.ifBlank { "<Без имени> ($pkg)" }

            // Scanner 1: Bloom filter + Known Trojan / Masquerade Package IDs
            if (SecuritySignaturesCatalog.malwareBloomFilter.mightContain(pkg)) {
                SecuritySignaturesCatalog.KNOWN_TROJAN_PACKAGE_IDS[pkg.lowercase()]?.let { family ->
                    signals.add(
                        RawThreatSignal(
                            targetKey = "pkg:$pkg",
                            displayTitle = label,
                            packageName = pkg,
                            apkFilePath = appInfo.sourceDir,
                            kind = ThreatObjectKind.SIGNATURE_MALWARE,
                            severity = ThreatSeverity.CRITICAL,
                            recommendedAction = ThreatActionType.UNINSTALL_OR_DELETE,
                            reasonRu = "Совпадение с базой вредоносных пакетов: $family",
                            technicalEvidence = "packageName=$pkg",
                        ),
                    )
                }
            }

            // Masquerade check: non-system app pretending to be com.google.update.* or com.android.system.*
            if (pkg.startsWith("com.android.system.") || pkg.startsWith("com.google.system.")) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.SIGNATURE_MALWARE,
                        severity = ThreatSeverity.HIGH,
                        recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                        reasonRu = "Несистемное приложение маскируется под системный пакет Google/Android!",
                        technicalEvidence = "FLAG_SYSTEM=false, package=$pkg",
                    ),
                )
            }

            // Scanner 2: Crypto-Miner Hunter inside APK native libs & assets
            val apkFile = appInfo.sourceDir?.let(::File)
            if (apkFile != null && apkFile.canRead()) {
                val minerHits = inspectApkZipForMinerMarkers(apkFile)
                if (minerHits.isNotEmpty()) {
                    signals.add(
                        RawThreatSignal(
                            targetKey = "pkg:$pkg",
                            displayTitle = label,
                            packageName = pkg,
                            apkFilePath = apkFile.absolutePath,
                            kind = ThreatObjectKind.CRYPTO_MINER,
                            severity = ThreatSeverity.CRITICAL,
                            recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                            reasonRu = "Внутри APK обнаружены сигнатуры скрытого криптомайнера!",
                            technicalEvidence = minerHits.joinToString("; "),
                        ),
                    )
                }
            }

            // Scanner 3: Ghost Adware / Blank-Icon HiddenAds / Silent Dropper
            val hasLauncherIntent = pm.getLaunchIntentForPackage(pkg) != null
            val requestedPerms = pkgInfo.requestedPermissions?.toSet().orEmpty()
            val canDrawOverlays = Manifest.permission.SYSTEM_ALERT_WINDOW in requestedPerms
            val canInstallPackages = Manifest.permission.REQUEST_INSTALL_PACKAGES in requestedPerms
            val canBootStart = Manifest.permission.RECEIVE_BOOT_COMPLETED in requestedPerms

            if (rawLabel.isBlank()) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = "Скрытый пакет без названия ($pkg)",
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.GHOST_ADWARE_DROPPER,
                        severity = ThreatSeverity.HIGH,
                        recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                        reasonRu = "У приложения намеренно стёрто имя иконки — типичный приём рекламных троянов HiddenAds!",
                        technicalEvidence = "applicationLabel=\"\", package=$pkg",
                    ),
                )
            } else if (!hasLauncherIntent && canDrawOverlays && canBootStart) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.GHOST_ADWARE_DROPPER,
                        severity = ThreatSeverity.HIGH,
                        recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                        reasonRu = "Скрытое от лаунчера приложение с автозапуском и показом окон поверх экрана (Adware-оверлей)",
                        technicalEvidence = "noLauncherIcon + SYSTEM_ALERT_WINDOW + RECEIVE_BOOT_COMPLETED",
                    ),
                )
            }

            // Scanner 4: Banking-Trojan Privilege Abuse (Accessibility + SMS / Install Packages)
            val isActiveAccessibility = enabledAccessibility.contains("$pkg/")
            val isActiveNotifListener = enabledNotifListeners.contains("$pkg/")
            val readsSms = Manifest.permission.READ_SMS in requestedPerms || Manifest.permission.RECEIVE_SMS in requestedPerms

            if (isActiveAccessibility && (readsSms || canInstallPackages || canDrawOverlays)) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.PRIVILEGE_ABUSE,
                        severity = ThreatSeverity.HIGH,
                        recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                        reasonRu = "Опасная комбинация: активный перехват экрана (Accessibility) вместе с чтением СМС / оверлеями / установкой APK",
                        technicalEvidence = "Active AccessibilityService + sms=$readsSms, overlay=$canDrawOverlays, install=$canInstallPackages",
                    ),
                )
            } else if (isActiveNotifListener && canInstallPackages) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.PRIVILEGE_ABUSE,
                        severity = ThreatSeverity.MEDIUM,
                        recommendedAction = ThreatActionType.REVIEW_MANUAL,
                        reasonRu = "Приложение читает все входящие уведомления и имеет право установки пакетов",
                        technicalEvidence = "NotificationListener + REQUEST_INSTALL_PACKAGES",
                    ),
                )
            }

            // Scanner 4B: Pre-Android-6 targetSdk (<23) Runtime-Permission Bypass & Debuggable Build Check
            val targetSdk = appInfo.targetSdkVersion
            val isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (targetSdk in 1..22 && (readsSms || canDrawOverlays || canInstallPackages)) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.PRIVILEGE_ABUSE,
                        severity = ThreatSeverity.HIGH,
                        recommendedAction = ThreatActionType.QUARANTINE_SUSPEND,
                        reasonRu = "Устаревший targetSdk=$targetSdk в сочетании с доступом к SMS/оверлеям позволяет обходить запросы разрешений Android!",
                        technicalEvidence = "targetSdkVersion=$targetSdk, sms=$readsSms, overlay=$canDrawOverlays",
                    ),
                )
            } else if (isDebuggable && canInstallPackages) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "pkg:$pkg",
                        displayTitle = label,
                        packageName = pkg,
                        apkFilePath = appInfo.sourceDir,
                        kind = ThreatObjectKind.SIGNATURE_MALWARE,
                        severity = ThreatSeverity.MEDIUM,
                        recommendedAction = ThreatActionType.REVIEW_MANUAL,
                        reasonRu = "Отладочная сборка (FLAG_DEBUGGABLE) с правом установки сторонних APK",
                        technicalEvidence = "FLAG_DEBUGGABLE=true + REQUEST_INSTALL_PACKAGES",
                    ),
                )
            }
        }

        // Scanner 5A: Scan loose files in /sdcard/Download for EICAR / Malware SHA-256 Bloom hits
        step++
        onProgress(step, totalSteps)
        val downloadDir = File(Environment.getExternalStorageDirectory(), "Download")
        downloadDir.listFiles()?.take(80)?.forEach { f ->
            if (f.isFile && f.length() in 1L..(35L * 1024 * 1024)) {
                val sha = computeSha256(f)
                if (sha.isNotEmpty() && SecuritySignaturesCatalog.malwareBloomFilter.mightContain(sha)) {
                    SecuritySignaturesCatalog.KNOWN_MALWARE_SHA256[sha]?.let { sigName ->
                        signals.add(
                            RawThreatSignal(
                                targetKey = "file:${f.absolutePath}",
                                displayTitle = f.name,
                                apkFilePath = f.absolutePath,
                                kind = ThreatObjectKind.SIGNATURE_MALWARE,
                                severity = ThreatSeverity.CRITICAL,
                                recommendedAction = ThreatActionType.UNINSTALL_OR_DELETE,
                                reasonRu = "Файл в Загрузках совпал с сигнатурой угрозы: $sigName",
                                technicalEvidence = "SHA-256=$sha",
                            ),
                        )
                    }
                }
            }
        }

        // Scanner 5B: System & Network Hijack Audit (MITM CA, Global HTTP Proxy, Hijacked /etc/hosts)
        step++
        onProgress(step, totalSteps)
        val httpProxy = executor.readSetting(SettingNamespace.GLOBAL, "http_proxy")
        if (!httpProxy.isNullOrBlank() && httpProxy != ":0" && !httpProxy.startsWith("127.0.0.1")) {
            signals.add(
                RawThreatSignal(
                    targetKey = "setting:global:http_proxy",
                    displayTitle = "Перехват трафика через системный HTTP-прокси",
                    kind = ThreatObjectKind.SYSTEM_NETWORK_HIJACK,
                    severity = ThreatSeverity.HIGH,
                    recommendedAction = ThreatActionType.CURE_SYSTEM_SETTING,
                    reasonRu = "В системе прописан сторонний прокси-сервер, через который уходит трафик устройства",
                    technicalEvidence = "Global:http_proxy = $httpProxy",
                ),
            )
        }

        val hostsFile = File("/system/etc/hosts")
        if (hostsFile.canRead()) {
            val hijackedLines = analyzeHostsHijacks(runCatching { hostsFile.readText() }.getOrDefault(""))
            if (hijackedLines.isNotEmpty()) {
                signals.add(
                    RawThreatSignal(
                        targetKey = "sys:hosts_hijack",
                        displayTitle = "Подозрительная подмена IP в /etc/hosts",
                        kind = ThreatObjectKind.SYSTEM_NETWORK_HIJACK,
                        severity = ThreatSeverity.CRITICAL,
                        recommendedAction = ThreatActionType.REVIEW_MANUAL,
                        reasonRu = "В файле hosts обнаружено перенаправление доменов на сторонние IP-адреса (возможный фишинг!)",
                        technicalEvidence = hijackedLines.take(3).joinToString(" | "),
                    ),
                )
            }
        }

        val userCaDir = File("/data/misc/user/0/cacerts-added")
        val userCerts = userCaDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (userCerts.isNotEmpty()) {
            signals.add(
                RawThreatSignal(
                    targetKey = "sys:user_ca_mitm",
                    displayTitle = "Установлен пользовательский корневой сертификат CA",
                    kind = ThreatObjectKind.SYSTEM_NETWORK_HIJACK,
                    severity = ThreatSeverity.MEDIUM,
                    recommendedAction = ThreatActionType.REVIEW_MANUAL,
                    reasonRu = "Сторонний сертификат позволяет расшифровывать HTTPS-соединения (проверьте, устанавливали ли вы AdGuard/корпоративный CA сами)",
                    technicalEvidence = "Сертификатов в cacerts-added: ${userCerts.size}",
                ),
            )
        }

        aggregateDecisions(signals)
    }

    // =========================================================================
    // Quarantine & Remediation Manager (StateVault-backed!)
    // =========================================================================

    suspend fun quarantinePackage(
        pkg: String,
        appLabel: String,
        reasonRu: String,
    ): Result<QuarantineEntry> = withContext(Dispatchers.IO) {
        if (!Regex("^[a-zA-Z0-9._]+$").matches(pkg)) {
            return@withContext Result.failure(IllegalArgumentException("Некорректное имя пакета"))
        }
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext Result.failure(
                IllegalStateException("Для жёсткой изоляции в карантин подключите ADB / Shizuku или Root (либо используйте кнопку «Удалить угрозу»)."),
            )
        }

        executor.execute("am force-stop $pkg")

        val tx = vaultManager.applyTransaction(
            title = "Карантин угрозы: $appLabel",
            reason = reasonRu,
            source = FeatureSource.SECURITY,
            mutations = listOf(
                VaultMutationSpec.MutateAppOp(pkg, "SYSTEM_ALERT_WINDOW", "ignore"),
                VaultMutationSpec.MutateAppOp(pkg, "REQUEST_INSTALL_PACKAGES", "ignore"),
                VaultMutationSpec.MutateAppOp(pkg, "RUN_ANY_IN_BACKGROUND", "ignore"),
                VaultMutationSpec.MutatePackageState(pkg, PackageStateTarget.SUSPENDED),
            ),
        )

        when (tx) {
            is VaultTransactionResult.Success -> Result.success(
                QuarantineEntry(
                    packageName = pkg,
                    appLabel = appLabel,
                    quarantinedAtEpochMs = System.currentTimeMillis(),
                    reasonSummaryRu = reasonRu,
                    vaultSnapshotId = tx.snapshotId,
                ),
            )
            is VaultTransactionResult.Failure -> Result.failure(IllegalStateException(tx.errorMessage))
        }
    }

    suspend fun restoreFromQuarantine(entry: QuarantineEntry): Boolean = withContext(Dispatchers.IO) {
        vaultManager.rollbackSnapshot(entry.vaultSnapshotId) is VaultTransactionResult.Success
    }

    suspend fun cureHijackedProxy(): Boolean = withContext(Dispatchers.IO) {
        val tx = vaultManager.applyTransaction(
            title = "Очистка стороннего HTTP-прокси",
            reason = "Удаление перехватчика трафика Global:http_proxy",
            source = FeatureSource.SECURITY,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "http_proxy", ":0"),
            ),
        )
        tx is VaultTransactionResult.Success
    }

    fun buildSystemUninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // =========================================================================
    // Streaming Exodus Privacy DEX Tracker Radar
    // =========================================================================

    suspend fun scanInstalledAppsForTrackers(): List<AppTrackerScanItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { p ->
                val ai = p.applicationInfo ?: return@filter false
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && p.packageName != context.packageName
            }
            .take(60)

        apps.mapNotNull { pkgInfo ->
            val ai = pkgInfo.applicationInfo ?: return@mapNotNull null
            val apk = ai.sourceDir?.let(::File) ?: return@mapNotNull null
            if (!apk.canRead()) return@mapNotNull null

            val foundTrackers = inspectApkDexForTrackers(apk)
            val dangerousCount = pkgInfo.requestedPermissions?.count { perm ->
                perm.contains("LOCATION") || perm.contains("CAMERA") || perm.contains("RECORD_AUDIO") ||
                    perm.contains("CONTACTS") || perm.contains("READ_SMS")
            } ?: 0

            AppTrackerScanItem(
                packageName = pkgInfo.packageName,
                appLabel = pm.getApplicationLabel(ai).toString(),
                detectedTrackers = foundTrackers,
                requestedDangerousPermsCount = dangerousCount,
            )
        }.sortedByDescending { it.detectedTrackers.size }
    }

    // =========================================================================
    // Low-memory ZIP / DEX / Native Lib Streaming Helpers
    // =========================================================================

    private fun inspectApkZipForMinerMarkers(apkFile: File): List<String> = runCatching {
        val hits = LinkedHashSet<String>()
        val chunkBuf = ByteArray(STREAM_CHUNK_BYTES)
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.lowercase()
                if (name.contains("xmrig")) {
                    hits.add("Файл майнера в APK: ${entry.name}")
                }
                if (!entry.isDirectory && (name.startsWith("assets/") || name.startsWith("lib/")) && entry.size in 16..25_000_000) {
                    zip.getInputStream(entry).use { inp ->
                        scanStreamWithOverlap(inp, chunkBuf) { windowAscii ->
                            for ((marker, labelRu) in SecuritySignaturesCatalog.CRYPTO_MINER_MARKERS) {
                                if (windowAscii.contains(marker, ignoreCase = true)) {
                                    hits.add("$labelRu ($marker)")
                                }
                            }
                        }
                    }
                }
                if (hits.size >= 4) break
            }
        }
        hits.toList()
    }.getOrDefault(emptyList())

    private fun inspectApkDexForTrackers(apkFile: File): List<TrackerSdkSignature> = runCatching {
        val matched = LinkedHashMap<String, TrackerSdkSignature>()
        val chunkBuf = ByteArray(STREAM_CHUNK_BYTES)
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                    zip.getInputStream(entry).use { inp ->
                        scanStreamWithOverlap(inp, chunkBuf) { windowAscii ->
                            for (sig in SecuritySignaturesCatalog.EXODUS_TRACKER_SIGNATURES) {
                                if (sig.id !in matched && windowAscii.contains(sig.dexMarkerAscii)) {
                                    matched[sig.id] = sig
                                }
                            }
                        }
                    }
                }
            }
        }
        matched.values.toList()
    }.getOrDefault(emptyList())

    /**
     * Bounded 64 KB sliding-window stream scanner with 256-byte chunk overlap so markers split
     * across read boundaries are still matched with <128 KB heap usage instead of 24 MB allocations.
     */
    private inline fun scanStreamWithOverlap(
        input: java.io.InputStream,
        chunkBuf: ByteArray,
        onWindow: (String) -> Unit,
    ) {
        var tail = ""
        while (true) {
            val readLen = input.read(chunkBuf)
            if (readLen <= 0) break
            val current = String(chunkBuf, 0, readLen, Charsets.ISO_8859_1)
            val combined = if (tail.isEmpty()) current else tail + current
            onWindow(combined)
            tail = if (combined.length > STREAM_OVERLAP_BYTES) {
                combined.substring(combined.length - STREAM_OVERLAP_BYTES)
            } else {
                combined
            }
        }
    }

    private fun computeSha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { inp ->
            val buf = ByteArray(16_384)
            while (true) {
                val r = inp.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    companion object {
        private const val STREAM_CHUNK_BYTES = 65_536
        private const val STREAM_OVERLAP_BYTES = 256
        private val SAFE_LOOPBACK_IPS = setOf("127.0.0.1", "0.0.0.0", "::1", "fe00::0", "ff00::0", "ff02::1", "ff02::2", "ff02::3")

        /**
         * Aggregates multiple [RawThreatSignal]s per target using MinerSearch `ThreatManager`
         * priority ordering (`UNINSTALL_OR_DELETE > QUARANTINE_SUSPEND > CURE_SYSTEM_SETTING > REVIEW_MANUAL`).
         */
        fun aggregateDecisions(signals: List<RawThreatSignal>): List<AggregatedThreatDecision> {
            return signals.groupBy { it.targetKey }
                .map { (targetKey, group) ->
                    val topSeverity = group.maxBy { it.severity.weight }.severity
                    val bestAction = group.minBy { it.recommendedAction.priorityRank }.recommendedAction
                    val primary = group.maxBy { it.severity.weight }
                    val combinedScore = (topSeverity.weight + (group.size - 1) * 10).coerceAtMost(100)

                    AggregatedThreatDecision(
                        targetKey = targetKey,
                        displayTitle = primary.displayTitle,
                        packageName = primary.packageName,
                        apkFilePath = primary.apkFilePath,
                        primaryKind = primary.kind,
                        maxSeverity = topSeverity,
                        riskScore = combinedScore,
                        action = bestAction,
                        reasonsRu = group.map { it.reasonRu }.distinct(),
                        evidenceLines = group.map { it.technicalEvidence }.distinct(),
                    )
                }
                .sortedWith(
                    compareBy<AggregatedThreatDecision> { it.action.priorityRank }
                        .thenByDescending { it.riskScore },
                )
        }

        /**
         * Port of MinerSearch `HostsThreatAnalyzer`: flags `/etc/hosts` entries pointing real
         * domains to arbitrary external phishing/hijack IPs while allowing `0.0.0.0` / `127.0.0.1` AdBlock entries!
         */
        fun analyzeHostsHijacks(hostsContent: String): List<String> {
            val suspicious = ArrayList<String>()
            for (rawLine in hostsContent.lineSequence()) {
                val clean = rawLine.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                val parts = clean.split(Regex("\\s+"))
                if (parts.size < 2) continue
                val ip = parts[0].lowercase()
                val host = parts[1].lowercase()
                if (ip !in SAFE_LOOPBACK_IPS && host != "localhost" && host != "ip6-localhost") {
                    suspicious.add("$ip → $host")
                }
            }
            return suspicious
        }

        fun detectMinerMarkersInBytes(bytes: ByteArray): List<String> {
            val ascii = String(bytes, Charsets.ISO_8859_1).lowercase()
            return SecuritySignaturesCatalog.CRYPTO_MINER_MARKERS
                .filter { (marker, _) -> ascii.contains(marker) }
                .map { (marker, labelRu) -> "$labelRu ($marker)" }
        }

        fun detectTrackersInDexBytes(bytes: ByteArray, length: Int = bytes.size): List<TrackerSdkSignature> {
            val ascii = String(bytes, 0, length, Charsets.ISO_8859_1)
            return SecuritySignaturesCatalog.EXODUS_TRACKER_SIGNATURES.filter { sig ->
                ascii.contains(sig.dexMarkerAscii)
            }
        }
    }
}
