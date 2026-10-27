# SwagDroid: разбор текущей версии и основа для следующей

**Дата:** 26 октября 2026 года.<br>
**Исследованный код:** `acc3647b8b7ce07ce41395663b36691959015d86` — актуальный `origin/main` на момент синхронизации.<br>
**Рабочая ветка:** `arena/975757fb-swagdroid` обновлена fast-forward, без переключения веток.<br>
**Объём работы:** изучение архитектуры, пользовательских маршрутов по исходникам, механизмов всех функциональных модулей, безопасности изменений, тестов и сборки. Код приложения в рамках этого разбора не перерабатывался.

> Это исторический разбор указанного коммита. Ход исправлений: [этап 1](STABILIZATION_STEP_1.md) и [этап 2](STABILIZATION_STEP_2.md). Ссылки на исходники закреплены за исследованной ревизией.

## 1. Главный вывод

SwagDroid — **не пустой макет**. Здесь есть настоящий Android-код: Compose-интерфейс, Shizuku/libsu, команды Settings/AppOps/PackageManager, Room-журнал, файловые сканеры, разбор APK/DEX и собственный DNS VPN.

Основная проблема не в отсутствии функций и не только в оформлении. **Приложение объединяет много низкоуровневых действий, но ещё не объединяет их в надёжные пользовательские сценарии.** Сейчас:

- доступность часто определяется только уровнем прав, а не возможностями конкретного устройства;
- штатная настройка или пользовательский выбор могут считаться неисправностью;
- отсутствие доступа к данным иногда превращается в «всё в норме»;
- успешная команда, а иногда даже неуспешная, превращается в «проблема решена»;
- сохранённая команда обратного действия не всегда означает точный откат;
- интерфейс показывает устройство внутренних модулей, а не кратчайший путь к решению задачи;
- обещания в интерфейсе и документации заметно сильнее доказанных гарантий кода.

**Рекомендация:** сохранить технологическую основу и идею «швейцарского ножа», но строить следующую версию вокруг проверенных сценариев, общей модели доступности и безопасного выполнения. Не начинать с добавления новых переключателей или полного переписывания всего проекта.

## 2. Что проверено, а что пока нет

### Подтверждено в этой сессии

- Подтянут `main`, `HEAD` совпал с `origin/main`. Начальная shallow-копия истории углублена перед fast-forward.
- Изучены точка входа, навигационная оболочка, модели, исполнитель команд, StateVault, основные экраны/ViewModel и движки всех функций, все восемь файлов unit-тестов.
- Статически проверены численность и уникальность каталогов, наличие обработчиков всех сценариев Fixer, JSON базы UAD, синтаксис XML и shell-скриптов.
- Через GitHub API проверен [запуск CI №40351262846](https://github.com/ovococjsjs-gif/SwagDroid/actions/runs/40351262846): `success`, SHA совпадает с исследованным кодом. Шаг тестов/сборки и оба шага выгрузки артефактов отмечены успешными.
- Метаданные API подтверждают существование неистёкших артефактов `SwagDroid-v1.0.0-release-apk` и `SwagDroid-v1.0.0-debug-apk`.

### Ограничения проверки

- Локальный `./gradlew testDebugUnitTest --no-daemon` остановился **до выполнения тестов**: нет Java/JAVA_HOME. Android SDK, `adb` и подключённого устройства здесь тоже нет.
- Сетевой доступ среды не включает стандартные серверы Gradle/Google Maven. Полный лог CI также не удалось получить: попытка загрузки завершилась ошибкой EOF. Подробные логи и точное число выполненных в CI тестов не проверены.
- APK не устанавливался; реальная работа Shizuku, Root, VPN, OEM-команд и поведения интерфейса не проверена на телефоне.
- Ниже разделяются **факты исходного кода**, **следующие из них риски** и **проверки, необходимые на устройстве**. Наличие зелёного CI не считается доказательством работоспособности всех функций.

## 3. Размер и стек проекта

| Параметр | Фактическое состояние |
|---|---|
| Gradle-модули | 11: приложение, 4 core, 6 feature |
| Kotlin | 61 файл: 53 production + 8 test |
| Строки Kotlin | 14 566 production + 897 test = 15 463 |
| UI | Jetpack Compose, Material 3, собственная тема и компоненты |
| Состояние | ViewModel, Coroutines, StateFlow, `collectAsStateWithLifecycle` |
| DI | Hilt, KSP |
| Хранение | Room для StateVault, JSON-файл пользовательских DNS-правил; большая часть экранного состояния только в памяти |
| Системные действия | Local shell / Shizuku / Sui / libsu |
| Android | `minSdk=28` (Android 9+), `compileSdk=37`, `targetSdk=37` |
| Сборка | AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0; JDK 21 в CI, JVM target 17 |
| Unit-тесты | 32 метода `@Test` в 8 файлах; это подсчёт исходников, не отчёт нового локального прогона |
| Инструментальные/UI-тесты | `src/androidTest` отсутствует |

Основные файлы конфигурации: [settings.gradle.kts](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/settings.gradle.kts), [versions](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/gradle/libs.versions.toml), [app/build.gradle.kts](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/app/build.gradle.kts), [manifest](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/app/src/main/AndroidManifest.xml).

### Архитектурная карта

```text
MainActivity → SwagAppShell
                  ├─ Пульс   → DashboardViewModel → DevicePassportCollector
                  ├─ Защита  → SecurityCenterViewModel → SecurityPipelineEngine
                  │                                   → AdblockController → VpnService
                  ├─ Очистка → CleanerViewModel → CleanerScanAndPurgeEngine
                  ├─ Твики   → OptimizerViewModel → SystemTweaksController / UadCatalogRepository
                  └─ Фиксер  → FixerViewModel → FixerDiagnosticEngine / FixerCatalog

Изменения Settings / AppOps / пакетов → StateVaultManager → PrivilegedCommandExecutor
                                      ↕ Room                  ↕ PrivilegeManager
                                                     Local / Shizuku / libsu

Часть обслуживания и удаления → напрямую executor / java.io.File, вне StateVault
```

| Модуль | Роль | Оценка основы |
|---|---|---|
| `app` | Activity, пять вкладок, manifest, локальный crash-log | Небольшая и понятная оболочка; полноценной модели навигации/истории нет |
| `core:model` | Права, паспорт, health score, операции отката | Подход полезный; нужны типы неизвестного/неподдерживаемого состояния и более точная модель результата |
| `core:designsystem` | Тема, значки, карточки, бейджи, индикаторы | Можно развивать, не требуется выбрасывать |
| `core:privilege` | Определение прав, shell, чтение параметров, паспорт | Правильная точка централизации, но контракт слишком оптимистичен при ошибках |
| `core:vault` | Снимки, применение, обратные операции, guard | Самая ценная идея и самый важный объект доработки безопасности |
| `feature:dashboard` | Главная, паспорт, подключение прав, история | Перегружена инженерными понятиями и дублирующими действиями |
| `feature:fixer` | Каталог проблем, диагностика, исправления, помощь с пушами | Хороший задел для сценарного продукта; диагностические критерии требуют пересмотра |
| `feature:optimizer` | Пресеты, твики, UI, ART, управление пакетами | Много настоящих команд, мало проверки поддержки и результата |
| `feature:cleaner` | Мусор, медиа, остатки приложений, дубликаты | Есть полезные алгоритмы, но текущую массовую очистку нельзя считать безопасной по умолчанию |
| `feature:security` | Эвристики APK, трекеры, карантин | Полезнее позиционировать как инспектор рисков; полноценный антивирус не подтверждён |
| `feature:adblock` | DNS VPN, Private DNS, hosts, netpolicy | Три существенно разных механизма ошибочно подаются почти как взаимозаменяемые |

Модульность есть, но это не строго разделённая Clean Architecture: UI, каталог, диагностика, команды и тексты часто тесно связаны. В крупнейших файлах — `OptimizerScreen.kt` 1 138 строк, `FixerDiagnosticEngine.kt` 1 099, `CleanerScanAndPurgeEngine.kt` 793, `SecurityCenterScreen.kt` 774. Делить их стоит по ответственностям, а не просто по размеру.

## 4. Инвентаризация функций: обещание и реальность

| Область | Что действительно реализовано | Важное ограничение |
|---|---|---|
| Паспорт | BatteryManager, StatFs, ActivityManager, `/proc/meminfo`, свойства устройства | Ресурс АКБ — оценка по заряду/проценту, а не измерение фактической деградации; Root Advisor эвристический |
| Подключение прав | Работа с уже доступным Shizuku/Sui и запрос `su` | Собственного Wireless ADB pairing-клиента нет: мастер открывает настройки и отдельное приложение Shizuku |
| Fixer | 53 уникальных сценария, 12 подсистем; для всех есть ветки диагностики/обработки | 51 сценарий требует ADB/Shizuku, 1 Root, 1 BASIC; единственный BASIC — проблемный аудит CA через закрытый путь |
| Откат Fixer | У 42 сценариев заявлен StateVault, у 11 — разовое обслуживание | Не все custom shell обратные действия восстанавливают исходное значение |
| Оптимизатор | 7 пресетов, множество Settings/AppOps-твиков, ART, Game Mode, профили JSON | Уровень прав не гарантирует поддержку команды OEM/версией Android; часть параметров может записаться без эффекта |
| Debloater | 4 123 уникальных записи, сопоставление с установленными пакетами, suspend/disable/enable | `Recommended` не является доказательством безопасности на конкретной прошивке; динамические роли приложений не учитываются |
| Русификация UAD | 61 запись имеет `ru=true` | Это не 4 123 русских описания; 693 описания заканчиваются `...`; нет воспроизводимого обновления базы в scripts |
| Очистка | Обход файлов, категории, SHA-256, dHash, `pm trim-caches`, installer sessions | Чужие `Android/data` недоступны обычному File API на современных Android; медиа ошибочно обобщаются как кэш |
| Сканер угроз | 7 package-ID, строки майнеров, комбинации разрешений, proxy/hosts, хеши Download | В SHA-256-каталоге 3 записи: две обозначены как EICAR, третья искусственно вычислена разворотом строки; обновляемой malware-базы нет |
| Трекеры | 32 DEX-маркера SDK | Только первые 60 пользовательских приложений, только основной `sourceDir`, без split APK; SDK ≠ вредоносное ПО |
| Карантин | Force-stop, AppOps и suspend пакета, связь с Room-снимком | Это не зашифрованный файловый карантин и не универсальная сетевая изоляция |
| DNS VPN | Разбор IPv4/IPv6 UDP DNS, NXDOMAIN, пересылка UDP-запросов | Только обращения к виртуальным DNS-адресам, не весь трафик; нет TCP DNS fallback, DoH/прямые IP не покрываются |
| Блок-лист | 105 уникальных доменов + сохраняемые пользовательские правила | Нет загрузки/обновления больших внешних списков |
| Private DNS | Запись режима/hostname через Settings | Фильтрация зависит от внешнего резолвера; собственные DNS-правила приложения туда не передаются |
| Root hosts | Генерация hosts и запись в module/system path | Активация mount не проверяется; существующий модуль и резервная копия требуют безопасного управления |

### Распределение Fixer

| Сеть | Уведомления | Батарея | Хранилище | Пакеты | Экран | Звук | Ввод | GPS | Приватность | Производительность | OEM |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 6 | 5 | 5 | 4 | 5 | 6 | 4 | 4 | 3 | 4 | 3 | 4 |

48 из 53 сценариев помечены `SAFE`. Такой ярлык сейчас слишком широк: среди действий есть изменение приватности локскрина, отключение accessibility и необратимое обслуживание.

## 5. Приоритетные проблемы

Приоритеты ниже — порядок работы над следующей версией, не CVSS-оценки. **P0** — ограничить опасный сценарий до исправления; **P1** — необходимая надёжность перед публичным релизом; **P2** — качество сопровождения и UX. «Подтверждено по коду» не означает, что действие запускалось на телефоне.

### P0-01. Массовая очистка может удалять нужные медиа без отдельного подтверждения

**Факты:**

- `scanMessengers()` включает не только cache, но и `Telegram/Telegram Images`, `Telegram/Telegram Video`, каталоги `files`/`media`.
- Для изображений/видео `defaultChecked=true`, возраст по умолчанию — любой.
- `cleanAllSelected()` объединяет выбор **со всех** подразделов, включая невидимые в текущий момент мессенджеры, остатки и дубликаты.
- Кнопка «Очистить» сразу вызывает удаление; единственный AlertDialog этого экрана относится к ослаблению защиты паролей, не к подтверждению удаления.
- Удаление идёт через `File.delete()`, без корзины и без StateVault.

**Последствие:** пользователь может прийти за очисткой временного мусора и удалить сохранённые фото/видео. Имя папки и отсутствие credential-маркеров не доказывают, что файл восстановим из облака. Наличие точного дубликата тоже нужно повторно проверять перед удалением, включая сохранность оставляемого оригинала.

**Что изменить:** разделить подтверждённый кэш и пользовательские медиа; отключить автоселекцию последних; показывать полный план по всем категориям, предпросмотр и явное подтверждение; для медиа использовать корзину/восстановление там, где это поддерживается. План должен устранять пересечения между категориями.

**Код:** [каталоги Telegram, 269–290](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L269-L290), [значения выбора, 91–136](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/model/CleanerModels.kt#L91-L136), [общая очистка, 216–244](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/CleanerViewModel.kt#L216-L244), [кнопка, 257–264](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/CleanerScreen.kt#L257-L264), [удаление, 603–634](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L603-L634).

### P0-02. «Пакеты-призраки» удаляются слишком широко

Разность `pm list packages -u` и `pm list packages` автоматически трактуется как конфликтный мусор. Для **всех** найденных имён запускается `cmd package uninstall`, без выбора пакета, `--user`, проверки его роли через SystemBrickGuard и возможности вернуть данные. Разность списков сама по себе не доказывает ненужность сохранённых данных или безопасность для других профилей.

**Что изменить:** убрать массовое автоматическое удаление из «ремонта»; показать конкретные кандидаты, пользователя, сохранённые данные, основания и необратимость. Ошибка чтения одного из списков должна отменять план, а не расширять список кандидатов.

**Код:** [удаление, 620–633](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L620-L633), [разность списков, 1063–1076](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L1063-L1076), [прямой запуск из карточки, 490–497](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/FixerScreen.kt#L490-L497).

### P0-03. StateVault не откатывает частично выполненный провалившийся шаг

При ошибке откатывается только `appliedSoFar`. Текущая операция добавляется туда **после** успешного результата. Но одна операция может состоять из нескольких команд и изменить систему до ошибки.

Конкретный путь по коду: перевод отключённого пакета в SUSPENDED сначала делает `pm enable`, затем `cmd package suspend`. Если enable прошёл, а suspend завершился ошибкой, неуспешный шаг не попадёт в откат. При отсутствии предыдущих шагов результат при этом содержит `autoReverted=true`, а снимок становится `ROLLED_BACK`.

Аналогичная проблема есть у составной записи двух kernel-параметров. Исключение/отмена между шагами отдельно не обрабатываются как прерванная транзакция.

**Что изменить:** сохранять состояние исполнения каждого шага; учитывать частичное выполнение; проверять фактическое состояние после ошибки; не объявлять откат завершённым без проверки. Не обещать атомарность внешних OS-команд как у SQL-транзакции.

**Код:** [применение и компенсация, 140–163](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/StateVaultManager.kt#L140-L163), [команда suspend, 130–143](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/privilege/src/main/java/com/swagdroid/core/privilege/CommandExecutor.kt#L130-L143), [составная kernel-команда, 898–912](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L898-L912).

### P0-04. Таймаут Root-команды прекращает ожидание, но не обеспечивает остановку работы

В libsu используется `job.enqueue()` и `future.get(timeout, ...)`. При исключении возвращается код 124; остановки job/shell, отмены future и ожидания прекращения изменения системы нет. Команда может продолжить работу после сообщения об ошибке, а StateVault уже начнёт компенсирующие действия. Буферы вывода libsu также не ограничены как в ветке локального процесса.

**Что изменить:** проектировать жизненный цикл операции и безопасное прерывание с учётом API libsu. Если остановку подтвердить нельзя — статус «результат неизвестен», повторное чтение, запрет конкурентной компенсации до определения состояния. Это отдельный тестовый сценарий, а не просто увеличение таймаута.

**Код:** [executeViaLibsu, 176–198](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/privilege/src/main/java/com/swagdroid/core/privilege/CommandExecutor.kt#L176-L198).

### P1-01. Отсутствие доступа ошибочно интерпретируется как отсутствие проблемы

- `readSetting()` возвращает одинаковый `null` при отсутствии ключа и неудаче чтения.
- `readAppOpMode()` возвращает `default` при ошибке команды.
- `readPackageState()` при исключении возвращает ENABLED.
- В аудите CA `listFiles()` закрытого `/data/misc/user/0/cacerts-added` превращается в пустой список и затем в «сертификатов не обнаружено».
- В `DiagnosticVerdict` нет отдельного результата ошибки/недоступных данных.

**Последствие:** ложное «здоров», неверно захваченное исходное состояние для отката. Само наличие Root/Shizuku не повышает права Java File API процесса приложения.

**Что изменить:** типизированное чтение `Value / Missing / PermissionDenied / Unsupported / Error`; unknown не считается ни нормой, ни неисправностью. Для закрытых ресурсов нужен реальный привилегированный транспорт либо честный переход в системные настройки.

**Код:** [чтение системного состояния, 78–162](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/privilege/src/main/java/com/swagdroid/core/privilege/CommandExecutor.kt#L78-L162), [CA-диагностика, 489–496](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L489-L496), [CA-сканер, 302–316](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/security/src/main/java/com/swagdroid/feature/security/engine/SecurityPipelineEngine.kt#L302-L316).

### P1-02. Файловая очистка не реализует заявленные уровни доступа

Обход чужих `Android/data`/`obb` и удаление выполняются через `java.io.File`, а не через Shizuku/root bridge. Разрешение MANAGE_EXTERNAL_STORAGE не превращается в универсальный доступ к чужим app-specific каталогам. На API 28–29 `hasFullStoragePermission()` вообще возвращает `true`, хотя runtime-запроса READ/WRITE разрешений в проекте нет.

**Что изменить:** отдельные доступы и источники данных: публичные файлы/MediaStore/SAF, собственные каталоги, доступные привилегированные операции. Перед сканированием — карта покрытия; после — «проверено / пропущено / недоступно», а не просто пустой список. Поддержку проверить по Android/OEM на устройствах.

**Код:** [права и сканирование, 46–104](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L46-L104), [manifest, 17–21](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/app/src/main/AndroidManifest.xml#L17-L21), [экран разрешения, 187–213](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/CleanerScreen.kt#L187-L213).

### P1-03. Результат обслуживания иногда выдумывается независимо от команды

В `net_soft_stack_reset`, перезапуске медиапровайдера, переиндексации, перепроверке ссылок и остановке медиасессии результаты команд не проверяются перед `AppliedMaintenance`. В глобальном ART-ускорителе любой неуспех компиляции показывается как **«bg-dexopt запущен»**, хотя fallback-команды там нет. В Fixer после транзакции свежий диагностический verdict отбрасывается и устанавливается FIXED_ACTIVE.

**Что изменить:** общий результат выполнения и проверка эффекта. Различать «команда принята», «изменение подтверждено», «проблема всё ещё наблюдается» и «проверить не удалось»; сохранять ошибки понятным текстом и с техническими деталями.

**Код:** [maintenance, 571–653](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L571-L653), [ART, 525–533](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/engine/SystemTweaksController.kt#L525-L533), [FIXED_ACTIVE, 184–199](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/FixerViewModel.kt#L184-L199).

### P1-04. «Реальный антивирус» не соответствует доказательствам и покрытию

Хеш-каталог содержит всего три записи, включая EICAR и искусственный sample. Для установленных APK проверяются package-ID и строки, а не хеш каждого APK по большой актуальной базе. В Download берутся первые 80 элементов и только файлы 1–35 MiB. Пропущенные/нечитаемые файлы не становятся отдельными ограничениями отчёта. UI после пустого результата утверждает, что трояны, майнеры и перехватчики **отсутствуют**.

Одной строки `xmrig`/`randomx`, запрошенного permission или похожего package name недостаточно для доказанного заражения. Наличие рекламного SDK также не равно вредоносности. Поведенческого наблюдения CPU/WakeLock для обнаружения майнера в коде нет.

**Что изменить:** до появления проверяемой базы и тестового корпуса назвать функцию «Проверка рисков и приложений»; разделить подтверждённые сигнатуры, эвристики и обычные разрешения/SDK. Показывать область проверки и уверенность. Не обещать отсутствие всех угроз.

**Код:** [каталог, 42–90](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/security/src/main/java/com/swagdroid/feature/security/scanner/BloomAndSignaturesCatalog.kt#L42-L90), [проверка APK, 49–132](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/security/src/main/java/com/swagdroid/feature/security/engine/SecurityPipelineEngine.kt#L49-L132), [Download, 240–264](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/security/src/main/java/com/swagdroid/feature/security/engine/SecurityPipelineEngine.kt#L240-L264), [утверждение UI, 249–272](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/security/src/main/java/com/swagdroid/feature/security/SecurityCenterScreen.kt#L249-L272).

### P1-05. Настройки предпочтений и доступности принимаются за неисправности

Диагностика считает проблемой, например, штатный Captive Portal URL, выбранный Private DNS, другой масштаб шрифта, включённую цветокоррекцию, экранную лупу и любой активный AccessibilityService. «Исправление» последнего очищает весь `enabled_accessibility_services` и выключает accessibility — это может отключить необходимый пользователю экранный диктор или другое средство доступности.

В той же системе понятий Оптимизатор включает Private DNS и скрывает иконки, а Fixer затем обнаруживает это как отклонение. У модулей нет общей модели пользовательского намерения.

**Что изменить:** разделить «неисправность», «предпочтение», «риск», «совет» и «недостаточно данных». Диагностика должна начинаться с симптома, а не с приведения телефона к набору любимых значений. Accessibility нельзя массово выключать как безопасный универсальный фикс.

**Код:** [сеть, 58–74](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L58-L74), [экран, 299–340](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L299-L340), [accessibility, 506–512](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L506-L512), [отключение, 884–886](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L884-L886).

### P1-06. Откат часто означает «поставить дефолт», а не «вернуть как было»

Конкретные примеры custom shell:

- Deep Doze: apply и revert оба выполняют `enable`.
- GPU overdraw: apply и revert оба устанавливают `false`.
- Game Mode: revert всегда возвращает режим 1 без чтения старого.
- Standby bucket в реаниматоре пушей: revert всегда `working_set`, хотя текущая корзина уже есть в аудите.
- DeviceConfig: revert удаляет ключ вместо восстановления прежнего значения.
- Очистка logcat имеет обратную команду `true` — это необратимое действие в составе обратимой транзакции.

**Что изменить:** типизированные операции с чтением before-state под тем же механизмом сериализации, что и запись; необратимые шаги — отдельно с явным предупреждением. Сохранение текстовой revert-команды само по себе не даёт гарантии.

**Код:** [FCM/Doze/logcat, 727–770](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L727-L770), [overdraw, 891–895](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L891-L895), [standby, 985–1000](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/fixer/src/main/java/com/swagdroid/feature/fixer/engine/FixerDiagnosticEngine.kt#L985-L1000), [Game Mode, 549–562](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/engine/SystemTweaksController.kt#L549-L562).

### P1-07. Журналу не хватает восстановления после ошибок и учёта конфликтов

- Снимок получает ACTIVE ещё до начала применения; устойчивого состояния отдельного шага нет.
- Откат произвольного старого снимка не проверяет, не меняли ли тот же параметр более поздняя операция или сам пользователь.
- FAILED-снимки не входят в «откатить всё» и экспорт активных; UI предлагает повторный откат только ACTIVE.
- При ошибке декодирования операций Room-сущность подставляет пустой список; такой снимок может получить статус успешного отката без восстановления данных.
- В Room включён `fallbackToDestructiveMigration(true)`: **при будущем изменении схемы без миграции** история откатов будет потеряна.
- Стартовый «слепок системы» — всего 9 ключей Settings; экспорт JSON не является полным бэкапом, импорта этих снимков нет.

**Что изменить:** состояния PREPARED/RUNNING/APPLIED/PARTIAL/ROLLING_BACK/ROLLED_BACK/FAILED, журнал по шагам, повтор неудавшегося восстановления, проверка конфликтов, миграции и запрет молча принимать повреждённые снимки. Называть baseline точнее.

**Код:** [StateVault, 127–281](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/StateVaultManager.kt#L127-L281), [декодирование, 26–47](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/db/SnapshotEntity.kt#L26-L47), [Room, 40–48](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/di/VaultModule.kt#L40-L48), [UI истории, 381–444](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/dashboard/src/main/java/com/swagdroid/feature/dashboard/DashboardSheets.kt#L381-L444).

### P1-08. SystemBrickGuard полезен, но не даёт универсальной защиты

Guard проверяет только `MutatePackageState` и статический список имён. Он не является общей политикой для прямых команд, custom shell и необратимых операций. В список включён `com.swagdroid`, но не `com.swagdroid.debug`; не определяются текущий launcher, IME, Device Owner и критичные OEM-роли. Package-команды жёстко используют `--user 0`, тогда как чтение через PackageManager относится к контексту приложения.

**Что изменить:** runtime-защита собственного applicationId и критичных ролей, явный Android user/profile, проверка действия на уровне общего execution layer. Удалить обещания «математически исключает бутлуп» и «100% защита».

**Код:** [SystemBrickGuard](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/SystemBrickGuard.kt), [граница guard, 76–84](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/vault/src/main/java/com/swagdroid/core/vault/StateVaultManager.kt#L76-L84), [user 0, 130–143](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/privilege/src/main/java/com/swagdroid/core/privilege/CommandExecutor.kt#L130-L143).

### P1-09. DNS VPN требует отдельной проверки надёжности и энергопотребления

Builder не включает блокирующий режим TUN. Цикл при `readLen <= 0` или ошибке чтения немедленно делает `continue`, без ожидания/выхода. Для неблокирующего TUN это риск постоянного активного цикла даже при отсутствии запросов. **Фактическую загрузку CPU надо измерить на устройстве.**

Дополнительно: semaphore ограничивает активные upstream-сокеты, но не число ожидающих coroutine; UDP-ответ принимается без явной проверки отправителя/transaction ID/question; буфер ответа 2 048 байт, TCP fallback отсутствует. Настройки резолвера и исключений при sticky-перезапуске с null intent заменяются дефолтами.

**Что изменить:** корректный blocking/poll I/O и закрытие ресурсов, ограниченная очередь, проверка ответов, обработка ошибок и lifecycle, сохранение конфигурации. Обещания «нулевые утечки»/«100% скорость»/«<0.5% батареи» не подтверждены измерениями.

**Код:** [создание и read-loop, 50–129](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/vpn/SwagDnsVpnService.kt#L50-L129), [upstream, 132–160](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/vpn/SwagDnsVpnService.kt#L132-L160), [заявления модели](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/model/AdblockModels.kt).

### P1-10. Три режима AdBlock имеют разную семантику, hosts-откат ненадёжен

Private DNS не использует локальные правила. В локальном VPN allowlist отменяет только собственный запрет, но внешний фильтрующий резолвер может всё равно блокировать домен. VPN matcher блокирует суффиксы, а hosts содержит точные имена — одинаковая цифра правил не означает одинаковое покрытие. `netpolicy restrict-background-blacklist` не является полноценным per-app firewall для всего трафика.

В root-режиме код записывает общий `/data/adb/modules/hosts/module.prop`, копирует резервный файл по **одному и тому же пути при каждом применении**, не включает эти подготовительные изменения в транзакцию и не проверяет фактическую активацию overlay. Последовательные снимки могут ссылаться уже на перезаписанный backup.

**Что изменить:** описать ограничения отдельно по режимам, проверять работающий DNS/mount, иметь собственное пространство модуля и уникальные неизменяемые backup-файлы. Повторная установка должна быть идемпотентной и учитывать чужой hosts-модуль.

**Код:** [hosts и статус, 99–151](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/AdblockController.kt#L99-L151), [netpolicy, 158–197](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/AdblockController.kt#L158-L197), [генерация hosts, 122–124](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/data/BlocklistRepository.kt#L122-L124), [suffix matcher, 139–162](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/adblock/src/main/java/com/swagdroid/feature/adblock/dns/DnsPacketEngine.kt#L139-L162).

### P1-11. Нет общей проверки поддержки операций

`BASIC / ADB_SHIZUKU / ROOT` — только транспорт прав, не матрица возможностей. Для refresh rate, Game Mode, Immersive, freezer, AppOps и OEM-команд нет единого preflight по SDK, оборудованию, профилю и наличию команды. Успех `settings put` не доказывает, что прошивка использует этот ключ.

JSON-профиль содержит `schemaVersion`, но импорт не проверяет поддерживаемую версию. В отличие от ручного ввода DNS, импорт не использует ту же валидацию hostname/mode. Нет dry-run/предпросмотра изменений импортированного профиля.

**Что изменить:** единый CapabilityRegistry и валидируемый план действий; UI показывает причину недоступности до нажатия, а не ошибку после. Проверка результата должна зависеть от операции, не только от exit code.

**Код:** [импорт, 305–384](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/engine/SystemTweaksController.kt#L305-L384), [ручная валидация DNS, 421–434](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/engine/SystemTweaksController.kt#L421-L434), [профили частоты, 397–405](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/engine/SystemTweaksController.kt#L397-L405).

### P1-12. Release-сборка допускает debug-подпись

При отсутствии собственного keystore релиз молча подписывается debug-конфигурацией. Workflow не задаёт production-подпись явно. Это удобно для тестовой установки, но не определяет устойчивый ключ для публичных обновлений. Сертификат текущего APK в этой сессии не извлекался.

**Что изменить:** разделить тестовую устанавливаемую сборку и production release; для production отсутствие настроенной подписи должно останавливать сборку. Заранее проверить миграцию существующих установок: смена ключа может потребовать переустановки и потери локальной истории.

**Код:** [подпись, 27–53](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/app/build.gradle.kts#L27-L53), [workflow](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/.github/workflows/build.yml).

### P1-13. «Разморозить всё» не ограничивается изменениями SwagDroid

`restoreAllFrozenPackages()` выбирает все установленные записи каталога с состоянием не ENABLED. Не проверяется, кто и почему отключил пакет. Кнопка может включить то, что пользователь ранее осознанно отключил другим способом. Наличие нового снимка для этой операции полезно, но не делает её эквивалентом «отменить мои действия в SwagDroid».

**Что изменить:** разделить «отменить изменения SwagDroid» и экспертное «включить выбранные пакеты»; в обоих случаях показывать план и источник текущего состояния.

**Код:** [массовое включение, 422–426](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/optimizer/src/main/java/com/swagdroid/feature/optimizer/OptimizerViewModel.kt#L422-L426).

### P1-14. Объём удаления не привязан к проверенному плану сканирования

Подсчёт размера каталога ограничивает глубину обхода, а удаление рекурсивно обходит его без такого ограничения. Кроме того, между сканированием и нажатием кнопки содержимое может измениться. Повторная проверка credential-маркеров, canonical path и symlink — полезная защита, но она не гарантирует, что удаляются только показанные пользователю объекты.

Глобальный `pm trim-caches` также не может реализовать исключения отдельных пакетов из локального Credential Shield: команда получает только требуемый объём свободного места. Installer sessions считаются мусором без доказательства, что каждая сессия действительно брошена.

**Что изменить:** фиксированный перечень объектов с повторной проверкой перед удалением; отдельное объяснение ограничений глобальной очистки; явные критерии заброшенной установочной сессии. Не выдавать счётчик удалённых объектов за доказанный объём освобождённого пространства.

**Код:** [план installer sessions, 223–239](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L223-L239), [привилегированная очистка, 567–584](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L567-L584), [удаление и ограниченный подсчёт, 603–650](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/cleaner/src/main/java/com/swagdroid/feature/cleaner/engine/CleanerScanAndPurgeEngine.kt#L603-L650).

## 6. Почему интерфейс ощущается неудобным

Это анализ структуры Compose-кода, **не визуальное тестирование APK**.

1. **Первый экран рассказывает об архитектуре, а не помогает выбрать задачу.** Health score, ядро привилегий, Root Advisor, StateVault, отдельный слепок и анимации 0.5x предшествуют простым ответам «что я могу сделать прямо сейчас».
2. **Слишком много уровней каталогов.** Пять основных вкладок; внутри очистки пять подразделов, оптимизатора пять, защиты четыре; у Fixer ещё 12 подсистем. Пользователь должен знать, где автор разместил нужный механизм.
3. **Одни параметры живут в нескольких местах.** Private DNS — в защите, оптимизаторе и фиксе; анимации — на главной, в пресетах и Fixer; пакеты — в карантине, Debloater и OEM-ремонте. Единого владельца состояния/намерения нет.
4. **Нет общей карточки операции.** Перед действием нужен ответ: что изменится, на каком основании, чем рискуем, какие права требуются, можно ли вернуть назад. Сейчас эти сведения разнесены по длинным текстам, бейджам и раскрывающимся карточкам.
5. **Недоступность объясняется слишком поздно.** У Fixer кнопка доступна даже до проверки; многие твики блокируются только `isBusy`, а требование прав сообщается после нажатия.
6. **Успехи и ошибки не имеют общего формата.** В каждом модуле собственный banner и string; нет общей истории выполнения с причиной сбоя, частичным результатом и следующим шагом. В длинном списке banner вверху можно не заметить.
7. **«Здоровье» смешано с подключением самого приложения.** За BASIC снимается 8 баллов, за отсутствие снимка 4: совершенно одинаковый телефон получает +12 за настройку SwagDroid, а не за улучшение состояния. Рекомендации не должны подталкивать к повышенным правам ради оценки.
8. **Не хватает понятного разделения обычного и экспертного режима.** Системные ключи, UID, AOT, GKI, AppOps, SQL-термины и безусловные обещания идут вперемешку с бытовыми задачами.
9. **Состояние и навигация хрупкие.** Основные вкладки переключаются через `when`, без back stack/общего сохранения состояния экранов. `openVaultTrigger > 0` при новом входе на главную снова открывает историю. Связи preset/fix ↔ snapshot находятся только в ViewModel и не восстанавливаются из Room после нового процесса; карантин уже делает это лучше.
10. **Светлая тема требует проверки контраста.** RiskBadge использует яркий текст на том же цвете с alpha 0.14. При статическом sRGB-расчёте на белой поверхности контраст SAFE ≈ 2.22:1, ADVANCED ≈ 1.93:1, EXPERT ≈ 3.13:1 — ниже 4.5:1 для мелкого текста. Это расчёт цветов, не screenshot-тест.
11. **Нет полноценной системы локализованных текстов.** Почти весь текст встроен в Kotlin; единственный `strings.xml` содержит имя приложения. Это усложняет редактуру, plurals, согласованность формулировок и дальнейшую локализацию.

Источники: [оболочка](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/app/src/main/java/com/swagdroid/SwagAppShell.kt), [главная, 246–395](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/dashboard/src/main/java/com/swagdroid/feature/dashboard/DashboardScreen.kt#L246-L395), [триггер истории, 71–75](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/dashboard/src/main/java/com/swagdroid/feature/dashboard/DashboardScreen.kt#L71-L75), [штраф за права и снимок, 104–128](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/feature/dashboard/src/main/java/com/swagdroid/feature/dashboard/HealthScoreCalculator.kt#L104-L128), [RiskBadge](https://github.com/ovococjsjs-gif/SwagDroid/blob/acc3647b8b7ce07ce41395663b36691959015d86/core/designsystem/src/main/java/com/swagdroid/core/designsystem/component/Components.kt#L169-L189).

## 7. Жизненный цикл, данные и тесты

### Состояние операций

Во ViewModel длительные действия обычно имеют форму `isBusy=true → suspend-вызовы → isBusy=false`, без общего `try/finally`, структурированной ошибки и восстановления после смерти процесса. Поиск/сканирование/ART нельзя нормально отменить из интерфейса. Для обновлений разрешений и внешних настроек нет общего refresh-on-resume. Часть модулей может показывать устаревшие данные после изменения параметра в соседней вкладке.

Не следует автоматически переносить все операции в WorkManager: короткий интерактивный запрос и длительная привилегированная операция требуют разного lifecycle. Сначала нужен общий контракт и явно выбранное время жизни каждой операции.

### Что покрывают существующие тесты

| Область | Тестов | Что проверяется |
|---|---:|---|
| Privilege | 5 | Shell quoting, battery/meminfo/kernel/root-version parsers |
| StateVault | 3 | Happy path, отказ следующего простого setting-шага, блокировка нескольких критических пакетов на fake executor/store |
| Dashboard | 2 | Формула health score |
| Fixer | 5 | Каталог/поиск и парсеры, не фактическое исправление 53 сценариев |
| Optimizer | 3 | UAD JSON, round-trip профиля, icon_blacklist |
| Cleaner | 5 | Фильтры строк/путей, классификация остатков, сортировка имён, dHash |
| Adblock | 4 | Синтетические IPv4/IPv6 DNS-пакеты, доменный matcher, seed/hosts |
| Security | 5 | Bloom на собственных записях, тестовые строки майнера/DEX, hosts и агрегация |

Это полезная начальная база, но не доказательство безопасности полного сценария. Нет тестов реального файлового удаления с планом/корзиной, отмены транзакции, неудачного частично выполненного шага, process death, Room-миграций, libsu-timeout, Shizuku lifecycle, VPN lifecycle, OEM-совместимости, Compose UI и восстановления состояния.

Дополнительная деталь: fake VaultStore сортирует одинаковые timestamps по `id`, production DAO — только по времени. Следовательно, fake не доказывает полностью тот же порядок отката, что и Room. Нужен интеграционный тест.

CI следует расширить публикацией XML/HTML тест-отчётов и R8 mapping даже при ошибке, lint и инструментальными smoke-тестами; зафиксировать установку нужного Android SDK вместо зависимости только от содержимого `ubuntu-latest`.

## 8. Что сохранить, что изменить, что пока не расширять

### Сохранить и развить

- Kotlin/Compose, Hilt, существующие границы core/feature.
- Общий интерфейс `PrivilegedCommandExecutor`, но с более строгим результатом и управлением lifecycle.
- Идею StateVault и запись before-state до изменения системы.
- Обратимое управление пакетами как инструмент для осознанного пользователя.
- Паспорт устройства с источником, временем и уверенностью показаний.
- SHA-256 для точных дубликатов, защитные списки как дополнительный слой, не как абсолютную гарантию.
- Инвентаризацию tracker SDK, честно отделённую от антивирусного вердикта.
- Поиск проблем по бытовым симптомам из каталога Fixer.
- Локальный crash-log и отсутствие обнаруженного в исходниках SDK облачной аналитики.

### Переработать в первую очередь

- Политику удаления и подтверждение действий.
- Типы доступности/ошибок, план выполнения, проверку эффекта и откат.
- Семантику диагностики: не считать все отличия от дефолтов неисправностью.
- Общий источник состояния сети и пакетов между модулями.
- Первый запуск, подключение дополнительных возможностей и историю действий.
- Release-подпись, хранение схем Room и обновление без потери истории.

### Не усиливать маркетинг до появления доказательств

- «Полноценный антивирус», «0% риска», «100% защита от бутлупа», «90% Root за 45 секунд».
- Универсальные ускорители и обещания FPS/пинга без замера и поддержки устройства.
- Массовые «безопасные» пресеты, автоматически выключающие привычные/необходимые функции.
- Прошивочные рекомендации Root Advisor как гарантированную совместимость по одной строке ядра.

Документация должна различать планы и реально проверенные возможности. Старые `STAGE*`-отчёты полезны как история намерений, но не как сертификат безопасности. Для внешних баз нужны источник, версия/commit, лицензия/атрибуция и воспроизводимый импорт; общий GPL-файл не заменяет такой проверки.

## 9. Предлагаемая модель следующей версии

Это направление для обсуждения, не уже принятое ТЗ.

### Продуктовый принцип

**Швейцарский нож с понятными инструментами, а не кнопка «починить всё».** Обычный режим полезен сразу после установки. Shizuku и Root открывают дополнительные действия, но их отсутствие не делает телефон «нездоровым».

Вместо выбора технического модуля пользователь сначала выбирает задачу, например:

- «Освободить место, не потеряв важное».
- «Разобраться, почему не приходят уведомления».
- «Проверить подозрительное приложение».
- «Настроить защиту DNS».
- «Управлять ненужными приложениями».

Точный стартовый набор надо согласовать по реальным задачам владельца. Остальные инструменты могут остаться в каталоге/экспертном режиме; сокращение главного экрана не означает отказ от универсальности.

### Единый путь любого действия

```text
Задача → проверка возможностей → диагностика с доказательствами
      → план изменений + ограничения + последствия
      → подтверждение → выполнение с прогрессом/контролем отмены
      → проверка результата → запись в историю → доступный откат/восстановление
```

### Технический контракт

1. **CapabilityRegistry:** SDK/OEM, текущий Android user, разрешения, Shizuku/root, доступность команды/ресурса, аппаратная поддержка. Не только ordinal уровня прав.
2. **OperationPlan:** идентификатор, понятная цель, затрагиваемые объекты, before/after, риск, обратимость, возможная потеря связи/данных, источник доказательства.
3. **ExecutionEngine:** общий результат, сериализация конфликтующих изменений, таймаут и подтверждённое завершение, проверка postcondition, журнал по шагам.
4. **StateVault v2:** повтор сбойного восстановления, конфликты между операциями, сохранение состояния при process death, безопасные миграции.
5. **Repositories состояния:** сеть, пакеты, разрешения и история; соседние экраны наблюдают один источник, а не собственные копии.
6. **UI операции:** одинаковое объяснение доступности, подтверждение, прогресс, частичный результат, повтор и история. Технические детали доступны, но не обязательны для чтения.

Это можно внедрять последовательно внутри существующей архитектуры; создавать ещё десятки модулей заранее не нужно.

## 10. Порядок работы и критерии готовности

| Этап | Что делаем | Когда считаем готовым |
|---|---|---|
| 0. Зафиксировать реальность | Сохранить текущий APK/базу, собрать воспроизводимые проблемы на устройстве, согласовать основные задачи | Есть список сценариев, модель/Android/OEM/уровень прав, шаги и наблюдаемый результат |
| 1. Ограничить опасные пути | Очистка медиа, ghost uninstall, массовое отключение accessibility; убрать ложные гарантии | Нет скрытой необратимой операции под видом безопасной кнопки |
| 2. Укрепить ядро | Typed reads/results, capabilities, journal по шагам, ошибки/таймауты/точный откат | Негативные тесты проходят, неизвестный результат не выдаётся за успех |
| 3. Один вертикальный сценарий | Выбрать одну частую задачу и провести через новый путь от UI до проверки результата | Работает на целевой матрице устройств, понятен без знания UID/AppOps, есть история |
| 4. Новая оболочка | Главная с задачами, каталог инструментов, история, настройка доступа; обычный/экспертный режим | UI-тесты, возврат/поворот/большой шрифт, состояния отказа и отсутствия прав |
| 5. Перенос функций | Включать только операции с проверенной поддержкой и ограничениями | У каждой функции есть owner, тесты и честный статус доступности |
| 6. Подготовка выпуска | Production signing, миграции, отчёты CI, обновление документации и атрибуций | Проверено обновление установленной версии без потери данных и истории |

### Минимальная матрица последующего тестирования

- Чистая установка без Shizuku/root; отказ в каждом разрешении; повторная выдача.
- API 28–29 для заявленной legacy-поддержки; Android 11+ с scoped storage; актуальный Android на целевых телефонах.
- AOSP/Pixel-подобная система и хотя бы реальные целевые OEM (например, Xiaomi/HyperOS и Samsung/One UI), а не предположение об одинаковых командах.
- Shizuku отсутствует, установлен/остановлен, разрешён, отозван, binder погиб во время действия; Root-команда зависла или изменила только часть состояния.
- Два пресета меняют один ключ; откат старого; ручное изменение пользователем; потеря прав перед откатом; FAILED-снимок и повтор.
- Прерывание процесса посередине операции, смена ориентации/вкладки, повторное открытие, миграция Room.
- Очистка только на тестовом наборе: сохранённые медиа, protected vault-файлы, оригинал дубликата исчез/изменился, новые файлы после скана, пересекающиеся категории, symlink/path-boundary, отказ доступа.
- VPN: простой без трафика и замер CPU, потеря/смена сети, IPv4/IPv6, DNS по TCP/DoT/DoH, крупные ответы, рестарт сервиса, смена резолвера, обычный VPN рядом.
- Android secondary user/work profile: никакого неявного изменения пользователя 0.
- Экран 360 dp, увеличенный системный шрифт, светлая/тёмная тема, TalkBack, отсутствие обрезанных кнопок и доступность всех объяснений.

## 11. Следующий практический шаг

Прежде чем рисовать все новые экраны или переписывать движки, выбрать **3–5 действительно нужных пользователю задач** и **первый сквозной сценарий**. Для runtime-проверки понадобятся модель тестового телефона, версия Android/оболочки, доступность Shizuku/root и несколько конкретных примеров «нажал → ожидал → получил».

**Итог:** основу проекта можно сохранить. Приоритет — не количество функций, а доверие к каждой операции: понятная доступность, доказательство проблемы, контролируемое изменение, проверяемый результат и честное восстановление.
