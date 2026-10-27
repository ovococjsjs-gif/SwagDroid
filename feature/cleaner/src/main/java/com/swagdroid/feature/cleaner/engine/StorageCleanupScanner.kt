package com.swagdroid.feature.cleaner.engine

import com.swagdroid.feature.cleaner.model.CleanupBucket
import com.swagdroid.feature.cleaner.model.CleanupCandidate
import com.swagdroid.feature.cleaner.model.CleanupScanProgress
import com.swagdroid.feature.cleaner.model.ScanCoverage
import com.swagdroid.feature.cleaner.model.ScanLocationReport
import com.swagdroid.feature.cleaner.model.ScanLocationStatus
import com.swagdroid.feature.cleaner.model.StorageCleanupScan
import com.swagdroid.feature.cleaner.safety.CleanupFileReader
import com.swagdroid.feature.cleaner.safety.SharedStorageBoundary
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Bounded, read-only discovery for one explicit shared-storage scenario. No package/shell mutations. */
class StorageCleanupScanner(
    storageRoot: File,
    isPathAllowed: (String) -> Boolean,
    private val checkCancellation: () -> Unit = {},
    private val onProgress: (CleanupScanProgress) -> Unit = {},
    private val maxEntries: Int = 10_000,
    private val maxCandidates: Int = 3_000,
    private val maxDepth: Int = 6,
    private val maxChildrenPerDirectory: Int = 4_000,
    private val maxDuplicateFileBytes: Long = 64L * 1024 * 1024,
    private val duplicateReadBudget: Long = 256L * 1024 * 1024,
    private val maxScanMillis: Long = 30_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val listDirectory: (Path, Int) -> ChildListing = ::readChildren,
) {
    data class Location(val title: String, val relativePath: String)
    data class ChildListing(val paths: List<Path>, val truncated: Boolean)
    private val boundary = SharedStorageBoundary(storageRoot, isPathAllowed)
    private class BudgetExceeded(val explanation: String) : RuntimeException()

    fun scan(locations: List<Location> = defaultLocations): StorageCleanupScan {
        require(maxEntries > 0 && maxCandidates > 0 && maxDepth >= 0 && maxScanMillis > 0)
        val started = nanoTime()
        val nowMs = System.currentTimeMillis()
        val candidates = linkedMapOf<String, CleanupCandidate>()
        val reports = mutableListOf<ScanLocationReport>()
        val visited = hashSetOf<Path>()
        val limits = linkedSetOf<String>()
        var protected = 0
        var inspected = 0
        var stopped = false

        fun checkBudget() {
            checkCancellation()
            if ((nanoTime() - started) / 1_000_000 >= maxScanMillis) throw BudgetExceeded("Достигнут лимит времени поиска (${maxScanMillis / 1000} с)")
        }

        for (location in locations) {
            checkCancellation()
            if (stopped) {
                reports += ScanLocationReport(location.title, location.relativePath, ScanLocationStatus.NOT_REACHED, 0, listOf("Поиск остановлен по лимиту"))
                continue
            }
            val root = boundary.resolve(File(boundary.root.toFile(), location.relativePath).path)
            if (root == null) {
                reports += ScanLocationReport(location.title, location.relativePath, ScanLocationStatus.UNAVAILABLE, 0, listOf("Путь вне выбранного хранилища"))
                continue
            }
            var localFiles = 0
            var rootStatus = ScanLocationStatus.CHECKED
            val notes = linkedSetOf<String>()
            onProgress(CleanupScanProgress(location.title, inspected))

            fun partial(reason: String) {
                if (rootStatus == ScanLocationStatus.CHECKED) rootStatus = ScanLocationStatus.PARTIAL
                notes += reason
            }

            fun visit(path: Path, depth: Int) {
                checkBudget()
                if (!visited.add(path)) return
                if (visited.size > maxEntries) throw BudgetExceeded("Просмотрено $maxEntries объектов; остальная часть хранилища не проверена")
                if (!boundary.allows(path)) {
                    protected++
                    if (path == root) { rootStatus = ScanLocationStatus.PARTIAL; notes += "Папка исключена фильтром защиты" }
                    return
                }
                try {
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attrs.isDirectory) {
                        val children = listDirectory(path, maxChildrenPerDirectory)
                        if (children.truncated) partial("В большой папке проверены только первые $maxChildrenPerDirectory объектов")
                        if (children.paths.isEmpty() && !children.truncated && depth > 0) {
                            if (candidates.size >= maxCandidates) throw BudgetExceeded("В списке первые $maxCandidates объектов. Сузьте поиск или проверьте оставшееся отдельно.")
                            candidates[path.toString()] = CleanupCandidate(CleanupFileReader.metadata(path, attrs), CleanupBucket.EMPTY_DIRECTORIES, location.title)
                        } else if (depth >= maxDepth && children.paths.isNotEmpty()) {
                            partial("Вложенные папки глубже $maxDepth уровней не проверены")
                        } else {
                            children.paths.sortedBy { it.fileName.toString() }.forEach { visit(it, depth + 1) }
                        }
                    } else if (attrs.isRegularFile) {
                        inspected++
                        localFiles++
                        if (candidates.size >= maxCandidates) throw BudgetExceeded("В списке первые $maxCandidates объектов. Остальные файлы не проверены.")
                        candidates[path.toString()] = CleanupCandidate(
                            CleanupFileReader.metadata(path, attrs), classify(path.toFile(), attrs.lastModifiedTime().toMillis(), nowMs), location.title,
                        )
                        if (inspected % 100 == 0) onProgress(CleanupScanProgress(location.title, inspected))
                    } else {
                        partial("Необычные объекты файловой системы пропущены")
                    }
                } catch (_: NoSuchFileException) {
                    if (path == root) rootStatus = ScanLocationStatus.ABSENT else partial("Часть объектов исчезла во время поиска")
                } catch (_: SecurityException) {
                    if (path == root) rootStatus = ScanLocationStatus.UNAVAILABLE else partial("Часть вложенных папок или файлов недоступна")
                    notes += "Android не разрешил прочитать содержимое"
                } catch (_: IOException) {
                    if (path == root) rootStatus = ScanLocationStatus.UNAVAILABLE else partial("Часть объектов не удалось прочитать")
                    notes += "Ошибка чтения; отсутствие файлов не подтверждено"
                } catch (_: DirectoryIteratorException) {
                    if (path == root) rootStatus = ScanLocationStatus.UNAVAILABLE else partial("Не удалось дочитать папку")
                    notes += "Ошибка перечисления содержимого"
                }
            }

            try {
                visit(root, 0)
            } catch (limit: BudgetExceeded) {
                limits += limit.explanation
                partial(limit.explanation)
                stopped = true
            }
            reports += ScanLocationReport(location.title, location.relativePath, rootStatus, localFiles, notes.toList())
        }

        // Only exact equality is offered. Similar photos are not treated as expendable copies.
        onProgress(CleanupScanProgress("Сравнение точных копий", inspected))
        val originals = linkedSetOf<String>()
        var readBytes = 0L
        var compared = 0
        var notCompared = 0
        val possibleDuplicates = candidates.values.filterNot { it.file.isEmptyDirectory }.groupBy { it.file.bytes }.values.filter { it.size >= 2 }
        for (sameSize in possibleDuplicates) {
            checkCancellation()
            val bytes = sameSize.first().file.bytes
            val needed = if (bytes > Long.MAX_VALUE / sameSize.size) Long.MAX_VALUE else bytes * sameSize.size
            if (bytes > maxDuplicateFileBytes || needed > duplicateReadBudget - readBytes || (nanoTime() - started) / 1_000_000 >= maxScanMillis) {
                notCompared += sameSize.size
                continue
            }
            val hashed = mutableListOf<CleanupCandidate>()
            for (candidate in sameSize) {
                checkCancellation()
                readBytes += candidate.file.bytes
                try {
                    val full = CleanupFileReader.capture(File(candidate.file.path).toPath(), boundary) {
                        checkBudget()
                    }
                    if (!CleanupFileReader.matchesExpected(full, candidate.file)) {
                        candidates.remove(candidate.file.path)
                        limits += "Изменившиеся во время поиска файлы исключены"
                        notCompared++
                        continue
                    }
                    hashed += candidate.copy(file = full)
                    compared++
                } catch (_: IOException) {
                    notCompared++
                    limits += "Часть файлов не удалось сравнить по содержимому"
                } catch (_: SecurityException) {
                    notCompared++
                    limits += "Доступ к части файлов отозван во время сравнения"
                } catch (_: BudgetExceeded) {
                    notCompared++
                    limits += "Сравнение копий ограничено временем поиска"
                }
            }
            for (duplicates in hashed.groupBy { it.file.sha256 }.values.filter { it.size >= 2 }) {
                val sorted = duplicates.sortedWith(
                    compareBy<CleanupCandidate> { copyName.containsMatchIn(it.name) }.thenBy { it.name.length }
                        .thenBy { it.file.modifiedAtMs }.thenBy { it.file.path },
                )
                val original = sorted.first().file
                originals += original.path
                candidates.remove(original.path)
                for (copy in sorted.drop(1)) {
                    candidates[copy.file.path] = copy.copy(bucket = CleanupBucket.DUPLICATES, keptOriginal = original)
                }
            }
        }
        if (notCompared > 0) limits += "Не сравнивалось содержимое $notCompared файлов: лимиты размера/чтения либо недоступность. Это не полный поиск копий."
        return StorageCleanupScan(
            UUID.randomUUID().toString(), nowMs, boundary.root.toString(),
            candidates.values.sortedWith(compareByDescending<CleanupCandidate> { it.file.bytes }.thenBy { it.file.path }),
            originals,
            ScanCoverage(reports, minOf(visited.size, maxEntries), protected, compared, notCompared, limits.toList()),
        )
    }

    companion object {
        val defaultLocations = listOf(
            Location("Загрузки", "Download"), Location("Документы", "Documents"),
            Location("Камера", "DCIM"), Location("Изображения", "Pictures"),
            Location("Видео", "Movies"), Location("Музыка", "Music"),
            Location("Медиа приложений", "Android/media"), Location("Telegram", "Telegram"),
            Location("WhatsApp", "WhatsApp"), Location("Viber", "Viber"), Location("ВКонтакте", "VK"),
        )
        private val copyName = Regex("\\(\\d+\\)|copy|копия", RegexOption.IGNORE_CASE)
        private val mediaExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mkv", "webm", "3gp", "mp3", "aac", "m4a", "wav", "ogg", "opus", "amr")
        private fun classify(file: File, modified: Long, now: Long): CleanupBucket {
            val name = file.name.lowercase()
            val extension = file.extension.lowercase()
            return when {
                extension in setOf("tmp", "temp", "log", "dmp") || name in setOf(".ds_store", "thumbs.db", "desktop.ini") || file.path.contains("/.thumbnails/") -> CleanupBucket.TEMPORARY
                extension in setOf("part", "crdownload") && modified < now - 86_400_000L -> CleanupBucket.TEMPORARY
                extension == "apk" -> CleanupBucket.INSTALLERS
                extension in mediaExtensions -> CleanupBucket.MEDIA
                else -> CleanupBucket.DOWNLOADS
            }
        }

        fun readChildren(path: Path, limit: Int): ChildListing {
            val children = ArrayList<Path>()
            Files.newDirectoryStream(path).use { stream ->
                val iterator = stream.iterator()
                while (children.size < limit && iterator.hasNext()) children.add(iterator.next())
                return ChildListing(children, iterator.hasNext())
            }
        }
    }
}
