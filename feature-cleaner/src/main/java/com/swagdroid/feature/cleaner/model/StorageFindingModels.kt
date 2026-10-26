package com.swagdroid.feature.cleaner.model

import com.swagdroid.api.PrivilegeTier
import kotlinx.serialization.Serializable

/**
 * Категория обнаруженного объекта в хранилище.
 * Чётко отделяет безопасный системный кэш от пользовательских медиа, бэкапов и диагностических логов.
 */
@Serializable
enum class StorageCategory(
    val titleRu: String,
    val isSafeForAutoClean: Boolean,
) {
    STANDARD_APP_CACHE(
        titleRu = "Стандартный кэш приложения (getCacheDir / внешний cache)",
        isSafeForAutoClean = true,
    ),
    VERIFIED_TEMP_RULE(
        titleRu = "Временные служебные файлы по проверенному правилу",
        isSafeForAutoClean = true,
    ),
    CORPSE_APP_SPECIFIC(
        titleRu = "Остатки удалённого приложения (Android/data, media, obb)",
        isSafeForAutoClean = false, // После превью пользователя
    ),
    CORPSE_PRIVATE_ROOT(
        titleRu = "Приватные остатки в /data/user (Экспертный Root-режим)",
        isSafeForAutoClean = false,
    ),
    CORPSE_PUBLIC_INVENTORY(
        titleRu = "Папка в корне памяти (Сопоставлена через Uninstall Watcher)",
        isSafeForAutoClean = false,
    ),
    MESSENGER_USER_MEDIA_REVIEW(
        titleRu = "Мессенджеры: Пользовательские фото/видео (Только ручной обзор)",
        isSafeForAutoClean = false,
    ),
    MESSENGER_EXACT_DUPLICATE(
        titleRu = "Мессенджеры: Точный дубликат медиа (SHA-256 совпадает с оригиналом в Галерее)",
        isSafeForAutoClean = false,
    ),
    MESSENGER_BACKUP_PROTECTED(
        titleRu = "Мессенджеры: Резервные копии чатов (Защищены от автоудаления)",
        isSafeForAutoClean = false,
    ),
    DOWNLOADED_APK_INSTALLER(
        titleRu = "Установочный файл APK в папке Загрузки",
        isSafeForAutoClean = false,
    ),
    SYSTEM_STAGE_AND_CRASH_DIAGNOSTIC(
        titleRu = "Системная диагностика (vmdl.tmp / ANR / Tombstones — только отчёт)",
        isSafeForAutoClean = false,
    ),
}

/**
 * Уровень уверенности в принадлежности каталога/файла.
 */
@Serializable
enum class OwnershipConfidence(val displayNameRu: String) {
    HIGH("Высокая уверенность (App-specific каталог, пакет отсутствует у всех пользователей)"),
    HIGH_ROOT("Высокая уверенность Root (/data/user/<id>/<pkg> при удалённом пакете)"),
    MEDIUM("Средняя уверенность (Совпадение по базе правил или снимку Uninstall Watcher)"),
    LOW_KEEP("Низкая уверенность / НЕ ТРОГАТЬ (Пользовательские данные, бэкапы, сохранения)"),
}

/**
 * Строго ограниченный набор допустимых действий над `StorageFinding`.
 * Важно: перемещение в корзину MediaStore НЕ освобождает место мгновенно — это явно отражается в UI!
 */
@Serializable
enum class CleanerActionType(
    val actionTitleRu: String,
    val freesSpaceImmediately: Boolean,
) {
    CLEAR_DEFAULT_CACHE(
        actionTitleRu = "Очистить стандартный кэш через PackageManager",
        freesSpaceImmediately = true,
    ),
    DELETE_BY_VERIFIED_RULE(
        actionTitleRu = "Удалить временные файлы (Место освобождается сразу)",
        freesSpaceImmediately = true,
    ),
    MEDIA_STORE_TRASH(
        actionTitleRu = "Переместить в системную Корзину MediaStore (Место освободится после очистки корзины)",
        freesSpaceImmediately = false,
    ),
    MEDIA_STORE_DELETE(
        actionTitleRu = "Удалить окончательно (Место освобождается сразу)",
        freesSpaceImmediately = true,
    ),
    OPEN_IN_APP_STORAGE_MANAGER(
        actionTitleRu = "Открыть встроенный менеджер памяти приложения (Telegram / WhatsApp)",
        freesSpaceImmediately = false,
    ),
    REPORT_ONLY_DO_NOT_TOUCH(
        actionTitleRu = "Только диагностика / Защищено от удаления",
        freesSpaceImmediately = false,
    ),
}

/**
 * Единая предметная единица результата сканирования хранилища (`StorageFinding`).
 */
@Serializable
data class StorageFinding(
    val findingId: String,
    val canonicalPath: String,
    val ownerPackage: String?,
    val category: StorageCategory,
    val bytesLogical: Long,
    val bytesAllocated: Long,
    val confidence: OwnershipConfidence,
    val evidenceRule: String,
    val explanationRu: String,
    val minPrivilege: PrivilegeTier,
    val action: CleanerActionType,
    val reversible: Boolean,
)
