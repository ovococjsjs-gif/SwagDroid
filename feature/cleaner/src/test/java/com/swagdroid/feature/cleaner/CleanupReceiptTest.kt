package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.data.CleanupReceiptCodec
import com.swagdroid.feature.cleaner.data.CleanupReceiptStore
import com.swagdroid.feature.cleaner.engine.RecordedCleanupExecutor
import com.swagdroid.feature.cleaner.model.CleanupExecutionResult
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.PlannedCleanupTarget
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CleanupReceiptTest {
    private class Store : CleanupReceiptStore {
        var last: CleanupReceipt? = null
        var failBeginning = false
        var failCompletion = false
        override suspend fun readLast() = last
        override suspend fun write(receipt: CleanupReceipt) {
            if ((receipt.status == CleanupReceipt.Status.RUNNING && failBeginning) ||
                (receipt.status == CleanupReceipt.Status.COMPLETE && failCompletion)
            ) throw IOException("unavailable")
            last = receipt
        }
    }
    private fun approval(id: String = "plan") = PreparedCleanupPlan(
        id, "/storage", listOf(PlannedCleanupTarget(CleanupFileSnapshot("/storage/a.tmp", 4, 1, "sha"), emptySet())), emptyList(),
    ).let { requireNotNull(CleanupReview.Awaiting(it).approve(id, true)) }
    private fun result() = CleanupExecutionResult(1, 0, 4, emptyList(), emptyList())

    @Test fun `beginning is acknowledged before deletion and complete counts are persisted`() = runBlocking {
        val store = Store()
        val outcome = RecordedCleanupExecutor(store).run(approval()) {
            assertEquals(CleanupReceipt.Status.RUNNING, store.last!!.status)
            result()
        } as CleanupRunOutcome.Completed
        assertTrue(outcome.receiptSaved)
        assertEquals(CleanupReceipt.Status.COMPLETE, store.last!!.status)
        assertEquals(1, store.last!!.deletedFiles)
        assertEquals(4L, store.last!!.deletedBytes)
    }

    @Test fun `failed beginning never invokes deletion`() = runBlocking {
        val store = Store().apply { failBeginning = true }
        var called = false
        val outcome = RecordedCleanupExecutor(store).run(approval()) { called = true; result() }
        assertTrue(outcome is CleanupRunOutcome.NotStarted)
        assertFalse(called)
        assertNull(store.last)
    }

    @Test fun `failed final save keeps pending marker and does not pretend persistence`() = runBlocking {
        val store = Store().apply { failCompletion = true }
        val outcome = RecordedCleanupExecutor(store).run(approval()) { result() } as CleanupRunOutcome.Completed
        assertFalse(outcome.receiptSaved)
        assertEquals(1, outcome.result.deletedFiles)
        assertEquals(CleanupReceipt.Status.RUNNING, store.last!!.status)
    }

    @Test fun `execution failure has unknown counts not fake zero success`() = runBlocking {
        val store = Store()
        val outcome = RecordedCleanupExecutor(store).run(approval()) { throw IOException("failed after a possible unlink") }
        assertTrue(outcome is CleanupRunOutcome.Interrupted)
        assertEquals(CleanupReceipt.Status.INTERRUPTED, store.last!!.status)
        assertNull(store.last!!.deletedFiles)
        assertNull(store.last!!.deletedBytes)
    }

    @Test fun `cancellation propagates and records interruption`() = runBlocking {
        val store = Store()
        try {
            RecordedCleanupExecutor(store).run(approval()) { throw CancellationException("cancel") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(CleanupReceipt.Status.INTERRUPTED, store.last!!.status)
        assertNull(store.last!!.deletedFiles)
    }

    @Test fun `replayed approval cannot overwrite a completed receipt or delete again`() = runBlocking {
        val store = Store(); val worker = RecordedCleanupExecutor(store); val token = approval()
        worker.run(token) { result() }
        val saved = store.last
        val second = worker.run(token) { throw AssertionError("must not execute twice") }
        assertTrue(second is CleanupRunOutcome.NotStarted)
        assertEquals(saved, store.last)
    }

    @Test fun `receipt and deletion are serialized together across callers`() = runBlocking {
        val store = Store(); val worker = RecordedCleanupExecutor(store)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async {
            worker.run(approval("one")) { entered.complete(Unit); release.await(); result() }
        }
        entered.await()
        val second = async { worker.run(approval("two")) { assertEquals("two", store.last!!.planId); result() } }
        assertEquals("one", store.last!!.planId)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals("two", store.last!!.planId)
        assertEquals(CleanupReceipt.Status.COMPLETE, store.last!!.status)
    }

    @Test fun `receipt codec distinguishes new interrupted complete and corrupt records`() {
        assertNull(CleanupReceiptCodec.decode(emptyMap()))
        val running = CleanupReceipt("id", 123, 1, CleanupReceipt.Status.RUNNING)
        assertEquals(running, CleanupReceiptCodec.decode(CleanupReceiptCodec.encode(running)))
        val interrupted = running.copy(status = CleanupReceipt.Status.INTERRUPTED)
        assertEquals(interrupted, CleanupReceiptCodec.decode(CleanupReceiptCodec.encode(interrupted)))
        val complete = running.copy(status = CleanupReceipt.Status.COMPLETE, completedAtMs = 124, deletedFiles = 1, deletedDirectories = 0, deletedBytes = 4, skipped = 0, failed = 0)
        assertEquals(complete, CleanupReceiptCodec.decode(CleanupReceiptCodec.encode(complete)))
        assertThrows(IllegalArgumentException::class.java) { CleanupReceiptCodec.decode(mapOf("version" to "999")) }
        assertThrows(IllegalArgumentException::class.java) { CleanupReceiptCodec.decode(CleanupReceiptCodec.encode(running.copy(status = CleanupReceipt.Status.COMPLETE))) }
        assertThrows(IllegalArgumentException::class.java) { CleanupReceiptCodec.decode(CleanupReceiptCodec.encode(complete.copy(deletedFiles = 2))) }
    }
}
