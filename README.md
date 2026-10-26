<p align="center">
  <img src="docs/assets/swagdroid_icon.png" width="168" alt="SwagDroid Official Logo" />
</p>

<h1 align="center">SwagDroid v1.0.0 🛡️⚡</h1>

<p align="center">
  <b>Открытый швейцарский нож для Android-смартфонов (GPL-3.0)</b><br/>
  Программный ремонт 12 подсистем с точками отката <b>StateVault</b> · Умная очистка с бронёй паролей · Оптимизатор PRO + Debloater (4 123 пакета UAD-ng) · Реальный Антивирус / Анти-Майнер · 3-режимный AdBlock (IPv4 + IPv6)
</p>

---

## ✨ Ключевые инженерные преимущества `v1.0.0`

1. **Трёхуровневое ядро привилегий (`:core:privilege`) без ПК и потери гарантии**:
   - **Tier 1 — Базовый (`No-Root`)**: работает сразу после установки APK.
   - **Tier 2 — Суперправа за 45 секунд (`Wireless ADB + Shizuku`, `UID 2000`)**: активируется прямо с экрана смартфона (Android 11+) по 6-значному коду сопряжения — открывает ~90% системных возможностей **без разблокировки загрузчика, без потери данных и с полностью рабочими банковскими приложениями и NFC**.
   - **Tier 3 — Полный Root (`libsu` + `Sui / Shizuku UID 0`: Magisk, KernelSU, APatch)** + встроенный **Root-советник** с анализом GKI 2.0, A/B слотов и Verified Boot.
   - Встроенный сторожевой таймер команд (`35 с` / `180 с`) с защитой от зависаний системных утилит.
2. **Транзакционный движок точек восстановления (`:core:vault` / `StateVault` + `SystemBrickGuard`)**:
   - Сериализованные через `Mutex` транзакции с записью точного состояния `Settings.{Global,Secure,System}`, пакетов и `AppOps` в Room **до** внесения изменений и 1-тап `LIFO`-откатом.
   - **Анти-бутлуп щит `SystemBrickGuard`**: математически исключает заморозку или отключение критических пакетов Android (`systemui`, `settings`, `phone`, `packageinstaller`, `permissioncontroller`, `shizuku`, `magisk`, `kernelsu`, `swagdroid`) из любого модуля или JSON-профиля.
3. **5 боевых модулей без рекламы, трекеров и облачной телеметрии**:
   - **Пульс и Паспорт железа (`:feature:dashboard`)**: индекс здоровья `0–100`, износ и циклы АКБ, UFS/RAM/ZRAM, мастер Wireless ADB, Root-советник и экспорт `StateVault` в JSON.
   - **Фиксер проблем (`:feature:fixer`)**: **53 сценария** автодиагностики и ремонта по **всем 12 подсистемам телефона** + 5-точечный **Реаниматор пуш-уведомлений**.
   - **Оптимизатор PRO v2 (`:feature:optimizer`)**: 7 пресетов + портативные JSON-профили, 24 системных/ядерных/сетевых твика, Тюнер статус-бара и Immersive-режима, Android Game Mode + ART `dex2oat` и Debloater на **4 123 пакета `UAD-ng`** на русском языке.
   - **Умная очистка (`:feature:cleaner`)**: двухконтурная **Броня паролей, аккаунтов, 2FA и автозаполнения (включена по умолчанию, 0 вызовов `pm clear`)**, защита от `symlink-escape` атак, `CorpseFinder`, Сейф мессенджеров (Telegram/WhatsApp/VK/TikTok/Viber) и `SHA-256` + `dHash` дедупликатор фото.
   - **Антивирус, Анти-Майнер и AdBlock 3-в-1 (`:feature:security` + `:feature:adblock`)**: фильтр Блума `SHA-256`, потоковый `64 КБ` сканер скрытых криптомайнеров (`XMRig`/`Stratum`) и `Exodus` трекеров (`32 SDK` в `classes.dex`), персистентный Карантин `StateVault`, а также Dual-Stack (`IPv4 + IPv6`) DNS-щит без Root, системный `Private DNS DoT` без занятия слота VPN и `Systemless /etc/hosts` для Root.

---

## 🏗️ Структура 11 модулей проекта

```text
SwagDroid/
├── app/                   # Оболочка v1.0.0, адаптивные иконки SwagDroid, R8-правила, офлайн Crash Black-Box
├── core/
│   ├── model/             # Доменные модели привилегий, паспорта железа и полиморфных операций StateVault
│   ├── designsystem/      # Тема Material 3 (OLED Dark / Light), векторные иконки, фирменная эмблема
│   ├── privilege/         # Движок команд (Local / Shizuku UID 2000 / Sui & libsu UID 0) с таймаут-защитой
│   └── vault/             # Транзакционное хранилище Room + Mutex + анти-бутлуп щит SystemBrickGuard
├── feature/
│   ├── dashboard/         # Экран «Пульс», мастер Беспроводного ADB, Root-советник и экспорт StateVault JSON
│   ├── fixer/             # Доктор системы: 53 диагностики и авто-ремонта по 12 подсистемам + Реаниматор пушей
│   ├── optimizer/         # Оптимизатор PRO v2: 7 пресетов + JSON, 24 твика, Тюнер UI, Game Mode и 4 123 UAD-ng
│   ├── cleaner/           # Умная очистка с Бронёй паролей/аккаунтов, защитой от симлинков и Дедупликатором
│   ├── adblock/           # 3-режимный AdBlock (Dual-Stack IPv4/IPv6 DNS VPN / DoT без слота VPN / Root hosts)
│   └── security/          # Антивирус (Bloom SHA-256), Анти-Майнер, персистентный Карантин и Exodus DEX Радар
├── scripts/
│   └── build-release.sh   # Единый скрипт запуска всех JUnit-тестов и сборки Debug + Release APK
└── docs/
    ├── RESEARCH_AND_PLAN.md                       # Инженерный ресерч и дорожная карта проекта
    ├── STAGE2_FIXER_AND_OPTIMIZER_CATALOG.md      # Каталог 12 подсистем Фиксера и базы Оптимизатора
    ├── STAGE3_CLEANER_AND_OPTIMIZER_PRO.md        # Архитектура Брони паролей и Оптимизатора PRO v2
    ├── STAGE4_SECURITY_ANTIVIRUS_AND_ADBLOCK.md   # Архитектура Антивируса/Анти-Майнера и AdBlock 3-в-1
    ├── STAGE5_RELEASE_AUDIT_AND_BUILD.md          # Финальный security-аудит, харденинг и выпуск v1.0.0
    └── ci/github-actions-build.yml                # Workflow облачной сборки Debug + R8 Release APK
```

---

## 🚀 Полная сборка `SwagDroid v1.0.0`

### Вариант 1 — Скриптом в 1 команду (JDK 21 + Android SDK 37)
```bash
./scripts/build-release.sh
```
Скрипт последовательно запускает модульные тесты всех 11 модулей (`testDebugUnitTest`), собирает отладочный `app-debug.apk` (`com.swagdroid.debug`) и сжатый R8 подписанный релизный **`app/build/outputs/apk/release/app-release.apk`** (`com.swagdroid` `v1.0.0`), готовый к прямой установке на смартфон.

### Вариант 2 — Облачная сборка в GitHub Actions (без локального Android SDK)
Поместите [`docs/ci/github-actions-build.yml`](docs/ci/github-actions-build.yml) в `.github/workflows/build.yml` на GitHub и запустите workflow **`SwagDroid v1.0.0 Full Build & Tests`** — готовые APK появятся во вкладке **Artifacts**.

---

## 📄 Лицензия
Проект распространяется под открытой лицензией **[GNU GPL v3.0](LICENSE)**.
