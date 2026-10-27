package com.swagdroid.feature.cleaner.model

import com.swagdroid.core.model.RiskLevel

enum class CleanerSubTab(val titleRu: String) {
    SYSTEM_CACHE("Кэш и Мусор"),
    MESSENGERS("Мессенджеры"),
    CORPSES("Хвосты APK"),
    DUPLICATES("Дубликаты"),
    AUTH_SHIELD("Броня паролей"),
}

enum class SystemJunkCategory(
    val titleRu: String,
    val subtitleRu: String,
    val defaultChecked: Boolean,
    val risk: RiskLevel,
) {
    PUBLIC_APP_CACHES(
        titleRu = "Внешние кэши приложений",
        subtitleRu = "Доступные файлы Android/data/*/cache. Перед удалением проверьте список",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    PRIVILEGED_TRIM_CACHES(
        titleRu = "Внутренние кэши всех приложений (ADB / Root)",
        subtitleRu = "Только оценка объёма: глобальное удаление пока отключено",
        defaultChecked = false,
        risk = RiskLevel.ADVANCED,
    ),
    TEMP_AND_INCOMPLETE(
        titleRu = "Временные файлы и обрывки загрузок",
        subtitleRu = "Файлы *.tmp, *.temp, старые недокачанные *.crdownload и *.part (>24 ч)",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    SYSTEM_LOGS_AND_CRASHES(
        titleRu = "Журналы отладки, логи и дампы падений",
        subtitleRu = "Файлы *.log, debug_log оболочки, кольцевой буфер отладки",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    BROKEN_THUMBNAILS(
        titleRu = "Раздутый кэш миниатюр Галереи (.thumbnails)",
        subtitleRu = "Содержимое папок .thumbnails; актуальность миниатюр не проверяется",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    DESKTOP_OS_TRASH(
        titleRu = "Мусор десктопных систем (.DS_Store, Thumbs.db)",
        subtitleRu = "Служебные индексные файлы macOS, Windows и корзин .Trash",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    ABANDONED_STAGING_SESSIONS(
        titleRu = "Сессии установщика игр/APK · только просмотр",
        subtitleRu = "Наличие сессии не доказывает, что установка брошена",
        defaultChecked = false,
        risk = RiskLevel.ADVANCED,
    ),
    EMPTY_FOLDERS(
        titleRu = "Пустые папки-пустышки",
        subtitleRu = "Пустые директории без вложенных файлов во внешней памяти",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    DOWNLOADED_APK_INSTALLERS(
        titleRu = "Скачанные установочные файлы .apk в Загрузках",
        subtitleRu = "По умолчанию НЕ отмечено — включайте, только если инсталляторы больше не нужны",
        defaultChecked = false,
        risk = RiskLevel.ADVANCED,
    );

    val cleanupBlockReason: String?
        get() = when (this) {
            PRIVILEGED_TRIM_CACHES -> "Временно недоступно: глобальная очистка не соблюдает исключения отдельных приложений."
            ABANDONED_STAGING_SESSIONS -> "Автоматическая отмена отключена: среди сессий могут быть действующие установки."
            else -> null
        }
}

data class SystemJunkGroup(
    val category: SystemJunkCategory,
    val bytesCount: Long,
    val itemsCount: Int,
    val targetPaths: List<String>,
    val isSelected: Boolean = category.defaultChecked && category.cleanupBlockReason == null,
)

enum class MessengerBrand(val titleRu: String) {
    TELEGRAM("Telegram / Клиенты"),
    WHATSAPP("WhatsApp / Business"),
    TIKTOK("TikTok"),
    VK("ВКонтакте"),
    VIBER("Viber"),
}

enum class MessengerShelfKind(
    val titleRu: String,
    val subtitleRu: String,
    val defaultChecked: Boolean,
) {
    VIDEO_AND_CIRCLES(
        titleRu = "Видео и видеосообщения",
        subtitleRu = "Могут включать сохранённые видео; наличие копии в облаке не проверено",
        defaultChecked = false,
    ),
    IMAGE_FEED_CACHE(
        titleRu = "Изображения и предпросмотры",
        subtitleRu = "Могут включать сохранённые фото, а не только временный кэш",
        defaultChecked = false,
    ),
    STICKERS_AND_GIFS(
        titleRu = "Стикеры и GIF-анимации",
        subtitleRu = "Возможность повторной загрузки приложением не проверена",
        defaultChecked = false,
    ),
    SENT_DUPLICATES(
        titleRu = "Отправленные медиа (папки Sent)",
        subtitleRu = "Файлы не проверены на наличие другой копии. Это могут быть единственные экземпляры",
        defaultChecked = false,
    ),
    VOICE_NOTES_CACHE(
        titleRu = "Голосовые сообщения и аудио",
        subtitleRu = "Могут включать локальные записи, которые нельзя восстановить",
        defaultChecked = false,
    ),
}

enum class MediaAgeFilter(val daysThreshold: Int, val labelRu: String) {
    ANY_AGE(0, "Любой возраст файлов"),
    OLDER_THAN_7_DAYS(7, "Старше 7 дней"),
    OLDER_THAN_30_DAYS(30, "Старше 30 дней"),
}

data class MessengerShelfItem(
    val id: String,
    val brand: MessengerBrand,
    val shelf: MessengerShelfKind,
    val bytesCount: Long,
    val filesCount: Int,
    val filePaths: List<String>,
    val isSelected: Boolean = shelf.defaultChecked,
)

data class CorpseFinding(
    val id: String,
    val packageName: String,
    val absolutePath: String,
    val locationLabelRu: String,
    val bytesCount: Long,
    val filesCount: Int,
    val isSelected: Boolean = false,
)

enum class DuplicateMatchKind(val labelRu: String) {
    EXACT_SHA256("Точная копия 1-в-1 (SHA-256)"),
    SIMILAR_DHASH("Похожий кадр серии (dHash)"),
}

data class DuplicateCluster(
    val id: String,
    val kind: DuplicateMatchKind,
    val keptOriginalPath: String,
    val duplicatePaths: List<String>,
    val reclaimableBytes: Long,
    val isSelected: Boolean = false,
)

/**
 * Additional exclusion filters for known credential paths and packages.
 * These heuristics are not a guarantee that every valuable file will be recognized.
 *
 * ALL flags are ENABLED (`true`) BY DEFAULT per explicit product safety design.
 */
data class CredentialShieldConfig(
    /** Master default-ON protection for accounts, autofill services, and login tokens. */
    val protectAccountsAndAutofill: Boolean = true,
    /** Protects all password managers & 2FA authenticators (Bitwarden, Google/Yandex Auth, KeePass, etc.). */
    val protectPasswordManagersAnd2Fa: Boolean = true,
    /** Protects browser caches & WebView stores so website logins, OAuth & form autofill stay untouched. */
    val protectBrowsersAndWebViewSessions: Boolean = true,
    /** Protects encrypted backup & vault files (.kdbx, .2fas, .aegis, passkey exports) anywhere on storage. */
    val protectVaultAnd2FaBackupFiles: Boolean = true,
)
