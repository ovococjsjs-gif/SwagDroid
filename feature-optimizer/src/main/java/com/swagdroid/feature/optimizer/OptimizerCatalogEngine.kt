package com.swagdroid.feature.optimizer

import com.swagdroid.adb.OemVendorProfile
import com.swagdroid.api.CriticalPackageGuard
import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.api.SettingNamespace
import com.swagdroid.core.rollback.ReversibilityType
import com.swagdroid.core.rollback.ReversibleSystemOp
import com.swagdroid.core.rollback.RollbackJournal
import com.swagdroid.core.rollback.WalEntryState

/**
 * Движок каталога паспортов `TogglePassport` и базы Деблоатера (`OptimizerCatalogEngine`).
 *
 * Все операции проходят через единый конвейер:
 * `StateReader -> WAL PREPARED Snapshot -> Write -> ReadBack Verifier`.
 */
class OptimizerCatalogEngine(
    private val rollbackJournal: RollbackJournal,
) {
    val togglePassports: List<TogglePassport> = DEFAULT_TOGGLE_PASSPORTS
    val curatedDebloatCatalog: List<CuratedDebloatPackageEntry> = DEFAULT_DEBLOAT_CATALOG

    /**
     * Читает фактическое состояние тумблера из системы (`true` если все ключи равны `enabledWireValue`).
     */
    fun readToggleActualState(
        passport: TogglePassport,
        service: ISwagDroidService,
    ): Boolean {
        val values = passport.settingKeys.map { key ->
            service.getSystemSetting(passport.namespace.wireName, key)
        }
        return values.all { it == passport.enabledWireValue }
    }

    /**
     * Применяет желаемое состояние тумблера с записью в Write-Ahead журнал и обязательной проверкой чтения после записи!
     */
    suspend fun setToggleDesiredState(
        passport: TogglePassport,
        desiredEnabled: Boolean,
        service: ISwagDroidService,
    ): Result<Boolean> = runCatching {
        val targetWireValue = if (desiredEnabled) passport.enabledWireValue else passport.disabledWireValue

        // 1. Читаем старые значения всех ключей паспорта
        val ops = passport.settingKeys.map { key ->
            val oldVal = service.getSystemSetting(passport.namespace.wireName, key)
            ReversibleSystemOp.SystemSettingChange(
                namespace = passport.namespace,
                key = key,
                previousValue = oldVal,
                newValue = targetWireValue,
            )
        }

        // 2. Создаём PREPARED запись в Write-Ahead журнале
        val tx = rollbackJournal.recordPreMutationSnapshot(
            featureId = "optimizer_toggle_${passport.id}",
            titleRu = "${if (desiredEnabled) "Включение" else "Отключение"}: ${passport.titleRu}",
            descriptionRu = passport.sideEffectsWarningRu,
            operations = ops,
            reversibility = passport.reversibility,
            initialState = WalEntryState.PREPARED,
        )

        rollbackJournal.updateWalState(tx.transactionId, WalEntryState.APPLYING)

        // 3. Записываем значения и сразу перечитываем обратно (Verifier)
        for (key in passport.settingKeys) {
            service.putSystemSetting(passport.namespace.wireName, key, targetWireValue)
            val verifiedReadBack = service.getSystemSetting(passport.namespace.wireName, key)
            if (verifiedReadBack != targetWireValue) {
                rollbackJournal.updateWalState(tx.transactionId, WalEntryState.VERIFY_FAILED)
                throw IllegalStateException(
                    "Прошивка отклонила или перезаписала ключ ${passport.namespace.wireName}:$key " +
                        "(ожидалось '$targetWireValue', фактически '$verifiedReadBack')"
                )
            }
        }

        rollbackJournal.updateWalState(tx.transactionId, WalEntryState.VERIFIED)
        desiredEnabled
    }

    /**
     * Возвращает безопасный бейдж для любого пакета (с безусловным приоритетом `CriticalPackageGuard`).
     */
    fun resolveSafetyBadgeForPackage(
        packageName: String,
        currentDefaultLauncher: String? = null,
        currentDefaultInputMethod: String? = null,
    ): DebloatSafetyBadge {
        if (CriticalPackageGuard.isHardBlocked(packageName, currentDefaultLauncher, currentDefaultInputMethod)) {
            return DebloatSafetyBadge.UNSAFE_PROTECTED
        }
        if (CriticalPackageGuard.isHighCaution(packageName)) {
            return DebloatSafetyBadge.EXPERT
        }
        return curatedDebloatCatalog.firstOrNull { it.packageName == packageName }?.badge
            ?: DebloatSafetyBadge.UNKNOWN
    }

    companion object {
        val DEFAULT_TOGGLE_PASSPORTS = listOf(
            // 1. Скорость анимаций (0.5x согласованно по всем 3 шкалам)
            TogglePassport(
                id = "animations_snappy_0_5x",
                titleRu = "Ускоренные системные анимации (0.5x)",
                subtitleRu = "Синхронно устанавливает window, transition и animator duration scale = 0.5",
                category = OptimizerCategory.DISPLAY_AND_PERCEIVED_SPEED,
                namespace = SettingNamespace.GLOBAL,
                settingKeys = listOf(
                    "window_animation_scale",
                    "transition_animation_scale",
                    "animator_duration_scale",
                ),
                enabledWireValue = "0.5",
                disabledWireValue = "1.0",
                minPrivilege = PrivilegeTier.ADB_SHELL,
                supportedVendors = emptySet(),
                sideEffectsWarningRu = "Повышает только визуальную скорость переходов интерфейса; производительность процессора не меняет.",
            ),

            // 2. Фоновое сканирование Wi-Fi для геолокации
            TogglePassport(
                id = "disable_wifi_background_scan",
                titleRu = "Отключить постоянное сканирование Wi-Fi в фоне",
                subtitleRu = "Ключ Settings.Global: wifi_scan_always_enabled = 0",
                category = OptimizerCategory.PRIVACY_AND_LOCATION_SCANNING,
                namespace = SettingNamespace.GLOBAL,
                settingKeys = listOf("wifi_scan_always_enabled"),
                enabledWireValue = "0",
                disabledWireValue = "1",
                minPrivilege = PrivilegeTier.ADB_SHELL,
                supportedVendors = emptySet(),
                sideEffectsWarningRu = "Снижает фоновую активность радиомодуля и передачу данных об окружении, но может замедлить определение геопозиции внутри помещений.",
            ),

            // 3. Фоновое сканирование Bluetooth BLE для геолокации
            TogglePassport(
                id = "disable_ble_background_scan",
                titleRu = "Отключить фоновое сканирование Bluetooth (BLE)",
                subtitleRu = "Ключ Settings.Global: ble_scan_always_enabled = 0",
                category = OptimizerCategory.PRIVACY_AND_LOCATION_SCANNING,
                namespace = SettingNamespace.GLOBAL,
                settingKeys = listOf("ble_scan_always_enabled"),
                enabledWireValue = "0",
                disabledWireValue = "1",
                minPrivilege = PrivilegeTier.ADB_SHELL,
                supportedVendors = emptySet(),
                sideEffectsWarningRu = "Запрещает приложениям искать BLE-маяки при выключенном Bluetooth; может повлиять на поиск умных меток и геопозицию в ТЦ.",
            ),

            // 4. Phantom Process Killer — строго в разделе «Для Termux и разработчиков»!
            TogglePassport(
                id = "disable_phantom_process_monitor",
                titleRu = "Снять лимит дочерних процессов (Phantom Process Killer)",
                subtitleRu = "Для Termux / PRoot / Linux-окружений (settings_enable_monitor_phantom_procs = false)",
                category = OptimizerCategory.TERMUX_AND_DEVELOPERS,
                namespace = SettingNamespace.GLOBAL,
                settingKeys = listOf("settings_enable_monitor_phantom_procs"),
                enabledWireValue = "false",
                disabledWireValue = "true",
                minPrivilege = PrivilegeTier.ADB_SHELL,
                supportedVendors = emptySet(),
                minSdk = 31,
                maxSdk = 36,
                sideEffectsWarningRu = "Предназначено для пользователей Termux и локальных серверов. Не является общим «ускорителем» обычных Android-приложений. На Android 14+ также доступен системный тумблер 'Disable child process restrictions'.",
            ),

            // 5. Samsung RAM Plus (нейтральная техническая формулировка про целевой размер zRAM)
            TogglePassport(
                id = "samsung_ram_plus_zram_target",
                titleRu = "Samsung One UI: Минимизировать целевой профиль RAM Plus (zRAM)",
                subtitleRu = "Ключ Settings.Global: ram_expand_size (управление выделением памяти под zRAM)",
                category = OptimizerCategory.OEM_VENDOR_SPECIFIC,
                namespace = SettingNamespace.GLOBAL,
                settingKeys = listOf("ram_expand_size"),
                enabledWireValue = "0",
                disabledWireValue = "4096",
                minPrivilege = PrivilegeTier.ADB_SHELL,
                supportedVendors = setOf(OemVendorProfile.SAMSUNG_ONEUI),
                requiresReboot = true,
                sideEffectsWarningRu = "На современных моделях Galaxy настройка регулирует целевой размер zRAM в оперативной памяти (требуется перезагрузка). Базовый системный zRAM при этом не отключается полностью.",
            ),
        )

        val DEFAULT_DEBLOAT_CATALOG = listOf(
            CuratedDebloatPackageEntry(
                packageName = "com.miui.msa.global",
                titleRu = "MIUI/HyperOS System Ads (MSA Global)",
                vendor = OemVendorProfile.XIAOMI_HYPEROS_MIUI,
                badge = DebloatSafetyBadge.RECOMMENDED,
                whatBreaksIfDisabledRu = "Отключает показ системных рекламных баннеров в стандартных приложениях Xiaomi. Никакие пользовательские функции не страдают.",
                recommendedMechanism = DebloatMechanism.SUSPEND_PACKAGE,
            ),
            CuratedDebloatPackageEntry(
                packageName = "com.miui.analytics",
                titleRu = "Xiaomi Analytics Daemon",
                vendor = OemVendorProfile.XIAOMI_HYPEROS_MIUI,
                badge = DebloatSafetyBadge.RECOMMENDED,
                whatBreaksIfDisabledRu = "Отключает сбор телеметрии использования оболочки MIUI/HyperOS.",
                recommendedMechanism = DebloatMechanism.SUSPEND_PACKAGE,
            ),
            CuratedDebloatPackageEntry(
                packageName = "com.samsung.android.game.gos",
                titleRu = "Samsung Game Optimizing Service (GOS)",
                vendor = OemVendorProfile.SAMSUNG_ONEUI,
                badge = DebloatSafetyBadge.EXPERT,
                whatBreaksIfDisabledRu = "ВНИМАНИЕ: Принудительная заморозка пакета может сломать Game Booster, автоопределение игр и обходную зарядку (Bypass Charging). Рекомендуется сначала использовать официальный переключатель в Game Booster Labs!",
                recommendedMechanism = DebloatMechanism.SUSPEND_PACKAGE,
            ),
            CuratedDebloatPackageEntry(
                packageName = "com.miui.securitycenter",
                titleRu = "Xiaomi Безопасность (SecurityCenter)",
                vendor = OemVendorProfile.XIAOMI_HYPEROS_MIUI,
                badge = DebloatSafetyBadge.UNSAFE_PROTECTED,
                whatBreaksIfDisabledRu = "КРИТИЧНО: Удаление или отключение этого пакета гарантированно приводит к вечной перезагрузке (Bootloop) на MIUI и HyperOS!",
                recommendedMechanism = DebloatMechanism.SUSPEND_PACKAGE,
            ),
        )
    }
}
