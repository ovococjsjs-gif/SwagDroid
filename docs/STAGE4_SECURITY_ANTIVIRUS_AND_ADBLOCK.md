# Этап 4 — Реальный Антивирус, Анти-Майнер, Радар трекеров и Адаптивный 3-режимный AdBlock (`:feature:security` + `:feature:adblock`)

На четвёртом этапе завершена реализация всех 5 главных экранов **SwagDroid**: подключены модули **`:feature:adblock`** и **`:feature:security`**, а временная заглушка на вкладке `SwagTab.SECURITY` полностью заменена на боевой центр безопасности `SecurityCenterRoute`.

---

## 1. Архитектура Антивируса и Анти-Майнера (`:feature:security`)

Архитектура построена по инженерному паттерну **BlendLog/MinerSearch** (`ThreatScanners → ThreatAnalyzers → Prioritized ThreatDecisions → Quarantine`) в сочетании с компактным фильтром Блума (**Divested-Mobile/Hypatia**) и прямым DEX-анализом трекеров (**Exodus Privacy**):

1. **Фильтр Блума `BloomSignatureFilter` (`Kirsch-Mitzenmacher` поверх `SHA-256`)**:
   - Мгновенная `O(1)` проверка хешей файлов в `/sdcard/Download` и установленных пакетов без сетевых запросов и с **0% ложноотрицательных срабатываний** (включая эталонный тестовый маркер `EICAR-Test-Signature` и базу маскирующихся банковских троянов / дропперов).
2. **Охотник на скрытые криптомайнеры (`CryptoMinerHunter`)**:
   - Потоковая инспекция ZIP-контейнера установленных APK (`lib/*.so` и `assets/*`) с ограничением памяти на предмет бинарных ядер и пулов скрытой добычи (`xmrig`, `stratum+tcp://`, `stratum+ssl://`, `randomx`, `cryptonight`, `minexmr`, `nanopool`, `moneroocean`, `c3pool`, `hashvault`).
3. **Детектор призрачного Adware и дропперов (`GhostAdwareAndDropper`)**:
   - Ловит рекламные трояны семейства **HiddenAds**, которые намеренно стирают имя иконки (`applicationLabel == ""`) либо прячутся из лаунчера (`getLaunchIntentForPackage == null`), имея при этом связку `SYSTEM_ALERT_WINDOW` + `RECEIVE_BOOT_COMPLETED`.
4. **Аудит злоупотребления спецправами (`Banking Accessibility Audit`)**:
   - Проверяет реально активные сервисы `Settings.Secure:enabled_accessibility_services` и `enabled_notification_listeners` в опасной комбинации с перехватом SMS (`READ_SMS` / `RECEIVE_SMS`), рисованием поверх банковских окон и установкой сторонних пакетов (`REQUEST_INSTALL_PACKAGES`).
5. **Защита сети и целостности системы (`HostsThreatAnalyzer` + MITM/Proxy Audit)**:
   - Портированный анализатор `/system/etc/hosts`: отличает легитимные правила AdBlock (`0.0.0.0` / `127.0.0.1`) от фишинговой подмены банковских/почтовых доменов на сторонние внешние IP-адреса.
   - Проверяет перехват трафика через `Settings.Global:http_proxy` (с лечением в 1 тап через `StateVault`) и наличие сторонних корневых сертификатов в `/data/misc/user/0/cacerts-added`.
6. **Обратимый Карантин (`StateVault Quarantine`)**:
   - Изоляция пакета мгновенно выполняет `am force-stop`, отзывает через `AppOps` права `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES` и `RUN_ANY_IN_BACKGROUND`, переводит пакет в состояние `SUSPENDED` и сохраняет транзакционную точку отката в `StateVault` (можно восстановить в 1 тап или окончательно удалить APK).
7. **Радар трекеров (`Exodus DEX Inspector`)**:
   - Прямой потоковый байт-анализ таблиц строк `classes*.dex` внутри установленных приложений по **32 реальным сигнатурам SDK Exodus Privacy** (`Google AdMob`, `AppMetrica`, `VK MyTracker`, `AppsFlyer`, `Adjust`, `AppLovin`, `Unity Ads`, `ironSource`, `Mintegral`, `TikTok Pangle`, `Start.io`, `Mixpanel`, `OneSignal` и др.) + кнопка мгновенной блокировки фонового трафика (`cmd netpolicy add restrict-background-blacklist`).

---

## 2. Адаптивный 3-режимный AdBlock & DNS-фаервол (`:feature:adblock`)

Вместо навязывания одного компромиссного способа блокировки рекламы SwagDroid адаптируется под уровень прав устройства:

| Режим | Требуемые права | Как работает | Главное преимущество |
|---|---|---|---|
| **Режим 1 · Локальный DNS-щит (`SwagDnsVpnService`)** | `Tier 1: Без Root` | Split-route `VpnService` (`10.215.173.2/32`) заворачивает в туннель **только UDP:53 DNS-запросы**; парсер `DnsPacketEngine` (`RFC-1035`) мгновенно отдаёт `NXDOMAIN` с валидной контрольной суммой IPv4 | 99.9% обычного трафика (видео, игры, торренты) идёт мимо туннеля без потери скорости и расхода батареи |
| **Режим 2 · Системный DoT (`Private DNS`)** | `Tier 2: Wireless ADB / Shizuku` | Прописывает защищённый `DNS-over-TLS` резолвер (`AdGuard`, `ControlD`, `Quad9`) в `Settings.Global` с точкой отката `StateVault` | **Слот Android VPN остаётся на 100% свободным** для одновременной работы любого рабочего или личного VPN! |
| **Режим 3 · Systemless `/etc/hosts`** | `Tier 3: Root (Magisk / KernelSU / APatch)` | Автоматически формирует оверлей модуля `/data/adb/modules/hosts/system/etc/hosts` из офлайн-базы и пользовательских правил | **0 МБ ОЗУ и 0% расхода батареи** на уровне системного резолвера ядра/Bionic |

### Дополнительные возможности `:feature:adblock`:
- **Суффиксное дерево блокировки (`O(k)` по меткам домена)**: одно правило `doubleclick.net` автоматически режет любые вложенные поддомены `*.ads.doubleclick.net`, а пользовательский белый список имеет гарантированный приоритет.
- **Офлайн-база `swag_blocklist_seed.txt`**: работает сразу после установки без интернета и включает рекламные сети, системную телеметрию OEM (`Xiaomi MSA`, `ColorOS/Realme`, `Samsung Ads`, `HiCloud`) и пулы браузерного/скрытого майнинга (`NoCoin`: `minexmr`, `nanopool`, `moneroocean`, `coinhive`, `c3pool`, `hashvault`).
- **Живой журнал DNS-запросов** в реальном времени и редактор собственных правил блокировки/исключений.
