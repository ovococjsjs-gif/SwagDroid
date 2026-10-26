package com.swagdroid.feature.cleaner.messenger

import com.swagdroid.api.PrivilegeTier
import com.swagdroid.feature.antivirus.layer1.SinglePassMultiHasher
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.OwnershipConfidence
import com.swagdroid.feature.cleaner.model.StorageCategory
import com.swagdroid.feature.cleaner.model.StorageFinding
import com.swagdroid.feature.cleaner.path.StoragePathCanonicalizer
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Строго типизированное дата-правило для временных папок конкретного приложения (WhatsApp, Telegram, TikTok, VK).
 * Содержит как целевой шаблон, так и обязательные `protectedSiblingPrefixes`, которые правило НИКОГДА не должно задевать!
 */
@Serializable
data class AppSpecificTempRule(
    val ruleId: String,
    val ownerPackages: Set<String>,
    val targetPathPrefix: String,
    val allowedExtensions: Set<String>, // Пустой = любые расширения внутри временной папки
    val minFileAgeMillis: Long,
    val protectedSiblingPrefixes: Set<String>,
    val descriptionRu: String,
) {
    fun matchesSafeTempFile(canonicalFilePath: String, fileAgeMillis: Long): Boolean {
        val normPath = StoragePathCanonicalizer.canonicalize(canonicalFilePath)
        // 1. Жёсткая проверка: путь не должен попадать ни в одну соседнюю пользовательскую папку!
        if (protectedSiblingPrefixes.any { normPath == it || normPath.startsWith("$it/") }) {
            return false
        }
        // 2. Путь должен лежать строго внутри проверенного временного каталога
        if (!normPath.startsWith("$targetPathPrefix/")) {
            return false
        }
        // 3. Проверка минимального возраста (чтобы не удалить файл в процессе скачивания/отправки)
        if (fileAgeMillis < minFileAgeMillis) {
            return false
        }
        // 4. Проверка расширения, если задано
        if (allowedExtensions.isNotEmpty()) {
            val ext = normPath.substringAfterLast('.', "").lowercase()
            if (ext !in allowedExtensions) return false
        }
        return true
    }
}

/**
 * Четырёхкорзинный анализатор мессенджеров (`MessengerCenterAnalyzer` для WhatsApp, Telegram, TikTok, VK):
 * 1. Стандартный кэш (`STANDARD_APP_CACHE`).
 * 2. Проверенные временные файлы по датированным правилам (`VERIFIED_TEMP_RULE`).
 * 3. Пользовательские медиа и точные дубликаты (`MESSENGER_USER_MEDIA_REVIEW` / `MESSENGER_EXACT_DUPLICATE`):
 *    - Папка `WhatsApp/Media/Sent` НЕ считается мусором по умолчанию (пользователь мог уже удалить оригинал из камеры!).
 *    - Файл в `Sent/` помечается как `MESSENGER_EXACT_DUPLICATE` только если его точный SHA-256 оригинал ПРЯМО СЕЙЧАС
 *      существует в основной Галерее (`DCIM` / `Pictures`)!
 * 4. Резервные копии (`MESSENGER_BACKUP_PROTECTED`):
 *    - Последний актуальный бэкап никогда не предлагается к удалению!
 */
class MessengerCenterAnalyzer(
    val verifiedRules: List<AppSpecificTempRule> = DEFAULT_VERIFIED_MESSENGER_RULES,
) {
    /**
     * Ищет точные дубликаты по `SHA-256` между папкой отправленных медиа мессенджера (`Sent/`)
     * и основной галереей пользователя (`DCIM/Camera`, `Pictures/`).
     * Если оригинал в Галерее был удалён — файл в `Sent/` является единственной копией и НЕ помечается как дубликат!
     */
    fun findVerifiedSentDuplicates(
        sentFiles: List<File>,
        galleryOriginalFiles: List<File>,
        ownerPackage: String = "com.whatsapp",
    ): List<StorageFinding> {
        val galleryHashesToPath = HashMap<String, String>()
        for (orig in galleryOriginalFiles) {
            if (!orig.exists() || !orig.isFile || orig.length() == 0L) continue
            val canonicalOrig = StoragePathCanonicalizer.canonicalize(orig.path)
            val digest = SinglePassMultiHasher.hashFile(orig)
            galleryHashesToPath[digest.sha256Hex] = canonicalOrig
        }

        val findings = mutableListOf<StorageFinding>()
        for (sent in sentFiles) {
            if (!sent.exists() || !sent.isFile || sent.length() == 0L) continue
            val canonicalSent = StoragePathCanonicalizer.canonicalize(sent.path)
            val sentDigest = SinglePassMultiHasher.hashFile(sent)
            val existingGalleryOriginal = galleryHashesToPath[sentDigest.sha256Hex]

            if (existingGalleryOriginal != null && existingGalleryOriginal != canonicalSent) {
                findings += StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonicalSent,
                    ownerPackage = ownerPackage,
                    category = StorageCategory.MESSENGER_EXACT_DUPLICATE,
                    bytesLogical = sent.length(),
                    bytesAllocated = sent.length(),
                    confidence = OwnershipConfidence.HIGH,
                    evidenceRule = "SHA256_VERIFIED_GALLERY_ORIGINAL_EXISTS",
                    explanationRu = "Точная копия отправленного файла (SHA-256: ${sentDigest.sha256Hex.take(12)}...). " +
                        "Оригинал сохранён в Галерее: $existingGalleryOriginal",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.MEDIA_STORE_TRASH,
                    reversible = true,
                )
            } else {
                // Оригинал в галерее отсутствует — значит копия в Sent/ единственная! Только ручной обзор.
                findings += StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonicalSent,
                    ownerPackage = ownerPackage,
                    category = StorageCategory.MESSENGER_USER_MEDIA_REVIEW,
                    bytesLogical = sent.length(),
                    bytesAllocated = sent.length(),
                    confidence = OwnershipConfidence.LOW_KEEP,
                    evidenceRule = "SENT_MEDIA_UNIQUE_COPY_KEEP",
                    explanationRu = "Отправленный медиафайл не имеет дубликата в Галерее (возможна единственная копия). Автоудаление запрещено.",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.OPEN_IN_APP_STORAGE_MANAGER,
                    reversible = true,
                )
            }
        }
        return findings
    }

    /**
     * Анализирует папку резервных копий WhatsApp (`Databases/msgstore-*.db.crypt14/15`):
     * Гарантированно защищает самый свежий бэкап (`REPORT_ONLY_DO_NOT_TOUCH`) и никогда не удаляет все копии!
     */
    fun analyzeMessengerBackups(
        backupFiles: List<File>,
        ownerPackage: String = "com.whatsapp",
    ): List<StorageFinding> {
        val validBackups = backupFiles
            .filter { it.exists() && it.isFile }
            .sortedByDescending { it.lastModified() }

        if (validBackups.isEmpty()) return emptyList()

        return validBackups.mapIndexed { index, file ->
            val canonical = StoragePathCanonicalizer.canonicalize(file.path)
            if (index == 0) {
                // Самый свежий бэкап неприкосновенен!
                StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonical,
                    ownerPackage = ownerPackage,
                    category = StorageCategory.MESSENGER_BACKUP_PROTECTED,
                    bytesLogical = file.length(),
                    bytesAllocated = file.length(),
                    confidence = OwnershipConfidence.LOW_KEEP,
                    evidenceRule = "LATEST_MESSENGER_BACKUP_PROTECTED",
                    explanationRu = "Актуальная резервная копия базы чатов (${file.name}). Удаление заблокировано для сохранности переписок.",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
                    reversible = true,
                )
            } else {
                StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonical,
                    ownerPackage = ownerPackage,
                    category = StorageCategory.MESSENGER_BACKUP_PROTECTED,
                    bytesLogical = file.length(),
                    bytesAllocated = file.length(),
                    confidence = OwnershipConfidence.MEDIUM,
                    evidenceRule = "OLDER_MESSENGER_BACKUP_MANUAL_REVIEW",
                    explanationRu = "Архивная копия базы чатов (${file.name}). Доступна только для ручного обзора (свежий бэкап сохранён отдельно).",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
                    reversible = true,
                )
            }
        }
    }

    companion object {
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

        val DEFAULT_VERIFIED_MESSENGER_RULES = listOf(
            AppSpecificTempRule(
                ruleId = "whatsapp_shared_temp_v1",
                ownerPackages = setOf("com.whatsapp", "com.whatsapp.w4b"),
                targetPathPrefix = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/.Shared",
                allowedExtensions = emptySet(),
                minFileAgeMillis = ONE_DAY_MS,
                protectedSiblingPrefixes = setOf(
                    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media",
                    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases",
                    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Backups",
                ),
                descriptionRu = "Временные буферные файлы обмена WhatsApp (.Shared) старше 24 часов",
            ),
            AppSpecificTempRule(
                ruleId = "whatsapp_trash_temp_v1",
                ownerPackages = setOf("com.whatsapp", "com.whatsapp.w4b"),
                targetPathPrefix = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/.Trash",
                allowedExtensions = emptySet(),
                minFileAgeMillis = ONE_DAY_MS,
                protectedSiblingPrefixes = setOf(
                    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media",
                    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Databases",
                ),
                descriptionRu = "Внутренняя временная корзина WhatsApp (.Trash) старше 24 часов",
            ),
            AppSpecificTempRule(
                ruleId = "vk_exoplayer_media_cache_v1",
                ownerPackages = setOf("com.vkontakte.android"),
                targetPathPrefix = "/storage/emulated/0/Android/data/com.vkontakte.android/cache/exo_cache",
                allowedExtensions = setOf("exo", "tmp"),
                minFileAgeMillis = ONE_DAY_MS,
                protectedSiblingPrefixes = setOf(
                    "/storage/emulated/0/Android/data/com.vkontakte.android/files",
                ),
                descriptionRu = "Кэш потокового видеоплеера ExoPlayer ВКонтакте (без задевания офлайн-музыки и документов в files/)",
            ),
        )
    }
}
