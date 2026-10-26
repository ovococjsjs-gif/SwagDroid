package com.swagdroid.feature.fixer

import com.swagdroid.adb.OemVendorProfile
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.core.rollback.ReversibilityType
import kotlinx.serialization.Serializable

/**
 * 5 архитектурных слоёв Android, к которым привязывается локализация любого симптома.
 */
@Serializable
enum class AndroidSystemLayer(val titleRu: String) {
    HARDWARE_KERNEL_HAL_MODEM("1. Железо, ядро, HAL и радиомодуль (Thermal, SIM, Батарея)"),
    SYSTEM_SERVICES("2. Системные сервисы (PackageManager, ActivityManager, DeviceIdle, Connectivity, IMS)"),
    POLICIES_AND_STATE_DB("3. Политики и базы состояния (Settings, AppOps, Doze Whitelist, APN)"),
    OEM_FRAMEWORK("4. Надстройки производителя (Xiaomi Autostart, Samsung Sleeping Apps, ColorOS)"),
    APP_AND_DATA("5. Конкретное приложение и данные (Кэш, профили ART, WebView, Синхронизация)"),
}

/**
 * Строго типизированные идентификаторы читателей состояния (`TypedDiagnosticReaderId`).
 * В JSON-рецептах запрещены произвольные shell-команды — рецепт ссылается только на эти ID!
 */
@Serializable
enum class TypedDiagnosticReaderId {
    READ_NOTIFICATION_DELIVERY_CHAIN,
    READ_SCREEN_OFF_BATTERY_DELTA_AND_WAKELOCKS,
    READ_WIFI_CAPTIVE_PORTAL_AND_DNS_PROBES,
    READ_SIM_SLOTS_AND_RADIO_STATE,
    READ_MOBILE_DATA_SUBSCRIPTION_AND_APN,
    READ_IMS_VOLTE_VOWIFI_STATE,
    READ_BLUETOOTH_ABSOLUTE_VOLUME_STATE,
    READ_POST_OTA_ART_PACKAGE_COMPILE_STATES,
    READ_MULTIUSER_PACKAGE_INSTALL_CONFLICTS,
    READ_AUTO_TIME_AND_TLS_CLOCK_SKEW,
    READ_SENSOR_PRIVACY_AND_LOCATION_SCAN_STATE,
    READ_WORK_PROFILE_QUIET_MODE_STATE,
}

/**
 * Строго типизированные идентификаторы ремонтных действий (`TypedFixActionId`).
 */
@Serializable
enum class TypedFixActionId {
    ACTION_WHITELIST_DOZE_AND_OPEN_UNRESTRICTED_BATTERY,
    ACTION_FORCE_STOP_OR_RESTRICT_WAKELOCK_CULPRIT,
    ACTION_SWITCH_CAPTIVE_PORTAL_ENDPOINTS_OR_RECONNECT,
    ACTION_ESCALATE_TO_HARDWARE_OR_CARRIER_NO_FAKE_FIX,
    ACTION_RESTORE_DEFAULT_DATA_APN_WITH_SNAPSHOT,
    ACTION_OPEN_IMS_PROVISIONING_SETTINGS,
    ACTION_TOGGLE_BLUETOOTH_DISABLE_ABSOLUTE_VOLUME,
    ACTION_RUN_CONDITIONAL_SPEED_PROFILE_DEXOPT,
    ACTION_RESOLVE_MULTIUSER_INSTALL_CONFLICT,
    ACTION_ENABLE_AUTO_TIME_AND_TIMEZONE,
    ACTION_UNBLOCK_CAMERA_MIC_LOCATION_TOGGLES,
    ACTION_UNPAUSE_WORK_PROFILE_USER,
}

/**
 * Декларативный паспорт рецепта Программного Фиксера (`FixPlaybookRecipe`).
 *
 * Безопасность:
 * Не содержит ни одной сырой shell-строки! Ссылается только на `TypedDiagnosticReaderId` и `TypedFixActionId`.
 */
@Serializable
data class FixPlaybookRecipe(
    val recipeId: String,
    val symptomTitleRu: String,
    val symptomDescriptionRu: String,
    val primaryLayer: AndroidSystemLayer,
    val readerId: TypedDiagnosticReaderId,
    val actionId: TypedFixActionId,
    val minPrivilege: PrivilegeTier,
    val applicableVendors: Set<OemVendorProfile> = emptySet(), // Пустой = все вендоры
    val minSdk: Int = 29,
    val maxSdk: Int = 36,
    val reversibility: ReversibilityType,
    val verificationWindowMinutes: Int, // 0 = мгновенная проверка, 20 = отложенная проверка
    val sideEffectsWarningRu: String,
    val hardwareOrCarrierEscalationNoteRu: String? = null,
)
