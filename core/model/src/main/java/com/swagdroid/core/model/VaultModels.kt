package com.swagdroid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SettingNamespace(val cliName: String) {
    GLOBAL("global"),
    SECURE("secure"),
    SYSTEM("system"),
}

@Serializable
enum class PackageStateTarget(val labelRu: String) {
    ENABLED("Включён"),
    DISABLED_USER("Отключён (Disable)"),
    SUSPENDED("Заморожен (Suspend)"),
}

@Serializable
enum class PackageEnabledSetting(val command: String) {
    DEFAULT("default-state"),
    ENABLED("enable"),
    DISABLED("disable"),
    DISABLED_USER("disable-user"),
    DISABLED_UNTIL_USED("disable-until-used"),
}

/** Enabled-setting and suspension are independent Android properties. */
@Serializable
data class PackageStateSnapshot(
    val enabledSetting: PackageEnabledSetting,
    val suspended: Boolean,
    val effectivelyEnabled: Boolean,
    val uid: Int,
    val lastUpdateTime: Long,
) {
    val isConsistent: Boolean
        get() = uid >= 0 && lastUpdateTime >= 0 && when (enabledSetting) {
            PackageEnabledSetting.DEFAULT -> true
            PackageEnabledSetting.ENABLED -> effectivelyEnabled
            else -> !effectivelyEnabled
        }

    val coarseState: PackageStateTarget
        get() = when {
            !effectivelyEnabled -> PackageStateTarget.DISABLED_USER
            suspended -> PackageStateTarget.SUSPENDED
            else -> PackageStateTarget.ENABLED
        }

    fun forTarget(target: PackageStateTarget): PackageStateSnapshot = when (target) {
        PackageStateTarget.ENABLED -> if (coarseState == PackageStateTarget.ENABLED) this else copy(
            enabledSetting = if (effectivelyEnabled) enabledSetting else PackageEnabledSetting.ENABLED,
            suspended = false, effectivelyEnabled = true,
        )
        PackageStateTarget.DISABLED_USER -> copy(enabledSetting = PackageEnabledSetting.DISABLED_USER, effectivelyEnabled = false)
        // Suspending an already-disabled package must NOT first enable it.
        PackageStateTarget.SUSPENDED -> copy(suspended = true)
    }
}

@Serializable
sealed interface VaultOperation {
    val summaryRu: String

    @Serializable
    @SerialName("setting_change")
    data class SettingChange(
        val namespace: SettingNamespace,
        val key: String,
        val previousValue: String?,
        val newValue: String?,
        override val summaryRu: String = "${namespace.cliName}:$key (${previousValue ?: "default"} → ${newValue ?: "удалено"})",
    ) : VaultOperation

    @Serializable
    @SerialName("package_state")
    data class PackageStateChange(
        val packageName: String,
        val previousState: PackageStateTarget,
        val newState: PackageStateTarget,
        val previousDetails: PackageStateSnapshot? = null,
        val newDetails: PackageStateSnapshot? = null,
        override val summaryRu: String = "$packageName (${previousState.labelRu} → ${newState.labelRu})",
    ) : VaultOperation

    @Serializable
    @SerialName("app_op")
    data class AppOpChange(
        val packageName: String,
        val opName: String,
        val previousMode: String,
        val newMode: String,
        override val summaryRu: String = "AppOps $packageName [$opName: $previousMode → $newMode]",
    ) : VaultOperation

    @Serializable
    @SerialName("shell_revert")
    data class CustomShellChange(
        val applyCommand: String,
        val revertCommand: String,
        override val summaryRu: String,
    ) : VaultOperation
}

@Serializable
enum class SnapshotStatus(val labelRu: String) {
    PREPARED("Подготовлено"),
    APPLYING("Применение не завершено"),
    ACTIVE("Изменения проверены"),
    ROLLING_BACK("Откат не завершён"),
    ROLLED_BACK("Изменения отменены и проверены"),
    FAILED("Нужно повторить восстановление"),
    RECOVERY_REQUIRED("Результат команды неизвестен"),
    CHECKPOINT("Справочный снимок"),
    LEGACY_UNVERIFIED("Старая запись: откат не подтверждён"),
    CORRUPTED("Повреждена запись журнала"),
    ;

    val canAttemptRollback: Boolean
        get() = this in setOf(PREPARED, APPLYING, ACTIVE, ROLLING_BACK, FAILED, RECOVERY_REQUIRED)

    val needsRecovery: Boolean
        get() = this in setOf(PREPARED, APPLYING, ROLLING_BACK, FAILED, RECOVERY_REQUIRED)
}

@Serializable
enum class VaultStepStatus {
    PLANNED, STARTED, APPLIED, APPLY_FAILED, REVERTING, REVERTED, REVERT_FAILED, OUTCOME_UNKNOWN,
}

@Serializable
data class VaultStepJournal(
    val index: Int,
    val status: VaultStepStatus = VaultStepStatus.PLANNED,
    val error: String? = null,
)

@Serializable
enum class FeatureSource(val titleRu: String) {
    FIXER("Фиксер проблем"),
    OPTIMIZER("Оптимизатор"),
    CLEANER("Очистка"),
    SECURITY("Защита"),
    ADBLOCK("AdBlock"),
    MANUAL("Точка восстановления"),
}

@Serializable
data class VaultSnapshot(
    val id: Long = 0L,
    val title: String,
    val reason: String,
    val source: FeatureSource,
    val createdAtEpochMs: Long,
    val rolledBackAtEpochMs: Long? = null,
    val privilegeTierUsed: PrivilegeTier,
    val status: SnapshotStatus,
    val operations: List<VaultOperation>,
    val journalVersion: Int = 0,
    val userId: Int? = null,
    val bootId: String? = null,
    val steps: List<VaultStepJournal> = emptyList(),
    val lastError: String? = null,
    /** Original metadata/payload for legacy or unreadable records; retained for export. */
    val unreadablePayload: String? = null,
)

@Serializable
data class VaultExportBundle(
    val formatVersion: Int = 2,
    val exportedAtEpochMs: Long,
    val deviceModel: String,
    val snapshots: List<VaultSnapshot>,
)
