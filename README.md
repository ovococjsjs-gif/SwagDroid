# SwagDroid 🛡️⚡🧹🔧

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android API](https://img.shields.io/badge/Android-10%E2%80%9316%20(API%2029%E2%80%9336)-3DDC84)](build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)](gradle/libs.versions.toml)
[![Architecture](https://img.shields.io/badge/IPC-Binder%20%2B%20WAL%20Rollback-orange)](core-rollback)

**SwagDroid** — полностью открытый (`FOSS`, лицензия `GPL-3.0`) системный комбайн для **Android 10–16**, объединяющий привилегированное управление устройством без ПК, четырёхслойный антивирус и охотник на скрытые майнеры, честную глубокую очистку памяти, сетевой блокировщик рекламы, паспортный оптимизатор/деблоатер и программный фиксер с журналом предзаписи (`Write-Ahead Log`).

---

## 🌟 Ключевые возможности (Реализованы все 7 этапов)

| Этап | Модуль | Что делает и чем отличается от «обычных утилит» |
| :--- | :--- | :--- |
| **Этап 1** | `:api`, `:server`, `:adb-pairing`, `:backend-root`, `:core-rollback` | **Единое привилегированное ядро**: один и тот же Binder-демон `swagdroid_server` работает поверх **Беспроводной отладки (`uid=2000`) без ПК**, моста **Shizuku** или **Root (`uid=0` через Magisk / KernelSU / APatch)**. Включает проверку цифровой подписи вызывающего пакета (`ClientCallerVerifier`) и защиту от бутлупа (`CriticalPackageGuard`). |
| **Этап 2** | `:feature-antivirus` | **4-слойный Антивирус и Охотник на майнеры (`MinerHunter`)**: (1) офлайн-сканер APK/DEX/ELF на фильтрах Блума (`Hypatia`), (2) эвристика опасных связок прав (`Accessibility + SMS + Overlay`), (3) детектор майнеров (`RandomX`/`Stratum`/`xmrig`) по `UsageStats`, `ThermalManager`, `uid_stat` и `/proc` с защитой легитимных `Termux`/`PRoot`, (4) **Менеджер карантина** (`pm suspend` + запрет сети + остановка процесса). |
| **Этап 3** | `:feature-cleaner` | **Честный глубокий клинер**: 3 режима (Безопасный / Стандартный / Глубокий), дедупликация перекрывающихся путей до расчёта байтов, учёт клонов и профилей (`user 0`, `10`, `999`), точечная очистка **только кэша** мессенджеров (Telegram, WhatsApp, TikTok) без удаления голосовых/документов/черновиков, анализ `Dalvik/ART` и `fstrim` без маркетинговых выдумок. |
| **Этап 4** | `:feature-adblock` | **Честный AdBlock**: выбор между альтернативными сетевыми режимами (**Системный Private DNS DoT** vs **Локальный per-app VPN с логом доменов для анти-майнера** vs **интеграция с Root `bindhosts`**). Включает 15-секундный таймер безопасного отката DNS при потере сети и экспериментальную Root-лабораторию `Intent Firewall (/data/system/ifw)` для одного приложения с авто-откатом при падении (`exit-info`). |
| **Этап 5** | `:feature-optimizer` | **Паспортный Оптимизатор и 5-цветный Деблоатер**: каждый тумблер описан контрактом `TogglePassport` (`Read -> WAL Snapshot -> Write -> Verify`), `Phantom Process Killer` вынесен в раздел «Для Termux и разработчиков», а деблоатер использует 5-цветную шкалу `UAD-ng` (`⚪ Unknown`, `🟢 Recommended`, `🟡 Advanced`, `🟠 Expert`, `🔴 Unsafe / Protected`) и различает `pm suspend`, `pm disable-user` и `pm uninstall --user 0`. |
| **Этап 6** | `:feature-fixer` | **Программный Фиксер и `TelephonyDoctor`**: локализация симптомов по 5 слоям Android, 12 эталонных декларативных рецептов без единой сырой shell-команды в JSON, отложенная верификация пушей/батареи и диагностика SIM/eSIM/APN/VoLTE с обязательным маскированием `IMSI`, `ICCID`, `EID`, номеров телефонов и `Cell ID`. |
| **Этап 7** | `:feature-fixer`, `:app` | **Паспорт железа, Непротиворечивые Пресеты и Семантический Экспорт (`schema = 1`)**: каждое поле паспорта батареи и памяти помечено реальным источником (`TelemetrySourceConfidence`), пресеты показывают честный дифф перед применением, а экспорт сетапа переносит высокоуровневые намерения с повторной валидацией на новом смартфоне. |

---

## 🏗️ Модульная архитектура репозитория

```text
SwagDroid/
├── api/                 # AIDL-контракт ISwagDroidService, модели уровней привилегий и CriticalPackageGuard
├── core-rollback/       # Write-Ahead Log (WAL) журнал состояний (PREPARED -> APPLYING -> APPLIED -> VERIFIED) + детектор конфликтов перед откатом
├── server/              # Привилегированный сервер swagdroid_server (запуск через setsid app_process под uid=2000 или uid=0)
├── adb-pairing/         # Клиент Беспроводной отладки (Android 11+): mDNS, спаривание из шторки, автозапуск и OEM-шпаргалки
├── backend-root/        # Оркестратор привилегий: Root (libsu: Magisk/KernelSU/APatch), мост Shizuku и ADB Shell
├── feature-antivirus/   # 4-слойный антивирус, Bloom-фильтры, эвристика APK, MinerHunter и QuarantineManager
├── feature-cleaner/     # Deep & Honest Cleaner: AppCacheTrimmer, CorpseFinder, MessengerCleaner, TrashBinManager
├── feature-adblock/     # PrivateDnsSafetyController (с авто-откатом за 15 сек), Local VPN и Root IFW Lab
├── feature-optimizer/   # Движок TogglePassport и 5-цветный каталог Деблоатера (UAD-ng)
├── feature-fixer/       # 12 типизированных рецептов Фиксера, TelephonyDoctor (с маскированием PII) и Семантический Экспорт
└── app/                 # UI-приложение (Jetpack Compose + Material 3) с 6 вкладками и BinderContainerProvider
```

---

## 🔒 Принципы инженерной честности и безопасности

1. **Никакой «магии» и ложных обещаний**:
   - Приложение честно разделяет полностью обратимые настройки (`EXACT_REVERSIBLE`), частично обратимые системные операции (`PARTIALLY_REVERSIBLE`, например `cmd package compile -m speed-profile`) и необратимые операции обслуживания (`IRREVERSIBLE_MAINTENANCE`, например очистка кэша или `fstrim`).
   - Если симптом вызван физической неисправностью SIM-карты, SIM-лотка или отсутствием провижининга VoLTE у оператора, `TelephonyDoctor` прямо сообщает об этом и не предлагает фейковых системных правок.
2. **Write-Ahead Log (WAL) и проверка конфликтов перед откатом**:
   - Перед любым изменением настроек старое состояние сначала записывается на диск в статусе `PREPARED`.
   - Если процесс был прерван посередине (`APPLYING`), при следующем старте `RollbackJournal` находит незавершённые операции для сверки.
   - Перед выполнением отката `detectConflictsBeforeRollback()` проверяет, не изменил ли пользователь эту настройку вручную после применения рецепта.
3. **Защита от вредоносных рецептов и бутлупа**:
   - В декларативных рецептах Фиксера (`FixPlaybookRecipe`) запрещены сырые shell-команды — рецепты ссылаются только на типизированные `TypedDiagnosticReaderId` и `TypedFixActionId`.
   - `CriticalPackageGuard` на уровне сервера блокирует отключение/удаление системно критичных пакетов (`SystemUI`, `com.miui.securitycenter`, текущего лаунчера, клавиатуры, звонилки и пакета телефонии).

---

## 🛠️ Сборка и запуск тестов

Требования: **JDK 17** и **Android SDK (API 35)**.

```bash
# Запуск модульных тестов по всем фича-модулям
gradle testDebugUnitTest

# Сборка отладочного APK
gradle :app:assembleDebug
```

---

## 📄 Лицензия и сторонние компоненты

Проект распространяется под лицензией **GNU General Public License v3.0 (`GPL-3.0-or-later`)**.
Подробная матрица используемых открытых библиотек и архитектурных источников приведена в [`THIRD_PARTY.md`](THIRD_PARTY.md) и [`RESEARCH_AND_ROADMAP.md`](RESEARCH_AND_ROADMAP.md).
