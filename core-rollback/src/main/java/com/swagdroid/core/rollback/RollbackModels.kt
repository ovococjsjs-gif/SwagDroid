package com.swagdroid.core.rollback

import com.swagdroid.api.SettingNamespace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Степень обратимости системного действия (честная классификация для пользователя).
 */
@Serializable
enum class ReversibilityType(val displayNameRu: String) {
    EXACT_REVERSIBLE("Точно обратимо (Возврат исходного значения 1-в-1)"),
    PARTIALLY_REVERSIBLE("Частично обратимо (Например, перекомпиляция профилей ART)"),
    IRREVERSIBLE_MAINTENANCE("Необратимая операция обслуживания (Очистка кэша, fstrim)"),
}

/**
 * Состояние записи в Write-Ahead журнале (`WalEntryState`).
 * Позволяет обнаружить прерванные операции, если процесс погиб или телефон перезагрузился между шагами.
 */
@Serializable
enum class WalEntryState {
    PREPARED,
    APPLYING,
    APPLIED,
    FAILED,
    VERIFIED,
    VERIFY_FAILED,
    ROLLED_BACK,
}

/**
 * Результат проверки конфликтов перед откатом:
 * не перезаписываем настройку вслепую, если пользователь или система уже поменяли её после нас!
 */
@Serializable
sealed class RollbackConflictCheck {
    @Serializable
    data object SafeToRollback : RollbackConflictCheck()

    @Serializable
    data class ConflictDetected(
        val opDescription: String,
        val expectedValueAfterOurFix: String?,
        val actualCurrentSystemValue: String?,
        val originalValueBeforeOurFix: String?,
    ) : RollbackConflictCheck()
}

/**
 * Единичная типизированная операция в Write-Ahead журнале с сохранённым состоянием ДО (`previous`)
 * и ожидаемым состоянием ПОСЛЕ (`expectedAfter`).
 */
@Serializable
sealed class ReversibleSystemOp {

    /**
     * Снимок состояния системной настройки (`Settings.Global / Secure / System`).
     */
    @Serializable
    @SerialName("system_setting")
    data class SystemSettingChange(
        val namespace: SettingNamespace,
        val key: String,
        val previousValue: String?, // null означает, что ключа не было до применения
        val newValue: String?,
    ) : ReversibleSystemOp()

    /**
     * Снимок состояния пакета (`enabledState` / `uninstalledForUser`).
     */
    @Serializable
    @SerialName("package_state")
    data class PackageStateChange(
        val packageName: String,
        val userId: Int,
        val previousEnabledState: Int,
        val wasInstalledForUser: Boolean,
        val targetAction: PackageMutationType,
    ) : ReversibleSystemOp()

    /**
     * Снимок режима AppOps до и после изменения.
     */
    @Serializable
    @SerialName("app_op")
    data class AppOpChange(
        val packageName: String,
        val uid: Int,
        val opCode: Int,
        val previousMode: Int,
        val newMode: Int,
    ) : ReversibleSystemOp()

    /**
     * Снимок присутствия пакета в белом списке Doze (`deviceidle whitelist`).
     */
    @Serializable
    @SerialName("device_idle_whitelist")
    data class DeviceIdleWhitelistChange(
        val packageName: String,
        val wasWhitelistedBefore: Boolean,
        val whitelistedAfter: Boolean,
    ) : ReversibleSystemOp()
}

@Serializable
enum class PackageMutationType {
    ENABLE,
    DISABLE_USER,
    SUSPEND,
    UNINSTALL_FOR_USER,
}

/**
 * Запись Write-Ahead журнала (`RollbackTransaction`).
 */
@Serializable
data class RollbackTransaction(
    val transactionId: String,
    val featureId: String,
    val titleRu: String,
    val descriptionRu: String,
    val createdAtEpochMs: Long,
    val operations: List<ReversibleSystemOp>,
    val reversibility: ReversibilityType = ReversibilityType.EXACT_REVERSIBLE,
    val walState: WalEntryState = WalEntryState.APPLIED,
    val isRolledBack: Boolean = false,
    val rolledBackAtEpochMs: Long? = null,
)
