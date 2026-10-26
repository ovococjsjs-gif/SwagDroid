package com.swagdroid.feature.optimizer

import com.swagdroid.adb.OemVendorProfile
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.api.SettingNamespace
import com.swagdroid.core.rollback.ReversibilityType
import kotlinx.serialization.Serializable

/**
 * Честная рубрикация тумблеров Оптимизатора (в духе `hellzerg/optimizer`, адаптированная под реальность Android).
 */
@Serializable
enum class OptimizerCategory(val titleRu: String) {
    DISPLAY_AND_PERCEIVED_SPEED("Экран и воспринимаемая плавность UI"),
    PRIVACY_AND_LOCATION_SCANNING("Приватность и фоновое сканирование геолокации"),
    TERMUX_AND_DEVELOPERS("Для Termux, контейнеров и разработчиков"),
    OEM_VENDOR_SPECIFIC("Специфичные настройки вашей прошивки (OEM)"),
}

/**
 * Паспорт системного тумблера (`TogglePassport`).
 *
 * Каждый тумблер описывается не сырой shell-строкой, а строгим контрактом:
 * - как прочитать текущее состояние (`settingKeys`),
 * - на каких версиях Android и вендорах он реально поддерживается,
 * - какие у него побочные эффекты (`sideEffectsWarningRu`),
 * - как верифицировать применение и откатить через Write-Ahead журнал.
 */
@Serializable
data class TogglePassport(
    val id: String,
    val titleRu: String,
    val subtitleRu: String,
    val category: OptimizerCategory,
    val namespace: SettingNamespace,
    val settingKeys: List<String>,
    val enabledWireValue: String,
    val disabledWireValue: String,
    val minPrivilege: PrivilegeTier,
    val supportedVendors: Set<OemVendorProfile>, // Пустой = все вендоры
    val minSdk: Int = 29,
    val maxSdk: Int = 36,
    val requiresReboot: Boolean = false,
    val reversibility: ReversibilityType = ReversibilityType.EXACT_REVERSIBLE,
    val sideEffectsWarningRu: String,
) {
    fun isSupportedOnDevice(vendor: OemVendorProfile, sdkInt: Int): Boolean {
        val vendorOk = supportedVendors.isEmpty() || vendor in supportedVendors
        return vendorOk && sdkInt in minSdk..maxSdk
    }
}

/**
 * Пятиуровневый светофор безопасности пакетов Деблоатера (расширенная шкала UAD-ng).
 */
@Serializable
enum class DebloatSafetyBadge(
    val emojiAndTitleRu: String,
    val descriptionRu: String,
    val allowActionInUi: Boolean,
) {
    UNKNOWN(
        emojiAndTitleRu = "⚪ Unknown (Неизвестный пакет)",
        descriptionRu = "Нет проверенных данных в базе UAD-ng. Действуйте только если точно знаете назначение пакета.",
        allowActionInUi = true,
    ),
    RECOMMENDED(
        emojiAndTitleRu = "🟢 Recommended (Рекомендуется)",
        descriptionRu = "Рекламные или неиспользуемые сервисы; обычно безопасно отключить, но ознакомьтесь с описанием.",
        allowActionInUi = true,
    ),
    ADVANCED(
        emojiAndTitleRu = "🟡 Advanced (Продвинутый)",
        descriptionRu = "Отключение приведёт к потере конкретной функции (например, синхронизации облака или печати).",
        allowActionInUi = true,
    ),
    EXPERT(
        emojiAndTitleRu = "🟠 Expert (Высокий риск)",
        descriptionRu = "Системный компонент с глубокими зависимостями. Отключение может нарушить работу оболочки.",
        allowActionInUi = true,
    ),
    UNSAFE_PROTECTED(
        emojiAndTitleRu = "🔴 Unsafe / Protected (Заблокировано)",
        descriptionRu = "Критически важный системный пакет (SystemUI, лаунчер, телефония, SecurityCenter). Заблокировано защитой от бутлупа!",
        allowActionInUi = false,
    ),
}

/**
 * Способ заморозки/удаления пакета с честным описанием разницы и fallback для HyperOS.
 */
@Serializable
enum class DebloatMechanism(
    val titleRu: String,
    val technicalBehaviorRu: String,
) {
    SUSPEND_PACKAGE(
        titleRu = "Мягкая заморозка (pm suspend)",
        technicalBehaviorRu = "Самый безопасный способ: иконка сереет, фоновая работа останавливается, данные сохраняются. Работает даже там, где HyperOS запрещает disable-user.",
    ),
    DISABLE_USER(
        titleRu = "Отключение компонента (pm disable-user)",
        technicalBehaviorRu = "Скрывает приложение из лаунчера и запрещает запуск. Если прошивка возвращает 'Cannot disable system packages', автоматически предлагается переход на suspend или uninstall --user 0.",
    ),
    UNINSTALL_FOR_USER_0(
        titleRu = "Удаление для текущего пользователя (pm uninstall --user 0)",
        technicalBehaviorRu = "Удаляет пакет и его пользовательские данные из профиля 0 (системный APK остаётся в /system и может быть восстановлен через install-existing).",
    ),
}

@Serializable
data class CuratedDebloatPackageEntry(
    val packageName: String,
    val titleRu: String,
    val vendor: OemVendorProfile?,
    val badge: DebloatSafetyBadge,
    val whatBreaksIfDisabledRu: String,
    val recommendedMechanism: DebloatMechanism,
)
