package com.swagdroid.feature.optimizer.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RiskLevel
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.optimizer.model.AndroidGameModeChoice
import com.swagdroid.feature.optimizer.model.ArtCompileMode
import com.swagdroid.feature.optimizer.model.BoosterUserApp
import com.swagdroid.feature.optimizer.model.ImmersiveModeOption
import com.swagdroid.feature.optimizer.model.LiveSystemTweaksState
import com.swagdroid.feature.optimizer.model.OptimizerPortableProfile
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset
import com.swagdroid.feature.optimizer.model.RefreshRateProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTweaksController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
    private val vaultManager: StateVaultManager,
) {

    fun defaultPresets(): List<OptimizerPresetTemplate> = listOf(
        OptimizerPresetTemplate(
            id = "preset_clean_new_phone",
            titleRu = "1. Чистый старт нового смартфона",
            subtitleRu = "Базовая гигиена сразу из коробки без малейшей потери функций",
            bulletPoints = listOf(
                "Плавные ускоренные переходы интерфейса 0.75x",
                "Выключение скрытого круглосуточного радиоскана Wi-Fi и BLE",
                "Блокировка фоновой отправки дампов ошибок",
                "Скрытие вводимых символов паролей точками (show_password = 0)",
                "Clipboard Guard: защита буфера обмена от сторонних приложений",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_max_battery",
            titleRu = "2. Максимальная автономность в дорогу",
            subtitleRu = "Глубокая экономия заряда в фоне без поломки будильников и мессенджеров",
            bulletPoints = listOf(
                "Ядерная заморозка свёрнутых процессов (Cached Apps Freezer = enabled)",
                "Отключение ресурсоёмкого размытия окон (disable_window_blurs = 1)",
                "Отключение скрытых гео-сканеров Wi-Fi/BLE и щелчков микшера",
                "Быстрые анимации 0.5x и щадящий потолок 60 Гц",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_gaming_snappy",
            titleRu = "3. Киберспорт: Отзывчивость и Игры",
            subtitleRu = "Минимальный аппаратный input-lag, стабильный пинг и высокий FPS",
            bulletPoints = listOf(
                "Спортивные анимации 0.35x и пиковый порог экрана 120 Гц",
                "Снятие убийцы фоновых процессов (Phantom Process Killer Android 12–16)",
                "Отключение перехватчика тройного тапа лупы (~150 мс экономии отклика!)",
                "Таймер долгого нажатия 300 мс и защита от пинг-спайков Wi-Fi",
            ),
            risk = RiskLevel.ADVANCED,
        ),
        OptimizerPresetTemplate(
            id = "preset_privacy_shield",
            titleRu = "4. Щит приватности + Анти-реклама DNS",
            subtitleRu = "Защита от трекеров, перехвата паролей и рекламы без занятия слота VPN",
            bulletPoints = listOf(
                "Зашифрованный системный AdGuard Private DNS (DoT порт 853)",
                "Clipboard Guard для пользовательских APK (клавиатура не затрагивается)",
                "Скрытие вводимых букв паролей на экране и мгновенный блок кнопкой питания",
                "Отключение фоновых гео-сканеров и отчётов телеметрии",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_zen_clean_ui",
            titleRu = "5. Чистый Дзен-интерфейс",
            subtitleRu = "Минималистичная строка состояния без визуального шума и лишних звуков",
            bulletPoints = listOf(
                "Скрытие лишних значков в статус-баре (NFC, VoLTE, Bluetooth, Будильник)",
                "Включение точного процента заряда батареи",
                "Выключение звуков блокировки, зарядки и системных щелчков",
                "Мягкие быстрые анимации 0.85x",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_power_multitask",
            titleRu = "6. Режим Разработчика и Многозадачности",
            subtitleRu = "Для Termux, локальных серверов, SSH, DeX и работы с десятками вкладок",
            bulletPoints = listOf(
                "Полное снятие лимита max_phantom_processes (2147483647)",
                "Бесшовное удержание мобильного канала параллельно с Wi-Fi",
                "Аппаратный оффлоад раздачи интернета (tether_offload_disabled = 0)",
                "Секунды в часах статус-бара для точного тайминга",
            ),
            risk = RiskLevel.ADVANCED,
        ),
        OptimizerPresetTemplate(
            id = "preset_low_ram_revival",
            titleRu = "7. Реанимация бюджетного смартфона (2–4 ГБ ОЗУ)",
            subtitleRu = "Максимальная разгрузка GPU-композитора и памяти на слабом железе",
            bulletPoints = listOf(
                "Отключение тяжёлого размытия фона шторок и окон (disable_window_blurs = 1)",
                "Ультра-короткие безопасные анимации 0.25x",
                "Принудительная заморозка свёрнутых процессов в ОЗУ",
                "Оптимизация кольцевого буфера логов до минимальных 64K",
            ),
            risk = RiskLevel.SAFE,
        ),
    )

    suspend fun readLiveTweaksState(): LiveSystemTweaksState = withContext(Dispatchers.IO) {
        val winAnim = executor.readSetting(SettingNamespace.GLOBAL, "window_animation_scale") ?: "1.0"
        val transAnim = executor.readSetting(SettingNamespace.GLOBAL, "transition_animation_scale") ?: "1.0"
        val anim = executor.readSetting(SettingNamespace.GLOBAL, "animator_duration_scale") ?: "1.0"
        val peakHz = executor.readSetting(SettingNamespace.SYSTEM, "peak_refresh_rate")
        val minHz = executor.readSetting(SettingNamespace.SYSTEM, "min_refresh_rate")
        val noBlur = executor.readSetting(SettingNamespace.GLOBAL, "disable_window_blurs") == "1"

        val dnsMode = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_mode") ?: "opportunistic"
        val dnsHost = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_specifier")
        val scanThrottle = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled") != "0"
        val dataAlways = executor.readSetting(SettingNamespace.GLOBAL, "mobile_data_always_on") != "0"
        val tetherHw = executor.readSetting(SettingNamespace.GLOBAL, "tether_offload_disabled") != "1"

        val freezer = executor.readSetting(SettingNamespace.GLOBAL, "cached_apps_freezer") == "enabled"
        val phantom = executor.readSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs") == "false"
        val wifiScan = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled")
        val bleScan = executor.readSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled")
        val errOff = executor.readSetting(SettingNamespace.GLOBAL, "send_action_app_error") == "0"
        val hidePass = executor.readSetting(SettingNamespace.SYSTEM, "show_password") == "0"
        val instantLock = executor.readSetting(SettingNamespace.SECURE, "lock_screen_lock_after_timeout") == "0"

        val batPct = executor.readSetting(SettingNamespace.SYSTEM, "status_bar_show_battery_percent") == "1"
        val clockSec = executor.readSetting(SettingNamespace.SECURE, "clock_seconds") == "1"
        val blacklistRaw = executor.readSetting(SettingNamespace.SECURE, "icon_blacklist") ?: ""
        val hiddenTokens = parseIconBlacklist(blacklistRaw)
        val policy = executor.readSetting(SettingNamespace.GLOBAL, "policy_control")
        val chargeSound = executor.readSetting(SettingNamespace.GLOBAL, "charging_sounds_enabled") != "0"
        val lockSound = executor.readSetting(SettingNamespace.SYSTEM, "lockscreen_sounds_enabled") != "0"
        val touchSound = executor.readSetting(SettingNamespace.SYSTEM, "sound_effects_enabled") == "1"
        val haptic = executor.readSetting(SettingNamespace.SYSTEM, "haptic_feedback_enabled") != "0"
        val safeVolOff = executor.readSetting(SettingNamespace.GLOBAL, "audio_safe_volume_state") == "1"

        val swappiness = runCatching {
            File("/proc/sys/vm/swappiness").takeIf { it.canRead() }?.readText()?.trim()
        }.getOrNull()

        LiveSystemTweaksState(
            windowAnimScale = winAnim,
            transitionAnimScale = transAnim,
            animatorScale = anim,
            peakRefreshRate = peakHz,
            minRefreshRate = minHz,
            disableWindowBlurs = noBlur,
            privateDnsMode = dnsMode,
            privateDnsHost = dnsHost,
            wifiScanThrottleEnabled = scanThrottle,
            mobileDataAlwaysOn = dataAlways,
            tetherHardwareOffloadEnabled = tetherHw,
            cachedAppsFreezerEnabled = freezer,
            phantomKillerDisabled = phantom,
            hiddenRadioScanDisabled = (wifiScan == "0" && bleScan == "0"),
            errorTelemetryDisabled = errOff,
            hidePasswordChars = hidePass,
            instantPowerButtonLock = instantLock,
            clipboardProtectedAppsCount = 0,
            swappinessKernel = swappiness,
            batteryPercentInStatusBar = batPct,
            clockSecondsInStatusBar = clockSec,
            hiddenStatusBarTokens = hiddenTokens,
            immersivePolicy = policy,
            chargingSoundsEnabled = chargeSound,
            lockscreenSoundsEnabled = lockSound,
            touchSoundsEnabled = touchSound,
            hapticFeedbackEnabled = haptic,
            safeAudioWarningDisabled = safeVolOff,
        )
    }

    suspend fun applyPreset(preset: OptimizerPresetTemplate): VaultTransactionResult = withContext(Dispatchers.IO) {
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext VaultTransactionResult.Failure(
                "Для применения пресета подключите Беспроводной ADB / Shizuku или Root на Главной.",
                autoReverted = true,
            )
        }

        val defaultImePkg = executor.readSetting(SettingNamespace.SECURE, "default_input_method")
            ?.substringBefore('/')

        val mutations: List<VaultMutationSpec> = when (preset.id) {
            "preset_clean_new_phone" -> buildList {
                addAll(animScaleMutations("0.75"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "send_action_app_error", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "show_password", "0"))
                thirdPartyUserPackages(defaultImePkg).take(25).forEach { pkg ->
                    add(VaultMutationSpec.MutateAppOp(pkg, "READ_CLIPBOARD", "ignore"))
                }
            }
            "preset_max_battery" -> animScaleMutations("0.5") + listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "cached_apps_freezer", "enabled"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "disable_window_blurs", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "sound_effects_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "peak_refresh_rate", "60.0"),
            )
            "preset_gaming_snappy" -> animScaleMutations("0.35") + listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "peak_refresh_rate", "120.0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", "false"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_magnification_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "long_press_timeout", "300"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled", "1"),
            )
            "preset_privacy_shield" -> buildList {
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", "dns.adguard-dns.com"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "show_password", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "lock_screen_lock_after_timeout", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "send_action_app_error", "0"))
                thirdPartyUserPackages(defaultImePkg).take(25).forEach { pkg ->
                    add(VaultMutationSpec.MutateAppOp(pkg, "READ_CLIPBOARD", "ignore"))
                }
            }
            "preset_zen_clean_ui" -> animScaleMutations("0.85") + listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "icon_blacklist", "nfc,volte,bluetooth,alarm_clock"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "status_bar_show_battery_percent", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "charging_sounds_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "lockscreen_sounds_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "sound_effects_enabled", "0"),
            )
            "preset_power_multitask" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", "false"),
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "cmd device_config put activity_manager max_phantom_processes 2147483647",
                    revertCommand = "cmd device_config delete activity_manager max_phantom_processes",
                    summaryRu = "Снятие потолка фоновых процессов max_phantom_processes",
                ),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "mobile_data_always_on", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "tether_offload_disabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "clock_seconds", "1"),
            )
            "preset_low_ram_revival" -> animScaleMutations("0.25") + listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "disable_window_blurs", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "cached_apps_freezer", "enabled"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "logger_buffer_size", "64K"),
            )
            else -> emptyList()
        }

        vaultManager.applyTransaction(
            title = "Пресет: ${preset.titleRu}",
            reason = preset.subtitleRu,
            source = FeatureSource.OPTIMIZER,
            mutations = mutations,
        )
    }

    // --- Portable JSON Profile Export & Import ---

    fun exportPortableProfileJson(
        tweaks: LiveSystemTweaksState,
        suspendedPkgIds: List<String>,
    ): String {
        val profile = OptimizerPortableProfile(
            profileName = "Профиль ${Build.MANUFACTURER} ${Build.MODEL}",
            exportedAtEpochMs = System.currentTimeMillis(),
            animationScale = tweaks.animatorScale,
            privateDnsMode = tweaks.privateDnsMode,
            privateDnsHost = tweaks.privateDnsHost,
            cachedAppsFreezerEnabled = tweaks.cachedAppsFreezerEnabled,
            phantomKillerDisabled = tweaks.phantomKillerDisabled,
            hiddenRadioScanDisabled = tweaks.hiddenRadioScanDisabled,
            errorTelemetryDisabled = tweaks.errorTelemetryDisabled,
            hidePasswordChars = tweaks.hidePasswordChars,
            disableWindowBlurs = tweaks.disableWindowBlurs,
            hiddenStatusBarIcons = tweaks.hiddenStatusBarTokens.sorted(),
            suspendedPackages = suspendedPkgIds.sorted(),
        )
        return encodePortableProfile(profile)
    }

    suspend fun importPortableProfileJson(rawJson: String): VaultTransactionResult = withContext(Dispatchers.IO) {
        val profile = runCatching { decodePortableProfile(rawJson) }.getOrElse { err ->
            return@withContext VaultTransactionResult.Failure(
                "Некорректный JSON-профиль: ${err.message}",
                autoReverted = true,
            )
        }
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext VaultTransactionResult.Failure(
                "Подключите Беспроводной ADB / Shizuku или Root для импорта системного профиля.",
                autoReverted = true,
            )
        }

        val safeScale = profile.animationScale.toFloatOrNull()?.coerceIn(0.1f, 2.0f)?.toString() ?: "0.75"
        val radioVal = if (profile.hiddenRadioScanDisabled) "0" else "1"
        val mutations = buildList {
            addAll(animScaleMutations(safeScale))
            add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", profile.privateDnsMode))
            if (!profile.privateDnsHost.isNullOrBlank()) {
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", profile.privateDnsHost))
            }
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "cached_apps_freezer",
                    if (profile.cachedAppsFreezerEnabled) "enabled" else "device_default",
                ),
            )
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "settings_enable_monitor_phantom_procs",
                    if (profile.phantomKillerDisabled) "false" else "true",
                ),
            )
            add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", radioVal))
            add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", radioVal))
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "send_action_app_error",
                    if (profile.errorTelemetryDisabled) "0" else "1",
                ),
            )
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.SYSTEM,
                    "show_password",
                    if (profile.hidePasswordChars) "0" else "1",
                ),
            )
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "disable_window_blurs",
                    if (profile.disableWindowBlurs) "1" else "0",
                ),
            )
            add(
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.SECURE,
                    "icon_blacklist",
                    profile.hiddenStatusBarIcons.takeIf { it.isNotEmpty() }?.joinToString(","),
                ),
            )
            profile.suspendedPackages
                .filter { SAFE_PKG_REGEX.matches(it) }
                .take(80)
                .forEach { pkg ->
                    add(VaultMutationSpec.MutatePackageState(pkg, PackageStateTarget.SUSPENDED))
                }
        }

        vaultManager.applyTransaction(
            title = "Импорт JSON: ${profile.profileName.take(40)}",
            reason = "Перенос пользовательского профиля настроек и заморозки пакетов",
            source = FeatureSource.OPTIMIZER,
            mutations = mutations,
        )
    }

    // --- Granular Tweaks & UI Tuner ---

    suspend fun setAnimationScale(scale: String): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Шкала анимаций ${scale}x",
            reason = "Плавное ускорение оконных и системных переходов",
            source = FeatureSource.OPTIMIZER,
            mutations = animScaleMutations(scale),
        )

    suspend fun applyRefreshRateProfile(profile: RefreshRateProfile): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Герцовка экрана: ${profile.titleRu}",
            reason = "Настройка границ частоты обновления дисплея",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "min_refresh_rate", profile.minHz),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "peak_refresh_rate", profile.peakHz),
            ),
        )

    suspend fun applyPrivateDns(preset: PrivateDnsPreset): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Private DNS: ${preset.titleRu}",
            reason = preset.subtitleRu,
            source = FeatureSource.OPTIMIZER,
            mutations = buildList {
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", preset.modeValue))
                if (preset.hostnameValue != null) {
                    add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", preset.hostnameValue))
                }
            },
        )

    suspend fun applyCustomPrivateDnsHost(customHost: String): VaultTransactionResult {
        val clean = customHost.trim().lowercase()
        if (!SAFE_DNS_HOST_REGEX.matches(clean)) {
            return VaultTransactionResult.Failure("Введите корректный DoT-домен (например, dns.adguard-dns.com)", true)
        }
        return vaultManager.applyTransaction(
            title = "Кастомный DoT DNS: $clean",
            reason = "Персональный сервер шифрованного Private DNS",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", clean),
            ),
        )
    }

    suspend fun applyClipboardGuardAllUserApps(): VaultTransactionResult = withContext(Dispatchers.IO) {
        val defaultImePkg = executor.readSetting(SettingNamespace.SECURE, "default_input_method")
            ?.substringBefore('/')
        val targets = thirdPartyUserPackages(defaultImePkg).take(50)
        if (targets.isEmpty()) {
            return@withContext VaultTransactionResult.Failure("Сторонних приложений для ограничения не найдено", true)
        }
        vaultManager.applyTransaction(
            title = "Clipboard Guard (${targets.size} прил.)",
            reason = "Запрет фонового чтения буфера обмена с сохранением работы клавиатуры",
            source = FeatureSource.OPTIMIZER,
            mutations = targets.map { VaultMutationSpec.MutateAppOp(it, "READ_CLIPBOARD", "ignore") },
        )
    }

    suspend fun toggleStatusBarIconHidden(token: String, hide: Boolean): VaultTransactionResult = withContext(Dispatchers.IO) {
        val currentRaw = executor.readSetting(SettingNamespace.SECURE, "icon_blacklist") ?: ""
        val updatedValue = mutateIconBlacklist(currentRaw, token, hide)
        vaultManager.applyTransaction(
            title = "${if (hide) "Скрыть" else "Показать"} значок статус-бара: $token",
            reason = "Настройка строки состояния в Тюнере UI",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "icon_blacklist", updatedValue),
            ),
        )
    }

    suspend fun resetAllStatusBarIcons(): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Показать все значки статус-бара",
            reason = "Очистка Secure:icon_blacklist",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "icon_blacklist", null)),
        )

    suspend fun applyImmersiveMode(option: ImmersiveModeOption): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Режим экрана: ${option.titleRu}",
            reason = option.subtitleRu,
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "policy_control", option.policyValue),
            ),
        )

    suspend fun setKernelSwappiness(targetSwappiness: Int): VaultTransactionResult = withContext(Dispatchers.IO) {
        if (executor.currentTier() != PrivilegeTier.ROOT) {
            return@withContext VaultTransactionResult.Failure("Настройка vm.swappiness требует Root-доступа", true)
        }
        val clamped = targetSwappiness.coerceIn(10, 160)
        val prev = executor.execute("cat /proc/sys/vm/swappiness").stdoutText.trim().toIntOrNull()?.coerceIn(0, 200) ?: 60
        vaultManager.applyTransaction(
            title = "ZRAM / VM Swappiness → $clamped",
            reason = "Баланс сжатия страниц памяти ядра Linux",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "echo $clamped > /proc/sys/vm/swappiness",
                    revertCommand = "echo $prev > /proc/sys/vm/swappiness",
                    summaryRu = "vm.swappiness ($prev → $clamped)",
                ),
            ),
        )
    }

    suspend fun setSingleSwitch(
        titleRu: String,
        mutations: List<VaultMutationSpec>,
    ): VaultTransactionResult = vaultManager.applyTransaction(
        title = titleRu,
        reason = "Переключение системного параметра в Оптимизаторе",
        source = FeatureSource.OPTIMIZER,
        mutations = mutations,
    )

    // --- App Booster & Android Game Mode API ---

    suspend fun loadUserAppsForBooster(): List<BoosterUserApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
            .map { BoosterUserApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun runGlobalArtCompileAndTrim(mode: ArtCompileMode): String = withContext(Dispatchers.IO) {
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext "Подключите Беспроводной ADB / Shizuku или Root для запуска ART-компиляции и TRIM."
        }
        val trimRes = executor.execute("sm fstrim")
        val artRes = executor.execute("cmd package compile -m ${mode.cliMode} -a")
        val trimStatus = if (trimRes.isSuccess) "UFS TRIM выполнен" else "TRIM код ${trimRes.exitCode}"
        val artStatus = if (artRes.isSuccess) "ART (${mode.cliMode}) завершён" else "bg-dexopt запущен"
        "$trimStatus · $artStatus"
    }

    suspend fun compileSingleApp(pkg: String, mode: ArtCompileMode): String = withContext(Dispatchers.IO) {
        if (!SAFE_PKG_REGEX.matches(pkg)) return@withContext "Некорректное имя пакета"
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext "Требуется уровень ADB / Shizuku или Root"
        }
        val res = executor.execute("cmd package compile -m ${mode.cliMode} -f $pkg")
        if (res.isSuccess) {
            "Пакет $pkg успешно скомпилирован в режиме «${mode.titleRu}»!"
        } else {
            res.stderrText.ifEmpty { "Команда завершилась с кодом ${res.exitCode}" }
        }
    }

    suspend fun applyAndroidGameMode(pkg: String, mode: AndroidGameModeChoice): VaultTransactionResult {
        if (!SAFE_PKG_REGEX.matches(pkg)) {
            return VaultTransactionResult.Failure("Некорректное имя пакета", true)
        }
        return vaultManager.applyTransaction(
            title = "Game Mode ($pkg → ${mode.labelRu})",
            reason = "Системный игровой профиль Android 12+ cmd game mode",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "cmd game mode ${mode.cliArg} $pkg",
                    revertCommand = "cmd game mode 1 $pkg",
                    summaryRu = "Game Mode $pkg → ${mode.labelRu}",
                ),
            ),
        )
    }

    // --- Debloater Pro ---

    suspend fun mutateDebloatPackages(
        packageIds: List<String>,
        targetState: PackageStateTarget,
        actionTitleRu: String,
    ): VaultTransactionResult = withContext(Dispatchers.IO) {
        if (packageIds.isEmpty()) {
            return@withContext VaultTransactionResult.Failure("Не выбрано ни одного пакета", autoReverted = true)
        }
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext VaultTransactionResult.Failure(
                "Для заморозки/отключения блоатвари подключите Беспроводной ADB / Shizuku или Root.",
                autoReverted = true,
            )
        }
        val mutations = packageIds.filter { SAFE_PKG_REGEX.matches(it) }.map { pkg ->
            VaultMutationSpec.MutatePackageState(pkg, targetState)
        }
        vaultManager.applyTransaction(
            title = "$actionTitleRu (${mutations.size} пак.)",
            reason = "Обратимое управление предустановленными пакетами по базе UAD-ng",
            source = FeatureSource.OPTIMIZER,
            mutations = mutations,
        )
    }

    private fun animScaleMutations(scale: String): List<VaultMutationSpec> = listOf(
        VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", scale),
        VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", scale),
        VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", scale),
    )

    private fun thirdPartyUserPackages(excludeIme: String?): List<String> =
        context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { it != excludeIme && it != context.packageName && SAFE_PKG_REGEX.matches(it) }
            .toList()

    companion object {
        private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._]+$")
        private val SAFE_DNS_HOST_REGEX = Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")

        private val profileJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encodePortableProfile(profile: OptimizerPortableProfile): String =
            profileJson.encodeToString(profile)

        fun decodePortableProfile(raw: String): OptimizerPortableProfile =
            profileJson.decodeFromString(raw)

        fun parseIconBlacklist(raw: String): Set<String> =
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" }
                .toSet()

        fun mutateIconBlacklist(currentRaw: String, token: String, hide: Boolean): String? {
            val mutable = parseIconBlacklist(currentRaw).toMutableSet()
            if (hide) mutable.add(token) else mutable.remove(token)
            return if (mutable.isEmpty()) null else mutable.sorted().joinToString(",")
        }
    }
}
