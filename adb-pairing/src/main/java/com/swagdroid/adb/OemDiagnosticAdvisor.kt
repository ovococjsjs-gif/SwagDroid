package com.swagdroid.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable

/**
 * Профиль производителя (OEM) и специфичные ограничения его прошивки,
 * мешающие работе Беспроводной отладки и `app_process`.
 */
@Serializable
enum class OemVendorProfile(val displayName: String) {
    XIAOMI_HYPEROS_MIUI("Xiaomi / Redmi / POCO (MIUI / HyperOS)"),
    SAMSUNG_ONEUI("Samsung (One UI)"),
    OPPO_REALME_ONEPLUS("OPPO / Realme / OnePlus (ColorOS / OxygenOS / RealmeUI)"),
    VIVO_ORIGIN_FUNTOUCH("vivo / iQOO (FuntouchOS / OriginOS)"),
    HUAWEI_HONOR("Huawei / Honor (EMUI / MagicOS)"),
    AOSP_PIXEL_OTHER("Google Pixel / Чистый Android (AOSP)"),
}

@Serializable
data class DiagnosticStepStatus(
    val stepId: String,
    val titleRu: String,
    val descriptionRu: String,
    val isPassed: Boolean,
    val isBlocking: Boolean,
    val actionSettingsIntent: String? = null,
)

@Serializable
data class OemReadinessReport(
    val vendorProfile: OemVendorProfile,
    val sdkInt: Int,
    val supportsOnDeviceWirelessAdb: Boolean,
    val writeSecureSettingsGranted: Boolean,
    val checklist: List<DiagnosticStepStatus>,
) {
    val isReadyForPairingOrConnect: Boolean
        get() = supportsOnDeviceWirelessAdb && checklist.filter { it.isBlocking }.all { it.isPassed }
}

/**
 * Умный диагностический советник (`OemDiagnosticAdvisor`), который до начала сопряжения
 * проверяет готовность телефона и подсказывает скрытые тумблеры конкретной прошивки
 * (Xiaomi Security Settings, Samsung Auto Blocker, Oppo Permission Monitoring).
 */
class OemDiagnosticAdvisor(
    private val context: Context,
) {
    fun evaluateReadiness(): OemReadinessReport {
        val vendor = detectOemVendor()
        val sdkInt = Build.VERSION.SDK_INT
        val supportsWirelessAdb = sdkInt >= Build.VERSION_CODES.R // Android 11+ (API 30+)
        val hasWriteSecure = context.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        val steps = mutableListOf<DiagnosticStepStatus>()

        // 1. Проверка подключения к Wi-Fi
        val wifiConnected = isWifiConnected()
        steps += DiagnosticStepStatus(
            stepId = "wifi_connected",
            titleRu = if (wifiConnected) "Wi-Fi подключён" else "Подключитесь к любой сети Wi-Fi",
            descriptionRu = "Беспроводная отладка Android работает через локальный сетевой интерфейс Wi-Fi (или точку доступа второго устройства).",
            isPassed = wifiConnected,
            isBlocking = true,
            actionSettingsIntent = Settings.ACTION_WIFI_SETTINGS,
        )

        // 2. Проверка «Для разработчиков»
        val devOptionsEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)
        steps += DiagnosticStepStatus(
            stepId = "developer_options",
            titleRu = if (devOptionsEnabled) "Режим разработчика активен" else "Включите «Для разработчиков»",
            descriptionRu = "Откройте Настройки → О телефоне и нажмите 7 раз на «Номер сборки» (или «Версия OS»).",
            isPassed = devOptionsEnabled,
            isBlocking = true,
            actionSettingsIntent = Settings.ACTION_DEVICE_INFO_SETTINGS,
        )

        // 3. Проверка тумблера «Отладка по Wi-Fi» (adb_wifi_enabled)
        val wirelessAdbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        steps += DiagnosticStepStatus(
            stepId = "wireless_adb_enabled",
            titleRu = if (wirelessAdbEnabled) {
                "Отладка по Wi-Fi включена"
            } else {
                "Включите «Отладку по Wi-Fi» в настройках разработчика"
            },
            descriptionRu = if (hasWriteSecure) {
                "У SwagDroid уже есть право WRITE_SECURE_SETTINGS — приложение может включить Отладку по Wi-Fi автоматически!"
            } else {
                "Перейдите в «Для разработчиков» → включите «Отладка по Wi-Fi» и нажмите «Подключение устройства с помощью кода сопряжения»."
            },
            isPassed = wirelessAdbEnabled || hasWriteSecure,
            isBlocking = true,
            actionSettingsIntent = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )

        // 4. Специфичные проверки по производителям (OEM Quirks)
        when (vendor) {
            OemVendorProfile.XIAOMI_HYPEROS_MIUI -> {
                steps += DiagnosticStepStatus(
                    stepId = "xiaomi_usb_debug_security",
                    titleRu = "Xiaomi/HyperOS: Включите «Отладка по USB (Настройки безопасности)»",
                    descriptionRu = "КРИТИЧНО для Xiaomi/POCO/Redmi: В разделе «Для разработчиков» обязательно включите второй тумблер «Отладка по USB (Настройки безопасности)», иначе MIUI блокирует изменение разрешений и заморозку пакетов!",
                    isPassed = true, // Предупреждение-памятка
                    isBlocking = false,
                    actionSettingsIntent = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                )
            }

            OemVendorProfile.SAMSUNG_ONEUI -> {
                steps += DiagnosticStepStatus(
                    stepId = "samsung_auto_blocker",
                    titleRu = "Samsung One UI: Проверьте «Автоблокировщик» (Auto Blocker)",
                    descriptionRu = "На One UI 6.0+ откройте Настройки → Безопасность и конфиденциальность → Автоблокировщик и убедитесь, что он выключен, иначе Samsung блокирует локальный ADB.",
                    isPassed = true,
                    isBlocking = false,
                    actionSettingsIntent = Settings.ACTION_SECURITY_SETTINGS,
                )
            }

            OemVendorProfile.OPPO_REALME_ONEPLUS -> {
                steps += DiagnosticStepStatus(
                    stepId = "coloros_permission_monitoring",
                    titleRu = "ColorOS / RealmeUI / OxygenOS: Отключите мониторинг разрешений",
                    descriptionRu = "Пролистайте «Для разработчиков» в самый низ и включите пункт «Отключить мониторинг разрешений» — без него прошивка сбрасывает команды ADB.",
                    isPassed = true,
                    isBlocking = false,
                    actionSettingsIntent = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                )
            }

            else -> Unit
        }

        return OemReadinessReport(
            vendorProfile = vendor,
            sdkInt = sdkInt,
            supportsOnDeviceWirelessAdb = supportsWirelessAdb,
            writeSecureSettingsGranted = hasWriteSecure,
            checklist = steps,
        )
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        fun detectOemVendor(
            manufacturer: String = Build.MANUFACTURER,
            brand: String = Build.BRAND,
        ): OemVendorProfile {
            val combined = "${manufacturer.lowercase()} ${brand.lowercase()}"
            return when {
                "xiaomi" in combined || "redmi" in combined || "poco" in combined ->
                    OemVendorProfile.XIAOMI_HYPEROS_MIUI
                "samsung" in combined ->
                    OemVendorProfile.SAMSUNG_ONEUI
                "oppo" in combined || "realme" in combined || "oneplus" in combined ->
                    OemVendorProfile.OPPO_REALME_ONEPLUS
                "vivo" in combined || "iqoo" in combined ->
                    OemVendorProfile.VIVO_ORIGIN_FUNTOUCH
                "huawei" in combined || "honor" in combined ->
                    OemVendorProfile.HUAWEI_HONOR
                else ->
                    OemVendorProfile.AOSP_PIXEL_OTHER
            }
        }
    }
}
