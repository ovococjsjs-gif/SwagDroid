package com.swagdroid.feature.fixer

import com.swagdroid.adb.OemVendorProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Источник и достоверность каждого поля в Паспорте батареи и памяти (Этап 7).
 */
@Serializable
enum class TelemetrySourceConfidence(val labelRu: String) {
    ANDROID_14_PUBLIC_API("Официальный API Android 14+ (BatteryManager.EXTRA_CYCLE_COUNT)"),
    ADB_BATTERY_STATS_HAL("Системный сервис BatteryStats / HAL (Зависит от контроллера вендора)"),
    SYSFS_CHARGE_COUNTER("Оценка по sysfs (charge_full / charge_full_design)"),
    PROC_MEMINFO_KERNEL("Ядро Linux (/proc/meminfo и /proc/swaps)"),
    UNAVAILABLE_ON_DEVICE("Не поддерживается контроллером на данной модели"),
}

@Serializable
data class SourcedMetric<T>(
    val value: T?,
    val source: TelemetrySourceConfidence,
)

@Serializable
data class HonestHardwarePassport(
    val cycleCount: SourcedMetric<Int>,
    val estimatedBatteryHealthPercent: SourcedMetric<Int>,
    val physicalRamTotalMb: SourcedMetric<Long>,
    val zramSwapTotalMb: SourcedMetric<Long>,
    val recentLowMemoryKillsCount: SourcedMetric<Int>,
)

/**
 * Превью применения пресета («желаемого состояния») с честным указанием пропущенных или конфликтующих пунктов.
 */
@Serializable
enum class PresetItemDiffStatus(val badgeRu: String) {
    WILL_APPLY("Будет изменено"),
    ALREADY_MATCHES("Уже в нужном состоянии"),
    SKIPPED_UNSUPPORTED_ON_DEVICE("Пропущено: не поддерживается на вашей модели/Android"),
    SKIPPED_INSUFFICIENT_PRIVILEGE("Пропущено: требуется уровень привилегий ADB Shell / Root"),
}

@Serializable
data class PresetDiffItem(
    val toggleId: String,
    val titleRu: String,
    val currentValue: String?,
    val targetValue: String,
    val status: PresetItemDiffStatus,
)

/**
 * Семантический формат экспорта/импорта пользовательского сетапа (`schema = 1`).
 *
 * Экспортирует высокоуровневые намерения, а НЕ сырые дампы системных таблиц `Settings`.
 * При импорте на новый телефон каждый пункт заново проходит валидацию совместимости и создаёт
 * новый Write-Ahead снепшот целевого устройства.
 */
@Serializable
data class SemanticSetupExportBundle(
    val schema: Int = 1,
    val exportedFromDeviceModel: String,
    val exportedAtEpochMs: Long,
    val adblockMode: String,
    val adblockProviderId: String,
    val desiredToggles: Map<String, Boolean>,
    val desiredSuspendedPackages: List<String>,
    val privacyNoticeRu: String = "ВНИМАНИЕ: Файл содержит список ваших пакетных настроек. Храните его приватно.",
)

class HardwarePassportAndPresetsEngine {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun exportSemanticSetupToJson(bundle: SemanticSetupExportBundle): String =
        json.encodeToString(bundle)

    fun parseAndValidateImportedSetup(
        rawJson: String,
        targetVendor: OemVendorProfile,
        targetSdkInt: Int,
        installedPackagesOnTarget: Set<String>,
    ): Result<Pair<SemanticSetupExportBundle, List<String>>> = runCatching {
        val bundle = json.decodeFromString<SemanticSetupExportBundle>(rawJson)
        require(bundle.schema == 1) { "Неподдерживаемая версия схемы сетапа: ${bundle.schema}" }

        val warnings = mutableListOf<String>()
        // Отфильтровываем пакеты, которых нет на целевом телефоне
        val missingPackages = bundle.desiredSuspendedPackages.filter { it !in installedPackagesOnTarget }
        if (missingPackages.isNotEmpty()) {
            warnings += "Пропущено ${missingPackages.size} пакетов, отсутствующих на этом устройстве: ${missingPackages.joinToString()}"
        }

        val sanitizedBundle = bundle.copy(
            desiredSuspendedPackages = bundle.desiredSuspendedPackages.filter { it in installedPackagesOnTarget },
        )
        sanitizedBundle to warnings
    }
}
