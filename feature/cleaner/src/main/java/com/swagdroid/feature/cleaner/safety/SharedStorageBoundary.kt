package com.swagdroid.feature.cleaner.safety

import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Shared by discovery, preview and execution. This is not an atomic check-and-unlink primitive. */
class SharedStorageBoundary(storageRoot: File, private val isPathAllowed: (String) -> Boolean) {
    private val logicalRoot = storageRoot.toPath().toAbsolutePath().normalize()
    val root: Path = storageRoot.canonicalFile.toPath()

    fun resolve(raw: String): Path? {
        val file = File(raw)
        if (!file.isAbsolute) return null
        val input = file.toPath().normalize()
        val relative = when {
            input.startsWith(logicalRoot) -> logicalRoot.relativize(input)
            input.startsWith(root) -> root.relativize(input)
            else -> return null
        }
        return root.resolve(relative).normalize().takeIf { it != root && it.startsWith(root) }
    }

    fun allows(path: Path): Boolean {
        if (path == root || !path.startsWith(root) || Files.isSymbolicLink(root) || !isPathAllowed(path.toString())) return false
        var current = root
        for (segment in root.relativize(path)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }
}

object CleanupFileReader {
    fun metadata(path: Path, attributes: BasicFileAttributes): CleanupFileSnapshot = CleanupFileSnapshot(
        path.toString(), if (attributes.isDirectory) 0 else attributes.size(), attributes.lastModifiedTime().toMillis(),
        null, attributes.isDirectory, attributes.fileKey()?.toString(),
    )

    fun capture(path: Path, boundary: SharedStorageBoundary, checkCancellation: () -> Unit): CleanupFileSnapshot {
        checkCancellation()
        if (!boundary.allows(path)) throw IOException("Путь защищён или содержит символическую ссылку")
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.isDirectory) {
            Files.newDirectoryStream(path).use { if (it.iterator().hasNext()) throw IOException("Папка больше не пуста") }
            return metadata(path, before)
        }
        if (!before.isRegularFile) throw IOException("Не обычный файл")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.fileKey() != after.fileKey() || !boundary.allows(path)
        ) throw IOException("Файл изменился во время проверки")
        return metadata(path, before).copy(sha256 = digest.digest().joinToString("") { "%02x".format(it) })
    }

    fun matchesExpected(current: CleanupFileSnapshot, expected: CleanupFileSnapshot): Boolean =
        current.path == expected.path && current.bytes == expected.bytes && current.modifiedAtMs == expected.modifiedAtMs &&
            current.isEmptyDirectory == expected.isEmptyDirectory &&
            (expected.fileKey == null || current.fileKey == expected.fileKey) &&
            (expected.sha256 == null || current.sha256 == expected.sha256)
}
