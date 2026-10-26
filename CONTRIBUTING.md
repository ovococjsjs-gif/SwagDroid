# Руководство контрибьютора SwagDroid (Contributing Guide)

Спасибо за интерес к развитию **SwagDroid**! Чтобы приложение оставалось безопасным и предсказуемым на всех прошивках (AOSP, Xiaomi HyperOS/MIUI, Samsung One UI, Realme/ColorOS/OxygenOS), все изменения должны соблюдать следующие инженерные правила:

## 1. Правила добавления новых тумблеров Оптимизатора (`TogglePassport`)
- Каждый новый тумблер в `:feature-optimizer` обязан иметь полный контракт `TogglePassport`:
  - Чтение реального состояния (`readToggleActualState`) и проверка после записи (`ReadBack Verifier`).
  - Честное описание побочных эффектов в `sideEffectsWarningRu` (например, влияние на точность геопозиции в помещениях или нагрев).
  - Точное указание `supportedVendors` и диапазона `minSdk..maxSdk`.

## 2. Правила пополнения базы Деблоатера
- Никогда не помечайте пакеты как `RECOMMENDED`, если их отключение ломает базовые функции оболочки.
- Критические системные пакеты должны быть добавлены в `CriticalPackageGuard` (`:api`) со статусом `UNSAFE_PROTECTED`.

## 3. Правила добавления рецептов в Программный Фиксер (`:feature-fixer`)
- **Никаких сырых shell-команд в описаниях рецептов!** Каждый рецепт (`FixPlaybookRecipe`) может ссылаться только на проверенные в Kotlin-коде `TypedDiagnosticReaderId` и `TypedFixActionId`.
- Указывайте честный класс обратимости (`EXACT_REVERSIBLE`, `PARTIALLY_REVERSIBLE` или `IRREVERSIBLE_MAINTENANCE`) и окно верификации `verificationWindowMinutes`.

## 4. Тестирование перед отправкой Pull Request
Перед открытием PR убедитесь, что проходят все модульные тесты:
```bash
gradle testDebugUnitTest
```
