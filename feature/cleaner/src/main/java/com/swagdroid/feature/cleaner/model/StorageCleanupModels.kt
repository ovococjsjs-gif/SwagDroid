package com.swagdroid.feature.cleaner.model

import java.io.File

/** This flow sees primary shared storage through the app UID, not another app's private data. */
enum class StorageAccess(val canScan: Boolean) {
    ALL_FILES(true), LEGACY_GRANTED(true), NEEDS_ALL_FILES(false), NEEDS_LEGACY_PERMISSIONS(false),
}

data class StorageSpace(val availableBytes: Long, val totalBytes: Long)

enum class CleanupBucket(val title: String, val explanation: String) {
    TEMPORARY("Временные файлы", "Название похоже на временный файл или журнал. Это не доказательство, что он больше не нужен."),
    DOWNLOADS("Загрузки и другие файлы", "Ваши файлы. Выбирайте только то, что больше не нужно или уже сохранено в другом месте."),
    MEDIA("Фото, видео и аудио", "Здесь могут быть единственные экземпляры. Наличие копии в облаке не проверяется."),
    INSTALLERS("Установочные APK", "Можно удалить, если установочный файл больше не нужен. Само приложение не удаляется."),
    DUPLICATES("Точные копии", "Содержимое совпало по SHA-256. У каждой копии указан сохраняемый файл."),
    EMPTY_DIRECTORIES("Пустые папки", "Удаляется только пустая папка. Если в ней появятся файлы, она будет пропущена."),
}

enum class ScanLocationStatus { CHECKED, PARTIAL, UNAVAILABLE, ABSENT, NOT_REACHED }

data class ScanLocationReport(
    val title: String,
    val relativePath: String,
    val status: ScanLocationStatus,
    val inspectedFiles: Int,
    val notes: List<String> = emptyList(),
)

data class ScanCoverage(
    val locations: List<ScanLocationReport>,
    val visitedEntries: Int,
    val protectedEntries: Int,
    val duplicateFilesCompared: Int,
    val duplicateFilesNotCompared: Int,
    val limits: List<String>,
) {
    val isPartial: Boolean get() = limits.isNotEmpty() || locations.any {
        it.status in setOf(ScanLocationStatus.PARTIAL, ScanLocationStatus.UNAVAILABLE, ScanLocationStatus.NOT_REACHED)
    }
}

data class CleanupCandidate(
    /** Metadata only for ordinary files; exact duplicate candidates also have a content digest. */
    val file: CleanupFileSnapshot,
    val bucket: CleanupBucket,
    val locationTitle: String,
    val keptOriginal: CleanupFileSnapshot? = null,
) {
    val name: String get() = File(file.path).name
    val parentPath: String get() = File(file.path).parent.orEmpty()
}

data class StorageCleanupScan(
    val id: String,
    val scannedAtMs: Long,
    val storageRoot: String,
    val candidates: List<CleanupCandidate>,
    val protectedOriginalPaths: Set<String>,
    val coverage: ScanCoverage,
) {
    val fileBytes: Long get() = candidates.sumOf { it.file.bytes }

    fun selected(paths: Set<String>): List<CleanupCandidate> = candidates.filter { it.file.path in paths }

    /** Foreign paths and filters cannot expand the explicit selection. Originals stay protected. */
    fun selection(paths: Set<String>): CleanupSelection {
        val chosen = selected(paths)
        val expected = buildMap {
            chosen.forEach { candidate ->
                put(candidate.file.path, candidate.file)
                candidate.keptOriginal?.let { put(it.path, it) }
            }
        }
        return CleanupSelection(
            groups = chosen.map { candidate ->
                CleanupSelectionGroup(
                    source = CleanupSource(CleanerSubTab.SHARED_FILES, "${candidate.bucket.title} · ${candidate.locationTitle}"),
                    paths = listOf(candidate.file.path),
                    keptOriginalPath = candidate.keptOriginal?.path,
                    exactDuplicates = candidate.keptOriginal != null,
                )
            },
            protectedOriginalPaths = protectedOriginalPaths,
            expectedFiles = expected,
            requireExactTargets = true,
        )
    }
}

data class CleanupScanProgress(val location: String, val inspectedFiles: Int)

enum class CleanupStep { ACCESS, CHOOSE, REVIEW, RESULT }

data class CleanupReceipt(
    val planId: String,
    val startedAtMs: Long,
    val plannedCount: Int,
    val status: Status,
    val completedAtMs: Long? = null,
    val deletedFiles: Int? = null,
    val deletedDirectories: Int? = null,
    val deletedBytes: Long? = null,
    val skipped: Int? = null,
    val failed: Int? = null,
) {
    enum class Status { RUNNING, INTERRUPTED, COMPLETE }
}

sealed interface CleanupRunOutcome {
    data class Completed(val result: CleanupExecutionResult, val receipt: CleanupReceipt, val receiptSaved: Boolean) : CleanupRunOutcome
    data class NotStarted(val reason: String) : CleanupRunOutcome
    data class Interrupted(val reason: String) : CleanupRunOutcome
}
