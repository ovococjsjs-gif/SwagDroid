package com.swagdroid.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PrivilegeTier(
    val level: Int,
    val titleRu: String,
    val shortBadge: String,
) {
    BASIC(
        level = 0,
        titleRu = "Базовый режим (Без Root)",
        shortBadge = "Базовый",
    ),
    ADB_SHIZUKU(
        level = 1,
        titleRu = "Суперправа ADB / Shizuku (UID 2000)",
        shortBadge = "ADB · UID 2000",
    ),
    ROOT(
        level = 2,
        titleRu = "Полный Root-доступ (UID 0)",
        shortBadge = "Root · UID 0",
    );

    fun satisfies(required: PrivilegeTier): Boolean = this.level >= required.level
}

enum class ShizukuStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

enum class RootProvider(val displayName: String) {
    NONE("Не обнаружен"),
    MAGISK("Magisk"),
    KERNEL_SU("KernelSU / KSU-Next"),
    APATCH("APatch"),
    GENERIC_SU("Superuser (su)"),
}

data class PrivilegeState(
    val activeTier: PrivilegeTier = PrivilegeTier.BASIC,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val shizukuUid: Int? = null,
    val isSuiActive: Boolean = false,
    val rootProvider: RootProvider = RootProvider.NONE,
    val rootVersion: String? = null,
    val isWirelessAdbSupported: Boolean = true,
    val isDeveloperOptionsEnabled: Boolean = false,
    val isChecking: Boolean = false,
    val lastCheckEpochMs: Long = 0L,
)

enum class CommandCompletion {
    /** The command process exited normally; the exit code still needs checking. */
    EXITED,
    /** No user command was dispatched. */
    NOT_STARTED,
    /** Timeout, cancellation or lost transport: side effects / child processes may remain. */
    UNKNOWN,
}

data class ShellExecResult(
    val command: String,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val executedAtTier: PrivilegeTier,
    val completion: CommandCompletion = CommandCompletion.EXITED,
    val outputTruncated: Boolean = false,
) {
    val isSuccess: Boolean get() = completion == CommandCompletion.EXITED && exitCode == 0
    val stdoutText: String get() = stdout.joinToString("\n").trim()
    val stderrText: String get() = stderr.joinToString("\n").trim()
}

@Serializable
enum class RiskLevel(val labelRu: String) {
    SAFE("Безопасно"),
    ADVANCED("Продвинуто"),
    EXPERT("Эксперт"),
}
