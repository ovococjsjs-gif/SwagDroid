package com.swagdroid.feature.cleaner.safety

import com.swagdroid.feature.cleaner.model.CleanupExclusion
import com.swagdroid.feature.cleaner.model.CleanupExecutionResult
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CleanupSource
import com.swagdroid.feature.cleaner.model.PlannedCleanupTarget
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Deliberately contains no Android or privileged-shell API. Preparation never mutates storage.
 * Execution only deletes individual reviewed files / already-empty directories, never a tree.
 *
 * Revalidation reduces scan/confirmation races, but is NOT an atomic filesystem transaction:
 * another process can still change a path between the last check and unlink. There is no undo.
 */
class FileCleanupPlanner(
    storageRoot: File,
    private val isPathAllowed: (String) -> Boolean,
    private val checkCancellation: () -> Unit = {},
    private val maxVisitedEntries: Int = 10_000,
    private val maxDepth: Int = 32,
    private val deleteFile: (Path) -> Unit = { Files.delete(it) },
) {
    private val boundary = SharedStorageBoundary(storageRoot, isPathAllowed)
    private val root = boundary.root

    fun prepare(selection: CleanupSelection): PreparedCleanupPlan {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Хранилище недоступно. Проверьте разрешение и повторите сканирование." }
        val exclusions = linkedMapOf<String, CleanupExclusion>()
        val targets = linkedMapOf<String, PlannedCleanupTarget>()
        val visited = hashSetOf<Path>()
        val protected = (selection.protectedOriginalPaths + selection.groups.mapNotNull { it.keptOriginalPath })
            .mapNotNull { resolveInsideRoot(it) }.toSet()
        val originalSnapshots = mutableMapOf<Path, CleanupFileSnapshot>()

        fun exclude(path: String, reason: String) {
            exclusions[path] = CleanupExclusion(path, reason)
        }

        // Associate duplicate constraints BEFORE traversal so another tab cannot bypass them.
        val originalsByCopy = mutableMapOf<Path, MutableSet<CleanupFileSnapshot>>()
        val exactOriginalsByCopy = mutableMapOf<Path, MutableSet<CleanupFileSnapshot>>()
        selection.groups.forEach { group ->
            val originalPath = group.keptOriginalPath ?: return@forEach
            val original = resolveInsideRoot(originalPath)
                ?: throw IllegalArgumentException("Оригинал дубликата вне доступного хранилища: $originalPath")
            val originalSnapshot = originalSnapshots.getOrPut(original) {
                capture(original).also {
                    require(!it.isEmptyDirectory) { "Оригинал дубликата больше не является файлом: $originalPath" }
                    if (selection.requireExactTargets) require(selection.expectedFiles.containsKey(original.toString())) { "Нет снимка сохраняемого оригинала. Нужно новое сканирование." }
                    selection.expectedFiles[original.toString()]?.let { expected ->
                        require(CleanupFileReader.matchesExpected(it, expected)) { "Оригинал изменился после поиска. Повторите сканирование: $originalPath" }
                    }
                }
            }
            group.paths.forEach copyLoop@ { copyPath ->
                val copy = resolveInsideRoot(copyPath) ?: return@copyLoop
                originalsByCopy.getOrPut(copy) { linkedSetOf() }.add(originalSnapshot)
                if (group.exactDuplicates) {
                    exactOriginalsByCopy.getOrPut(copy) { linkedSetOf() }.add(originalSnapshot)
                }
            }
        }

        fun visit(path: Path, source: CleanupSource, depth: Int) {
            checkCancellation()
            if (visited.add(path) && visited.size > maxVisitedEntries) {
                throw IllegalArgumentException("План слишком большой (более $maxVisitedEntries объектов). Выберите меньше категорий.")
            }
            require(depth <= maxDepth) { "Слишком глубокий каталог: $path. Выберите меньше категорий." }
            if (!isSafePath(path) || path in protected) {
                exclude(path.toString(), "Защищённый путь, символическая ссылка или сохраняемый оригинал")
                return
            }
            try {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val expected = selection.expectedFiles[path.toString()]
                if (selection.requireExactTargets && expected == null) {
                    exclude(path.toString(), "Объект не входил в просмотренный список")
                    return
                }
                if (expected != null && !CleanupFileReader.matchesExpected(CleanupFileReader.metadata(path, attrs), expected.copy(sha256 = null))) {
                    exclude(path.toString(), "Объект изменился после поиска; нужно новое сканирование")
                    return
                }
                if (attrs.isDirectory) {
                    val children = path.toFile().listFiles()
                        ?: throw IOException("Не удалось прочитать содержимое папки")
                    if (children.isNotEmpty()) {
                        if (selection.requireExactTargets || expected != null) {
                            exclude(path.toString(), "Выбранная пустая папка больше не пуста. Новые файлы не включены в план.")
                            return
                        }
                        children.sortedBy { it.name }.forEach { visit(it.toPath(), source, depth + 1) }
                        return
                    }
                }
                val existing = targets[path.toString()]
                if (existing != null) {
                    targets[path.toString()] = existing.copy(sources = existing.sources + source)
                    return
                }
                val snapshot = capture(path)
                if (expected != null && !CleanupFileReader.matchesExpected(snapshot, expected)) {
                    exclude(path.toString(), "Содержимое изменилось после поиска; нужно новое сканирование")
                    return
                }
                val exactOriginals = exactOriginalsByCopy[path].orEmpty()
                if (exactOriginals.any { snapshot.sha256 == null || it.sha256 != snapshot.sha256 }) {
                    exclude(path.toString(), "Файл больше не совпадает с оригиналом. Нужно новое сканирование.")
                    return
                }
                targets[path.toString()] = PlannedCleanupTarget(
                    snapshot = snapshot,
                    sources = setOf(source),
                    requiredOriginals = originalsByCopy[path].orEmpty().toSet(),
                )
            } catch (error: IOException) {
                exclude(path.toString(), error.message ?: "Файл недоступен")
            } catch (_: SecurityException) {
                exclude(path.toString(), "Нет доступа к файлу")
            }
        }

        selection.groups.forEach { group ->
            group.paths.forEach { raw ->
                val path = resolveInsideRoot(raw)
                if (path == null) {
                    // Rejects both arbitrary absolute paths and legacy ::SHELL_OPERATION:: markers.
                    exclude(raw, "Путь вне хранилища или операция недоступна для файловой очистки")
                } else {
                    visit(path, group.source, 0)
                }
            }
        }
        return PreparedCleanupPlan(
            id = UUID.randomUUID().toString(),
            storageRoot = root.toString(),
            targets = targets.values.toList(),
            exclusions = exclusions.values.toList(),
        )
    }

    fun execute(approval: CleanupReview.Approved): CleanupExecutionResult {
        val plan = checkNotNull(approval.claimPlan()) { "Подтверждение уже использовано. Подготовьте новый план." }
        require(plan.storageRoot == root.toString()) { "Хранилище изменилось. Нужно новое сканирование." }
        var deletedFiles = 0
        var deletedDirectories = 0
        var deletedBytes = 0L
        val skipped = mutableListOf<CleanupExclusion>()
        val failed = mutableListOf<CleanupExclusion>()
        val attempted = hashSetOf<String>()
        val protectedOriginals = plan.targets.flatMap { it.requiredOriginals }.map { it.path }.toSet()

        for (target in plan.targets) {
            checkCancellation()
            val snapshot = target.snapshot
            if (!attempted.add(snapshot.path)) continue
            try {
                if (snapshot.path in protectedOriginals || !matches(snapshot) || target.requiredOriginals.any { !matches(it) }) {
                    skipped += CleanupExclusion(snapshot.path, "Файл или оригинал изменился, недоступен либо защищён. Не удалён.")
                    continue
                }
                // No recursive delete and no fresh enumeration: new files are never added to a plan.
                deleteFile(File(snapshot.path).toPath())
                if (snapshot.isEmptyDirectory) {
                    deletedDirectories++
                } else {
                    deletedFiles++
                    deletedBytes += snapshot.bytes
                }
            } catch (error: IOException) {
                failed += CleanupExclusion(snapshot.path, error.message ?: "Не удалось удалить")
            } catch (_: SecurityException) {
                failed += CleanupExclusion(snapshot.path, "Доступ отозван. Не удалось удалить.")
            }
        }
        return CleanupExecutionResult(deletedFiles, deletedDirectories, deletedBytes, skipped, failed)
    }

    private fun matches(snapshot: CleanupFileSnapshot): Boolean = try {
        val path = resolveInsideRoot(snapshot.path)
        path != null && isSafePath(path) && capture(path) == snapshot
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun capture(path: Path): CleanupFileSnapshot = CleanupFileReader.capture(path, boundary, checkCancellation)
    private fun resolveInsideRoot(raw: String): Path? = boundary.resolve(raw)
    private fun isSafePath(path: Path): Boolean = boundary.allows(path)
}
