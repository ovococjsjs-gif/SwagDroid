package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.data.CleanupReceiptStore
import com.swagdroid.feature.cleaner.engine.CleanerOperations
import com.swagdroid.feature.cleaner.engine.CleanupFilePreview
import com.swagdroid.feature.cleaner.model.CleanupBucket
import com.swagdroid.feature.cleaner.model.CleanupCandidate
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.CleanupScanProgress
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CleanupStep
import com.swagdroid.feature.cleaner.model.PlannedCleanupTarget
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import com.swagdroid.feature.cleaner.model.ScanCoverage
import com.swagdroid.feature.cleaner.model.StorageAccess
import com.swagdroid.feature.cleaner.model.StorageCleanupScan
import com.swagdroid.feature.cleaner.model.StorageSpace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CleanerFlowViewModelTest {
    private class Store(var value: CleanupReceipt? = null) : CleanupReceiptStore {
        override suspend fun readLast() = value
        override suspend fun write(receipt: CleanupReceipt) { value = receipt }
    }
    private class Engine : CleanerOperations {
        var access = StorageAccess.ALL_FILES
        var scanCalls = 0
        var executeCalls = 0
        var lastSelection: CleanupSelection? = null
        var failScan = false
        var waitScan = false
        var execution = CompletableDeferred<CleanupRunOutcome>()
        private val candidates = listOf(
            CleanupCandidate(CleanupFileSnapshot("/storage/a.tmp", 4, 1, null), CleanupBucket.TEMPORARY, "Downloads"),
            CleanupCandidate(CleanupFileSnapshot("/storage/photo.jpg", 8, 1, null), CleanupBucket.MEDIA, "Pictures"),
        )
        override fun storageAccess() = access
        override fun storageSpace() = StorageSpace(100, 1000)
        override suspend fun scan(onProgress: (CleanupScanProgress) -> Unit): StorageCleanupScan {
            scanCalls++
            if (waitScan) awaitCancellation()
            if (failScan) throw IOException("denied")
            return StorageCleanupScan("scan", 1, "/storage", candidates, emptySet(), ScanCoverage(emptyList(), 2, 0, 0, 0, emptyList()))
        }
        override suspend fun prepare(selection: CleanupSelection): PreparedCleanupPlan {
            lastSelection = selection
            return PreparedCleanupPlan(UUID.randomUUID().toString(), "/storage", selection.groups.map {
                PlannedCleanupTarget(selection.expectedFiles.getValue(it.paths.single()).copy(sha256 = "sha"), setOf(it.source))
            }, emptyList())
        }
        override suspend fun execute(approval: CleanupReview.Approved): CleanupRunOutcome { executeCalls++; return execution.await() }
        override suspend fun preview(file: CleanupFileSnapshot) = CleanupFilePreview("content://preview/file", "image/jpeg")
    }
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun `scan requires access and returning from settings does not auto scan`() = runTest {
        val engine = Engine().apply { access = StorageAccess.NEEDS_ALL_FILES }
        val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle(); assertEquals(0, engine.scanCalls)
        engine.access = StorageAccess.ALL_FILES; vm.refreshAccess(); advanceUntilIdle()
        assertEquals(0, engine.scanCalls)
        vm.runScan(); advanceUntilIdle(); assertEquals(1, engine.scanCalls)
    }

    @Test fun `nothing is selected automatically and foreign paths are ignored`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle()
        assertTrue(vm.uiState.value.selectedPaths.isEmpty())
        vm.toggleFile("/not-in-catalog")
        vm.requestCleanupReview(); advanceUntilIdle()
        assertTrue(vm.uiState.value.cleanupReview is CleanupReview.None)
        assertEquals(0, engine.executeCalls)
    }

    @Test fun `filters never silently add remove or hide the global selection count`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle()
        vm.toggleFile("/storage/a.tmp")
        vm.setBucket(CleanupBucket.MEDIA); vm.setQuery("photo")
        assertEquals(1, vm.uiState.value.visibleCandidates.size)
        assertEquals(setOf("/storage/a.tmp"), vm.uiState.value.selectedPaths)
        assertEquals(4L, vm.uiState.value.selectedBytes)
        vm.requestCleanupReview(); advanceUntilIdle()
        assertEquals(listOf("/storage/a.tmp"), engine.lastSelection!!.groups.flatMap { it.paths })
    }

    @Test fun `bulk convenience does not select personal media`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle()
        vm.setBucket(CleanupBucket.MEDIA); vm.selectFilteredFiles()
        assertTrue(vm.uiState.value.selectedPaths.isEmpty())
        vm.setBucket(CleanupBucket.TEMPORARY); vm.selectFilteredFiles()
        assertEquals(setOf("/storage/a.tmp"), vm.uiState.value.selectedPaths)
    }

    @Test fun `approval requires current plan explicit consent and is consumed before launch`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle(); vm.toggleFile("/storage/a.tmp")
        vm.requestCleanupReview(); advanceUntilIdle()
        val id = (vm.uiState.value.cleanupReview as CleanupReview.Awaiting).plan.id
        vm.confirmCleanup("stale", true); vm.confirmCleanup(id, false); advanceUntilIdle()
        assertEquals(0, engine.executeCalls)
        vm.confirmCleanup(id, true); vm.confirmCleanup(id, true); advanceUntilIdle()
        assertEquals(1, engine.executeCalls)
        assertTrue(vm.uiState.value.isCleaning)
        engine.execution.complete(CleanupRunOutcome.NotStarted("fixture")); advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test fun `editing selection invalidates the previously reviewed plan`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle(); vm.toggleFile("/storage/a.tmp")
        vm.requestCleanupReview(); advanceUntilIdle()
        val old = (vm.uiState.value.cleanupReview as CleanupReview.Awaiting).plan.id
        vm.editSelection(); vm.toggleFile("/storage/photo.jpg"); vm.confirmCleanup(old, true); advanceUntilIdle()
        assertEquals(CleanupStep.CHOOSE, vm.uiState.value.step)
        assertTrue(vm.uiState.value.cleanupReview is CleanupReview.None)
        assertEquals(0, engine.executeCalls)
    }

    @Test fun `permission revocation clears stale results selection and confirmation`() = runTest {
        val engine = Engine(); val vm = CleanerViewModel(engine, Store()); advanceUntilIdle()
        vm.runScan(); advanceUntilIdle(); vm.toggleFile("/storage/a.tmp")
        vm.requestCleanupReview(); advanceUntilIdle()
        val id = (vm.uiState.value.cleanupReview as CleanupReview.Awaiting).plan.id
        engine.access = StorageAccess.NEEDS_ALL_FILES; vm.refreshAccess(); vm.confirmCleanup(id, true); advanceUntilIdle()
        assertEquals(CleanupStep.ACCESS, vm.uiState.value.step)
        assertTrue(vm.uiState.value.selectedPaths.isEmpty())
        assertTrue(vm.uiState.value.scan == null)
        assertEquals(0, engine.executeCalls)
    }

    @Test fun `unfinished receipt never resumes deletion or claims zero removed`() = runTest {
        val engine = Engine()
        val vm = CleanerViewModel(engine, Store(CleanupReceipt("interrupted", 123, 2, CleanupReceipt.Status.RUNNING)))
        advanceUntilIdle()
        assertEquals(CleanupStep.RESULT, vm.uiState.value.step)
        assertTrue(vm.uiState.value.lastReceipt!!.deletedFiles == null)
        assertEquals(0, engine.executeCalls)
        vm.finish()
        assertEquals(CleanupStep.ACCESS, vm.uiState.value.step)
    }

    @Test fun `failed scan is not an empty successful result and busy state recovers`() = runTest {
        val engine = Engine().apply { failScan = true }
        val vm = CleanerViewModel(engine, Store()); advanceUntilIdle(); vm.runScan(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
        assertTrue(vm.uiState.value.scan == null)
        assertTrue(vm.uiState.value.bannerMessage!!.contains("не завершён"))
    }

    @Test fun `scan cancellation resets busy flags without enabling a stale plan`() = runTest {
        val engine = Engine().apply { waitScan = true }
        val vm = CleanerViewModel(engine, Store()); advanceUntilIdle(); vm.runScan(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isScanning)
        vm.cancelScan(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
        assertTrue(vm.uiState.value.scan == null)
        assertEquals(0, engine.executeCalls)
    }
}
