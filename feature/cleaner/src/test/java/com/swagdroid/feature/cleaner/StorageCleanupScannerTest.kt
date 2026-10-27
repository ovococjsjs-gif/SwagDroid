package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.engine.StorageCleanupScanner
import com.swagdroid.feature.cleaner.model.CleanupBucket
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.ScanLocationStatus
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard
import com.swagdroid.feature.cleaner.safety.FileCleanupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class StorageCleanupScannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val locations = listOf(StorageCleanupScanner.Location("Downloads", "Download"))
    private fun file(root: File, name: String, content: String = name) = File(root, name).apply { parentFile.mkdirs(); writeText(content) }
    private fun scanner(root: File) = StorageCleanupScanner(root, { AuthAndCredentialsGuard.isPathSafeToClean(it) })

    @Test fun `discovery only reads files and offers explicit leaf candidates`() {
        val root = temporary.newFolder("storage")
        val a = file(root, "Download/a.tmp", "alpha")
        val b = file(root, "Download/document.pdf", "beta data")
        val scan = scanner(root).scan(locations)
        assertEquals(setOf(a.path, b.path), scan.candidates.map { it.file.path }.toSet())
        assertEquals("alpha", a.readText()); assertEquals("beta data", b.readText())
        assertTrue(scan.selection(emptySet()).groups.isEmpty())
        assertEquals(ScanLocationStatus.CHECKED, scan.coverage.locations.single().status)
    }

    @Test fun `selection has only catalog members including zero byte targets`() {
        val root = temporary.newFolder("storage")
        val zero = file(root, "Download/zero.tmp", "")
        val scan = scanner(root).scan(locations)
        val selected = scan.selection(setOf(zero.path, "/private/not-in-catalog"))
        assertEquals(listOf(zero.path), selected.groups.flatMap { it.paths })
        assertTrue(selected.requireExactTargets)
        assertEquals(0L, selected.expectedFiles.getValue(zero.path).bytes)
    }

    @Test fun `personal media and installers are not named temporary junk`() {
        val root = temporary.newFolder("storage")
        val photo = file(root, "Download/photo.jpg", "a")
        val apk = file(root, "Download/app.apk", "ab")
        val document = file(root, "Download/notes.txt", "abc")
        val unfinished = file(root, "Download/downloading.part", "abcd")
        val kinds = scanner(root).scan(locations).candidates.associate { it.file.path to it.bucket }
        assertEquals(CleanupBucket.MEDIA, kinds[photo.path])
        assertEquals(CleanupBucket.INSTALLERS, kinds[apk.path])
        assertEquals(CleanupBucket.DOWNLOADS, kinds[document.path])
        assertEquals(CleanupBucket.DOWNLOADS, kinds[unfinished.path])
    }

    @Test fun `exact copies keep their original out of every selectable category`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "Download/photo.jpg", "identical bytes")
        val copy = file(root, "Download/photo (1).jpg", "identical bytes")
        val other = file(root, "Download/keep.tmp", "leave this one alone")
        val scan = scanner(root).scan(locations)
        assertTrue(original.path in scan.protectedOriginalPaths)
        assertFalse(scan.candidates.any { it.file.path == original.path })
        val candidate = scan.candidates.single { it.file.path == copy.path }
        assertEquals(CleanupBucket.DUPLICATES, candidate.bucket)
        assertEquals(original.path, candidate.keptOriginal!!.path)
        val worker = FileCleanupPlanner(root, { true })
        val plan = worker.prepare(scan.selection(setOf(copy.path, original.path)))
        assertEquals(listOf(copy.path), plan.targets.map { it.snapshot.path })
        worker.execute(requireNotNull(CleanupReview.Awaiting(plan).approve(plan.id, true)))
        assertTrue(original.exists()); assertFalse(copy.exists()); assertTrue(other.exists())
    }

    @Test fun `different content of equal length is not a duplicate`() {
        val root = temporary.newFolder("storage")
        file(root, "Download/a.jpg", "abcd")
        file(root, "Download/b.jpg", "wxyz")
        val scan = scanner(root).scan(locations)
        assertEquals(2, scan.candidates.size)
        assertTrue(scan.candidates.none { it.bucket == CleanupBucket.DUPLICATES })
        assertTrue(scan.protectedOriginalPaths.isEmpty())
    }

    @Test fun `duplicate budget is explicit instead of claiming complete comparison`() {
        val root = temporary.newFolder("storage")
        file(root, "Download/a.jpg", "identical")
        file(root, "Download/b.jpg", "identical")
        val scan = StorageCleanupScanner(root, { true }, duplicateReadBudget = 1).scan(locations)
        assertEquals(2, scan.coverage.duplicateFilesNotCompared)
        assertTrue(scan.coverage.isPartial)
        assertTrue(scan.coverage.limits.isNotEmpty())
        assertTrue(scan.candidates.none { it.bucket == CleanupBucket.DUPLICATES })
    }

    @Test fun `denied directory is unavailable not empty or absent`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "Download/a.tmp")
        val scan = StorageCleanupScanner(root, { true }, listDirectory = { path, _ -> throw AccessDeniedException(path.toString()) }).scan(locations)
        assertTrue(scan.candidates.isEmpty())
        assertEquals(ScanLocationStatus.UNAVAILABLE, scan.coverage.locations.single().status)
        assertTrue(scan.coverage.isPartial); assertTrue(target.exists())
    }

    @Test fun `denied child marks its parent partial and keeps readable candidates`() {
        val root = temporary.newFolder("storage")
        val readable = file(root, "Download/readable.tmp")
        file(root, "Download/locked/secret.txt")
        val scan = StorageCleanupScanner(root, { true }, listDirectory = { path, limit ->
            if (path.fileName.toString() == "locked") throw AccessDeniedException(path.toString())
            StorageCleanupScanner.readChildren(path, limit)
        }).scan(locations)
        assertEquals(listOf(readable.path), scan.candidates.map { it.file.path })
        assertEquals(ScanLocationStatus.PARTIAL, scan.coverage.locations.single().status)
    }

    @Test fun `absent locations are reported without inventing scanned files`() {
        val root = temporary.newFolder("storage")
        val scan = scanner(root).scan(locations)
        assertEquals(ScanLocationStatus.ABSENT, scan.coverage.locations.single().status)
        assertEquals(0, scan.coverage.locations.single().inspectedFiles)
        assertTrue(scan.candidates.isEmpty())
    }

    @Test fun `bounded catalog is partial and later roots are not reported as checked`() {
        val root = temporary.newFolder("storage")
        val a = file(root, "Download/a.tmp")
        val b = file(root, "Download/b.tmp")
        file(root, "Pictures/c.jpg")
        val scan = StorageCleanupScanner(root, { true }, maxCandidates = 1).scan(locations + StorageCleanupScanner.Location("Pictures", "Pictures"))
        assertEquals(1, scan.candidates.size)
        assertEquals(ScanLocationStatus.PARTIAL, scan.coverage.locations.first().status)
        assertEquals(ScanLocationStatus.NOT_REACHED, scan.coverage.locations.last().status)
        assertTrue(scan.coverage.limits.isNotEmpty()); assertTrue(a.exists()); assertTrue(b.exists())
    }

    @Test fun `depth and directory limits are visible`() {
        val root = temporary.newFolder("storage")
        file(root, "Download/a/b/c.tmp")
        val scan = StorageCleanupScanner(root, { true }, maxDepth = 0).scan(locations)
        assertEquals(ScanLocationStatus.PARTIAL, scan.coverage.locations.single().status)
        assertTrue(scan.coverage.locations.single().notes.isNotEmpty())
        val truncated = StorageCleanupScanner(root, { true }, maxChildrenPerDirectory = 1).scan(locations)
        // A second sibling forces the directory-stream limit, not a deep traversal.
        file(root, "Download/second.tmp")
        val limited = StorageCleanupScanner(root, { true }, maxChildrenPerDirectory = 1).scan(locations)
        assertTrue(limited.coverage.isPartial)
        assertTrue(truncated.candidates.all { File(it.file.path).exists() })
    }

    @Test fun `credential paths and symlinks are excluded`() {
        val root = temporary.newFolder("storage")
        val vault = file(root, "Download/passwords.kdbx")
        val outside = file(temporary.newFolder("outside"), "valuable.jpg")
        Files.createSymbolicLink(File(root, "Download/link.jpg").toPath(), outside.toPath())
        val scan = scanner(root).scan(locations)
        assertTrue(scan.candidates.isEmpty())
        assertEquals(2, scan.coverage.protectedEntries)
        assertTrue(vault.exists()); assertTrue(outside.exists())
    }

    @Test fun `time limit and cancellation never mutate storage`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "Download/a.tmp")
        var now = 0L
        val timed = StorageCleanupScanner(root, { true }, maxScanMillis = 1, nanoTime = { now.also { now += 2_000_000 } }).scan(locations)
        assertTrue(timed.coverage.isPartial)
        assertThrows(CancellationException::class.java) {
            StorageCleanupScanner(root, { true }, checkCancellation = { throw CancellationException() }).scan(locations)
        }
        assertTrue(target.exists())
    }

    @Test fun `files changed since discovery do not enter preparation`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "Download/a.tmp", "old")
        val scan = scanner(root).scan(locations)
        target.writeText("changed contents")
        val plan = FileCleanupPlanner(root, { true }).prepare(scan.selection(setOf(target.path)))
        assertTrue(plan.targets.isEmpty()); assertEquals(1, plan.exclusions.size)
        assertEquals("changed contents", target.readText())
    }

    @Test fun `selected empty folder never expands to newly added children even with restored timestamp`() {
        val root = temporary.newFolder("storage")
        val empty = File(root, "Download/empty").apply { mkdirs() }
        val scan = scanner(root).scan(locations)
        assertEquals(listOf(empty.path), scan.candidates.map { it.file.path })
        val modified = empty.lastModified()
        val newFile = file(empty, "new.tmp")
        assertTrue(empty.setLastModified(modified))
        val plan = FileCleanupPlanner(root, { true }).prepare(scan.selection(setOf(empty.path)))
        assertTrue(plan.targets.isEmpty()); assertTrue(newFile.exists())
    }
}
