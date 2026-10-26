package com.swagdroid.feature.cleaner.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.feature.cleaner.model.CorpseFinding
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.model.DuplicateCluster
import com.swagdroid.feature.cleaner.model.DuplicateMatchKind
import com.swagdroid.feature.cleaner.model.MediaAgeFilter
import com.swagdroid.feature.cleaner.model.MessengerBrand
import com.swagdroid.feature.cleaner.model.MessengerShelfItem
import com.swagdroid.feature.cleaner.model.MessengerShelfKind
import com.swagdroid.feature.cleaner.model.SystemJunkCategory
import com.swagdroid.feature.cleaner.model.SystemJunkGroup
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class FullCleanerScanReport(
    val junkGroups: List<SystemJunkGroup>,
    val messengerShelves: List<MessengerShelfItem>,
    val corpses: List<CorpseFinding>,
    val duplicateClusters: List<DuplicateCluster>,
    val hasAllFilesAccess: Boolean,
)

@Singleton
class CleanerScanAndPurgeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
) {

    fun hasFullStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    suspend fun runFullScan(
        shield: CredentialShieldConfig,
        ageFilter: MediaAgeFilter,
    ): FullCleanerScanReport = withContext(Dispatchers.IO) {
        val activeAutofillPkg = executor.readSetting(SettingNamespace.SECURE, "autofill_service")
            ?.substringBefore('/')
        val rootDir = Environment.getExternalStorageDirectory()

        val junk = scanSystemJunk(rootDir, shield, activeAutofillPkg)
        val messengers = scanMessengers(rootDir, shield, activeAutofillPkg, ageFilter)
        val corpses = scanCorpses(rootDir, shield, activeAutofillPkg)
        val duplicates = scanDuplicatesAndBurstPhotos(rootDir, shield, activeAutofillPkg)

        FullCleanerScanReport(
            junkGroups = junk,
            messengerShelves = messengers,
            corpses = corpses,
            duplicateClusters = duplicates,
            hasAllFilesAccess = hasFullStoragePermission(),
        )
    }

    // =========================================================================
    // 1. System & App Cache Scanner
    // =========================================================================

    private suspend fun scanSystemJunk(
        sdcard: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
    ): List<SystemJunkGroup> {
        val groups = ArrayList<SystemJunkGroup>()
        val nowMs = System.currentTimeMillis()
        val dayAgoMs = nowMs - 24L * 3600_000L

        // 1A. Public Android/data/*/cache
        val appCachePaths = ArrayList<String>()
        var appCacheBytes = 0L
        val androidDataDir = File(sdcard, "Android/data")
        androidDataDir.listFiles()?.forEach { pkgDir ->
            val pkgName = pkgDir.name
            val cacheDir = File(pkgDir, "cache")
            if (cacheDir.isDirectory &&
                AuthAndCredentialsGuard.isPathSafeToClean(cacheDir.absolutePath, shield, pkgName, activeAutofillPkg)
            ) {
                val (b, c) = measureDirSafe(cacheDir, shield, activeAutofillPkg)
                if (c > 0 && b > 0L) {
                    appCacheBytes += b
                    appCachePaths.add(cacheDir.absolutePath)
                }
            }
        }
        if (appCacheBytes > 0L) {
            groups.add(
                SystemJunkGroup(
                    category = SystemJunkCategory.PUBLIC_APP_CACHES,
                    bytesCount = appCacheBytes,
                    itemsCount = appCachePaths.size,
                    targetPaths = appCachePaths,
                ),
            )
        }

        // 1B. Privileged internal caches via dumpsys diskstats (never touches user/login data!)
        if (executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            val diskOut = executor.execute("dumpsys diskstats").stdoutText
            val estInternalCache = parseDiskstatsAppCacheBytes(diskOut)
            if (estInternalCache > 0L) {
                groups.add(
                    SystemJunkGroup(
                        category = SystemJunkCategory.PRIVILEGED_TRIM_CACHES,
                        bytesCount = estInternalCache,
                        itemsCount = 1,
                        targetPaths = listOf("::PRIVILEGED_TRIM_CACHES::"),
                    ),
                )
            }
        }

        // 1C. Temp files, logs, desktop trash, thumbnails, downloaded APKs, empty dirs
        val tempPaths = ArrayList<String>()
        var tempBytes = 0L
        val logPaths = ArrayList<String>()
        var logBytes = 0L
        val thumbPaths = ArrayList<String>()
        var thumbBytes = 0L
        val desktopPaths = ArrayList<String>()
        var desktopBytes = 0L
        val apkPaths = ArrayList<String>()
        var apkBytes = 0L
        val emptyDirs = ArrayList<String>()

        val scanRoots = listOf(
            File(sdcard, "Download"),
            File(sdcard, "DCIM"),
            File(sdcard, "Pictures"),
            File(sdcard, "Documents"),
            File(sdcard, "MIUI/debug_log"),
        ).filter { it.exists() }

        for (base in scanRoots) {
            walkBounded(base, maxDepth = 4) { file ->
                val abs = file.absolutePath
                if (!AuthAndCredentialsGuard.isPathSafeToClean(abs, shield, null, activeAutofillPkg)) {
                    return@walkBounded
                }
                if (file.isDirectory) {
                    if (file.name.equals(".thumbnails", ignoreCase = true)) {
                        val (b, _) = measureDirSafe(file, shield, activeAutofillPkg)
                        if (b > 0L) {
                            thumbBytes += b
                            thumbPaths.add(abs)
                        }
                    } else if (file != base && file.list()?.isEmpty() == true) {
                        emptyDirs.add(abs)
                    }
                    return@walkBounded
                }

                val lowerName = file.name.lowercase()
                val len = file.length()
                when {
                    lowerName in DESKTOP_JUNK_NAMES -> {
                        desktopBytes += len
                        desktopPaths.add(abs)
                    }
                    lowerName.endsWith(".tmp") || lowerName.endsWith(".temp") ||
                        ((lowerName.endsWith(".crdownload") || lowerName.endsWith(".part")) && file.lastModified() < dayAgoMs) -> {
                        tempBytes += len
                        tempPaths.add(abs)
                    }
                    lowerName.endsWith(".log") || abs.contains("/debug_log/") -> {
                        logBytes += len
                        logPaths.add(abs)
                    }
                    lowerName.endsWith(".apk") && abs.contains("/Download", ignoreCase = true) -> {
                        apkBytes += len
                        apkPaths.add(abs)
                    }
                }
            }
        }

        if (tempBytes > 0L) {
            groups.add(SystemJunkGroup(SystemJunkCategory.TEMP_AND_INCOMPLETE, tempBytes, tempPaths.size, tempPaths))
        }
        if (logBytes > 0L) {
            groups.add(SystemJunkGroup(SystemJunkCategory.SYSTEM_LOGS_AND_CRASHES, logBytes, logPaths.size, logPaths))
        }
        if (thumbBytes > 0L) {
            groups.add(SystemJunkGroup(SystemJunkCategory.BROKEN_THUMBNAILS, thumbBytes, thumbPaths.size, thumbPaths))
        }
        if (desktopBytes > 0L) {
            groups.add(SystemJunkGroup(SystemJunkCategory.DESKTOP_OS_TRASH, desktopBytes, desktopPaths.size, desktopPaths))
        }
        if (emptyDirs.isNotEmpty()) {
            groups.add(SystemJunkGroup(SystemJunkCategory.EMPTY_FOLDERS, 0L, emptyDirs.size, emptyDirs))
        }
        if (apkBytes > 0L) {
            groups.add(
                SystemJunkGroup(
                    category = SystemJunkCategory.DOWNLOADED_APK_INSTALLERS,
                    bytesCount = apkBytes,
                    itemsCount = apkPaths.size,
                    targetPaths = apkPaths,
                    isSelected = false,
                ),
            )
        }

        // 1D. Abandoned PackageInstaller staging sessions
        if (executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
            val stg = executor.execute("pm list staging-sessions").stdoutText
            val sessionIds = Regex("\\bsessionId\\s*=\\s*(\\d+)|^\\s*(\\d+)\\s*:", RegexOption.MULTILINE)
                .findAll(stg)
                .mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.takeIf { it.isNotEmpty() } }
                .distinct()
                .toList()
            if (sessionIds.isNotEmpty()) {
                groups.add(
                    SystemJunkGroup(
                        category = SystemJunkCategory.ABANDONED_STAGING_SESSIONS,
                        bytesCount = 0L,
                        itemsCount = sessionIds.size,
                        targetPaths = sessionIds.map { "::STAGING_SESSION::$it" },
                    ),
                )
            }
        }

        return groups
    }

    // =========================================================================
    // 2. Messenger Vault Engine (Telegram, WhatsApp, TikTok, VK, Viber)
    // =========================================================================

    private fun scanMessengers(
        sdcard: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
        ageFilter: MediaAgeFilter,
    ): List<MessengerShelfItem> {
        val cutoffEpochMs = if (ageFilter.daysThreshold > 0) {
            System.currentTimeMillis() - ageFilter.daysThreshold * 24L * 3600_000L
        } else {
            Long.MAX_VALUE
        }

        data class ProbeSpec(
            val id: String,
            val brand: MessengerBrand,
            val shelf: MessengerShelfKind,
            val relativeDirs: List<String>,
        )

        val probes = listOf(
            // Telegram & forks (never touches tgnet.dat or userconfing.xml!)
            ProbeSpec(
                id = "tg_video_circles",
                brand = MessengerBrand.TELEGRAM,
                shelf = MessengerShelfKind.VIDEO_AND_CIRCLES,
                relativeDirs = listOf(
                    "Android/data/org.telegram.messenger/files/Telegram/Telegram Video",
                    "Android/data/org.telegram.messenger.web/files/Telegram/Telegram Video",
                    "Android/media/org.telegram.messenger/Telegram/Telegram Video",
                    "Telegram/Telegram Video",
                ),
            ),
            ProbeSpec(
                id = "tg_image_cache",
                brand = MessengerBrand.TELEGRAM,
                shelf = MessengerShelfKind.IMAGE_FEED_CACHE,
                relativeDirs = listOf(
                    "Android/data/org.telegram.messenger/cache",
                    "Android/data/org.telegram.messenger/files/Telegram/Telegram Images",
                    "Telegram/Telegram Images",
                ),
            ),
            ProbeSpec(
                id = "tg_voice_audio",
                brand = MessengerBrand.TELEGRAM,
                shelf = MessengerShelfKind.VOICE_NOTES_CACHE,
                relativeDirs = listOf(
                    "Android/data/org.telegram.messenger/files/Telegram/Telegram Audio",
                    "Telegram/Telegram Audio",
                ),
            ),
            // WhatsApp (strictly Sent copies, Stickers & Statuses — Databases/msgstore.db* is untouched!)
            ProbeSpec(
                id = "wa_sent_duplicates",
                brand = MessengerBrand.WHATSAPP,
                shelf = MessengerShelfKind.SENT_DUPLICATES,
                relativeDirs = listOf(
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent",
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent",
                    "WhatsApp/Media/WhatsApp Video/Sent",
                    "WhatsApp/Media/WhatsApp Images/Sent",
                ),
            ),
            ProbeSpec(
                id = "wa_stickers_statuses",
                brand = MessengerBrand.WHATSAPP,
                shelf = MessengerShelfKind.STICKERS_AND_GIFS,
                relativeDirs = listOf(
                    "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/Sent",
                ),
            ),
            // TikTok
            ProbeSpec(
                id = "tt_video_cache",
                brand = MessengerBrand.TIKTOK,
                shelf = MessengerShelfKind.VIDEO_AND_CIRCLES,
                relativeDirs = listOf(
                    "Android/data/com.zhiliaoapp.musically/cache",
                    "Android/data/com.ss.android.ugc.trill/cache",
                ),
            ),
            // VK
            ProbeSpec(
                id = "vk_feed_cache",
                brand = MessengerBrand.VK,
                shelf = MessengerShelfKind.IMAGE_FEED_CACHE,
                relativeDirs = listOf(
                    "Android/data/com.vkontakte.android/cache",
                ),
            ),
            // Viber
            ProbeSpec(
                id = "viber_media_cache",
                brand = MessengerBrand.VIBER,
                shelf = MessengerShelfKind.IMAGE_FEED_CACHE,
                relativeDirs = listOf(
                    "Android/data/com.viber.voip/cache",
                    "Android/data/com.viber.voip/files/.Temp",
                ),
            ),
        )

        val results = ArrayList<MessengerShelfItem>()
        for (spec in probes) {
            val matchedPaths = ArrayList<String>()
            var totalBytes = 0L
            for (rel in spec.relativeDirs) {
                val dir = File(sdcard, rel)
                if (!dir.isDirectory) continue
                walkBounded(dir, maxDepth = 4) { f ->
                    if (f.isFile && f.lastModified() <= cutoffEpochMs) {
                        val abs = f.absolutePath
                        if (AuthAndCredentialsGuard.isPathSafeToClean(abs, shield, null, activeAutofillPkg)) {
                            totalBytes += f.length()
                            matchedPaths.add(abs)
                        }
                    }
                }
            }
            if (matchedPaths.isNotEmpty() && totalBytes > 0L) {
                results.add(
                    MessengerShelfItem(
                        id = spec.id,
                        brand = spec.brand,
                        shelf = spec.shelf,
                        bytesCount = totalBytes,
                        filesCount = matchedPaths.size,
                        filePaths = matchedPaths,
                    ),
                )
            }
        }
        return results
    }

    // =========================================================================
    // 3. CorpseFinder Engine (Orphaned folders of uninstalled apps/games)
    // =========================================================================

    private fun scanCorpses(
        sdcard: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
    ): List<CorpseFinding> {
        @Suppress("DEPRECATION")
        val installedPkgSet: Set<String> = context.packageManager
            .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName.lowercase() }
            .toSet()

        val areas = listOf(
            File(sdcard, "Android/data") to "Данные удалённого пакета (Android/data)",
            File(sdcard, "Android/obb") to "Кэш удалённой игры (Android/obb)",
            File(sdcard, "Android/media") to "Медиа-папка удалённого пакета (Android/media)",
        )

        val findings = ArrayList<CorpseFinding>()
        for ((areaDir, labelRu) in areas) {
            areaDir.listFiles()?.forEach { child ->
                if (!child.isDirectory) return@forEach
                val pkgCandidate = child.name.trim()
                if (!isPlausiblePackageDirectoryName(pkgCandidate)) return@forEach
                if (isOrphanCorpseCandidate(pkgCandidate, installedPkgSet, shield, activeAutofillPkg)) {
                    val abs = child.absolutePath
                    if (!AuthAndCredentialsGuard.isPathSafeToClean(abs, shield, pkgCandidate, activeAutofillPkg)) {
                        return@forEach
                    }
                    // Extra guard: if directory contains any protected .kdbx/.2fas vault file, NEVER mark as corpse!
                    var containsVault = false
                    var bytes = 0L
                    var count = 0
                    walkBounded(child, maxDepth = 5) { f ->
                        if (!AuthAndCredentialsGuard.isPathSafeToClean(f.absolutePath, shield, pkgCandidate, activeAutofillPkg)) {
                            containsVault = true
                        } else if (f.isFile) {
                            bytes += f.length()
                            count++
                        }
                    }
                    if (!containsVault && count > 0) {
                        findings.add(
                            CorpseFinding(
                                id = "corpse:$abs",
                                packageName = pkgCandidate,
                                absolutePath = abs,
                                locationLabelRu = labelRu,
                                bytesCount = bytes,
                                filesCount = count,
                            ),
                        )
                    }
                }
            }
        }
        return findings.sortedByDescending { it.bytesCount }
    }

    // =========================================================================
    // 4. Two-Stage Deduplicator (Exact SHA-256 + 64-bit Perceptual dHash)
    // =========================================================================

    private fun scanDuplicatesAndBurstPhotos(
        sdcard: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
    ): List<DuplicateCluster> {
        val candidateFiles = ArrayList<File>()
        val folders = listOf(
            File(sdcard, "Download"),
            File(sdcard, "DCIM"),
            File(sdcard, "Pictures"),
        ).filter { it.isDirectory }

        for (dir in folders) {
            walkBounded(dir, maxDepth = 3) { f ->
                if (f.isFile && f.length() >= 16_384L && candidateFiles.size < 1200) {
                    if (AuthAndCredentialsGuard.isPathSafeToClean(f.absolutePath, shield, null, activeAutofillPkg)) {
                        candidateFiles.add(f)
                    }
                }
            }
        }

        val clusters = ArrayList<DuplicateCluster>()
        val alreadyGroupedPaths = HashSet<String>()

        // Stage A: Exact byte-for-byte duplicates (size -> head/tail fast digest -> full SHA-256)
        val bySize = candidateFiles.groupBy { it.length() }.filterValues { it.size >= 2 }
        for ((_, sameSizeGroup) in bySize) {
            val byFastSig = sameSizeGroup.groupBy { computeFastEdgeDigest(it) }.filterValues { it.size >= 2 }
            for ((_, fastMatches) in byFastSig) {
                val byFullSha = fastMatches.groupBy { computeFullSha256(it) }.filterValues { it.size >= 2 }
                for ((sha, exactFiles) in byFullSha) {
                    if (sha.isEmpty()) continue
                    val sorted = sortKeepBestOriginalFirst(exactFiles)
                    val original = sorted.first()
                    val copies = sorted.drop(1)
                    val reclaimBytes = copies.sumOf { it.length() }
                    copies.forEach { alreadyGroupedPaths.add(it.absolutePath) }
                    alreadyGroupedPaths.add(original.absolutePath)
                    clusters.add(
                        DuplicateCluster(
                            id = "sha256:$sha",
                            kind = DuplicateMatchKind.EXACT_SHA256,
                            keptOriginalPath = original.absolutePath,
                            duplicatePaths = copies.map { it.absolutePath },
                            reclaimableBytes = reclaimBytes,
                            isSelected = true,
                        ),
                    )
                }
            }
        }

        // Stage B: Perceptual 64-bit dHash for near-identical burst photos
        val photoFiles = candidateFiles
            .filter { f ->
                val ext = f.extension.lowercase()
                ext in PHOTO_EXTENSIONS && f.absolutePath !in alreadyGroupedPaths
            }
            .take(220)

        val hashedPhotos = photoFiles.mapNotNull { file ->
            computeBitmapDHash64(file)?.let { hash -> file to hash }
        }

        val usedIndices = BooleanArray(hashedPhotos.size)
        for (i in hashedPhotos.indices) {
            if (usedIndices[i]) continue
            val (baseFile, baseHash) = hashedPhotos[i]
            val nearGroup = mutableListOf(baseFile)
            for (j in (i + 1) until hashedPhotos.size) {
                if (usedIndices[j]) continue
                val (otherFile, otherHash) = hashedPhotos[j]
                if (hammingDistance64(baseHash, otherHash) <= 5) {
                    usedIndices[j] = true
                    nearGroup.add(otherFile)
                }
            }
            if (nearGroup.size >= 2) {
                usedIndices[i] = true
                // Keep largest/sharpest shot as original, mark smaller burst frames (unchecked by default!)
                val sortedByQuality = nearGroup.sortedByDescending { it.length() }
                val keep = sortedByQuality.first()
                val burstExtras = sortedByQuality.drop(1)
                clusters.add(
                    DuplicateCluster(
                        id = "dhash:${keep.absolutePath.hashCode()}",
                        kind = DuplicateMatchKind.SIMILAR_DHASH,
                        keptOriginalPath = keep.absolutePath,
                        duplicatePaths = burstExtras.map { it.absolutePath },
                        reclaimableBytes = burstExtras.sumOf { it.length() },
                        isSelected = false,
                    ),
                )
            }
        }

        return clusters.sortedByDescending { it.reclaimableBytes }
    }

    // =========================================================================
    // Double-Gated Safe Execution
    // =========================================================================

    suspend fun purgeTargets(
        filePathsToDelete: List<String>,
        shield: CredentialShieldConfig,
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val activeAutofillPkg = executor.readSetting(SettingNamespace.SECURE, "autofill_service")
            ?.substringBefore('/')

        var deletedItems = 0
        var freedBytes = 0L

        for (target in filePathsToDelete) {
            when {
                target == "::PRIVILEGED_TRIM_CACHES::" -> {
                    val cmd = "pm trim-caches 999999999999999999"
                    if (AuthAndCredentialsGuard.isShellCommandSafeForAccounts(cmd)) {
                        if (executor.execute(cmd).isSuccess) {
                            deletedItems++
                        }
                    }
                }
                target.startsWith("::STAGING_SESSION::") -> {
                    val sessionId = target.removePrefix("::STAGING_SESSION::").trim()
                    if (sessionId.all { it.isDigit() }) {
                        val cmd = "pm install-abandon $sessionId"
                        if (AuthAndCredentialsGuard.isShellCommandSafeForAccounts(cmd)) {
                            if (executor.execute(cmd).isSuccess) deletedItems++
                        }
                    }
                }
                else -> {
                    // Mandatory second-pass check through AuthAndCredentialsGuard right before delete!
                    if (!AuthAndCredentialsGuard.isPathSafeToClean(target, shield, null, activeAutofillPkg)) {
                        continue
                    }
                    val f = File(target)
                    if (!f.exists()) continue
                    val (cnt, bytes) = deleteRecursivelySafe(f, shield, activeAutofillPkg)
                    deletedItems += cnt
                    freedBytes += bytes
                }
            }
        }

        deletedItems to freedBytes
    }

    private fun deleteRecursivelySafe(
        file: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
    ): Pair<Int, Long> {
        val isSymlink = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (isSymlink) {
            // Never traverse symbolic links during deletion (blocks TOCTOU symlink escape attacks)
            return 0 to 0L
        }
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return 0 to 0L
        if (!AuthAndCredentialsGuard.isPathSafeToClean(file.absolutePath, shield, null, activeAutofillPkg) ||
            !AuthAndCredentialsGuard.isPathSafeToClean(canonical, shield, null, activeAutofillPkg)
        ) {
            return 0 to 0L
        }
        if (file.isDirectory) {
            var count = 0
            var bytes = 0L
            file.listFiles()?.forEach { child ->
                val (c, b) = deleteRecursivelySafe(child, shield, activeAutofillPkg)
                count += c
                bytes += b
            }
            if (file.list()?.isEmpty() == true && file.delete()) {
                count++
            }
            return count to bytes
        } else {
            val size = file.length()
            return if (file.delete()) 1 to size else 0 to 0L
        }
    }

    private fun measureDirSafe(
        dir: File,
        shield: CredentialShieldConfig,
        activeAutofillPkg: String?,
    ): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        walkBounded(dir, maxDepth = 5) { f ->
            if (f.isFile && AuthAndCredentialsGuard.isPathSafeToClean(f.absolutePath, shield, null, activeAutofillPkg)) {
                bytes += f.length()
                count++
            }
        }
        return bytes to count
    }

    private fun walkBounded(root: File, maxDepth: Int, visitor: (File) -> Unit) {
        fun step(cur: File, depth: Int) {
            if (depth > maxDepth) return
            visitor(cur)
            if (cur.isDirectory) {
                cur.listFiles()?.forEach { child ->
                    // Never follow symlinks outside tree
                    if (runCatching { child.canonicalPath.startsWith(root.canonicalPath) }.getOrDefault(false)) {
                        step(child, depth + 1)
                    }
                }
            }
        }
        if (root.exists()) step(root, 0)
    }

    private fun computeBitmapDHash64(file: File): Long? = runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sample = 1
        while (boundsOpts.outWidth / sample > 64 && boundsOpts.outHeight / sample > 64) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 9, 8, true)
        if (scaled !== bmp) bmp.recycle()

        val lumaGrid = IntArray(9 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                val px = scaled.getPixel(x, y)
                lumaGrid[y * 9 + x] = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
            }
        }
        scaled.recycle()
        computeDHashFrom9x8Luma(lumaGrid)
    }.getOrNull()

    companion object {
        private val DESKTOP_JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini", ".spotlight-v100")
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val PROTECTED_SYSTEM_PACKAGE_PREFIXES = listOf(
            "com.android.",
            "android",
            "com.google.android.gms",
            "com.google.android.gsf",
        )

        fun isPlausiblePackageDirectoryName(name: String): Boolean =
            name.count { it == '.' } >= 1 &&
                !name.startsWith('.') &&
                Regex("^[a-zA-Z0-9._]+$").matches(name)

        fun isOrphanCorpseCandidate(
            pkgName: String,
            installedPackagesLower: Set<String>,
            shield: CredentialShieldConfig,
            activeAutofillPkg: String? = null,
        ): Boolean {
            val lower = pkgName.lowercase()
            if (lower in installedPackagesLower) return false
            if (PROTECTED_SYSTEM_PACKAGE_PREFIXES.any { lower.startsWith(it) }) return false
            if (!AuthAndCredentialsGuard.isPathSafeToClean("/sdcard/Android/data/$lower/files", shield, lower, activeAutofillPkg)) {
                return false
            }
            return true
        }

        fun parseDiskstatsAppCacheBytes(diskstatsOutput: String): Long {
            // Parses `App Cache Size: 123456789` or sums `Cache Sizes: [1024, 2048, ...]`
            val single = Regex("App Cache Size:\\s*(\\d+)").find(diskstatsOutput)?.groupValues?.get(1)?.toLongOrNull()
            if (single != null && single > 0L) return single

            val bracket = Regex("Cache Sizes:\\s*\\[([0-9,\\s]+)]").find(diskstatsOutput)?.groupValues?.get(1)
            if (bracket != null) {
                return bracket.split(',').sumOf { it.trim().toLongOrNull() ?: 0L }
            }
            return 0L
        }

        fun computeFastEdgeDigest(file: File): String = runCatching {
            val len = file.length()
            val md = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val headBuf = ByteArray(minOf(8192L, len).toInt())
                raf.readFully(headBuf)
                md.update(headBuf)
                if (len > 16384L) {
                    raf.seek(len - 8192L)
                    val tailBuf = ByteArray(8192)
                    raf.readFully(tailBuf)
                    md.update(tailBuf)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        fun computeFullSha256(file: File): String = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { inp ->
                val buf = ByteArray(16_384)
                while (true) {
                    val r = inp.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        fun sortKeepBestOriginalFirst(files: List<File>): List<File> =
            files.sortedWith(
                compareBy<File> { Regex("\\(\\d+\\)|copy|копия", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                    .thenBy { it.name.length }
                    .thenBy { it.lastModified() },
            )

        fun computeDHashFrom9x8Luma(luma9x8: IntArray): Long {
            require(luma9x8.size == 72) { "Expected 9x8 = 72 luminance values" }
            var hash = 0L
            var bitIndex = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luma9x8[y * 9 + x]
                    val right = luma9x8[y * 9 + x + 1]
                    if (left > right) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex++
                }
            }
            return hash
        }

        fun hammingDistance64(a: Long, b: Long): Int =
            java.lang.Long.bitCount(a xor b)
    }
}
