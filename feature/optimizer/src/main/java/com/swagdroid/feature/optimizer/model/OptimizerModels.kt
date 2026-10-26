package com.swagdroid.feature.optimizer.model

import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.RiskLevel
import kotlinx.serialization.Serializable

enum class OptimizerSubTab(val titleRu: String) {
    PRESETS("Пресеты"),
    TWEAKS("Твики PRO"),
    UI_TUNER("Тюнер UI"),
    BOOSTER("Ускоритель"),
    DEBLOATER("Debloater"),
}

@Serializable
data class UadCatalogEntry(
    val id: String,
    val list: String,
    val removal: String,
    val desc: String,
    val ru: Boolean = false,
) {
    val riskLevel: RiskLevel
        get() = when (removal) {
            "Recommended" -> RiskLevel.SAFE
            "Advanced" -> RiskLevel.ADVANCED
            else -> RiskLevel.EXPERT
        }

    val removalLabelRu: String
        get() = when (removal) {
            "Recommended" -> "Рекомендуется"
            "Advanced" -> "Продвинуто"
            else -> "Эксперт"
        }

    val listLabelRu: String
        get() = when (list) {
            "Oem" -> "Прошивка OEM"
            "Google" -> "Google"
            "Carrier" -> "Оператор"
            "Aosp" -> "AOSP Система"
            else -> "Стороннее / Meta"
        }
}

data class DebloatAppItem(
    val entry: UadCatalogEntry,
    val appLabel: String,
    val isInstalledOnDevice: Boolean,
    val stateTarget: PackageStateTarget,
    val isSelected: Boolean = false,
)

enum class DebloatApplyMode(
    val titleRu: String,
    val subtitleRu: String,
) {
    SUSPEND_SAFE(
        titleRu = "Мягкая заморозка (Suspend)",
        subtitleRu = "Рекомендуется: пакет остаётся в системе, но полностью остановлен и серый",
    ),
    DISABLE_USER(
        titleRu = "Глубокое отключение (Disable)",
        subtitleRu = "pm disable-user --user 0 · Скрывает иконку и службы полностью",
    ),
}

enum class AnimationScaleChoice(val scaleText: String, val labelRu: String) {
    SCALE_100("1.0", "1.0x Стандарт"),
    SCALE_085("0.85", "0.85x Мягко"),
    SCALE_075("0.75", "0.75x Плавно"),
    SCALE_050("0.5", "0.5x Быстро"),
    SCALE_035("0.35", "0.35x Спорт"),
    SCALE_025("0.25", "0.25x Молния"),
}

enum class RefreshRateProfile(
    val titleRu: String,
    val minHz: String?,
    val peakHz: String?,
) {
    ADAPTIVE_AUTO("Адаптивно (LTPO / Авто)", null, null),
    LOCK_90HZ("Плавные 90 Гц", "90.0", "90.0"),
    HIGH_120HZ("Максимум 120 Гц", "60.0", "120.0"),
    BATTERY_60HZ("Экономия 60 Гц", "60.0", "60.0"),
}

enum class PrivateDnsPreset(
    val titleRu: String,
    val subtitleRu: String,
    val modeValue: String,
    val hostnameValue: String?,
) {
    SYSTEM_AUTO(
        titleRu = "Автоматический (системный)",
        subtitleRu = "Совместим с метро и отелями, без фильтрации рекламы",
        modeValue = "opportunistic",
        hostnameValue = null,
    ),
    ADGUARD_ADS(
        titleRu = "AdGuard DNS (Анти-реклама)",
        subtitleRu = "dns.adguard-dns.com · Режет рекламу и трекеры без слота VPN",
        modeValue = "hostname",
        hostnameValue = "dns.adguard-dns.com",
    ),
    ADGUARD_FAMILY(
        titleRu = "AdGuard Семейный (18+ и анти-реклама)",
        subtitleRu = "family.adguard-dns.com · Защита детей + фильтрация баннеров",
        modeValue = "hostname",
        hostnameValue = "family.adguard-dns.com",
    ),
    CONTROLD_ADS(
        titleRu = "ControlD Free (Анти-реклама и малварь)",
        subtitleRu = "p2.freedns.controld.com · Фильтр рекламы и фишинга по DoT",
        modeValue = "hostname",
        hostnameValue = "p2.freedns.controld.com",
    ),
    CLOUDFLARE_FAST(
        titleRu = "Cloudflare 1.1.1.1 (Минимальный пинг)",
        subtitleRu = "one.one.one.one · Скоростной DNS для онлайн-игр",
        modeValue = "hostname",
        hostnameValue = "one.one.one.one",
    ),
    QUAD9_SECURITY(
        titleRu = "Quad9 Security (Анти-фишинг)",
        subtitleRu = "dns.quad9.net · Блокировка вредоносных и ботнет-доменов",
        modeValue = "hostname",
        hostnameValue = "dns.quad9.net",
    ),
}

enum class StatusBarIconSlot(val token: String, val labelRu: String) {
    NFC("nfc", "Значок NFC"),
    ALARM("alarm_clock", "Будильник"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    VOLTE("volte", "Индикатор VoLTE / HD"),
    VOLUME("volume", "Режим без звука / Вибро"),
    ROTATE("rotate", "Автоповорот"),
    HEADSET("headset", "Иконка наушников"),
    DATA_SAVER("data_saver", "Экономия трафика"),
}

enum class ImmersiveModeOption(
    val titleRu: String,
    val subtitleRu: String,
    val policyValue: String?,
) {
    STANDARD("Стандартный экран", "Обе системные панели отображаются как обычно", null),
    HIDE_NAVIGATION("Скрыть нижнюю полосу жестов", "Защита нижней кромки OLED от выгорания полоски", "immersive.navigation=*"),
    HIDE_STATUS("Скрыть верхний статус-бар", "Чистый верх экрана, шторка доступна свайпом сверху", "immersive.status=*"),
    FULL_IMMERSIVE("Полное погружение (Кино / Чтение)", "Максимум полезной площади дисплея без рамок", "immersive.full=*"),
}

enum class ArtCompileMode(
    val cliMode: String,
    val titleRu: String,
    val subtitleRu: String,
) {
    SPEED_PROFILE(
        cliMode = "speed-profile",
        titleRu = "Профильный баланс (speed-profile)",
        subtitleRu = "Компилирует горячие участки кода без раздувания памяти — лучший выбор на каждый день",
    ),
    EVERYTHING(
        cliMode = "everything",
        titleRu = "Полный форсаж (everything)",
        subtitleRu = "Максимальная AOT-компиляция всех методов (идеально для избранных тяжёлых игр и банков)",
    ),
    SPACE_PROFILE(
        cliMode = "space-profile",
        titleRu = "Экономия накопителя (space-profile)",
        subtitleRu = "Ужимает скомпилированные .odex/.vdex файлы на смартфонах с малым объёмом памяти",
    ),
}

enum class AndroidGameModeChoice(val cliArg: String, val labelRu: String) {
    STANDARD("1", "Стандарт"),
    PERFORMANCE("2", "Производительность"),
    BATTERY("3", "Экономия батареи"),
}

data class BoosterUserApp(
    val packageName: String,
    val label: String,
)

data class LiveSystemTweaksState(
    val windowAnimScale: String = "1.0",
    val transitionAnimScale: String = "1.0",
    val animatorScale: String = "1.0",
    val peakRefreshRate: String? = null,
    val minRefreshRate: String? = null,
    val disableWindowBlurs: Boolean = false,
    val privateDnsMode: String = "opportunistic",
    val privateDnsHost: String? = null,
    val wifiScanThrottleEnabled: Boolean = true,
    val mobileDataAlwaysOn: Boolean = true,
    val tetherHardwareOffloadEnabled: Boolean = true,
    val cachedAppsFreezerEnabled: Boolean = false,
    val phantomKillerDisabled: Boolean = false,
    val hiddenRadioScanDisabled: Boolean = false,
    val errorTelemetryDisabled: Boolean = false,
    val hidePasswordChars: Boolean = false,
    val instantPowerButtonLock: Boolean = false,
    val clipboardProtectedAppsCount: Int = 0,
    val swappinessKernel: String? = null,
    // UI Tuner live state
    val batteryPercentInStatusBar: Boolean = false,
    val clockSecondsInStatusBar: Boolean = false,
    val hiddenStatusBarTokens: Set<String> = emptySet(),
    val immersivePolicy: String? = null,
    val chargingSoundsEnabled: Boolean = true,
    val lockscreenSoundsEnabled: Boolean = true,
    val touchSoundsEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val safeAudioWarningDisabled: Boolean = false,
)

data class OptimizerPresetTemplate(
    val id: String,
    val titleRu: String,
    val subtitleRu: String,
    val bulletPoints: List<String>,
    val risk: RiskLevel,
    val activeSnapshotId: Long? = null,
)

/**
 * Portable JSON profile (inspired by Windows Optimizer templates) so users can share or
 * transfer their favorite tweak & debloat setup across smartphones.
 */
@Serializable
data class OptimizerPortableProfile(
    val schemaVersion: Int = 2,
    val profileName: String,
    val exportedAtEpochMs: Long,
    val animationScale: String = "0.75",
    val privateDnsMode: String = "opportunistic",
    val privateDnsHost: String? = null,
    val cachedAppsFreezerEnabled: Boolean = true,
    val phantomKillerDisabled: Boolean = true,
    val hiddenRadioScanDisabled: Boolean = true,
    val errorTelemetryDisabled: Boolean = true,
    val hidePasswordChars: Boolean = true,
    val disableWindowBlurs: Boolean = false,
    val hiddenStatusBarIcons: List<String> = emptyList(),
    val suspendedPackages: List<String> = emptyList(),
)
