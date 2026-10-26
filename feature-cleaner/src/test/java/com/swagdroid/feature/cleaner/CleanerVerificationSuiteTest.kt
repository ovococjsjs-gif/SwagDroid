package com.swagdroid.feature.cleaner

import com.swagdroid.api.PackageSummary
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.feature.cleaner.corpse.CorpseFinderEngine
import com.swagdroid.feature.cleaner.corpse.MultiUserPackageInventory
import com.swagdroid.feature.cleaner.downloads.DownloadAndSystemDiagnosticsAnalyzer
import com.swagdroid.feature.cleaner.downloads.DownloadedApkStatus
import com.swagdroid.feature.cleaner.downloads.ParsedApkArchiveInfo
import com.swagdroid.feature.cleaner.messenger.MessengerCenterAnalyzer
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.OwnershipConfidence
import com.swagdroid.feature.cleaner.model.StorageCategory
import com.swagdroid.feature.cleaner.model.StorageFinding
import com.swagdroid.feature.cleaner.path.StoragePathCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CleanerVerificationSuiteTest {

    @Test
    fun `canonicalizer normalizes sdcard aliases and deduplicates overlapping cache findings`() {
        val p1 = StoragePathCanonicalizer.canonicalize("/sdcard/Android/data/com.example.app/cache")
        val p2 = StoragePathCanonicalizer.canonicalize("/storage/emulated/0/Android/data/com.example.app/cache/")
        val p3 = StoragePathCanonicalizer.canonicalize("/data/media/0/Android/data/com.example.app/cache")

        assertEquals("/storage/emulated/0/Android/data/com.example.app/cache", p1)
        assertEquals(p1, p2)
        assertEquals(p1, p3)

        // Если для пакета уже есть запись CLEAR_DEFAULT_CACHE, вложенный файловый путь в его cache/ дедуплицируется
        val defaultCacheFinding = StorageFinding(
            findingId = "1",
            canonicalPath = "/data/user/0/com.example.app",
            ownerPackage = "com.example.app",
            category = StorageCategory.STANDARD_APP_CACHE,
            bytesLogical = 50_000_000L,
            bytesAllocated = 50_000_000L,
            confidence = OwnershipConfidence.HIGH,
            evidenceRule = "STORAGE_STATS_CACHE",
            explanationRu = "Стандартный кэш",
            minPrivilege = PrivilegeTier.ADB_SHELL,
            action = CleanerActionType.CLEAR_DEFAULT_CACHE,
            reversible = false,
        )
        val overlappingFileCacheFinding = defaultCacheFinding.copy(
            findingId = "2",
            canonicalPath = "/sdcard/Android/data/com.example.app/cache/image_disk_cache",
            category = StorageCategory.VERIFIED_TEMP_RULE,
            bytesLogical = 20_000_000L,
            bytesAllocated = 20_000_000L,
            action = CleanerActionType.DELETE_BY_VERIFIED_RULE,
        )

        val deduplicated = StoragePathCanonicalizer.deduplicateFindings(
            listOf(defaultCacheFinding, overlappingFileCacheFinding)
        )
        assertEquals(1, deduplicated.size)
        assertEquals(CleanerActionType.CLEAR_DEFAULT_CACHE, deduplicated.first().action)
    }

    @Test
    fun `corpse finder stops when inventory is incomplete and protects multi-user apps and Notes folder`() {
        val engine = CorpseFinderEngine()
        val candidateDirs = listOf(
            File("/storage/emulated/0/Android/data/com.deleted.game"),
            File("/storage/emulated/0/Android/data/com.workprofile.crm"),
            File("/storage/emulated/0/Notes"),
        )

        // 1. Неполный инвентарь -> CorpseFinder обязан остановиться и вернуть пустой список
        val incompleteInventory = MultiUserPackageInventory(
            inspectedUserIds = setOf(0),
            installedPackagesAcrossAllUsers = setOf("com.workprofile.crm"),
            isCompleteAndReliable = false,
        )
        assertTrue(
            engine.scanForCorpses(candidateDirs, incompleteInventory, PrivilegeTier.ADB_SHELL).isEmpty()
        )

        // 2. Полный инвентарь по профилям 0 и 10 (рабочий профиль):
        // com.workprofile.crm установлен в профиле 10 -> НЕ труп!
        // com.deleted.game отсутствует везде -> HIGH confidence труп!
        // Notes -> защищённая пользовательская папка LOW_KEEP (REPORT_ONLY_DO_NOT_TOUCH)!
        val completeInventory = MultiUserPackageInventory(
            inspectedUserIds = setOf(0, 10),
            installedPackagesAcrossAllUsers = setOf("com.workprofile.crm"),
            isCompleteAndReliable = true,
        )
        val findings = engine.scanForCorpses(candidateDirs, completeInventory, PrivilegeTier.ADB_SHELL)
        assertEquals(2, findings.size)

        val gameCorpse = findings.first { it.ownerPackage == "com.deleted.game" }
        assertEquals(OwnershipConfidence.HIGH, gameCorpse.confidence)
        assertEquals(CleanerActionType.DELETE_BY_VERIFIED_RULE, gameCorpse.action)

        val notesFinding = findings.first { it.canonicalPath.endsWith("/Notes") }
        assertEquals(OwnershipConfidence.LOW_KEEP, notesFinding.confidence)
        assertEquals(CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH, notesFinding.action)
    }

    @Test
    fun `messenger rules never touch sibling Media or Databases and Sent duplicate requires existing Gallery original`() {
        val analyzer = MessengerCenterAnalyzer()
        val whatsappSharedRule = analyzer.verifiedRules.first { it.ruleId == "whatsapp_shared_temp_v1" }
        val twoDaysMs = 48L * 3600_000L

        assertTrue(
            whatsappSharedRule.matchesSafeTempFile(
                "/sdcard/Android/media/com.whatsapp/WhatsApp/.Shared/tmp_chunk.bin",
                twoDaysMs,
            )
        )
        // Негативный тест: соседние папки Media и Databases ни при каких условиях не должны матчиться!
        assertFalse(
            whatsappSharedRule.matchesSafeTempFile(
                "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg",
                twoDaysMs,
            )
        )
        assertFalse(
            whatsappSharedRule.matchesSafeTempFile(
                "/sdcard/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15",
                twoDaysMs,
            )
        )

        // Проверка детектора дублей Sent <-> Галерея
        val tempRoot = Files.createTempDirectory("swagdroid_messenger_test").toFile()
        try {
            val galleryFile = File(tempRoot, "DCIM_VID_001.mp4").apply {
                writeBytes("IDENTICAL_VIDEO_PAYLOAD_BYTES".toByteArray())
            }
            val sentDuplicateFile = File(tempRoot, "Sent_VID_001.mp4").apply {
                writeBytes("IDENTICAL_VIDEO_PAYLOAD_BYTES".toByteArray())
            }
            val sentUniqueFile = File(tempRoot, "Sent_VID_UNIQUE.mp4").apply {
                writeBytes("UNIQUE_SENT_VIDEO_WHOSE_ORIGINAL_WAS_DELETED".toByteArray())
            }

            val dupFindings = analyzer.findVerifiedSentDuplicates(
                sentFiles = listOf(sentDuplicateFile, sentUniqueFile),
                galleryOriginalFiles = listOf(galleryFile),
            )
            assertEquals(2, dupFindings.size)

            val duplicateItem = dupFindings.first { it.canonicalPath.endsWith("Sent_VID_001.mp4") }
            assertEquals(StorageCategory.MESSENGER_EXACT_DUPLICATE, duplicateItem.category)
            assertEquals(OwnershipConfidence.HIGH, duplicateItem.confidence)

            val uniqueItem = dupFindings.first { it.canonicalPath.endsWith("Sent_VID_UNIQUE.mp4") }
            assertEquals(StorageCategory.MESSENGER_USER_MEDIA_REVIEW, uniqueItem.category)
            assertEquals(OwnershipConfidence.LOW_KEEP, uniqueItem.confidence)
            assertEquals(CleanerActionType.OPEN_IN_APP_STORAGE_MANAGER, uniqueItem.action)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `downloaded APK classifier distinguishes obsolete, pending update and signature mismatch`() {
        val analyzer = DownloadAndSystemDiagnosticsAnalyzer()
        val installedMap = mapOf(
            "org.telegram.messenger" to PackageSummary(
                packageName = "org.telegram.messenger",
                uid = 10200,
                versionName = "11.2.0",
                versionCode = 5200L,
                isSystemApp = false,
                isUpdatedSystemApp = false,
                enabledState = 1,
                isSuspended = false,
                hasLauncherIcon = true,
                signingCertSha256 = "aabbccdd",
                requestsOverlayPermission = false,
                isCriticalProtected = false,
            )
        )

        // 1. APK в Загрузках НОВЕЕ установленного (5300 > 5200) -> это ожидающее обновление, НЕ мусор!
        val (newerStatus, newerFinding) = analyzer.classifyDownloadedApk(
            ParsedApkArchiveInfo(
                absolutePath = "/sdcard/Download/Telegram_v11.3.0.apk",
                fileSizeBytes = 75_000_000L,
                packageName = "org.telegram.messenger",
                versionCode = 5300L,
                versionName = "11.3.0",
                signingCertSha256 = "aabbccdd",
            ),
            installedMap,
        )
        assertEquals(DownloadedApkStatus.PENDING_NEWER_UPDATE, newerStatus)
        assertEquals(CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH, newerFinding.action)

        // 2. APK в Загрузках СТАРШЕ установленного (5100 < 5200) с той же подписью -> безопасно удалить
        val (olderStatus, olderFinding) = analyzer.classifyDownloadedApk(
            ParsedApkArchiveInfo(
                absolutePath = "/sdcard/Download/Telegram_v11.1.0.apk",
                fileSizeBytes = 72_000_000L,
                packageName = "org.telegram.messenger",
                versionCode = 5100L,
                versionName = "11.1.0",
                signingCertSha256 = "aabbccdd",
            ),
            installedMap,
        )
        assertEquals(DownloadedApkStatus.OBSOLETE_OLDER_THAN_INSTALLED, olderStatus)
        assertEquals(CleanerActionType.MEDIA_STORE_TRASH, olderFinding.action)
    }
}
