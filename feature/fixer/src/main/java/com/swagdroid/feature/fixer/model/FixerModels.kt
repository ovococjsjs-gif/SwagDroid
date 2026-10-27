package com.swagdroid.feature.fixer.model

import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RiskLevel

/**
 * All 12 hardware & software subsystems of an Android smartphone covered by the Doctor Engine.
 */
enum class PhoneSubsystem(
    val shortTitleRu: String,
    val fullTitleRu: String,
    val descriptionRu: String,
) {
    NETWORK(
        shortTitleRu = "Сеть и Wi-Fi",
        fullTitleRu = "1. Wi-Fi, Мобильная сеть, DNS и Раздача",
        descriptionRu = "Captive Portal («без интернета»), Private DNS, пинг-спайки, точка доступа, сброс стека без потери паролей",
    ),
    NOTIFICATIONS(
        shortTitleRu = "Уведомления",
        fullTitleRu = "2. Пуш-уведомления, Мессенджеры и Фон",
        descriptionRu = "Реанимация пушей, Doze, корзины активности, всплывающие баннеры, Phantom Process Killer",
    ),
    BATTERY(
        shortTitleRu = "Батарея и Сон",
        fullTitleRu = "3. Автономность, Нагрев, Doze и Зарядка",
        descriptionRu = "Ночной разряд, WakeLocks, зацикленный MediaScanner, скрытые радиосканеры, контроллер заряда",
    ),
    STORAGE(
        shortTitleRu = "Память и UFS",
        fullTitleRu = "4. Накопитель UFS/F2FS, Галерея и Кэш системы",
        descriptionRu = "Аппаратный TRIM (fstrim), зависшие staging-сессии APK, индекс MediaStore, буферы логов",
    ),
    PACKAGES(
        shortTitleRu = "Установка APK",
        fullTitleRu = "5. Установка пакетов, WebView и Магазин",
        descriptionRu = "Хвосты удалённых приложений (DELETE_KEEP_DATA), вылеты WebView, вечное скачивание, App Links",
    ),
    DISPLAY(
        shortTitleRu = "Экран и Гц",
        fullTitleRu = "6. Матрица, Масштаб DPI, 120 Гц и Цвета",
        descriptionRu = "Сбитый DPI/разрешение, серый или инвертированный экран, герцовка, пропавший статус-бар",
    ),
    AUDIO(
        shortTitleRu = "Звук и BT",
        fullTitleRu = "7. Динамики, Наушники и Bluetooth-аудио",
        descriptionRu = "Тихий звук в Bluetooth TWS, перекос баланса лево/право, моно-режим, залипшая гарнитура",
    ),
    INPUT(
        shortTitleRu = "Тач и Датчики",
        fullTitleRu = "8. Сенсор экрана, Жесты, Ввод и Автоповорот",
        descriptionRu = "Input-lag тройного касания, долгий тап, кружки касаний, застрявший поворот экрана",
    ),
    GPS(
        shortTitleRu = "GPS и Карты",
        fullTitleRu = "9. Спутники GNSS, A-GPS и Геолокация",
        descriptionRu = "Долгий поиск спутников, хвосты Mock Location (антифрод банков/такси), обрывы треков в фоне",
    ),
    SECURITY(
        shortTitleRu = "Приватность",
        fullTitleRu = "10. Безопасность, Буфер обмена и Сертификаты",
        descriptionRu = "Чтение буфера обмена, сторонние сертификаты прослушки MITM, открытый порт ADB 5555, Accessibility",
    ),
    PERFORMANCE(
        shortTitleRu = "CPU и ОЗУ",
        fullTitleRu = "11. Процессор, ОЗУ, Композитор GPU и Ядро",
        descriptionRu = "Заморозка кэшированных процессов в RAM, сброс стресс-флагов GPU, тайминги ядра (KTweak)",
    ),
    OEM_SHELL(
        shortTitleRu = "Оболочки OEM",
        fullTitleRu = "12. Особенности MIUI/HyperOS, OneUI, ColorOS, HiOS",
        descriptionRu = "Рекламные демоны MSA, троттлинг Samsung GOS, спам-пуши HeyTap и PhoneMaster",
    ),
}

enum class DiagnosticVerdict(val labelRu: String) {
    NOT_CHECKED("Ожидает проверки"),
    CHECKING("Проверяется…"),
    HEALTHY("В норме"),
    ISSUE_FOUND("Найдено отклонение"),
    REVIEW_REQUIRED("Нужна ручная проверка"),
    FIXED_ACTIVE("Исправлено · есть откат"),
    NEEDS_PRIVILEGE("Нужен ADB / Root"),
    NOT_APPLICABLE("Не для этой марки"),
}

data class FixPlaybook(
    val id: String,
    val subsystem: PhoneSubsystem,
    val symptomTitle: String,
    val rootCauseExplanation: String,
    val fixActionSummary: String,
    val searchKeywords: List<String>,
    val requiredTier: PrivilegeTier,
    val risk: RiskLevel,
    val supportsVaultRollback: Boolean = true,
    val vendorFilter: List<String> = emptyList(),
) {
    fun matchesQuery(rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return true
        return symptomTitle.lowercase().contains(q) ||
            rootCauseExplanation.lowercase().contains(q) ||
            fixActionSummary.lowercase().contains(q) ||
            subsystem.shortTitleRu.lowercase().contains(q) ||
            searchKeywords.any { it.lowercase().contains(q) }
    }
}

data class PlaybookRuntimeState(
    val playbook: FixPlaybook,
    val verdict: DiagnosticVerdict = DiagnosticVerdict.NOT_CHECKED,
    val liveEvidence: String = "Нажмите «Проверить» или запустите полную диагностику смартфона",
    val linkedSnapshotId: Long? = null,
    val isBusy: Boolean = false,
)

data class InstalledAppBrief(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

data class AppRescueAuditResult(
    val packageName: String,
    val appLabel: String,
    val inDozeWhitelist: Boolean,
    val standbyBucket: String,
    val runInBackgroundMode: String,
    val runAnyInBackgroundMode: String,
    val postNotificationMode: String,
    val isSuspendedOrDisabled: Boolean,
) {
    val hasProblems: Boolean
        get() = !inDozeWhitelist ||
            standbyBucket in setOf("rare", "restricted", "never", "40", "45", "50") ||
            runInBackgroundMode.contains("ignore") ||
            runInBackgroundMode.contains("deny") ||
            runAnyInBackgroundMode.contains("ignore") ||
            runAnyInBackgroundMode.contains("deny") ||
            postNotificationMode.contains("ignore") ||
            postNotificationMode.contains("deny") ||
            isSuspendedOrDisabled
}
