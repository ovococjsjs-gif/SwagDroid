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
    ACTIVE("Активна (можно откатить)"),
    ROLLED_BACK("Откачена"),
    FAILED("Ошибка при откате"),
}

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
)

@Serializable
data class VaultExportBundle(
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val deviceModel: String,
    val snapshots: List<VaultSnapshot>,
)
