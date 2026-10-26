package com.swagdroid.feature.security.model

enum class SecuritySubTab(val titleRu: String) {
    THREAT_SCANNER("Антивирус"),
    QUARANTINE("Карантин"),
    TRACKER_RADAR("Трекеры"),
    ADBLOCK_FIREWALL("AdBlock 3-в-1"),
}

/**
 * Typed threat object categories modeled on BlendLog/MinerSearch `ThreatObjectKind`.
 */
enum class ThreatObjectKind(val labelRu: String) {
    SIGNATURE_MALWARE("Вредоносная сигнатура / Троян"),
    CRYPTO_MINER("Скрытый криптомайнер"),
    GHOST_ADWARE_DROPPER("Скрытая реклама (HiddenAds) / Дроппер"),
    PRIVILEGE_ABUSE("Опасные права (Accessibility / Admin / SMS)"),
    SYSTEM_NETWORK_HIJACK("Перехват сети (MITM / Прокси / Hosts)"),
}

enum class ThreatSeverity(val labelRu: String, val weight: Int) {
    CRITICAL("Критическая угроза", 90),
    HIGH("Высокий риск", 70),
    MEDIUM("Подозрительно", 45),
    LOW("Предупреждение", 25),
}

/**
 * Remediation action ordered strictly by MinerSearch `ThreatManager` priority (`1 = highest`).
 */
enum class ThreatActionType(val priorityRank: Int, val buttonLabelRu: String) {
    UNINSTALL_OR_DELETE(1, "Удалить угрозу"),
    QUARANTINE_SUSPEND(2, "В карантин (изоляция)"),
    CURE_SYSTEM_SETTING(3, "Вылечить настройку"),
    REVIEW_MANUAL(4, "Проверить вручную"),
}

data class RawThreatSignal(
    val targetKey: String,
    val displayTitle: String,
    val packageName: String? = null,
    val apkFilePath: String? = null,
    val kind: ThreatObjectKind,
    val severity: ThreatSeverity,
    val recommendedAction: ThreatActionType,
    val reasonRu: String,
    val technicalEvidence: String,
)

data class AggregatedThreatDecision(
    val targetKey: String,
    val displayTitle: String,
    val packageName: String? = null,
    val apkFilePath: String? = null,
    val primaryKind: ThreatObjectKind,
    val maxSeverity: ThreatSeverity,
    val riskScore: Int,
    val action: ThreatActionType,
    val reasonsRu: List<String>,
    val evidenceLines: List<String>,
)

data class QuarantineEntry(
    val packageName: String,
    val appLabel: String,
    val quarantinedAtEpochMs: Long,
    val reasonSummaryRu: String,
    val vaultSnapshotId: Long,
)

data class TrackerSdkSignature(
    val id: String,
    val name: String,
    val categoryRu: String,
    val dexMarkerAscii: String,
)

data class AppTrackerScanItem(
    val packageName: String,
    val appLabel: String,
    val detectedTrackers: List<TrackerSdkSignature>,
    val requestedDangerousPermsCount: Int,
)
