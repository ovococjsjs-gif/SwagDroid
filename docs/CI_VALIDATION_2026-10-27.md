# Штатная CI-проверка этапов стабилизации 1–2

**Дата:** 27 октября 2026 года.

**Результат: SUCCESS.** Полный существующий pipeline прошёл с первого прогона нового кода: настоящие Gradle/JUnit unit-тесты, Debug APK и R8 Release APK.

- **Проверенный коммит:** [`9623ac0053366309dbcdd60be7b7d9a1abfaf0ff`](https://github.com/ovococjsjs-gif/SwagDroid/commit/9623ac0053366309dbcdd60be7b7d9a1abfaf0ff).
- **Ветка:** `arena/975757fb-swagdroid`.
- **Run:** [40432680309](https://github.com/ovococjsjs-gif/SwagDroid/actions/runs/40432680309).
- **Job:** [Unit Tests + Debug & R8 Release APK](https://github.com/ovococjsjs-gif/SwagDroid/actions/runs/40432680309/job/121953896942).
- Время job: 04:27:37–04:32:27 МСК, **4 мин 50 сек**.
- `main` не менялся: `acc3647b8b7ce07ce41395663b36691959015d86`. Pull request и release не создавались.

> **Продолжение в тот же день:** [Android smoke на API 35](ANDROID_SMOKE_RESULT_2026-10-27.md) завершён успешно. Ограничения этого первого CI-отчёта ниже сохранены как исторический срез.

## 1. Тесты

Числа взяты из **JUnit XML**, прочитанных `scripts/summarize-junit.py` на runner и опубликованных CI-аннотациями. Это уже не прежний локальный compatibility harness.

| Модуль | Тесты | Failures | Errors | Пропуски |
|---|---:|---:|---:|---:|
| `core:privilege` | 36 | 0 | 0 | 0 |
| `core:vault` | 62 | 0 | 0 | 0 |
| `feature:adblock` | 4 | 0 | 0 | 0 |
| `feature:cleaner` | 34 | 0 | 0 | 0 |
| `feature:dashboard` | 2 | 0 | 0 | 0 |
| `feature:fixer` | 10 | 0 | 0 | 0 |
| `feature:optimizer` | 3 | 0 | 0 | 0 |
| `feature:security` | 5 | 0 | 0 | 0 |
| **Итого: 14 XML-отчётов** | **156** | **0** | **0** | **0** |

В частности, прошли **13 тестов `SnapshotEntityTest` с настоящим kotlinx.serialization**, которые ранее не удавалось запустить локально.

Успешно выполнены:

```bash
python3 scripts/verify-vault-migration.py
./gradlew testDebugUnitTest --continue --stacktrace --no-daemon
./gradlew assembleDebug assembleRelease --continue --stacktrace --no-daemon
```

Android-код, Compose, Hilt/KSP и Room скомпилированы; Release собран с включёнными R8/minification и resource shrinking. Ошибок приложения по результатам этого CI-прогона исправлять не потребовалось.

## 2. Room

KSP сгенерировал схему БД v2. Её копия сохранена как [CI-доказательство](ci-evidence/2026-10-27/room-schema-2.json), не как вручную угаданный schema-файл.

Дополнительно на host SQLite сравнили результат настоящего `MIGRATION_1_2` со схемой, созданной из сгенерированного Room `createSql`: столбцы, типы, nullability, defaults, primary key, foreign keys и индексы совпадают. Smoke-test также подтвердил сохранение старых строк, ID и сырого JSON, включая непарсящийся payload.

**Это не Android-инструментационный тест миграции:** реальный SQLiteOpenHelper/Room при обновлении установленного приложения на телефоне ещё необходимо проверить.

## 3. APK-артефакты

| Вариант | Скачать через GitHub | Размер архива по API |
|---|---|---:|
| Debug | [SwagDroid-v1.0.0-debug-apk](https://github.com/ovococjsjs-gif/SwagDroid/actions/runs/40432680309/artifacts/12703270900) | 13 463 282 байта |
| Release с R8 | [SwagDroid-v1.0.0-release-apk](https://github.com/ovococjsjs-gif/SwagDroid/actions/runs/40432680309/artifacts/12702992261) | 2 304 355 байт |

Это размеры GitHub artifact-архивов, не измеренные размеры распакованных APK. Артефакты не истекли на момент проверки; для скачивания может потребоваться вход в GitHub.

Скачать APK внутрь sandbox не удалось: архивы выдаются через внешний storage-host, недоступный в этой среде (`EOF`). Поэтому содержимое APK и фактические сертификаты подписи **локально не инспектировались**. В Gradle release сохраняет fallback на debug signing при отсутствии отдельного keystore — успешная Release-сборка сама по себе не означает готовность production-подписи.

Для первых тестов предпочтителен отдельный тестовый профиль/эмулятор и Debug-вариант (`com.swagdroid.debug` по конфигурации). Не удаляйте единственную рабочую установку и её историю ради обхода ошибки несовпадающей подписи.

## 4. Как обошлись без расширения прав GitHub

Первая отправка была отклонена из-за отсутствия у GitHub App права `workflows`. Действующий `.github/workflows/build.yml` оставлен **без изменений относительно `main`**. Код проверен существующим workflow, который уже запускал `scripts/ci-build.sh`.

В разрешённом скрипте сборки добавлены SQL smoke-test и сводка реальных тестов. При отсутствии/повреждении XML не выводится фиктивный успех. Тестовые итоги доступны в job summary и аннотациях; сгенерированная Room-схема также опубликована аннотацией.

Расширенный workflow с дополнительным архивом отчётов остался предложением в [docs/ci](ci/github-actions-build.yml), но не устанавливался. Дополнительные права и токены не запрашивались.

Машиночитаемые сведения о run, шагах, тестах и артефактах: [validation.json](ci-evidence/2026-10-27/validation.json).

## 5. Что этот успех не подтверждает

- Запуск интерфейса, разрешения, Accessibility/TalkBack и поведение на реальном телефоне.
- Работу Shizuku/Sui/Magisk/KernelSU, отзыв прав, смерть Binder и Root-команд на конкретном OEM.
- Android-миграцию реальной базы v1, её работу после внезапной потери питания.
- Полную безопасность опасных системных действий или готовность приложения к production-релизу.

Следующий контрольный шаг — smoke-тесты на тестовом Android: сначала запуск и BASIC-режим, затем Shizuku, журнал/откат и обновление базы; Root — отдельно. Прежние ограничения старых снимков и неподтверждаемых shell-операций остаются в силе.

После успешного CI добавлены только этот отчёт, JSON-доказательства и ссылки в документации. Документационный коммит помечен `[skip ci]`: код приложения, тесты и скрипты проверенной сборки не изменялись. Приведённые APK и результаты относятся именно к `9623ac0`.
