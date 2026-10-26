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
        subtitleRu = "Временные буферы и шейдеры в Android/data/*/cache (без сброса авторизаций)",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
    ),
    PRIVILEGED_TRIM_CACHES(
        titleRu = "Внутренние кэши всех приложений (ADB / Root)",
        subtitleRu = "Штатный системный вызов pm trim-caches — освобождает кэши без выхода из аккаунтов",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
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
        subtitleRu = "Эскизы давно удалённых картинок (оригиналы фотографий не затрагиваются)",
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
        titleRu = "Зависшие сессии установщика игр/APK",
        subtitleRu = "Брошенные временные контейнеры PackageInstaller (ADB / Root)",
        defaultChecked = true,
        risk = RiskLevel.SAFE,
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
    ),
}

data class SystemJunkGroup(
    val category: SystemJunkCategory,
    val bytesCount: Long,
    val itemsCount: Int,
    val targetPaths: List<String>,
    val isSelected: Boolean = category.defaultChecked,
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
        titleRu = "Кэш просмотренных видео и видеокружочков",
        subtitleRu = "Самый тяжёлый раздел кэша; в облачных чатах ролики всегда остаются на сервере",
        defaultChecked = true,
    ),
    IMAGE_FEED_CACHE(
        titleRu = "Кэш ленты, каналов и предпросмотров",
        subtitleRu = "Временные копии просмотренных постов без удаления сохранённых в Галерею фото",
        defaultChecked = true,
    ),
    STICKERS_AND_GIFS(
        titleRu = "Кэш стикеров, эмодзи и GIF-анимаций",
        subtitleRu = "Мгновенно подгружаются заново при открытии чата",
        defaultChecked = true,
    ),
    SENT_DUPLICATES(
        titleRu = "Дубли отправленных медиа (папки Sent)",
        subtitleRu = "Вторые копии фото/видео, которые уже лежат в вашей основной камере",
        defaultChecked = false,
    ),
    VOICE_NOTES_CACHE(
        titleRu = "Голосовые сообщения и аудиокэш",
        subtitleRu = "По умолчанию НЕ отмечено для защиты локальных голосовых заметок",
        defaultChecked = false,
    ),
}

enum class MediaAgeFilter(val daysThreshold: Int, val labelRu: String) {
    ANY_AGE(0, "Все файлы кэша"),
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
    val isSelected: Boolean = true,
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
    val isSelected: Boolean = (kind == DuplicateMatchKind.EXACT_SHA256),
)

/**
 * Multi-layer shield guaranteeing that accounts, passwords, autofill databases, passkeys,
 * 2FA authenticators, browser cookies/sessions, and password backup vaults are NEVER wiped.
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
