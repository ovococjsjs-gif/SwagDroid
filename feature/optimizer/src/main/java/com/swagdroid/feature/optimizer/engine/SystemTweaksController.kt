package com.swagdroid.feature.optimizer.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RiskLevel
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.optimizer.model.LiveSystemTweaksState
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            titleRu = "Чистый старт нового смартфона",
            subtitleRu = "Идеальная базовая гигиена сразу после покупки без потери функций",
            bulletPoints = listOf(
                "Плавные ускоренные анимации интерфейса 0.75x",
                "Отключение скрытого круглосуточного сканирования Wi-Fi и BLE",
                "Отключение фоновой отправки дампов ошибок",
                "Защита от пинг-спайков Wi-Fi (троттлинг радиосканера)",
                "Clipboard Guard: запрет чтения буфера обмена сторонним APK",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_max_battery",
            titleRu = "Максимальная автономность",
            subtitleRu = "Экономия заряда в фоне и в дороге без поломки уведомлений",
            bulletPoints = listOf(
                "Принудительная заморозка свёрнутых процессов в ОЗУ (Cached Apps Freezer)",
                "Выключение скрытых радиосканеров Wi-Fi и Bluetooth LE",
                "Быстрые анимации 0.5x (меньше времени отрисовки GPU)",
                "Отключение звуковых щелчков каждого касания (экономия пробуждений ЦАП)",
            ),
            risk = RiskLevel.SAFE,
        ),
        OptimizerPresetTemplate(
            id = "preset_gaming_snappy",
            titleRu = "Отзывчивость и Игры",
            subtitleRu = "Мгновенный отклик сенсора, стабильный пинг и снятие лимитов фона",
            bulletPoints = listOf(
                "Молниеносные безопасные анимации 0.5x",
                "Отключение убийцы фоновых процессов (Phantom Process Killer Android 12+)",
                "Чёткий таймер долгого нажатия тачскрина 300 мс",
                "Троттлинг фонового обзора Wi-Fi против скачков пинга",
                "Отключение перехватчика тройного нажатия лупы (минимальный input-lag)",
            ),
            risk = RiskLevel.ADVANCED,
        ),
        OptimizerPresetTemplate(
            id = "preset_privacy_shield",
            titleRu = "Щит приватности + Анти-реклама DNS",
            subtitleRu = "Системная фильтрация трекеров без расхода батареи и без занятия слота VPN",
            bulletPoints = listOf(
                "Зашифрованный системный AdGuard Private DNS (DoT порт 853)",
                "Clipboard Guard для сторонних приложений (защита паролей и карт)",
                "Отключение фонового гео-сканирования сетей",
                "Отключение автоотправки отчётов об ошибках приложений",
            ),
            risk = RiskLevel.SAFE,
        ),
    )

    suspend fun readLiveTweaksState(): LiveSystemTweaksState = withContext(Dispatchers.IO) {
        val anim = executor.readSetting(SettingNamespace.GLOBAL, "animator_duration_scale") ?: "1.0"
        val dnsMode = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_mode") ?: "opportunistic"
        val dnsHost = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_specifier")
        val freezer = executor.readSetting(SettingNamespace.GLOBAL, "cached_apps_freezer") == "enabled"
        val phantom = executor.readSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs") == "false"
        val wifiScan = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled")
        val bleScan = executor.readSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled")
        val batPct = executor.readSetting(SettingNamespace.SYSTEM, "status_bar_show_battery_percent") == "1"
        val errOff = executor.readSetting(SettingNamespace.GLOBAL, "send_action_app_error") == "0"

        LiveSystemTweaksState(
            animationScale = anim,
            privateDnsMode = dnsMode,
            privateDnsHost = dnsHost,
            cachedAppsFreezerEnabled = freezer,
            phantomKillerDisabled = phantom,
            hiddenRadioScanDisabled = (wifiScan == "0" && bleScan == "0"),
            batteryPercentInStatusBar = batPct,
            errorTelemetryDisabled = errOff,
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
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.75"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", "0.75"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.75"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled", "1"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "send_action_app_error", "0"))
                thirdPartyUserPackages(defaultImePkg).take(25).forEach { pkg ->
                    add(VaultMutationSpec.MutateAppOp(pkg, "READ_CLIPBOARD", "ignore"))
                }
            }
            "preset_max_battery" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "cached_apps_freezer", "enabled"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "sound_effects_enabled", "0"),
            )
            "preset_gaming_snappy" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", "false"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "long_press_timeout", "300"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_magnification_enabled", "0"),
            )
            "preset_privacy_shield" -> buildList {
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", "dns.adguard-dns.com"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"))
                add(VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "send_action_app_error", "0"))
                thirdPartyUserPackages(defaultImePkg).take(25).forEach { pkg ->
                    add(VaultMutationSpec.MutateAppOp(pkg, "READ_CLIPBOARD", "ignore"))
                }
            }
            else -> emptyList()
        }

        vaultManager.applyTransaction(
            title = "Пресет: ${preset.titleRu}",
            reason = preset.subtitleRu,
            source = FeatureSource.OPTIMIZER,
            mutations = mutations,
        )
    }

    suspend fun setAnimationScale(scale: String): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Шкала анимаций ${scale}x",
            reason = "Плавное ускорение оконных и системных переходов",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", scale),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", scale),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", scale),
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

    suspend fun setSingleSwitch(
        titleRu: String,
        mutations: List<VaultMutationSpec>,
    ): VaultTransactionResult = vaultManager.applyTransaction(
        title = titleRu,
        reason = "Переключение системного параметра в Оптимизаторе",
        source = FeatureSource.OPTIMIZER,
        mutations = mutations,
    )

    suspend fun runArtBoosterAndTrim(): String = withContext(Dispatchers.IO) {
        if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            return@withContext "Требуется уровень ADB / Shizuku или Root для запуска ART-компиляции и TRIM."
        }
        val trimRes = executor.execute("sm fstrim")
        val artRes = executor.execute("cmd package bg-dexopt-job")
        val trimStatus = if (trimRes.isSuccess) "UFS TRIM выполнен" else "TRIM пропущен (${trimRes.exitCode})"
        val artStatus = if (artRes.isSuccess) "ART speed-profile оптимизация выполнена" else "ART код ${artRes.exitCode}"
        "$trimStatus · $artStatus"
    }

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
        val mutations = packageIds.map { pkg ->
            VaultMutationSpec.MutatePackageState(pkg, targetState)
        }
        vaultManager.applyTransaction(
            title = "$actionTitleRu (${packageIds.size} пак.)",
            reason = "Безопасное управление предустановленными пакетами по базе UAD-ng",
            source = FeatureSource.OPTIMIZER,
            mutations = mutations,
        )
    }

    private fun thirdPartyUserPackages(excludeIme: String?): List<String> =
        context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { it != excludeIme && it != context.packageName && Regex("^[a-zA-Z0-9._]+$").matches(it) }
            .toList()
}
