package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.cache.PrivilegedCacheCleaner
import com.swagdroid.feature.cleaner.corpse.CorpseFinderEngine
import com.swagdroid.feature.cleaner.downloads.DownloadAndSystemDiagnosticsAnalyzer
import com.swagdroid.feature.cleaner.messenger.MessengerCenterAnalyzer
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.StorageFinding
import com.swagdroid.feature.cleaner.path.StoragePathCanonicalizer
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Сводка анализа хранилища с честным разделением:
 * - сколько байт можно освободить СРАЗУ безопасно (`immediatelyFreeableSafeBytes`),
 * - сколько байт уйдёт в системную Корзину MediaStore (`trashPendingBytes` — место освободится после очистки корзины),
 * - сколько байт составляют пользовательские медиа/бэкапы для ручного обзора (`manualReviewProtectedBytes`).
 */
@Serializable
data class CleanerScanSummary(
    val findings: List<StorageFinding>,
    val immediatelyFreeableSafeBytes: Long,
    val trashPendingBytes: Long,
    val manualReviewProtectedBytes: Long,
)

class CleanerCoordinator(
    hostFilesDir: File,
    hostCacheDir: File,
    val corpseFinder: CorpseFinderEngine = CorpseFinderEngine(),
    val messengerAnalyzer: MessengerCenterAnalyzer = MessengerCenterAnalyzer(),
    val downloadsAnalyzer: DownloadAndSystemDiagnosticsAnalyzer = DownloadAndSystemDiagnosticsAnalyzer(),
) {
    val cacheCleaner = PrivilegedCacheCleaner(hostFilesDir, hostCacheDir)

    fun buildDeduplicatedSummary(rawFindings: List<StorageFinding>): CleanerScanSummary {
        val deduplicated = StoragePathCanonicalizer.deduplicateFindings(rawFindings)

        val immediateBytes = deduplicated
            .filter {
                it.action == CleanerActionType.CLEAR_DEFAULT_CACHE ||
                    it.action == CleanerActionType.DELETE_BY_VERIFIED_RULE ||
                    it.action == CleanerActionType.MEDIA_STORE_DELETE
            }
            .sumOf { it.bytesAllocated }

        val trashBytes = deduplicated
            .filter { it.action == CleanerActionType.MEDIA_STORE_TRASH }
            .sumOf { it.bytesAllocated }

        val protectedBytes = deduplicated
            .filter {
                it.action == CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH ||
                    it.action == CleanerActionType.OPEN_IN_APP_STORAGE_MANAGER
            }
            .sumOf { it.bytesAllocated }

        return CleanerScanSummary(
            findings = deduplicated,
            immediatelyFreeableSafeBytes = immediateBytes,
            trashPendingBytes = trashBytes,
            manualReviewProtectedBytes = protectedBytes,
        )
    }
}
