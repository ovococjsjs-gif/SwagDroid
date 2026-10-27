package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.model.CleanerSubTab
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CleanupSelectionGroup
import com.swagdroid.feature.cleaner.model.CleanupSource
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard
import com.swagdroid.feature.cleaner.safety.FileCleanupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class FileCleanupPlannerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cache = CleanupSource(CleanerSubTab.SYSTEM_CACHE, "Cache")
    private val media = CleanupSource(CleanerSubTab.MESSENGERS, "Media")
    private val duplicates = CleanupSource(CleanerSubTab.DUPLICATES, "Duplicates")

    private fun planner(root: File) = FileCleanupPlanner(root, { AuthAndCredentialsGuard.isPathSafeToClean(it) })
    private fun file(root: File, name: String, content: String = "content"): File =
        File(root, name).apply { parentFile.mkdirs(); writeText(content) }
    private fun selection(vararg groups: CleanupSelectionGroup) = CleanupSelection(groups.toList(), emptySet())
    private fun group(source: CleanupSource, vararg paths: File) = CleanupSelectionGroup(source, paths.map { it.path })
    private fun approve(plan: PreparedCleanupPlan): CleanupReview.Approved =
        requireNotNull(CleanupReview.Awaiting(plan).approve(plan.id, true))

    @Test
    fun `preparing a plan does not mutate any file`() {
        val root = temporary.newFolder("storage")
        val a = file(root, "cache/a.tmp")
        val b = file(root, "cache/nested/b.tmp")
        val plan = planner(root).prepare(selection(group(cache, a.parentFile)))
        assertEquals(setOf(a.path, b.path), plan.targets.map { it.snapshot.path }.toSet())
        assertEquals("content", a.readText())
        assertEquals("content", b.readText())
    }

    @Test
    fun `overlapping tabs retain both sources and count each file once`() {
        val root = temporary.newFolder("storage")
        val a = file(root, "cache/a.tmp", "1234")
        val plan = planner(root).prepare(selection(group(cache, a.parentFile), group(media, a)))
        assertEquals(1, plan.fileCount)
        assertEquals(4L, plan.fileBytes)
        assertEquals(setOf(cache, media), plan.sources)
        assertEquals(setOf(cache, media), plan.targets.single().sources)
        assertEquals(1, planner(root).execute(approve(plan)).deletedFiles)
    }

    @Test
    fun `new files after review are never added to execution`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "cache/a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, original.parentFile)))
        val newFile = file(root, "cache/new.tmp")
        val result = worker.execute(approve(plan))
        assertEquals(1, result.deletedFiles)
        assertFalse(original.exists())
        assertTrue(newFile.exists())
        assertTrue(original.parentFile.exists()) // Never a recursive deletion of the container.
    }

    @Test
    fun `same sized edit with restored timestamp is detected by hash`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp", "before")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, target)))
        val oldTime = target.lastModified()
        target.writeText("edited")
        assertTrue(target.setLastModified(oldTime))
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertEquals("edited", target.readText())
    }

    @Test
    fun `a removed file is skipped instead of reported as deleted`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, target)))
        assertTrue(target.delete())
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(0L, result.deletedFileBytes)
        assertEquals(1, result.skipped.size)
    }

    @Test
    fun `zero length files and empty directories can be explicitly approved`() {
        val root = temporary.newFolder("storage")
        val zero = file(root, "zero.tmp", "")
        val empty = File(root, "empty").apply { mkdir() }
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, zero, empty)))
        assertEquals(0L, plan.fileBytes)
        assertEquals(1, plan.fileCount)
        assertEquals(1, plan.directoryCount)
        val result = worker.execute(approve(plan))
        assertEquals(1, result.deletedFiles)
        assertEquals(1, result.deletedEmptyDirectories)
        assertFalse(zero.exists())
        assertFalse(empty.exists())
    }

    @Test
    fun `a reviewed empty directory that gains a file is retained`() {
        val root = temporary.newFolder("storage")
        val empty = File(root, "empty").apply { mkdir() }
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, empty)))
        val newFile = file(empty, "new.txt")
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedEmptyDirectories)
        assertEquals(1, result.skipped.size)
        assertTrue(newFile.exists())
    }

    @Test
    fun `protected vault files are excluded even inside a selected directory`() {
        val root = temporary.newFolder("storage")
        val vault = file(root, "Download/safe.kdbx")
        val disposable = file(root, "Download/a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, vault.parentFile)))
        assertEquals(listOf(disposable.path), plan.targets.map { it.snapshot.path })
        assertTrue(plan.exclusions.any { it.path == vault.path })
        worker.execute(approve(plan))
        assertTrue(vault.exists())
    }

    @Test
    fun `root sibling prefix relative path and shell markers are rejected`() {
        val root = temporary.newFolder("storage")
        val sibling = temporary.newFolder("storage-other")
        val outside = file(sibling, "a.tmp")
        val input = CleanupSelectionGroup(cache, listOf(root.path, outside.path, "../a.tmp", "::PRIVILEGED_TRIM_CACHES::", "::STAGING_SESSION::123"))
        val plan = planner(root).prepare(selection(input))
        assertTrue(plan.targets.isEmpty())
        assertEquals(5, plan.exclusions.size)
        assertTrue(outside.exists())
        assertNull(CleanupReview.Awaiting(plan).approve(plan.id, true))
    }

    @Test
    fun `file and directory symlinks are not traversed even within the storage root`() {
        val root = temporary.newFolder("storage")
        val real = file(root, "real/a.tmp")
        val fileLink = File(root, "a-link.tmp").toPath()
        val dirLink = File(root, "dir-link").toPath()
        Files.createSymbolicLink(fileLink, real.toPath())
        Files.createSymbolicLink(dirLink, real.parentFile.toPath())
        val plan = planner(root).prepare(selection(group(cache, fileLink.toFile(), dirLink.toFile())))
        assertTrue(plan.targets.isEmpty())
        assertEquals(2, plan.exclusions.size)
        assertTrue(real.exists())
    }

    @Test
    fun `a parent directory replaced by a symlink after review is skipped`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "cache/a.tmp")
        val elsewhere = temporary.newFolder("elsewhere")
        val valuable = file(elsewhere, "a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, target)))
        assertTrue(target.delete())
        assertTrue(target.parentFile.delete())
        Files.createSymbolicLink(target.parentFile.toPath(), elsewhere.toPath())
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertTrue(valuable.exists())
    }

    @Test
    fun `originals are protected even when their directory is selected in another tab`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "Photos/original.jpg")
        val copy = file(root, "Photos/copy.jpg")
        val input = selection(
            group(media, original.parentFile),
            CleanupSelectionGroup(duplicates, listOf(copy.path), original.path, exactDuplicates = true),
        )
        val worker = planner(root)
        val plan = worker.prepare(input)
        assertEquals(listOf(copy.path), plan.targets.map { it.snapshot.path })
        assertEquals(setOf(media, duplicates), plan.targets.single().sources)
        worker.execute(approve(plan))
        assertTrue(original.exists())
        assertFalse(copy.exists())
    }

    @Test
    fun `missing original prevents deleting its copy through another tab`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "original.jpg")
        val copy = file(root, "copy.jpg")
        val worker = planner(root)
        val plan = worker.prepare(selection(
            group(media, copy),
            CleanupSelectionGroup(duplicates, listOf(copy.path), original.path, true),
        ))
        assertTrue(original.delete())
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertTrue(copy.exists())
    }

    @Test
    fun `changed original prevents deleting the copy`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "original.jpg")
        val copy = file(root, "copy.jpg")
        val worker = planner(root)
        val plan = worker.prepare(selection(CleanupSelectionGroup(duplicates, listOf(copy.path), original.path, true)))
        original.writeText("different content")
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertTrue(copy.exists())
    }

    @Test
    fun `stale exact duplicate is excluded across every source before confirmation`() {
        val root = temporary.newFolder("storage")
        val original = file(root, "original.jpg", "original")
        val copy = file(root, "copy.jpg", "changed")
        val plan = planner(root).prepare(selection(
            group(media, copy),
            CleanupSelectionGroup(duplicates, listOf(copy.path), original.path, true),
        ))
        assertTrue(plan.targets.isEmpty())
        assertEquals(1, plan.exclusions.size)
        assertTrue(copy.exists())
    }

    @Test
    fun `a newly protected path is skipped at execution`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        var allow = true
        val worker = FileCleanupPlanner(root, { allow })
        val plan = worker.prepare(selection(group(cache, target)))
        allow = false
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertTrue(target.exists())
    }

    @Test
    fun `a different storage root cannot execute a prepared plan`() {
        val root = temporary.newFolder("storage")
        val other = temporary.newFolder("other")
        val target = file(root, "a.tmp")
        val plan = planner(root).prepare(selection(group(cache, target)))
        assertThrows(IllegalArgumentException::class.java) { planner(other).execute(approve(plan)) }
        assertTrue(target.exists())
    }

    @Test
    fun `oversized and overdeep plans fail closed without deleting anything`() {
        val root = temporary.newFolder("storage")
        val a = file(root, "cache/a.tmp")
        val b = file(root, "cache/nested/b.tmp")
        val input = selection(group(cache, a.parentFile))
        assertThrows(IllegalArgumentException::class.java) {
            FileCleanupPlanner(root, { true }, maxVisitedEntries = 1).prepare(input)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileCleanupPlanner(root, { true }, maxDepth = 0).prepare(input)
        }
        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test
    fun `preparation cancellation is propagated and never mutates files`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        val worker = FileCleanupPlanner(root, { true }, checkCancellation = { throw CancellationException("cancel") })
        assertThrows(CancellationException::class.java) { worker.prepare(selection(group(cache, target))) }
        assertTrue(target.exists())
    }

    @Test
    fun `confirmation requires current plan id nonempty targets and explicit irreversible consent`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        val plan = planner(root).prepare(selection(group(cache, target)))
        val review = CleanupReview.Awaiting(plan)
        assertNull(review.approve("stale-dialog", true))
        assertNull(review.approve(plan.id, false))
        assertNull(CleanupReview.None.approve(plan.id, true))
        val approved = review.approve(plan.id, true)
        assertNotNull(approved)
        assertNull(approved!!.approve(plan.id, true)) // An approved state is not another pending dialog.
        assertTrue(target.exists()) // Approving alone is also not execution.
    }

    @Test
    fun `one approval cannot be executed twice`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, target)))
        val approval = approve(plan)
        assertEquals(1, worker.execute(approval).deletedFiles)
        target.writeText("newly created file")
        assertThrows(IllegalStateException::class.java) { worker.execute(approval) }
        assertTrue(target.exists())
    }

    @Test
    fun `failed deletion is reported separately while later planned files can complete`() {
        val root = temporary.newFolder("storage")
        val failing = file(root, "a.tmp", "1234")
        val succeeding = file(root, "b.tmp", "5678")
        val worker = FileCleanupPlanner(root, { true }, deleteFile = { path ->
            if (path == failing.toPath()) throw IOException("simulated permission loss")
            Files.delete(path)
        })
        val plan = worker.prepare(selection(group(cache, failing, succeeding)))
        val result = worker.execute(approve(plan))
        assertEquals(1, result.deletedFiles)
        assertEquals(4L, result.deletedFileBytes)
        assertEquals(1, result.failed.size)
        assertEquals(failing.path, result.failed.single().path)
        assertTrue(result.skipped.isEmpty())
        assertTrue(failing.exists())
        assertFalse(succeeding.exists())
    }

    @Test
    fun `trusted storage root may itself be an alias but links below it remain forbidden`() {
        val realRoot = temporary.newFolder("real-storage")
        val rootAlias = File(temporary.root, "storage-alias")
        Files.createSymbolicLink(rootAlias.toPath(), realRoot.toPath())
        val target = file(rootAlias, "a.tmp")
        val worker = planner(rootAlias)
        val plan = worker.prepare(selection(group(cache, target)))
        assertEquals(realRoot.canonicalPath, plan.storageRoot)
        assertEquals(1, worker.execute(approve(plan)).deletedFiles)
        assertFalse(target.exists())
    }

    @Test
    fun `a replacement file with identical content and time is skipped when file keys are available`() {
        val root = temporary.newFolder("storage")
        val target = file(root, "a.tmp")
        val worker = planner(root)
        val plan = worker.prepare(selection(group(cache, target)))
        assertNotNull(plan.targets.single().snapshot.fileKey)
        val time = target.lastModified()
        assertTrue(target.renameTo(File(root, "moved.tmp")))
        target.writeText("content")
        assertTrue(target.setLastModified(time))
        val result = worker.execute(approve(plan))
        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.skipped.size)
        assertTrue(target.exists())
    }

}
