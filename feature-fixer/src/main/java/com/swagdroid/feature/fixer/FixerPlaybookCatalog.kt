package com.swagdroid.feature.fixer

import com.swagdroid.api.PrivilegeTier
import com.swagdroid.core.rollback.ReversibilityType

/**
 * Стартовый каталог из 12 разнотипных эталонных рецептов Программного Фиксера (`FixerPlaybookCatalog`),
 * проверяющих все механизмы движка: локализацию по слоям, Write-Ahead журнал, конфликт-чекер,
 * отложенную верификацию и честную эскалацию к оператору/железу.
 */
object FixerPlaybookCatalog {

    val CORE_12_PLAYBOOKS: List<FixPlaybookRecipe> = listOf(
        // 1. Уведомления
        FixPlaybookRecipe(
            recipeId = "fix_01_delayed_push_notifications",
            symptomTitleRu = "1. Не приходят или опаздывают пуш-уведомления (Мессенджеры и Банки)",
            symptomDescriptionRu = "Пошагово проверяет цепочку: разрешение и канал уведомлений -> DND -> force-stop -> Standby Bucket и Doze Whitelist.",
            primaryLayer = AndroidSystemLayer.SYSTEM_SERVICES,
            readerId = TypedDiagnosticReaderId.READ_NOTIFICATION_DELIVERY_CHAIN,
            actionId = TypedFixActionId.ACTION_WHITELIST_DOZE_AND_OPEN_UNRESTRICTED_BATTERY,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 20, // Отложенная проверка при выключенном экране через 15-30 минут
            sideEffectsWarningRu = "Выбранное приложение сможет пробуждать сеть в режиме ожидания (небольшое увеличение расхода батареи ради мгновенных пушей).",
        ),

        // 2. Ночной расход батареи (Wakelock Hunter)
        FixPlaybookRecipe(
            recipeId = "fix_02_night_wakelock_battery_drain",
            symptomTitleRu = "2. Сильный разряд батареи за ночь (Wakelock & BatteryStats Delta)",
            symptomDescriptionRu = "Сравнивает дельту BatteryStats и удержание PARTIAL_WAKE_LOCK за период с выключенным экраном.",
            primaryLayer = AndroidSystemLayer.SYSTEM_SERVICES,
            readerId = TypedDiagnosticReaderId.READ_SCREEN_OFF_BATTERY_DELTA_AND_WAKELOCKS,
            actionId = TypedFixActionId.ACTION_FORCE_STOP_OR_RESTRICT_WAKELOCK_CULPRIT,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 60,
            sideEffectsWarningRu = "Сначала предлагается ограничение фона или остановка процесса. Жёсткий запрет WAKE_LOCK ignore применяется только вручную, так как может прервать фоновые загрузки или звонки.",
        ),

        // 3. Wi-Fi «Подключено, без доступа к интернету»
        FixPlaybookRecipe(
            recipeId = "fix_03_wifi_connected_no_internet",
            symptomTitleRu = "3. Wi-Fi пишет «Подключено, без доступа к интернету» при рабочей сети",
            symptomDescriptionRu = "Отличает настоящий портал авторизации отеля/аэропорта от блокировки серверов проверки connectivitycheck и сбоя Private DNS / даты TLS.",
            primaryLayer = AndroidSystemLayer.POLICIES_AND_STATE_DB,
            readerId = TypedDiagnosticReaderId.READ_WIFI_CAPTIVE_PORTAL_AND_DNS_PROBES,
            actionId = TypedFixActionId.ACTION_SWITCH_CAPTIVE_PORTAL_ENDPOINTS_OR_RECONNECT,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Переключает captive_portal_https_url только при доказанной недоступности стандартного probe-сервера при живом интернете.",
        ),

        // 4. SIM-карта не определяется
        FixPlaybookRecipe(
            recipeId = "fix_04_sim_not_detected_diagnosis",
            symptomTitleRu = "4. SIM-карта не определяется телефоном",
            symptomDescriptionRu = "Сравнивает состояние слотов 1 и 2 до и после перестановки SIM пользователем.",
            primaryLayer = AndroidSystemLayer.HARDWARE_KERNEL_HAL_MODEM,
            readerId = TypedDiagnosticReaderId.READ_SIM_SLOTS_AND_RADIO_STATE,
            actionId = TypedFixActionId.ACTION_ESCALATE_TO_HARDWARE_OR_CARRIER_NO_FAKE_FIX,
            minPrivilege = PrivilegeTier.NONE,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Только честная диагностика без бесполезных системных правок.",
            hardwareOrCarrierEscalationNoteRu = "Если SIM не видна ни в одном слоте — требуется замена SIM у оператора; если не работает конкретный слот — диагностика SIM-лотка в сервисе.",
        ),

        // 5. Голос есть, мобильного интернета нет (APN / Default Data Sub)
        FixPlaybookRecipe(
            recipeId = "fix_05_voice_ok_mobile_data_missing",
            symptomTitleRu = "5. Звонки проходят, но мобильный интернет не работает",
            symptomDescriptionRu = "Проверяет выбранную SIM для передачи данных, лимиты трафика, Data Saver и корректность точки доступа APN.",
            primaryLayer = AndroidSystemLayer.POLICIES_AND_STATE_DB,
            readerId = TypedDiagnosticReaderId.READ_MOBILE_DATA_SUBSCRIPTION_AND_APN,
            actionId = TypedFixActionId.ACTION_RESTORE_DEFAULT_DATA_APN_WITH_SNAPSHOT,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 1,
            sideEffectsWarningRu = "Перед переключением точки доступа сохраняется ID текущего APN для мгновенного возврата.",
        ),

        // 6. Диагностика VoLTE / VoWiFi
        FixPlaybookRecipe(
            recipeId = "fix_06_volte_vowifi_ims_check",
            symptomTitleRu = "6. Не работает VoLTE или звонки по Wi-Fi (VoWiFi)",
            symptomDescriptionRu = "Проверяет системный тумблер, CarrierConfig и фактический статус регистрации в подсистеме IMS.",
            primaryLayer = AndroidSystemLayer.SYSTEM_SERVICES,
            readerId = TypedDiagnosticReaderId.READ_IMS_VOLTE_VOWIFI_STATE,
            actionId = TypedFixActionId.ACTION_OPEN_IMS_PROVISIONING_SETTINGS,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 2,
            sideEffectsWarningRu = "Не переписывает скрытые модемные build.prop ключи во избежание потери радиосвязи.",
            hardwareOrCarrierEscalationNoteRu = "Если тумблеры активны, но IMS не регистрируется — требуется активация услуги VoLTE на стороне оператора.",
        ),

        // 7. Тихий звук или скачки громкости в Bluetooth-наушниках
        FixPlaybookRecipe(
            recipeId = "fix_07_bluetooth_absolute_volume",
            symptomTitleRu = "7. Слишком тихий звук или резкий шаг громкости в Bluetooth-наушниках",
            symptomDescriptionRu = "Управляет флагом bluetooth_disable_absolute_volume (разделение громкости телефона и наушников).",
            primaryLayer = AndroidSystemLayer.POLICIES_AND_STATE_DB,
            readerId = TypedDiagnosticReaderId.READ_BLUETOOTH_ABSOLUTE_VOLUME_STATE,
            actionId = TypedFixActionId.ACTION_TOGGLE_BLUETOOTH_DISABLE_ABSOLUTE_VOLUME,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 1,
            sideEffectsWarningRu = "ВНИМАНИЕ: После переключения появятся два независимых регулятора громкости (на телефоне и на наушниках). Перед проверкой убавьте громкость и переподключите наушники!",
        ),

        // 8. Пост-OTA оптимизация ART (speed-profile)
        FixPlaybookRecipe(
            recipeId = "fix_08_post_ota_art_speed_profile",
            symptomTitleRu = "8. Долгий запуск приложений и фризы после обновления прошивки (OTA)",
            symptomDescriptionRu = "Проверяет долю пакетов в неоптимизированном состоянии verify/quicken и запускает компиляцию по профилям.",
            primaryLayer = AndroidSystemLayer.APP_AND_DATA,
            readerId = TypedDiagnosticReaderId.READ_POST_OTA_ART_PACKAGE_COMPILE_STATES,
            actionId = TypedFixActionId.ACTION_RUN_CONDITIONAL_SPEED_PROFILE_DEXOPT,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.PARTIALLY_REVERSIBLE,
            verificationWindowMinutes = 5,
            sideEffectsWarningRu = "Запускается только при подключённой зарядке и нормальной температуре. Временно нагревает устройство и немного увеличивает размер скомпилированного кода.",
        ),

        // 9. Ошибка установки APK между пользователями (UPDATE_INCOMPATIBLE)
        FixPlaybookRecipe(
            recipeId = "fix_09_apk_install_conflict_multiuser",
            symptomTitleRu = "9. Приложение не устанавливается («Пакет несовместим» или остатки во втором профиле)",
            symptomDescriptionRu = "Проверяет все профили пользователей (0, 10, 999) на наличие остатков пакета с флагом DELETE_KEEP_DATA или другим сертификатом.",
            primaryLayer = AndroidSystemLayer.SYSTEM_SERVICES,
            readerId = TypedDiagnosticReaderId.READ_MULTIUSER_PACKAGE_INSTALL_CONFLICTS,
            actionId = TypedFixActionId.ACTION_RESOLVE_MULTIUSER_INSTALL_CONFLICT,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.PARTIALLY_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Удаление конфликтного остатка со старой подписью удалит сохранённые данные этого пакета у второго профиля (требует явного подтверждения).",
        ),

        // 10. Неверное время и ошибки сертификатов TLS
        FixPlaybookRecipe(
            recipeId = "fix_10_wrong_clock_tls_errors",
            symptomTitleRu = "10. Ошибки SSL/TLS в браузере, не работает Play Store и банковские приложения",
            symptomDescriptionRu = "Проверяет рассинхронизацию системных часов и отключённые флаги auto_time / auto_time_zone.",
            primaryLayer = AndroidSystemLayer.POLICIES_AND_STATE_DB,
            readerId = TypedDiagnosticReaderId.READ_AUTO_TIME_AND_TLS_CLOCK_SKEW,
            actionId = TypedFixActionId.ACTION_ENABLE_AUTO_TIME_AND_TIMEZONE,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Включает автоматическую синхронизацию даты и часового пояса по сети.",
        ),

        // 11. Глобальные тумблеры приватности камеры, микрофона и геолокации
        FixPlaybookRecipe(
            recipeId = "fix_11_camera_mic_location_blocked",
            symptomTitleRu = "11. Приложения не видят камеру, микрофон или долго ищут геопозицию в помещении",
            symptomDescriptionRu = "Проверяет глобальные аппаратные выключатели SensorPrivacyManager (Android 12+) и отключённое Wi-Fi/BLE сканирование геолокации.",
            primaryLayer = AndroidSystemLayer.POLICIES_AND_STATE_DB,
            readerId = TypedDiagnosticReaderId.READ_SENSOR_PRIVACY_AND_LOCATION_SCAN_STATE,
            actionId = TypedFixActionId.ACTION_UNBLOCK_CAMERA_MIC_LOCATION_TOGGLES,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Восстанавливает доступ к датчикам и точности геолокации.",
        ),

        // 12. Рабочий профиль (Work Profile) поставлен на паузу
        FixPlaybookRecipe(
            recipeId = "fix_12_work_profile_quiet_mode_paused",
            symptomTitleRu = "12. Перестали запускаться рабочие приложения и клоны (серые иконки)",
            symptomDescriptionRu = "Проверяет, не переведён ли дополнительный профиль пользователя (Work Profile / User 10) в режим паузы (Quiet Mode).",
            primaryLayer = AndroidSystemLayer.SYSTEM_SERVICES,
            readerId = TypedDiagnosticReaderId.READ_WORK_PROFILE_QUIET_MODE_STATE,
            actionId = TypedFixActionId.ACTION_UNPAUSE_WORK_PROFILE_USER,
            minPrivilege = PrivilegeTier.ADB_SHELL,
            reversibility = ReversibilityType.EXACT_REVERSIBLE,
            verificationWindowMinutes = 0,
            sideEffectsWarningRu = "Снимает рабочий профиль с паузы и возобновляет доставку уведомлений в нём.",
        ),
    )
}
