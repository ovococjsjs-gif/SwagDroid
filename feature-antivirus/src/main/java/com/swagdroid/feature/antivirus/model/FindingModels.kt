package com.swagdroid.feature.antivirus.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Сущность, к которой относится находка сканера: установленный пакет или отдельный файл.
 */
@Serializable
sealed class ScannedEntity {
    @Serializable
    @SerialName("installed_app")
    data class InstalledApp(
        val packageName: String,
        val uid: Int,
        val versionName: String,
        val versionCode: Long,
        val installerPackageName: String?,
        val signingCertSha256: String?,
        val baseApkPath: String?,
        val baseApkSha256: String?,
    ) : ScannedEntity()

    @Serializable
    @SerialName("standalone_file")
    data class StandaloneFile(
        val absolutePath: String,
        val sizeBytes: Long,
        val sha256: String,
        val md5: String,
    ) : ScannedEntity()
}

/**
 * Тип детектора, сработавшего при проверке.
 */
@Serializable
enum class DetectorType(val displayNameRu: String) {
    EXACT_HASH_IOC("Слой 1: Сигнатурный (Bloom + Точный хэш)"),
    STALKERWARE_IOC("Слой 3: Индикаторы Stalkerware / Троянов (IOC)"),
    MINING_CODE_STATIC("Слой 2А: Статический анализатор майнеров (DEX/.so)"),
    BEHAVIOR_MINER_WATCH("Слой 2Б: Поведенческий дозор (MinerWatch CPU + Сеть)"),
    GHOST_AND_ACCESS_AUDIT("Слой 3: Аудит скрытых иконок и опасных доступов"),
    RECENT_AD_POPUP_CULPRIT("Слой 3: Детектор всплывающей рекламы (UsageStats)"),
}

/**
 * Уровень достоверности находки (Confidence).
 * Только `CONFIRMED` даёт право на формулировку «Известная угроза» и рекомендацию немедленного карантина.
 */
@Serializable
enum class FindingConfidence(val displayNameRu: String) {
    CONFIRMED("Подтверждено (100% совпадение IOC / точного хэша)"),
    PROBABLE("Высокая вероятность (Требуется проверка пользователем)"),
    WEAK("Косвенный признак (Аномалия или рискованная настройка)"),
}

/**
 * Честный ярлык вердикта (вместо крика «Найден вирус!» на любую подозрительную настройку).
 */
@Serializable
enum class VerdictLabel(
    val titleRu: String,
    val descriptionRu: String,
) {
    KNOWN_THREAT(
        titleRu = "Известная угроза",
        descriptionRu = "Точное совпадение SHA-256/MD5 файла, имени пакета или сертификата с базой вредоносного ПО / Stalkerware.",
    ),
    PROBABLE_HIDDEN_MINER(
        titleRu = "Вероятный скрытый майнер",
        descriptionRu = "Обнаружен встроенный майнинговый код (Stratum/XMRig/RandomX) в сочетании с подключением к пулу или ночной CPU-аномалией.",
    ),
    CONTAINS_MINING_CODE(
        titleRu = "Содержит майнинговый код",
        descriptionRu = "Внутри DEX или нативных библиотек (.so) найдены строки майнинга, но активная фоновая работа пока не зафиксирована.",
    ),
    ABNORMAL_RESOURCE_DRAIN(
        titleRu = "Аномальный расход ресурсов",
        descriptionRu = "Приложение систематически нагружает CPU и удерживает Wakelock/сеть при выключенном экране.",
    ),
    RISKY_ACCESS_CONFIGURATION(
        titleRu = "Рискованный набор доступов",
        descriptionRu = "Подозрительная конфигурация (например, отключённая после установки иконка лаунчера + показ поверх окон или Accessibility). Не является автоматическим вердиктом «вирус».",
    ),
}

/**
 * Рекомендуемое действие для пользователя.
 */
@Serializable
enum class RecommendedAction(val buttonLabelRu: String) {
    OBSERVE("Наблюдать (Проверить в Ночном дозоре)"),
    REVIEW("Проверить доказательства и доступы"),
    QUARANTINE("Поместить в карантин (Заморозить)"),
    UNINSTALL("Удалить полностью"),
}

/**
 * Конкретный проверяемый факт в цепочке доказательств (`EvidenceChain`).
 */
@Serializable
data class EvidenceFact(
    val code: String,
    val titleRu: String,
    val technicalDetail: String,
    val weight: Int,
)

/**
 * Единая структура находки (`Finding`), возвращаемая всеми детекторами.
 */
@Serializable
data class Finding(
    val findingId: String,
    val entity: ScannedEntity,
    val detector: DetectorType,
    val confidence: FindingConfidence,
    val verdictLabel: VerdictLabel,
    val evidence: List<EvidenceFact>,
    val recommendedAction: RecommendedAction,
    val detectedAtEpochMs: Long = System.currentTimeMillis(),
)
