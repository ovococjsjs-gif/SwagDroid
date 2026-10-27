package com.swagdroid.core.privilege

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.PrivilegeTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedProcessRunnerTest {
    @Test fun `captures stdout stderr and real exit code`() = runBlocking {
        val result = BoundedProcessRunner().run("test", PrivilegeTier.BASIC, 2_000) {
            ProcessBuilder("sh", "-c", "printf 'value\\n'; printf 'problem\\n' >&2; exit 7").start()
        }
        assertEquals(CommandCompletion.EXITED, result.completion)
        assertEquals(7, result.exitCode)
        assertEquals(listOf("value"), result.stdout)
        assertEquals(listOf("problem"), result.stderr)
        assertFalse(result.isSuccess)
    }

    @Test fun `output limits still drain a verbose process without deadlocking`() = runBlocking {
        val result = BoundedProcessRunner(maxOutputBytes = 128).run("verbose", PrivilegeTier.BASIC, 3_000) {
            ProcessBuilder("sh", "-c", "i=0; while [ \"\$i\" -lt 5000 ]; do printf '0123456789\\n'; i=\$((i+1)); done").start()
        }
        assertTrue(result.isSuccess)
        assertTrue(result.outputTruncated)
        assertTrue(result.stdout.joinToString("\n").length <= 128)
    }

    @Test fun `timeout is unknown and requests process termination`() = runBlocking {
        val process = AtomicReference<Process>()
        val result = BoundedProcessRunner().run("sleep", PrivilegeTier.ROOT, 100) {
            ProcessBuilder("sh", "-c", "exec sleep 10").start().also { process.set(it) }
        }
        assertEquals(CommandCompletion.UNKNOWN, result.completion)
        assertFalse(result.isSuccess)
        assertTrue(process.get().waitFor(3, TimeUnit.SECONDS))
    }

    @Test fun `local launch failure is not started but lost remote launch is unknown`() = runBlocking {
        val local = BoundedProcessRunner().run("local", PrivilegeTier.BASIC, 500) { throw java.io.IOException("not found") }
        val remote = BoundedProcessRunner().run("remote", PrivilegeTier.ADB_SHIZUKU, 500, CommandCompletion.UNKNOWN) { throw java.io.IOException("binder lost") }
        assertEquals(CommandCompletion.NOT_STARTED, local.completion)
        assertEquals(CommandCompletion.UNKNOWN, remote.completion)
    }

    @Test fun `cancellation propagates and still requests termination`() = runBlocking {
        val started = CompletableDeferred<Process>()
        val job = launch {
            BoundedProcessRunner().run("cancel", PrivilegeTier.BASIC, 10_000) {
                ProcessBuilder("sh", "-c", "exec sleep 10").start().also { started.complete(it) }
            }
        }
        val process = started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
    }

    @Test fun `hanging process wait RPC is bounded by the caller deadline`() = runBlocking {
        val released = CountDownLatch(1)
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor(): Int { released.await(); return 0 }
            override fun exitValue(): Int = error("must not call unbounded remote exitValue")
            override fun destroy() { released.countDown() }
        }
        val started = System.nanoTime()
        val result = BoundedProcessRunner().run("remote wait", PrivilegeTier.ADB_SHIZUKU, 100) { process }
        assertEquals(CommandCompletion.UNKNOWN, result.completion)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    @Test fun `parent exit with held output pipe is not reported as completed work`() = runBlocking {
        val closed = CountDownLatch(1)
        val heldInput = object : InputStream() {
            override fun read(): Int { closed.await(); return -1 }
            override fun close() { closed.countDown() }
        }
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = heldInput
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { closed.countDown() }
        }
        val result = BoundedProcessRunner(drainTimeoutMs = 100).run("detached", PrivilegeTier.BASIC, 500) { process }
        assertEquals(CommandCompletion.UNKNOWN, result.completion)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun `late process creation after startup timeout is stopped when it becomes available`() = runBlocking {
        val allowCreate = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val created = CountDownLatch(1)
        val result = BoundedProcessRunner().run("late binder", PrivilegeTier.ADB_SHIZUKU, 100, CommandCompletion.UNKNOWN) {
            while (true) {
                try { allowCreate.await(); break } catch (_: InterruptedException) { /* simulate an uncancellable Binder RPC */ }
            }
            object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() { destroyed.countDown() }
            }.also { created.countDown() }
        }
        assertEquals(CommandCompletion.UNKNOWN, result.completion)
        allowCreate.countDown()
        assertTrue(created.await(2, TimeUnit.SECONDS))
        assertTrue(destroyed.await(2, TimeUnit.SECONDS))
    }

    @Test fun `known authorization refusal before dispatch is not started even for a remote transport`() = runBlocking {
        val result = BoundedProcessRunner().run("denied", PrivilegeTier.ADB_SHIZUKU, 500, CommandCompletion.UNKNOWN) {
            throw CommandNotStartedException("permission revoked")
        }
        assertEquals(CommandCompletion.NOT_STARTED, result.completion)
    }

    @Test fun `late authorization reply cannot dispatch the user command after timeout`() = runBlocking {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var dispatched = false
        val result = BoundedProcessRunner().run("late preflight", PrivilegeTier.ADB_SHIZUKU, 100, CommandCompletion.UNKNOWN) { ensureMayStart ->
            try {
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { }
                }
                ensureMayStart()
                dispatched = true
                error("must not reach dispatch")
            } finally { finished.countDown() }
        }
        assertEquals(CommandCompletion.UNKNOWN, result.completion)
        release.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(dispatched)
    }

}
