package com.swagdroid.feature.cleaner.downloads

import com.swagdroid.api.PackageSummary
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.OwnershipConfidence
import com.swagdroid.feature.cleaner.model.StorageCategory
import com.swagdroid.feature.cleaner.model.StorageFinding
import com.swagdroid.feature.cleaner.path.StoragePathCanonicalizer
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Метаданные распарсенного установочного файла `.apk` / `.apks` / `.xapk` в папке `Download/`.
 */
@Serializable
data class ParsedApkArchiveInfo(
    val absolutePath: String,
    val fileSizeBytes: Long,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signingCertSha256: String?,
)

/**
 * Точная классификация установочного APK относительно уже установленных приложений.
 */
@Serializable
enum class DownloadedApkStatus(val titleRu: String) {
    ALREADY_INSTALLED_SAME_VERSION("Уже установлена точно эта же версия (Безопасно удалить установщик)"),
    OBSOLETE_OLDER_THAN_INSTALLED("Устаревший APK (В системе уже установлена более новая версия)"),
    PENDING_NEWER_UPDATE("Ожидающее обновление! (Файл в Загрузках НОВЕЕ установленной версии — НЕ мусор)"),
    SIGNATURE_MISMATCH_WARNING("Внимание: Пакет установлен, но у APK в Загрузках другой сертификат подписи!"),
    NOT_INSTALLED_POSSIBLE_BACKUP("Приложение не установлено (Возможный резервный APK пользователя — не трогать по умолчанию)"),
}

/**
 * Классификатор APK в `Download/` и безопасный диагностический инспектор системных каталогов
 * (`/data/app/vmdl*.tmp`, `/data/anr`, `/data/tombstones`).
 */
class DownloadAndSystemDiagnosticsAnalyzer {

    /**
     * Классифицирует скачанный `.apk` по сравнению со списком установленных в системе пакетов.
     */
    fun classifyDownloadedApk(
        apkInfo: ParsedApkArchiveInfo,
        installedPackagesByName: Map<String, PackageSummary>,
    ): Pair<DownloadedApkStatus, StorageFinding> {
        val canonical = StoragePathCanonicalizer.canonicalize(apkInfo.absolutePath)
        val installed = installedPackagesByName[apkInfo.packageName]

        val status: DownloadedApkStatus
        val confidence: OwnershipConfidence
        val action: CleanerActionType
        val explanation: String

        if (installed == null) {
            status = DownloadedApkStatus.NOT_INSTALLED_POSSIBLE_BACKUP
            confidence = OwnershipConfidence.LOW_KEEP
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH
            explanation = "Пакет ${apkInfo.packageName} (v${apkInfo.versionName}) сейчас не установлен. " +
                "Файл сохранён как возможный дистрибутив/бэкап пользователя."
        } else if (
            !apkInfo.signingCertSha256.isNullOrBlank() &&
            !installed.signingCertSha256.isNullOrBlank() &&
            !apkInfo.signingCertSha256.equals(installed.signingCertSha256, ignoreCase = true)
        ) {
            status = DownloadedApkStatus.SIGNATURE_MISMATCH_WARNING
            confidence = OwnershipConfidence.LOW_KEEP
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH
            explanation = "ВНИМАНИЕ: Пакет ${apkInfo.packageName} установлен, но подпись APK в Загрузках отличается от системной!"
        } else if (apkInfo.versionCode == installed.versionCode) {
            status = DownloadedApkStatus.ALREADY_INSTALLED_SAME_VERSION
            confidence = OwnershipConfidence.HIGH
            action = CleanerActionType.MEDIA_STORE_TRASH
            explanation = "Точная версия v${apkInfo.versionName} (${apkInfo.versionCode}) уже установлена в системе."
        } else if (apkInfo.versionCode < installed.versionCode) {
            status = DownloadedApkStatus.OBSOLETE_OLDER_THAN_INSTALLED
            confidence = OwnershipConfidence.HIGH
            action = CleanerActionType.MEDIA_STORE_TRASH
            explanation = "Устаревший установщик (v${apkInfo.versionCode}); в системе уже стоит более новая версия (v${installed.versionCode})."
        } else {
            status = DownloadedApkStatus.PENDING_NEWER_UPDATE
            confidence = OwnershipConfidence.LOW_KEEP
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH
            explanation = "Скачанный APK (v${apkInfo.versionCode}) НОВЕЕ установленной версии (v${installed.versionCode}). Защищён от удаления!"
        }

        val finding = StorageFinding(
            findingId = UUID.randomUUID().toString(),
            canonicalPath = canonical,
            ownerPackage = apkInfo.packageName,
            category = StorageCategory.DOWNLOADED_APK_INSTALLER,
            bytesLogical = apkInfo.fileSizeBytes,
            bytesAllocated = apkInfo.fileSizeBytes,
            confidence = confidence,
            evidenceRule = "DOWNLOAD_APK_${status.name}",
            explanationRu = explanation,
            minPrivilege = PrivilegeTier.NONE,
            action = action,
            reversible = true,
        )
        return status to finding
    }

    /**
     * Безопасная диагностика `/data/app/vmdl<sessionId>.tmp`, `/data/anr` и `/data/tombstones`.
     *
     * Никогда не делает автоматический `rm -rf`:
     * - для `vmdl*.tmp` проверяет, не принадлежит ли папка активной сессии `PackageInstaller`;
     * - для `tombstones` и `anr` формирует диагностическую сводку для модуля Фиксера проблем.
     */
    fun inspectStageSessionDirectory(
        stageDirPath: String,
        sizeBytes: Long,
        ageMillis: Long,
        activeInstallerSessionIds: Set<Int>,
    ): StorageFinding {
        val canonical = StoragePathCanonicalizer.canonicalize(stageDirPath)
        val sessionId = Regex("vmdl(\\d+)\\.tmp").find(canonical)?.groupValues?.get(1)?.toIntOrNull()
        val isActiveSession = sessionId != null && sessionId in activeInstallerSessionIds

        val explanation = if (isActiveSession) {
            "Активная сессия установки PackageInstaller (#$sessionId). Каталог трогать категорически нельзя!"
        } else {
            "Незавершённый stage-каталог установки (возраст: ${ageMillis / 3600_000L} ч). " +
                "Рекомендуемый безопасный фикс — обычная перезагрузка телефона (PackageInstaller очистит сироту автоматически)."
        }

        return StorageFinding(
            findingId = UUID.randomUUID().toString(),
            canonicalPath = canonical,
            ownerPackage = null,
            category = StorageCategory.SYSTEM_STAGE_AND_CRASH_DIAGNOSTIC,
            bytesLogical = sizeBytes,
            bytesAllocated = sizeBytes,
            confidence = OwnershipConfidence.LOW_KEEP,
            evidenceRule = if (isActiveSession) "ACTIVE_INSTALL_SESSION_DO_NOT_TOUCH" else "STALE_VMDL_REBOOT_RECOMMENDED",
            explanationRu = explanation,
            minPrivilege = PrivilegeTier.ROOT,
            action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
            reversible = true,
        )
    }
}
