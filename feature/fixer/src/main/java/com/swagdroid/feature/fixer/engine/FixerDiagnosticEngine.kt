package com.swagdroid.feature.fixer.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.StateReadException
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.privilege.SystemReadQuery
import com.swagdroid.core.privilege.requireReadOutput
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.fixer.model.AppRescueAuditResult
import com.swagdroid.feature.fixer.model.DiagnosticVerdict
import com.swagdroid.feature.fixer.model.FixPlaybook
import com.swagdroid.feature.fixer.model.InstalledAppBrief
import com.swagdroid.feature.fixer.safety.FixerSafetyPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FixExecutionOutcome {
    data class AppliedWithVault(val snapshotId: Long, val messageRu: String) : FixExecutionOutcome
    data class AppliedMaintenance(val messageRu: String) : FixExecutionOutcome
    data class Failed(val errorRu: String) : FixExecutionOutcome
}

@Singleton
class FixerDiagnosticEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
    private val vaultManager: StateVaultManager,
) {

    /**
     * Runs a live, non-destructive diagnostic check for [playbook] on the current device.
     */
    suspend fun diagnose(playbook: FixPlaybook): Pair<DiagnosticVerdict, String> = try {
        diagnoseAvailableState(playbook)
    } catch (error: StateReadException) {
        DiagnosticVerdict.REVIEW_REQUIRED to "Данные недоступны: ${error.message}. Неисправность или её отсутствие не подтверждены."
    }

    private suspend fun diagnoseAvailableState(playbook: FixPlaybook): Pair<DiagnosticVerdict, String> = withContext(Dispatchers.IO) {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (playbook.vendorFilter.isNotEmpty() && playbook.vendorFilter.none { brand.contains(it) }) {
            return@withContext DiagnosticVerdict.NOT_APPLICABLE to
                "Актуально для ${playbook.vendorFilter.joinToString("/")} (у вас: ${Build.MANUFACTURER})"
        }

        val currentTier = executor.currentTier()
        val needsRootCheck = playbook.requiredTier == PrivilegeTier.ROOT && currentTier != PrivilegeTier.ROOT
        if (needsRootCheck) {
            return@withContext DiagnosticVerdict.NEEDS_PRIVILEGE to
                "Для прямой инспекции ядра Linux (/proc/sys) требуется уровень Root"
        }

        when (playbook.id) {
            // --- 1. NETWORK ---
            "net_captive_portal_mirrors" -> {
                val mode = executor.readSetting(SettingNamespace.GLOBAL, "captive_portal_mode") ?: "1"
                val httpsUrl = executor.readSetting(SettingNamespace.GLOBAL, "captive_portal_https_url")
                if (httpsUrl != null && (httpsUrl.contains("cloudflare") || httpsUrl.contains("hicloud"))) {
                    DiagnosticVerdict.HEALTHY to "Режим=$mode, зеркало уже настроено: $httpsUrl"
                } else {
                    DiagnosticVerdict.ISSUE_FOUND to "Используется стандартный URL (${httpsUrl ?: "gstatic по умолчанию"}), возможны ложные «без интернета»"
                }
            }
            "net_private_dns_portal_conflict" -> {
                val mode = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_mode") ?: "opportunistic"
                val host = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_specifier")
                if (mode == "hostname") {
                    DiagnosticVerdict.ISSUE_FOUND to "Жёсткий hostname (${host ?: "задан"}): может блокировать вход в публичный Wi-Fi"
                } else {
                    DiagnosticVerdict.HEALTHY to "Адаптивный режим Private DNS ($mode)"
                }
            }
            "net_soft_stack_reset" -> {
                val dnsMode = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_mode") ?: "opportunistic"
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите Shizuku / Беспроводной ADB для мягкого перезапуска радиостека"
                } else {
                    DiagnosticVerdict.HEALTHY to "Готово к мягкому сбросу сокетов и DNS без потери паролей (DNS=$dnsMode)"
                }
            }
            "net_wifi_scan_ping_spikes" -> {
                val alwaysScan = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled")
                val throttle = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled")
                if (alwaysScan == "1" || throttle == "0") {
                    DiagnosticVerdict.ISSUE_FOUND to "Фоновый поиск Wi-Fi=${alwaysScan ?: "1"}, троттлинг=${throttle ?: "1"} — источник скачков пинга"
                } else {
                    DiagnosticVerdict.HEALTHY to "Фоновый поиск выключен, троттлинг активен"
                }
            }
            "net_seamless_wifi_lte_handoff" -> {
                val alwaysOn = executor.readSetting(SettingNamespace.GLOBAL, "mobile_data_always_on")
                if (alwaysOn == "0") {
                    DiagnosticVerdict.ISSUE_FOUND to "mobile_data_always_on=0: переход с Wi-Fi на LTE будет с паузой связи"
                } else {
                    DiagnosticVerdict.HEALTHY to "Бесшовное удержание мобильного канала активно (${alwaysOn ?: "1"})"
                }
            }
            "net_hotspot_tether_dun" -> {
                val dun = executor.readSetting(SettingNamespace.GLOBAL, "tether_dun_required")
                if (dun == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "tether_dun_required=1: оператор может блокировать трафик раздачи"
                } else {
                    DiagnosticVerdict.HEALTHY to "tether_dun_required=${dun ?: "не принуждается"}"
                }
            }

            // --- 2. NOTIFICATIONS ---
            "notif_heads_up_banners_missing" -> {
                val headsUp = executor.readSetting(SettingNamespace.GLOBAL, "heads_up_notifications_enabled")
                val zen = executor.readSetting(SettingNamespace.GLOBAL, "zen_mode")
                if (headsUp == "0" || (zen != null && zen != "0")) {
                    DiagnosticVerdict.ISSUE_FOUND to "heads_up=${headsUp ?: "1"}, Не беспокоить (zen_mode)=${zen ?: "0"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Всплывающие баннеры включены, режим DND выключен"
                }
            }
            "notif_lockscreen_hidden" -> {
                val show = executor.readSetting(SettingNamespace.SECURE, "lock_screen_show_notifications")
                val priv = executor.readSetting(SettingNamespace.SECURE, "lock_screen_allow_private_notifications")
                if (show == "0" || priv == "0") {
                    DiagnosticVerdict.ISSUE_FOUND to "Показ на локскрине=${show ?: "1"}, текст сообщений=${priv ?: "1"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Уведомления и текст на экране блокировки разрешены"
                }
            }
            "notif_fcm_push_delayed_batch" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Нужен ADB/Shizuku для чтения белого списка Doze транспорта FCM"
                } else {
                    val wl = executor.query(SystemReadQuery.Fixed.IDLE_WHITELIST).requireReadOutput().stdoutText
                    if (wl.contains("com.google.android.gms")) {
                        DiagnosticVerdict.HEALTHY to "Транспорт Google Play Services находится в белом списке Doze"
                    } else {
                        DiagnosticVerdict.ISSUE_FOUND to "com.google.android.gms отсутствует в исключениях Doze — пуши могут задерживаться"
                    }
                }
            }
            "notif_phantom_process_killer" -> {
                if (Build.VERSION.SDK_INT < 31) {
                    DiagnosticVerdict.NOT_APPLICABLE to "Phantom Process Killer появился начиная с Android 12 (API 31+)"
                } else {
                    val monitor = executor.readSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs")
                    if (monitor == "false") {
                        DiagnosticVerdict.HEALTHY to "Убийца фантомных процессов уже отключён (monitor=false)"
                    } else {
                        DiagnosticVerdict.ISSUE_FOUND to "Лимитер фантомных процессов активен (${monitor ?: "default=true"})"
                    }
                }
            }
            "notif_snooze_and_channels_aid" -> {
                val snooze = executor.readSetting(SettingNamespace.SECURE, "show_notification_snooze")
                if (snooze == "1") {
                    DiagnosticVerdict.HEALTHY to "Кнопка «Отложить уведомление» в шторке активна"
                } else {
                    DiagnosticVerdict.ISSUE_FOUND to "Таймер откладывания уведомлений скрыт (show_notification_snooze=${snooze ?: "0"})"
                }
            }

            // --- 3. BATTERY ---
            "bat_deep_doze_disabled" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для проверки статуса Deep Doze и WakeLocks"
                } else {
                    val out = executor.query(SystemReadQuery.Fixed.DEVICE_IDLE).requireReadOutput().stdoutText
                    val deepDisabled = out.contains("mDeepEnabled=false") || out.contains("Deep enabled: false")
                    if (deepDisabled) {
                        DiagnosticVerdict.ISSUE_FOUND to "Глубокий сон Android (Deep Doze) принудительно отключён!"
                    } else {
                        DiagnosticVerdict.HEALTHY to "Контроллер глубокого сна Deep Doze активен"
                    }
                }
            }
            "bat_mediascanner_cpu_loop" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для перезапуска зависшего воркера MediaProvider"
                } else {
                    DiagnosticVerdict.HEALTHY to "Готово к мягкому сбросу очереди медиасканера при нагреве"
                }
            }
            "bat_hidden_radio_scanners" -> {
                val wifiScan = executor.readSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled")
                val bleScan = executor.readSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled")
                if (wifiScan == "1" || bleScan == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "Скрытые сканеры активны: Wi-Fi=${wifiScan ?: "0"}, Bluetooth LE=${bleScan ?: "0"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Скрытое фоновое радиосканирование Wi-Fi и BLE отключено"
                }
            }
            "bat_error_telemetry_wakelocks" -> {
                val sendErr = executor.readSetting(SettingNamespace.GLOBAL, "send_action_app_error")
                if (sendErr == "0") {
                    DiagnosticVerdict.HEALTHY to "Фоновая упаковка отчётов об ошибках отключена (0)"
                } else {
                    DiagnosticVerdict.ISSUE_FOUND to "Фоновая отправка дампов активна (send_action_app_error=${sendErr ?: "1"})"
                }
            }
            "bat_fuel_gauge_stats_reset" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Нужен ADB/Shizuku для сброса профиля batterystats"
                } else {
                    DiagnosticVerdict.HEALTHY to "Доступен чистый сброс статистики расхода batterystats --reset"
                }
            }

            // --- 4. STORAGE ---
            "stor_ufs_fstrim_lag" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для немедленного запуска sm fstrim"
                } else {
                    val disk = executor.query(SystemReadQuery.Fixed.DISK_STATS).requireReadOutput().stdoutText
                    val fstrimLine = disk.lineSequence().firstOrNull { it.contains("Last fstrim", ignoreCase = true) }?.trim()
                    DiagnosticVerdict.HEALTHY to (fstrimLine ?: "Чип UFS готов к ручному обслуживанию TRIM")
                }
            }
            "stor_abandoned_install_sessions" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Нужен ADB/Shizuku для аудита staging-сессий PackageInstaller"
                } else {
                    val result = executor.query(SystemReadQuery.Fixed.STAGING_SESSIONS).requireReadOutput()
                    if (!result.isSuccess) {
                        DiagnosticVerdict.REVIEW_REQUIRED to "Не удалось прочитать сессии установщика. Их состояние неизвестно."
                    } else {
                        val ids = parseStagingSessionIds(result.stdoutText)
                        if (ids.isNotEmpty()) {
                            DiagnosticVerdict.REVIEW_REQUIRED to "Сессий установщика: ${ids.size} (${ids.joinToString()}). Это не доказывает, что они брошены; автоматическая отмена отключена."
                        } else {
                            DiagnosticVerdict.HEALTHY to "Команда не сообщила о staging-сессиях установки"
                        }
                    }
                }
            }
            "stor_gallery_mediastore_desync" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Нужен ADB/Shizuku для принудительного пересканирования MediaStore"
                } else {
                    DiagnosticVerdict.HEALTHY to "Готово к переиндексации DCIM / Pictures / Download"
                }
            }
            "stor_logd_ring_buffer_trim" -> {
                val bufSize = executor.readSetting(SettingNamespace.GLOBAL, "logger_buffer_size")
                if (bufSize != null && bufSize != "256K" && bufSize != "64K") {
                    DiagnosticVerdict.ISSUE_FOUND to "Увеличенный буфер логгера: $bufSize (рекомендуется 256K)"
                } else {
                    DiagnosticVerdict.HEALTHY to "Размер кольцевого буфера logd оптимален (${bufSize ?: "256K"})"
                }
            }

            // --- 5. PACKAGES ---
            "pkg_ghost_uninstalled_conflict" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для поиска хвостов удалённых APK (-u)"
                } else {
                    val allWithUninstalled = executor.query(SystemReadQuery.Packages(executor.currentUserId(), includeUninstalled = true)).requireReadOutput()
                    val activeOnly = executor.query(SystemReadQuery.Packages(executor.currentUserId())).requireReadOutput()
                    if (!allWithUninstalled.isSuccess || !activeOnly.isSuccess) {
                        DiagnosticVerdict.REVIEW_REQUIRED to "Не удалось прочитать оба списка пакетов. Нельзя определить кандидатов."
                    } else {
                        val candidates = parseGhostUninstalledPackages(allWithUninstalled.stdoutText, activeOnly.stdoutText)
                        if (candidates.isNotEmpty()) {
                            DiagnosticVerdict.REVIEW_REQUIRED to "Кандидатов для ручной проверки: ${candidates.size} — ${candidates.take(3).joinToString()}. Разность списков не доказывает конфликт; данные не удаляются."
                        } else {
                            DiagnosticVerdict.HEALTHY to "Различий в полученных списках пакетов нет; причина ошибки установки не установлена"
                        }
                    }
                }
            }
            "pkg_webview_crash_rescue" -> {
                val candidates = listOf("com.google.android.webview", "com.android.webview", "com.android.chrome")
                val installedStates = candidates.mapNotNull { pkg ->
                    if (isPackagePresent(pkg)) "$pkg=${executor.readPackageState(pkg).labelRu}" else null
                }
                val broken = candidates.any { isPackagePresent(it) && executor.readPackageState(it) != PackageStateTarget.ENABLED }
                if (broken) {
                    DiagnosticVerdict.ISSUE_FOUND to "Системный WebView отключён/заморожен: ${installedStates.joinToString()}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Провайдер WebView активен (${installedStates.firstOrNull() ?: "системный"})"
                }
            }
            "pkg_download_manager_stuck" -> {
                val dlPkg = "com.android.providers.downloads"
                val state = executor.readPackageState(dlPkg)
                if (state != PackageStateTarget.ENABLED) {
                    DiagnosticVerdict.ISSUE_FOUND to "Системный Менеджер загрузок отключён (${state.labelRu}) — магазины не смогут качать APK!"
                } else {
                    DiagnosticVerdict.HEALTHY to "Системный провайдер загрузок включён"
                }
            }
            "pkg_app_links_reverify" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для запуска pm verify-app-links"
                } else {
                    DiagnosticVerdict.HEALTHY to "Доступна принудительная перепроверка открытия ссылок в приложениях"
                }
            }
            "pkg_post_ota_art_compile" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для запуска AOT-компиляции speed-profile"
                } else {
                    DiagnosticVerdict.HEALTHY to "Готово к профильной оптимизации байткода всех приложений"
                }
            }

            // --- 6. DISPLAY ---
            "disp_broken_dpi_and_size" -> {
                val fontScale = executor.readSetting(SettingNamespace.SYSTEM, "font_scale") ?: "1.0"
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Шрифт=$fontScale · Подключите ADB/Shizuku для проверки Override DPI и разрешения"
                } else {
                    val densityOut = executor.query(SystemReadQuery.Fixed.DISPLAY_DENSITY).requireReadOutput().stdoutText
                    val sizeOut = executor.query(SystemReadQuery.Fixed.DISPLAY_SIZE).requireReadOutput().stdoutText
                    val overDensity = parseWmOverride(densityOut)
                    val overSize = parseWmOverride(sizeOut)
                    if (overDensity != null || overSize != null || (fontScale != "1.0" && fontScale != "1")) {
                        DiagnosticVerdict.ISSUE_FOUND to "Переопределено: DPI=${overDensity ?: "родной"}, размер=${overSize ?: "родной"}, шрифт=$fontScale"
                    } else {
                        DiagnosticVerdict.HEALTHY to "Масштаб, разрешение и плотность пикселей строго заводские"
                    }
                }
            }
            "disp_grayscale_inversion_colors" -> {
                val daltonizer = executor.readSetting(SettingNamespace.SECURE, "accessibility_display_daltonizer_enabled")
                val inversion = executor.readSetting(SettingNamespace.SECURE, "accessibility_display_inversion_enabled")
                if (daltonizer == "1" || inversion == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "Активен цветовой фильтр! Дальтонайзер/монохром=${daltonizer ?: "0"}, инверсия=${inversion ?: "0"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Искажающие цветовые фильтры и монохром выключены"
                }
            }
            "disp_high_refresh_rate_unlock" -> {
                val peak = executor.readSetting(SettingNamespace.SYSTEM, "peak_refresh_rate")
                val minRate = executor.readSetting(SettingNamespace.SYSTEM, "min_refresh_rate")
                val peakNum = peak?.toFloatOrNull()
                if (peakNum != null && peakNum < 89f) {
                    DiagnosticVerdict.ISSUE_FOUND to "Пиковая частота ограничена: peak=${peak} Гц, min=${minRate ?: "авто"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Пороги частоты экрана: peak=${peak ?: "максимум матрицы"}, min=${minRate ?: "адаптивно"}"
                }
            }
            "disp_missing_statusbar_icons" -> {
                val blacklist = executor.readSetting(SettingNamespace.SECURE, "icon_blacklist")
                val policy = executor.readSetting(SettingNamespace.GLOBAL, "policy_control")
                if (!blacklist.isNullOrBlank() || !policy.isNullOrBlank()) {
                    DiagnosticVerdict.ISSUE_FOUND to "Скрыты индикаторы: blacklist=${blacklist ?: "нет"}, policy=${policy ?: "нет"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Все системные индикаторы строки состояния отображаются штатно"
                }
            }
            "disp_screen_timeout_sanity" -> {
                val timeoutMs = executor.readSetting(SettingNamespace.SYSTEM, "screen_off_timeout")?.toLongOrNull()
                val stayOn = executor.readSetting(SettingNamespace.GLOBAL, "stay_on_while_plugged_in")
                if ((timeoutMs != null && (timeoutMs < 20_000L || timeoutMs > 600_000L)) || (stayOn != null && stayOn != "0")) {
                    DiagnosticVerdict.ISSUE_FOUND to "Таймаут=${(timeoutMs ?: 60000L) / 1000} сек, не гасить на зарядке=${stayOn ?: "0"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Таймаут экрана ${(timeoutMs ?: 60000L) / 1000} сек, защита OLED в норме"
                }
            }
            "disp_zero_animator_app_freeze" -> {
                val anim = executor.readSetting(SettingNamespace.GLOBAL, "animator_duration_scale")
                if (anim == "0" || anim == "0.0") {
                    DiagnosticVerdict.ISSUE_FOUND to "animator_duration_scale = 0.0! Это вызывает вечную загрузку в банках и Compose-приложениях"
                } else {
                    DiagnosticVerdict.HEALTHY to "Безопасная шкала аниматора: ${anim ?: "1.0"}x"
                }
            }

            // --- 7. AUDIO ---
            "aud_bt_absolute_volume_low" -> {
                val disableAbs = executor.readSetting(SettingNamespace.GLOBAL, "bluetooth_disable_absolute_volume")
                val safeVol = executor.readSetting(SettingNamespace.GLOBAL, "audio_safe_volume_state")
                if (safeVol == "3" || safeVol == "2") {
                    DiagnosticVerdict.ISSUE_FOUND to "Активен лимитер громкости audio_safe_volume_state=$safeVol (abs_vol_disabled=${disableAbs ?: "0"})"
                } else {
                    DiagnosticVerdict.HEALTHY to "Ограничитель громкости не режет выход (abs_vol_disabled=${disableAbs ?: "0"})"
                }
            }
            "aud_stereo_balance_and_mono_bug" -> {
                val mono = executor.readSetting(SettingNamespace.SYSTEM, "master_mono")
                val balance = executor.readSetting(SettingNamespace.SYSTEM, "master_balance")?.toFloatOrNull() ?: 0f
                if (mono == "1" || kotlin.math.abs(balance) > 0.01f) {
                    DiagnosticVerdict.ISSUE_FOUND to "Сбит микшер! Моно=${mono ?: "0"}, баланс лево/право=$balance (должно быть 0.0)"
                } else {
                    DiagnosticVerdict.HEALTHY to "Чистое стерео (моно=0, баланс=0.0 по центру)"
                }
            }
            "aud_stuck_call_routing" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для инспекции AudioMode в dumpsys audio"
                } else {
                    val audioOut = executor.query(SystemReadQuery.Fixed.AUDIO).requireReadOutput().stdoutText
                    val stuckCall = audioOut.contains("MODE_IN_COMMUNICATION") || audioOut.contains("MODE_IN_CALL")
                    if (stuckCall) {
                        DiagnosticVerdict.ISSUE_FOUND to "Аудиосервер удерживает разговорный профиль MODE_IN_COMMUNICATION"
                    } else {
                        DiagnosticVerdict.HEALTHY to "Аудиомаршрутизация в штатном режиме MODE_NORMAL"
                    }
                }
            }
            "aud_touch_click_mixer_lag" -> {
                val clicks = executor.readSetting(SettingNamespace.SYSTEM, "sound_effects_enabled")
                if (clicks == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "Системные звуки каждого касания включены (пробуждают ЦАП на каждый тап)"
                } else {
                    DiagnosticVerdict.HEALTHY to "Лишние звуковые щелчки касаний выключены"
                }
            }

            // --- 8. INPUT ---
            "inp_triple_tap_magnification_lag" -> {
                val mag = executor.readSetting(SettingNamespace.SECURE, "accessibility_display_magnification_enabled")
                val magNav = executor.readSetting(SettingNamespace.SECURE, "accessibility_display_magnification_navbar_enabled")
                if (mag == "1" || magNav == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "Включена экранная лупа (mag=${mag ?: "0"}) — добавляет ~150 мс задержки каждому касанию!"
                } else {
                    DiagnosticVerdict.HEALTHY to "Перехватчик тройного тапа выключен — минимальный аппаратный input-lag"
                }
            }
            "inp_long_press_sluggish" -> {
                val timeout = executor.readSetting(SettingNamespace.SECURE, "long_press_timeout")?.toIntOrNull() ?: 400
                if (timeout > 420) {
                    DiagnosticVerdict.ISSUE_FOUND to "Замедленный отклик долгого нажатия: $timeout мс (оптимально 300–400 мс)"
                } else {
                    DiagnosticVerdict.HEALTHY to "Отклик удержания пальца: $timeout мс"
                }
            }
            "inp_ghost_touches_pointer_overlay" -> {
                val touches = executor.readSetting(SettingNamespace.SYSTEM, "show_touches")
                val pointer = executor.readSetting(SettingNamespace.SYSTEM, "pointer_location")
                if (touches == "1" || pointer == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "Включены отладочные кружки/полосы тача (touches=${touches ?: "0"}, pointer=${pointer ?: "0"})"
                } else {
                    DiagnosticVerdict.HEALTHY to "Визуальные отладочные оверлеи касаний выключены"
                }
            }
            "inp_autorotate_locked_sideways" -> {
                val autoRot = executor.readSetting(SettingNamespace.SYSTEM, "accelerometer_rotation")
                val userRot = executor.readSetting(SettingNamespace.SYSTEM, "user_rotation")
                if (autoRot == "0" && userRot != null && userRot != "0") {
                    DiagnosticVerdict.ISSUE_FOUND to "Экран заперт в повёрнутом положении (user_rotation=$userRot)"
                } else {
                    DiagnosticVerdict.HEALTHY to "Ориентация в норме (автоповорот=${autoRot ?: "1"})"
                }
            }

            // --- 9. GPS ---
            "gps_cold_lock_agps_aid" -> {
                val agps = executor.readSetting(SettingNamespace.GLOBAL, "assisted_gps_enabled")
                val locMode = executor.readSetting(SettingNamespace.SECURE, "location_mode")
                if (agps == "0" || locMode == "0" || locMode == "1") {
                    DiagnosticVerdict.ISSUE_FOUND to "A-GPS=${agps ?: "1"}, режим геолокации=${locMode ?: "3"}"
                } else {
                    DiagnosticVerdict.HEALTHY to "Помощь A-GPS и высокая точность спутников активны"
                }
            }
            "gps_mock_location_antifraud" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для сканирования AppOps MOCK_LOCATION"
                } else {
                    val out = executor.query(SystemReadQuery.MockLocations(executor.currentUserId())).requireReadOutput().stdoutText
                        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("No ") }.toList()
                    if (out.isNotEmpty()) {
                        DiagnosticVerdict.ISSUE_FOUND to "Пакеты с активным правом подмены GPS: ${out.joinToString()}"
                    } else {
                        DiagnosticVerdict.HEALTHY to "Активных пакетов с правом MOCK_LOCATION не обнаружено"
                    }
                }
            }
            "gps_background_track_gaps" -> {
                val interval = executor.readSetting(SettingNamespace.GLOBAL, "location_background_throttle_interval_ms")?.toLongOrNull()
                if (interval == null || interval > 300_000L) {
                    DiagnosticVerdict.ISSUE_FOUND to "Фоновый интервал GPS=${(interval ?: 1_800_000L) / 1000} сек — трекеры могут рвать маршрут"
                } else {
                    DiagnosticVerdict.HEALTHY to "Частый опрос GPS в фоне настроен (${interval / 1000} сек)"
                }
            }

            // --- 10. SECURITY ---
            "sec_clipboard_spy_guard" -> {
                if (currentTier.ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
                    DiagnosticVerdict.NEEDS_PRIVILEGE to "Подключите ADB/Shizuku для защиты AppOps READ_CLIPBOARD"
                } else {
                    val userApps = listThirdPartyPackages().take(15)
                    val defaultImePkg = executor.readSetting(SettingNamespace.SECURE, "default_input_method")
                        ?.substringBefore('/')
                    val allowedCount = userApps.count { pkg ->
                        pkg != defaultImePkg && executor.readAppOpMode(pkg, "READ_CLIPBOARD") == "allow"
                    }
                    if (allowedCount > 0) {
                        DiagnosticVerdict.ISSUE_FOUND to "Доступ к буферу обмена открыт у $allowedCount+ сторонних приложений"
                    } else {
                        DiagnosticVerdict.HEALTHY to "Clipboard Guard защищает буфер обмена от сторонних приложений"
                    }
                }
            }
            "sec_rogue_mitm_ca_audit" -> {
                val userCaDir = File("/data/misc/user/${executor.currentUserId()}/cacerts-added")
                val certs = userCaDir.listFiles()?.filter { it.isFile }
                DiagnosticVerdict.REVIEW_REQUIRED to if (certs == null) {
                    "Каталог сертификатов недоступен процессу приложения. Проверьте пользовательские сертификаты в системных настройках. Отсутствие сертификатов не подтверждено."
                } else {
                    "Файлов в доступном каталоге CA: ${certs.size}. Наличие сертификата само по себе не доказывает перехват трафика; нужна ручная проверка."
                }
            }
            "sec_open_adb_tcp_5555" -> {
                val port = executor.query(SystemReadQuery.Property("service.adb.tcp.port")).requireReadOutput().stdoutText.trim()
                if (port.isNotEmpty() && port != "-1" && port != "0") {
                    DiagnosticVerdict.ISSUE_FOUND to "ОПАСНО: Открыт незащищённый сетевой порт ADB TCP $port!"
                } else {
                    DiagnosticVerdict.HEALTHY to "Незащищённый порт ADB 5555 закрыт (${port.ifEmpty { "выкл" }})"
                }
            }
            "sec_accessibility_scroll_stutter_audit" -> {
                val enabled = executor.readSetting(SettingNamespace.SECURE, "enabled_accessibility_services")
                if (!enabled.isNullOrBlank() && enabled != "null") {
                    val count = enabled.split(':').count { it.isNotBlank() }
                    DiagnosticVerdict.REVIEW_REQUIRED to "Включено служб доступности: $count ($enabled). Это не признак неисправности. Автоматическое отключение запрещено."
                } else {
                    DiagnosticVerdict.REVIEW_REQUIRED to "Список служб не получен или пуст. Проверьте системные настройки; отсутствие служб не подтверждено."
                }
            }

            // --- 11. PERFORMANCE ---
            "perf_cached_apps_freezer" -> {
                val freezer = executor.readSetting(SettingNamespace.GLOBAL, "cached_apps_freezer")
                if (freezer == "enabled") {
                    DiagnosticVerdict.HEALTHY to "Ядерный замораживатель кэшированных процессов активен (enabled)"
                } else {
                    DiagnosticVerdict.ISSUE_FOUND to "Замораживатель свёрнутых процессов не форсирован (${freezer ?: "device_default"})"
                }
            }
            "perf_gpu_debug_overdraw_reset" -> {
                val overdraw = executor.query(SystemReadQuery.Property("debug.hwui.overdraw")).requireReadOutput().stdoutText.trim()
                if (overdraw.isNotEmpty() && overdraw != "false") {
                    DiagnosticVerdict.ISSUE_FOUND to "Активен стресс-оверлей отладки GPU: debug.hwui.overdraw=$overdraw"
                } else {
                    DiagnosticVerdict.HEALTHY to "Аппаратный рендерер GPU работает без отладочных оверлеев"
                }
            }
            "perf_kernel_sched_vm_tune" -> {
                val schedStats = executor.query(SystemReadQuery.Fixed.SCHEDSTATS).requireReadOutput().stdoutText.trim()
                if (schedStats == "0") {
                    DiagnosticVerdict.HEALTHY to "sched_schedstats=0 — накладные расходы статистики ядра уже сняты"
                } else {
                    DiagnosticVerdict.ISSUE_FOUND to "sched_schedstats=${schedStats.ifEmpty { "1" }} — доступен твик планировщика KTweak"
                }
            }

            // --- 12. OEM SHELLS ---
            "oem_xiaomi_msa_ads_cure" -> diagnoseOemBloat(
                listOf("com.miui.msa.global", "com.miui.analytics", "com.miui.systemAdSolution"),
            )
            "oem_samsung_gos_and_rubin" -> diagnoseOemBloat(
                listOf("com.samsung.android.game.gos", "com.samsung.android.rubin.app"),
            )
            "oem_bbk_heytap_spam_cure" -> diagnoseOemBloat(
                listOf("com.heytap.mcs", "com.oplus.statistics.rom"),
            )
            "oem_transsion_phonemaster_spam" -> diagnoseOemBloat(
                listOf("com.transsion.phonemaster", "com.transsion.aha.games", "com.palm.store"),
            )

            else -> DiagnosticVerdict.HEALTHY to "Готово к проверке"
        }
    }

    /**
     * Executes the automated repair for [playbook], persisting a reversible snapshot in
     * [StateVaultManager] whenever [FixPlaybook.supportsVaultRollback] is true.
     */
    suspend fun applyFix(playbook: FixPlaybook): FixExecutionOutcome = withContext(Dispatchers.IO) {
        // Enforced below the UI, before even checking privileges or issuing any command.
        FixerSafetyPolicy.automaticFixBlockReason(playbook.id)?.let { reason ->
            return@withContext FixExecutionOutcome.Failed(reason)
        }
        if (executor.currentTier().ordinal < playbook.requiredTier.ordinal) {
            return@withContext FixExecutionOutcome.Failed(
                "Требуется уровень прав «${playbook.requiredTier.titleRu}». Откройте переключатель прав на Главной.",
            )
        }

        val maintenanceCommands = when (playbook.id) {
            "net_soft_stack_reset" -> listOf(
                "svc wifi disable && sleep 1 && svc wifi enable",
                "cmd connectivity airplane-mode enable && sleep 1 && cmd connectivity airplane-mode disable",
            )
            "bat_mediascanner_cpu_loop" -> listOf("am force-stop com.android.providers.media.module", "am force-stop com.android.providers.media")
            "bat_fuel_gauge_stats_reset" -> listOf("dumpsys batterystats --reset")
            "stor_ufs_fstrim_lag" -> listOf("sm fstrim")
            "stor_gallery_mediastore_desync" -> listOf("DCIM", "Pictures", "Download").map {
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/$it"
            }
            "pkg_app_links_reverify" -> listOf("pm verify-app-links --re-verify")
            "pkg_post_ota_art_compile" -> listOf("cmd package bg-dexopt-job")
            "aud_stuck_call_routing" -> listOf("input keyevent 86")
            else -> null
        }
        if (maintenanceCommands != null) {
            var completed = 0
            for (command in maintenanceCommands) {
                val result = executor.execute(command)
                if (!result.isSuccess) {
                    return@withContext FixExecutionOutcome.Failed(
                        "Обслуживание не завершено (команд завершено: $completed). " +
                            result.stderrText.ifBlank { "Код ${result.exitCode}" } +
                            ". Предыдущие команды могли изменить состояние; автоматического отката нет.",
                    )
                }
                completed++
            }
            return@withContext FixExecutionOutcome.AppliedMaintenance(
                "Команды обслуживания завершились без ошибки ($completed). Исправление исходного симптома не подтверждено; автоматического отката нет.",
            )
        }

        // --- Vault-backed reversible fixes ---
        val mutations = buildVaultMutationsForPlaybook(playbook)
        if (mutations.isEmpty()) {
            return@withContext FixExecutionOutcome.Failed("Нет доступных изменений по этому сценарию. Состояние не менялось; исправление проблемы не подтверждено.")
        }

        when (
            val tx = vaultManager.applyTransaction(
                title = "Фикс: ${playbook.symptomTitle.take(64)}",
                reason = playbook.fixActionSummary,
                source = FeatureSource.FIXER,
                mutations = mutations,
            )
        ) {
            is VaultTransactionResult.Success -> FixExecutionOutcome.AppliedWithVault(
                snapshotId = tx.snapshotId,
                messageRu = "Изменения записаны и проверены (${tx.appliedCount} опер.) · журнал #${tx.snapshotId}. Проверьте исходный симптом.",
            )
            is VaultTransactionResult.Failure -> FixExecutionOutcome.Failed(tx.errorMessage)
        }
    }

    private suspend fun buildVaultMutationsForPlaybook(playbook: FixPlaybook): List<VaultMutationSpec> =
        when (playbook.id) {
            "net_captive_portal_mirrors" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "captive_portal_mode", "1"),
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "captive_portal_https_url",
                    "https://cp.cloudflare.com/generate_204",
                ),
                VaultMutationSpec.MutateSetting(
                    SettingNamespace.GLOBAL,
                    "captive_portal_http_url",
                    "http://connectivitycheck.platform.hicloud.com/generate_204",
                ),
            )
            "net_private_dns_portal_conflict" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "opportunistic"),
            )
            "net_wifi_scan_ping_spikes" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled", "1"),
            )
            "net_seamless_wifi_lte_handoff" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "mobile_data_always_on", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "network_avoid_bad_wifi", "1"),
            )
            "net_hotspot_tether_dun" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "tether_dun_required", "0"),
            )
            "notif_heads_up_banners_missing" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "heads_up_notifications_enabled", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "zen_mode", "0"),
            )
            "notif_lockscreen_hidden" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "lock_screen_show_notifications", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "lock_screen_allow_private_notifications", "1"),
            )
            "notif_fcm_push_delayed_batch" -> listOf(
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "dumpsys deviceidle whitelist +com.google.android.gms",
                    revertCommand = "dumpsys deviceidle whitelist -com.google.android.gms",
                    summaryRu = "Белый список Doze для транспорта FCM (com.google.android.gms)",
                ),
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "am set-standby-bucket com.google.android.gms active",
                    revertCommand = "am set-standby-bucket com.google.android.gms working_set",
                    summaryRu = "Корзина активности GMS → active",
                ),
            )
            "notif_phantom_process_killer" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", "false"),
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "cmd device_config put activity_manager max_phantom_processes 2147483647",
                    revertCommand = "cmd device_config delete activity_manager max_phantom_processes",
                    summaryRu = "device_config activity_manager:max_phantom_processes → без лимита",
                ),
            )
            "notif_snooze_and_channels_aid" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "show_notification_snooze", "1"),
            )
            "bat_deep_doze_disabled" -> listOf(
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "dumpsys deviceidle enable",
                    revertCommand = "dumpsys deviceidle enable",
                    summaryRu = "Реактивация глубокого сна Android Deep Doze",
                ),
            )
            "bat_hidden_radio_scanners" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", "0"),
            )
            "bat_error_telemetry_wakelocks" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "send_action_app_error", "0"),
            )
            "stor_logd_ring_buffer_trim" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "logger_buffer_size", "256K"),
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "logcat -c",
                    revertCommand = "true",
                    summaryRu = "Очистка кольцевого буфера logcat",
                ),
            )
            "pkg_webview_crash_rescue" -> {
                listOf("com.google.android.webview", "com.android.webview", "com.android.chrome")
                    .filter { isPackagePresent(it) }
                    .map { VaultMutationSpec.MutatePackageState(it, PackageStateTarget.ENABLED) }
            }
            "pkg_download_manager_stuck" -> {
                listOf("com.android.providers.downloads", "com.android.providers.downloads.ui")
                    .filter { isPackagePresent(it) }
                    .map { VaultMutationSpec.MutatePackageState(it, PackageStateTarget.ENABLED) }
            }
            "disp_broken_dpi_and_size" -> {
                val prevDensity = parseWmOverride(executor.query(SystemReadQuery.Fixed.DISPLAY_DENSITY).requireReadOutput().stdoutText)
                val prevSize = parseWmOverride(executor.query(SystemReadQuery.Fixed.DISPLAY_SIZE).requireReadOutput().stdoutText)
                buildList {
                    add(
                        VaultMutationSpec.MutateCustomShell(
                            applyCommand = "wm density reset",
                            revertCommand = if (prevDensity != null) "wm density $prevDensity" else "wm density reset",
                            summaryRu = "Сброс плотности экрана wm density (${prevDensity ?: "родной"} → заводской)",
                        ),
                    )
                    add(
                        VaultMutationSpec.MutateCustomShell(
                            applyCommand = "wm size reset",
                            revertCommand = if (prevSize != null) "wm size $prevSize" else "wm size reset",
                            summaryRu = "Сброс разрешения wm size (${prevSize ?: "родное"} → заводское)",
                        ),
                    )
                    add(VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "font_scale", "1.0"))
                }
            }
            "disp_grayscale_inversion_colors" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_daltonizer_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_inversion_enabled", "0"),
            )
            "disp_high_refresh_rate_unlock" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "peak_refresh_rate", "120.0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "min_refresh_rate", "60.0"),
            )
            "disp_missing_statusbar_icons" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "icon_blacklist", null),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "policy_control", null),
            )
            "disp_screen_timeout_sanity" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "60000"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "stay_on_while_plugged_in", "0"),
            )
            "disp_zero_animator_app_freeze" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.25"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", "0.25"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.25"),
            )
            "aud_bt_absolute_volume_low" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "bluetooth_disable_absolute_volume", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "audio_safe_volume_state", "1"),
            )
            "aud_stereo_balance_and_mono_bug" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "master_mono", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "master_balance", "0.0"),
            )
            "aud_touch_click_mixer_lag" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "sound_effects_enabled", "0"),
            )
            "inp_triple_tap_magnification_lag" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_magnification_enabled", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "accessibility_display_magnification_navbar_enabled", "0"),
            )
            "inp_long_press_sluggish" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "long_press_timeout", "300"),
            )
            "inp_ghost_touches_pointer_overlay" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "show_touches", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "pointer_location", "0"),
            )
            "inp_autorotate_locked_sideways" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "user_rotation", "0"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SYSTEM, "accelerometer_rotation", "1"),
            )
            "gps_cold_lock_agps_aid" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "assisted_gps_enabled", "1"),
                VaultMutationSpec.MutateSetting(SettingNamespace.SECURE, "location_mode", "3"),
            )
            "gps_mock_location_antifraud" -> {
                val pkgs = executor.query(SystemReadQuery.MockLocations(executor.currentUserId())).requireReadOutput().stdoutText
                    .lineSequence()
                    .map { it.trim() }
                    .filter { SAFE_PKG_REGEX.matches(it) }
                    .toList()
                pkgs.map { VaultMutationSpec.MutateAppOp(it, "MOCK_LOCATION", "deny") }
            }
            "gps_background_track_gaps" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "location_background_throttle_interval_ms", "60000"),
            )
            "sec_clipboard_spy_guard" -> {
                val defaultImePkg = executor.readSetting(SettingNamespace.SECURE, "default_input_method")
                    ?.substringBefore('/')
                listThirdPartyPackages()
                    .filter { it != defaultImePkg && it != context.packageName }
                    .take(40)
                    .map { VaultMutationSpec.MutateAppOp(it, "READ_CLIPBOARD", "ignore") }
            }
            "sec_open_adb_tcp_5555" -> {
                val rawPort = executor.query(SystemReadQuery.Property("service.adb.tcp.port")).requireReadOutput().stdoutText.trim()
                val prev = rawPort.toIntOrNull()?.coerceIn(-1, 65535) ?: -1
                listOf(
                    VaultMutationSpec.MutateCustomShell(
                        applyCommand = "setprop service.adb.tcp.port -1",
                        revertCommand = "setprop service.adb.tcp.port $prev",
                        summaryRu = "Закрытие открытого TCP-порта ADB ($prev → -1)",
                    ),
                )
            }
            "perf_cached_apps_freezer" -> listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "cached_apps_freezer", "enabled"),
            )
            "perf_gpu_debug_overdraw_reset" -> listOf(
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = "setprop debug.hwui.overdraw false",
                    revertCommand = "setprop debug.hwui.overdraw false",
                    summaryRu = "Сброс отладочного оверлея GPU debug.hwui.overdraw → false",
                ),
            )
            "perf_kernel_sched_vm_tune" -> {
                val prevStats = executor.query(SystemReadQuery.Fixed.SCHEDSTATS).requireReadOutput().stdoutText.trim().toIntOrNull()?.coerceIn(0, 1) ?: 1
                val prevDirty = executor.query(SystemReadQuery.Fixed.DIRTY_RATIO).requireReadOutput().stdoutText.trim().toIntOrNull()?.coerceIn(5, 90) ?: 20
                val prevBgDirty = executor.query(SystemReadQuery.Fixed.DIRTY_BACKGROUND_RATIO).requireReadOutput().stdoutText.trim().toIntOrNull()?.coerceIn(1, 50) ?: 10
                listOf(
                    VaultMutationSpec.MutateCustomShell(
                        applyCommand = "echo 0 > /proc/sys/kernel/sched_schedstats",
                        revertCommand = "echo $prevStats > /proc/sys/kernel/sched_schedstats",
                        summaryRu = "kernel.sched_schedstats ($prevStats → 0)",
                    ),
                    VaultMutationSpec.MutateCustomShell(
                        applyCommand = "echo 30 > /proc/sys/vm/dirty_ratio && echo 10 > /proc/sys/vm/dirty_background_ratio",
                        revertCommand = "echo $prevDirty > /proc/sys/vm/dirty_ratio && echo $prevBgDirty > /proc/sys/vm/dirty_background_ratio",
                        summaryRu = "vm.dirty_ratio ($prevDirty → 30) & dirty_background_ratio ($prevBgDirty → 10)",
                    ),
                )
            }
            "oem_xiaomi_msa_ads_cure" -> buildOemFreezeMutations(
                listOf("com.miui.msa.global", "com.miui.analytics", "com.miui.systemAdSolution", "com.miui.daemon"),
            )
            "oem_samsung_gos_and_rubin" -> buildOemFreezeMutations(
                listOf("com.samsung.android.game.gos", "com.samsung.android.rubin.app"),
            )
            "oem_bbk_heytap_spam_cure" -> buildOemFreezeMutations(
                listOf("com.heytap.mcs", "com.oplus.statistics.rom"),
            )
            "oem_transsion_phonemaster_spam" -> buildOemFreezeMutations(
                listOf("com.transsion.phonemaster", "com.transsion.aha.games", "com.palm.store"),
            )
            else -> emptyList()
        }

    // =========================================================================
    // 5-Point Interactive App Notification Rescuer
    // =========================================================================

    suspend fun loadUserAppsForRescue(): List<InstalledAppBrief> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                (!isSystem || isUpdatedSystem) && app.packageName != context.packageName
            }
            .map { app ->
                InstalledAppBrief(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun inspectAppNotifications(app: InstalledAppBrief): AppRescueAuditResult = withContext(Dispatchers.IO) {
        val pkg = app.packageName
        val whitelistOut = executor.query(SystemReadQuery.Fixed.IDLE_WHITELIST).requireReadOutput().stdoutText
        val inWhitelist = whitelistOut.contains(pkg)
        val bucketRaw = executor.query(SystemReadQuery.StandbyBucket(executor.currentUserId(), pkg)).requireReadOutput().stdoutText.trim().ifEmpty { "active" }
        val runBg = executor.readAppOpMode(pkg, "RUN_IN_BACKGROUND")
        val runAnyBg = executor.readAppOpMode(pkg, "RUN_ANY_IN_BACKGROUND")
        val postNotif = executor.readAppOpMode(pkg, "POST_NOTIFICATION")
        val pkgState = executor.readPackageState(pkg)

        AppRescueAuditResult(
            packageName = pkg,
            appLabel = app.label,
            inDozeWhitelist = inWhitelist,
            standbyBucket = bucketRaw,
            runInBackgroundMode = runBg,
            runAnyInBackgroundMode = runAnyBg,
            postNotificationMode = postNotif,
            isSuspendedOrDisabled = pkgState != PackageStateTarget.ENABLED,
        )
    }

    suspend fun rescueAppNotifications(audit: AppRescueAuditResult): FixExecutionOutcome = withContext(Dispatchers.IO) {
        val pkg = audit.packageName
        if (!SAFE_PKG_REGEX.matches(pkg)) {
            return@withContext FixExecutionOutcome.Failed("Некорректное имя пакета: $pkg")
        }
        if (executor.currentTier().ordinal < PrivilegeTier.ADB_SHIZUKU.ordinal) {
            return@withContext FixExecutionOutcome.Failed("Подключите Беспроводной ADB / Shizuku для снятия фоновых ограничений")
        }

        val mutations = listOf(
            VaultMutationSpec.MutatePackageState(pkg, PackageStateTarget.ENABLED),
            VaultMutationSpec.MutateAppOp(pkg, "RUN_IN_BACKGROUND", "allow"),
            VaultMutationSpec.MutateAppOp(pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            VaultMutationSpec.MutateAppOp(pkg, "WAKE_LOCK", "allow"),
            VaultMutationSpec.MutateAppOp(pkg, "POST_NOTIFICATION", "allow"),
            VaultMutationSpec.MutateCustomShell(
                applyCommand = "dumpsys deviceidle whitelist +$pkg",
                revertCommand = if (audit.inDozeWhitelist) "true" else "dumpsys deviceidle whitelist -$pkg",
                summaryRu = "Исключение из экономии заряда Doze для ${audit.appLabel}",
            ),
            VaultMutationSpec.MutateCustomShell(
                applyCommand = "am set-standby-bucket $pkg active",
                revertCommand = "am set-standby-bucket $pkg working_set",
                summaryRu = "Корзина активности ${audit.appLabel} → active",
            ),
        )

        when (
            val tx = vaultManager.applyTransaction(
                title = "Реанимация пушей: ${audit.appLabel}",
                reason = "Снятие всех 5 уровней фоновых блокировок (Doze, Standby, AppOps) для $pkg",
                source = FeatureSource.FIXER,
                mutations = mutations,
            )
        ) {
            is VaultTransactionResult.Success -> FixExecutionOutcome.AppliedWithVault(
                snapshotId = tx.snapshotId,
                messageRu = "Уведомления и фон «${audit.appLabel}» полностью разблокированы (точка отката #${tx.snapshotId})",
            )
            is VaultTransactionResult.Failure -> FixExecutionOutcome.Failed(tx.errorMessage)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun diagnoseOemBloat(packages: List<String>): Pair<DiagnosticVerdict, String> {
        val present = packages.filter { isPackagePresent(it) }
        if (present.isEmpty()) {
            return DiagnosticVerdict.HEALTHY to "Рекламные пакеты оболочки отсутствуют в прошивке"
        }
        val activeOnes = present.filter { executor.readPackageState(it) == PackageStateTarget.ENABLED }
        return if (activeOnes.isNotEmpty()) {
            DiagnosticVerdict.ISSUE_FOUND to "Активны рекламные службы: ${activeOnes.joinToString()}"
        } else {
            DiagnosticVerdict.HEALTHY to "Все рекламные службы уже обезврежены (${present.size} шт.)"
        }
    }

    private fun buildOemFreezeMutations(packages: List<String>): List<VaultMutationSpec> =
        packages.filter { isPackagePresent(it) }.map { pkg ->
            VaultMutationSpec.MutatePackageState(pkg, PackageStateTarget.SUSPENDED)
        }

    private fun isPackagePresent(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        true
    } catch (_: Exception) {
        false
    }

    private fun listThirdPartyPackages(): List<String> =
        context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { SAFE_PKG_REGEX.matches(it) }
            .toList()

    companion object {
        private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9._]+$")

        /**
         * Pure parser: finds "ghost" packages left behind with `DELETE_KEEP_DATA` (`pm list packages -u` minus `pm list packages`).
         */
        fun parseGhostUninstalledPackages(
            allPackagesOutput: String,
            installedPackagesOutput: String,
        ): List<String> {
            fun extractSet(raw: String): Set<String> = raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { SAFE_PKG_REGEX.matches(it) }
                .toSet()

            val allWithUninstalled = extractSet(allPackagesOutput)
            val activeInstalled = extractSet(installedPackagesOutput)
            return (allWithUninstalled - activeInstalled).sorted()
        }

        /**
         * Pure parser: extracts `Override density: 520` or `Override size: 1080x2400` from `wm` CLI output.
         */
        fun parseWmOverride(wmOutput: String): String? =
            wmOutput.lineSequence()
                .firstOrNull { it.contains("Override", ignoreCase = true) }
                ?.substringAfter(':', "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && Regex("^[0-9x.]+$").matches(it) }

        /**
         * Pure parser: extracts numeric session IDs from `pm list staging-sessions`.
         */
        fun parseStagingSessionIds(stagingOutput: String): List<String> =
            Regex("\\bsessionId\\s*=\\s*(\\d+)|^\\s*(\\d+)\\s*:", RegexOption.MULTILINE)
                .findAll(stagingOutput)
                .mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotEmpty() } }
                .distinct()
                .toList()
    }
}
