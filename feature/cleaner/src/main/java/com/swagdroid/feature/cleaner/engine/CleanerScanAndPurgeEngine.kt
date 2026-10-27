package com.swagdroid.feature.cleaner.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.CleanupScanProgress
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import com.swagdroid.feature.cleaner.model.StorageAccess
import com.swagdroid.feature.cleaner.model.StorageCleanupScan
import com.swagdroid.feature.cleaner.model.StorageSpace
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard
import com.swagdroid.feature.cleaner.safety.CleanupFileReader
import com.swagdroid.feature.cleaner.safety.FileCleanupPlanner
import com.swagdroid.feature.cleaner.safety.SharedStorageBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CleanerScanAndPurgeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
    private val recorded: RecordedCleanupExecutor,
) : CleanerOperations {
    override fun storageAccess(): StorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (Environment.isExternalStorageManager()) StorageAccess.ALL_FILES else StorageAccess.NEEDS_ALL_FILES
    } else {
        val granted = listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) StorageAccess.LEGACY_GRANTED else StorageAccess.NEEDS_LEGACY_PERMISSIONS
    }

    override fun storageSpace(): StorageSpace? = try {
        val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        if (stats.totalBytes > 0 && stats.availableBytes in 0..stats.totalBytes) StorageSpace(stats.availableBytes, stats.totalBytes) else null
    } catch (_: Exception) { null }

    override suspend fun scan(onProgress: (CleanupScanProgress) -> Unit): StorageCleanupScan = withContext(Dispatchers.IO) {
        check(storageAccess().canScan) { "Сначала разрешите доступ к общим файлам" }
        val allowed = pathGuard()
        val job = currentCoroutineContext()
        StorageCleanupScanner(
            Environment.getExternalStorageDirectory(), allowed,
            checkCancellation = { job.ensureActive() }, onProgress = onProgress,
        ).scan()
    }

    override suspend fun prepare(selection: CleanupSelection): PreparedCleanupPlan = withContext(Dispatchers.IO) {
        check(storageAccess().canScan) { "Доступ к файлам отозван. План не подготовлен." }
        planner().prepare(selection)
    }

    override suspend fun execute(approval: CleanupReview.Approved): CleanupRunOutcome = withContext(Dispatchers.IO) {
        if (!storageAccess().canScan) return@withContext CleanupRunOutcome.NotStarted("Доступ к файлам отозван. Удаление не запускалось.")
        // Construct the guard before recording a run; a failed prerequisite is not partial deletion.
        val worker = try { planner() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return@withContext CleanupRunOutcome.NotStarted("Не удалось проверить ограничения. Удаление не запускалось: ${error.message}") }
        recorded.run(approval) { worker.execute(approval) }
    }

    override suspend fun preview(file: CleanupFileSnapshot): CleanupFilePreview = withContext(Dispatchers.IO) {
        check(storageAccess().canScan) { "Нет доступа к файлу" }
        check(!file.isEmptyDirectory) { "Для папки доступен только список пути и параметров" }
        val boundary = SharedStorageBoundary(Environment.getExternalStorageDirectory(), pathGuard())
        val path = checkNotNull(boundary.resolve(file.path))
        check(boundary.allows(path)) { "Путь защищён или изменился" }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isRegularFile && CleanupFileReader.matchesExpected(CleanupFileReader.metadata(path, attributes), file.copy(sha256 = null))) {
            "Файл изменился после поиска. Повторите сканирование."
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.toFile().extension.lowercase(Locale.ROOT))
            ?: error("Для этого формата доступна только информация о файле")
        check(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") || mime == "application/pdf" || mime.startsWith("text/")) {
            "Этот формат не открывается из очистки. Установщики APK не запускаются."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cleaner.preview", path.toFile())
        CleanupFilePreview(uri.toString(), mime)
    }

    private suspend fun pathGuard(): (String) -> Boolean {
        val autofill = executor.readSetting(SettingNamespace.SECURE, "autofill_service")?.substringBefore('/')
        return { path -> AuthAndCredentialsGuard.isPathSafeToClean(path, CredentialShieldConfig(), null, autofill) }
    }

    private suspend fun planner(): FileCleanupPlanner {
        val allowed = pathGuard()
        val job = currentCoroutineContext()
        return FileCleanupPlanner(
            Environment.getExternalStorageDirectory(),
            isPathAllowed = { storageAccess().canScan && allowed(it) },
            checkCancellation = { job.ensureActive() },
        )
    }

    // Legacy pure algorithms stay covered by their regression tests. They are not automatic
    // cleanup capabilities of the new shared-files scenario (no corpse/dHash/shell purge).
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
