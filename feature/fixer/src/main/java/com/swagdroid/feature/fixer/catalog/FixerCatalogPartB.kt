package com.swagdroid.feature.fixer.catalog

import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RiskLevel
import com.swagdroid.feature.fixer.model.FixPlaybook
import com.swagdroid.feature.fixer.model.PhoneSubsystem

object FixerCatalogPartB {

    val playbooks: List<FixPlaybook> = listOf(
        // =========================================================================
        // 7. AUDIO: Динамики, Наушники и Bluetooth-аудио (4 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "aud_bt_absolute_volume_low",
            subsystem = PhoneSubsystem.AUDIO,
            symptomTitle = "В Bluetooth-наушниках или колонке звук на максимуме играет вдвое тише нормы",
            rootCauseExplanation = "Конфликт протокола Bluetooth AVRCP Absolute Volume между телефоном и прошивкой наушников приводит к двойному программному занижению усиления, а ограничитель audio_safe_volume_state режет порог громкости.",
            fixActionSummary = "Отключает конфликтную связку абсолютной громкости Bluetooth и сбрасывает залипший ограничитель мощности.",
            searchKeywords = listOf("тихий звук", "тихие наушники", "bluetooth громкость", "аирподс тихо", "колонка тихо", "absolute volume"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "aud_stereo_balance_and_mono_bug",
            subsystem = PhoneSubsystem.AUDIO,
            symptomTitle = "Один наушник играет тише другого ИЛИ пропал объёмный стереозвук в играх и музыке",
            rootCauseExplanation = "В системном микшере случайно сдвинут ползунок баланса каналов (System:master_balance != 0.0) или включён режим сведения в один канал (master_mono = 1) — частая причина ложных обращений в сервис по «поломке наушника»!",
            fixActionSummary = "Возвращает идеальный центр стереобаланса (master_balance = 0.0) и полноценное двухканальное стерео (master_mono = 0).",
            searchKeywords = listOf("один наушник тише", "левый тише", "правый тише", "нет стерео", "моно звук", "баланс звука"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "aud_stuck_call_routing",
            subsystem = PhoneSubsystem.AUDIO,
            symptomTitle = "Пропал звук мультимедиа или собеседника после отключения гарнитуры / автомагнитолы",
            rootCauseExplanation = "Аудиосервер зависает в состоянии MODE_IN_COMMUNICATION или удерживает маршрутизацию на уже отключённом профиле гарнитуры.",
            fixActionSummary = "Освобождает залипший аудиофокус и перезапускает маршрутизацию медиасессий без перезагрузки смартфона.",
            searchKeywords = listOf("пропал звук", "не слышно собеседника", "залипли наушники", "значок наушников", "после машины нет звука"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
            supportsVaultRollback = false,
        ),
        FixPlaybook(
            id = "aud_touch_click_mixer_lag",
            subsystem = PhoneSubsystem.AUDIO,
            symptomTitle = "Микрозадержки при быстром наборе текста и лишнее пробуждение аудиокодека от системных щелчков",
            rootCauseExplanation = "Включённые системные щелчки касаний (sound_effects_enabled = 1) заставляют аудиомикшер пробуждать ЦАП на каждое касание экрана.",
            fixActionSummary = "Безопасно отключает звуковые щелчки касаний интерфейса с сохранением точки отката.",
            searchKeywords = listOf("щелчки экрана", "звук касания", "тормозит клавиатура", "звук блокировки"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),

        // =========================================================================
        // 8. INPUT: Сенсор экрана, Жесты, Ввод и Автоповорот (4 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "inp_triple_tap_magnification_lag",
            subsystem = PhoneSubsystem.INPUT,
            symptomTitle = "Задержка отклика тачскрина (~150 мс на каждое одиночное нажатие в системе и шутерах!)",
            rootCauseExplanation = "Когда включено увеличение экрана по тройному касанию (accessibility_display_magnification_enabled = 1), система НЕ передаёт одиночное касание сразу в игру или интерфейс, а ждёт ~150–200 мс, проверяя, не последует ли 2-й и 3-й тап!",
            fixActionSummary = "Отключает перехватчик ожидания тройного нажатия, возвращая мгновенный аппаратный отклик сенсора.",
            searchKeywords = listOf("инпут лаг", "input lag", "задержка сенсора", "тач тормозит", "отклик экрана", "шутеры", "лупа"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "inp_long_press_sluggish",
            subsystem = PhoneSubsystem.INPUT,
            symptomTitle = "Долгое нажатие на иконки и выделение текста срабатывают слишком медленно («ватный» тач)",
            rootCauseExplanation = "Завышен порог распознавания удержания пальца Secure:long_press_timeout (500–1000 мс вместо отзывчивых 300 мс).",
            fixActionSummary = "Калибрует таймер долгого нажатия на чёткие 300 мс с возможностью отката к прежнему значению.",
            searchKeywords = listOf("долгое нажатие", "удержание пальца", "медленно выделяет", "ватный интерфейс"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "inp_ghost_touches_pointer_overlay",
            subsystem = PhoneSubsystem.INPUT,
            symptomTitle = "На экране остались белые кружки под пальцем или сетка координат сверху экрана",
            rootCauseExplanation = "Остались активны отладочные оверлеи System:show_touches или System:pointer_location (часто включаются после проверки тачскрина в сервисе).",
            fixActionSummary = "Немедленно убирает визуальные кружки касаний и полосу трассировки координат.",
            searchKeywords = listOf("белые кружки", "точка под пальцем", "полосы сверху экрана", "координаты", "show touches"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "inp_autorotate_locked_sideways",
            subsystem = PhoneSubsystem.INPUT,
            symptomTitle = "Экран застрял в горизонтальном (альбомном) положении или сломался автоповорот",
            rootCauseExplanation = "После вылета полноэкранной игры или видеоплеера параметр System:user_rotation остаётся заблокированным на 1 или 3 (90°/270°) при выключенном accelerometer_rotation.",
            fixActionSummary = "Возвращает портретную ориентацию (user_rotation = 0) и включает штатный автоповорот по датчику.",
            searchKeywords = listOf("экран боком", "застрял поворот", "не переворачивается экран", "автоповорот"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),

        // =========================================================================
        // 9. GPS: Спутники GNSS, A-GPS и Геолокация (3 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "gps_cold_lock_agps_aid",
            subsystem = PhoneSubsystem.GPS,
            symptomTitle = "Навигатор долго ищет спутники («Поиск GPS…») при старте поездки",
            rootCauseExplanation = "Отключена загрузка альманаха орбит через сотовую сеть (Global:assisted_gps_enabled = 0) или понижен режим точности позиционирования Secure:location_mode.",
            fixActionSummary = "Включает аппаратную помощь A-GPS (assisted_gps_enabled = 1) и режим максимальной точности датчиков.",
            searchKeywords = listOf("долго ищет спутники", "не работает gps", "навигатор теряет", "яндекс навигатор", "геолокация"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "gps_mock_location_antifraud",
            subsystem = PhoneSubsystem.GPS,
            symptomTitle = "Банки, такси или доставка блокируют работу из-за «подмены GPS», хотя Fake GPS не запущен",
            rootCauseExplanation = "Даже после отключения программы подмены координат за ней остаётся активное разрешение AppOps MOCK_LOCATION = allow, на которое срабатывает антифрод.",
            fixActionSummary = "Находит сторонние пакеты с разрешением фиктивных координат и переводит MOCK_LOCATION в deny с точкой отката.",
            searchKeywords = listOf("подмена gps", "фиктивные координаты", "таксометр ругается", "банк ошибка геолокации", "mock location"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "gps_background_track_gaps",
            subsystem = PhoneSubsystem.GPS,
            symptomTitle = "Беговые/вело-трекеры и семейные локаторы рисуют прямые линии вместо маршрута на выключенном экране",
            rootCauseExplanation = "Глобальный лимитер location_background_throttle_interval_ms по умолчанию режет частоту опроса GPS для фоновых приложений до одного раза в 30 минут.",
            fixActionSummary = "Снижает интервал фонового троттлинга геолокации для плавной записи треков.",
            searchKeywords = listOf("рвет трек", "беговой трекер", "strava", "маршрут прямой линией", "геолокация в фоне"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),

        // =========================================================================
        // 10. SECURITY: Безопасность, Буфер обмена и Сертификаты (4 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "sec_clipboard_spy_guard",
            subsystem = PhoneSubsystem.SECURITY,
            symptomTitle = "Защита буфера обмена: сторонние приложения читают скопированные пароли, коды и номера карт",
            rootCauseExplanation = "Многие приложения при открытии обращаются к ClipboardManager. Система позволяет точечно закрыть фоновое чтение AppOps READ_CLIPBOARD для сторонних программ, сохранив работу текущей клавиатуры.",
            fixActionSummary = "Включает Clipboard Guard: переводит READ_CLIPBOARD в ignore для сторонних пользовательских приложений (не трогая системную клавиатуру).",
            searchKeywords = listOf("буфер обмена", "пароли", "шпионаж", "копирование", "clipboard", "приватность"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "sec_rogue_mitm_ca_audit",
            subsystem = PhoneSubsystem.SECURITY,
            symptomTitle = "Проверка на установленные сертификаты прослушки HTTPS-трафика (MITM / фишинг)",
            rootCauseExplanation = "Если злоумышленник, чужой профиль Wi-Fi или сомнительная программа установили пользовательский корневой сертификат в /data/misc/user/0/cacerts-added, зашифрованный трафик браузера может перехватываться.",
            fixActionSummary = "Инспектирует хранилище пользовательских Root CA и предупреждает о любых нештатных сертификатах.",
            searchKeywords = listOf("сертификаты", "прослушка", "перехват трафика", "mitm", "безопасность сети", "ca"),
            requiredTier = PrivilegeTier.BASIC,
            risk = RiskLevel.SAFE,
            supportsVaultRollback = false,
        ),
        FixPlaybook(
            id = "sec_open_adb_tcp_5555",
            subsystem = PhoneSubsystem.SECURITY,
            symptomTitle = "Открытый без пароля сетевой порт отладки TCP 5555 (опасность взлома в публичном Wi-Fi!)",
            rootCauseExplanation = "Устаревший режим adb tcpip 5555 (свойство service.adb.tcp.port = 5555) в отличие от современной TLS-отладки Android 11+ оставляет открытый порт командной оболочки в локальной сети.",
            fixActionSummary = "Проверяет статус порта 5555 и безопасно закрывает незащищённый TCP-слушатель.",
            searchKeywords = listOf("порт 5555", "adb wifi дыра", "безопасность отладки", "удаленный доступ"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "sec_accessibility_scroll_stutter_audit",
            subsystem = PhoneSubsystem.SECURITY,
            symptomTitle = "Проверка включённых служб специальных возможностей",
            rootCauseExplanation = "Службы доступности могут читать экран и помогать управлять телефоном. Наличие службы не доказывает вредоносность или причину задержек; TalkBack и другие необходимые средства нельзя массово отключать.",
            fixActionSummary = "Показывает доступный список и предлагает ручную проверку в системных настройках. Ничего не отключает.",
            searchKeywords = listOf("спецвозможности", "читает экран", "микрофризы прокрутки", "accessibility", "лаги ленты"),
            requiredTier = PrivilegeTier.BASIC,
            risk = RiskLevel.ADVANCED,
            supportsVaultRollback = false,
        ),

        // =========================================================================
        // 11. PERFORMANCE: Процессор, ОЗУ, Композитор GPU и Ядро (3 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "perf_cached_apps_freezer",
            subsystem = PhoneSubsystem.PERFORMANCE,
            symptomTitle = "Свёрнутые в ОЗУ приложения продолжают втихую расходовать процессор и греть телефон",
            rootCauseExplanation = "На многих прошивках ядерный замораживатель cgroup v2 (Global:cached_apps_freezer) оставлен в консервативном значении device_default вместо принудительного enabled.",
            fixActionSummary = "Активирует cached_apps_freezer = enabled: свёрнутые приложения мгновенно открываются из памяти, но потребляют 0% CPU.",
            searchKeywords = listOf("фоновые процессы", "озу", "процессор в фоне", "заморозка кэша", "cached apps freezer"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "perf_gpu_debug_overdraw_reset",
            subsystem = PhoneSubsystem.PERFORMANCE,
            symptomTitle = "Сильный нагрев в простых меню или розовые/зелёные полосы отладки GPU",
            rootCauseExplanation = "Забытые отладочные свойства визуализации перерисовки GPU (debug.hwui.overdraw / show_non_rect_clip) нагружают видеоядро на каждом кадре.",
            fixActionSummary = "Сбрасывает стрессовые отладочные флаги рендерера HWUI к штатному аппаратному режиму.",
            searchKeywords = listOf("розовый экран", "полосы gpu", "перерисовка", "hwui", "греется в меню"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
        ),
        FixPlaybook(
            id = "perf_kernel_sched_vm_tune",
            subsystem = PhoneSubsystem.PERFORMANCE,
            symptomTitle = "Снижение задержек планировщика ядра Linux и накладных расходов статистики (методика KTweak)",
            rootCauseExplanation = "Сбор детальной статистики планировщика /proc/sys/kernel/sched_schedstats = 1 и неоптимальные пороги сброса страниц VM создают лишние системные накладные расходы.",
            fixActionSummary = "Отключает sched_schedstats = 0 и балансирует dirty_ratio = 30 / dirty_background_ratio = 10 с полным сохранением исходных значений ядра в StateVault.",
            searchKeywords = listOf("ядро", "kernel", "ktweak", "планировщик", "микролаги", "root оптимизация"),
            requiredTier = PrivilegeTier.ROOT,
            risk = RiskLevel.EXPERT,
        ),

        // =========================================================================
        // 12. OEM_SHELL: Xiaomi HyperOS/MIUI, Samsung OneUI, Realme/OPPO, Tecno (4 playbooks)
        // =========================================================================
        FixPlaybook(
            id = "oem_xiaomi_msa_ads_cure",
            subsystem = PhoneSubsystem.OEM_SHELL,
            symptomTitle = "Xiaomi / Redmi / POCO: Рекламные баннеры в Проводнике, Очистке, Музыке и при установке приложений",
            rootCauseExplanation = "Системные пакеты com.miui.msa.global (MIUI System Ads) и com.miui.analytics подгружают рекламные блоки во все стандартные утилиты MIUI и HyperOS.",
            fixActionSummary = "Мягко замораживает рекламные демоны MSA и аналитики через StateVault — реклама в системе пропадает полностью без риска бутлупа.",
            searchKeywords = listOf("реклама сяоми", "xiaomi реклама", "msa", "hyperos реклама", "poco реклама", "redmi баннеры"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
            vendorFilter = listOf("xiaomi", "redmi", "poco", "blackshark"),
        ),
        FixPlaybook(
            id = "oem_samsung_gos_and_rubin",
            subsystem = PhoneSubsystem.OEM_SHELL,
            symptomTitle = "Samsung OneUI: Сброс FPS и яркости в играх (троттлинг GOS) + фоновый сбор досье Rubin",
            rootCauseExplanation = "Служба Game Optimizing Service (com.samsung.android.game.gos) принудительно режет частоты CPU/GPU и разрешение рендера, а Customization Service (rubin) собирает телеметрию привычек.",
            fixActionSummary = "Замораживает пакеты GOS и Rubin через StateVault с возможностью разморозки в 1 нажатие.",
            searchKeywords = listOf("самсунг лагает в играх", "gos", "oneui троттлинг", "samsung fps", "rubin"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.ADVANCED,
            vendorFilter = listOf("samsung"),
        ),
        FixPlaybook(
            id = "oem_bbk_heytap_spam_cure",
            subsystem = PhoneSubsystem.OEM_SHELL,
            symptomTitle = "Realme / OPPO / OnePlus: Навязчивые рекламные пуши и фоновая телеметрия HeyTap",
            rootCauseExplanation = "Системный пуш-сервис com.heytap.mcs и сборщик статистики com.oplus.statistics.rom присылают маркетинговые уведомления и расходуют заряд в фоне.",
            fixActionSummary = "Безопасно замораживает маркетинговые каналы HeyTap и телеметрию ROM через StateVault.",
            searchKeywords = listOf("realme реклама", "oppo уведомления", "oneplus", "heytap", "coloros"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
            vendorFilter = listOf("realme", "oppo", "oneplus"),
        ),
        FixPlaybook(
            id = "oem_transsion_phonemaster_spam",
            subsystem = PhoneSubsystem.OEM_SHELL,
            symptomTitle = "Tecno / Infinix (HiOS / XOS): Бесконечные всплывающие окна «Очистите память» от Phone Master и Palm Store",
            rootCauseExplanation = "Предустановленные пакеты com.transsion.phonemaster и com.transsion.aha.games генерируют ложные тревоги о «переполнении ОЗУ» ради показа рекламных объявлений.",
            fixActionSummary = "Мягко замораживает навязчивые рекламные сервисы оболочки с точкой отката в StateVault.",
            searchKeywords = listOf("tecno реклама", "infinix реклама", "phone master", "palm store", "hios", "xos"),
            requiredTier = PrivilegeTier.ADB_SHIZUKU,
            risk = RiskLevel.SAFE,
            vendorFilter = listOf("tecno", "infinix", "itel"),
        ),
    )
}

object FixerCatalog {
    val allPlaybooks: List<FixPlaybook> by lazy {
        FixerCatalogPartA.playbooks + FixerCatalogPartB.playbooks
    }

    fun byId(id: String): FixPlaybook? = allPlaybooks.firstOrNull { it.id == id }
}
