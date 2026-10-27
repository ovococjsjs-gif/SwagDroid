package com.swagdroid.core.privilege

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.ShellExecResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Thrown only by a factory that knows the user command has not been dispatched. */
class CommandNotStartedException(message: String) : IllegalStateException(message)

/** One owned process per command. Never waits indefinitely on startup, exit or output readers. */
class BoundedProcessRunner(
    private val maxOutputBytes: Int = 256 * 1024,
    private val drainTimeoutMs: Long = 1_000,
) {
    suspend fun run(
        command: String,
        tier: PrivilegeTier,
        timeoutMs: Long,
        startFailureCompletion: CommandCompletion = CommandCompletion.NOT_STARTED,
        start: (ensureMayStart: () -> Unit) -> Process,
    ): ShellExecResult = withContext(Dispatchers.IO) {
        runOwned(command, tier, timeoutMs, startFailureCompletion, start)
    }

    private suspend fun runOwned(
        command: String,
        tier: PrivilegeTier,
        timeoutMs: Long,
        startFailureCompletion: CommandCompletion,
        start: (ensureMayStart: () -> Unit) -> Process,
    ): ShellExecResult {
        require(timeoutMs > 0 && maxOutputBytes > 0 && drainTimeoutMs > 0)
        val context = currentCoroutineContext()
        context.ensureActive()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val workers = Executors.newFixedThreadPool(3) { task -> Thread(task, "SwagDroid-command-io").apply { isDaemon = true } }
        val process = AtomicReference<Process?>()
        val abandoned = AtomicBoolean(false)
        val out = BoundedCapture(maxOutputBytes)
        val err = BoundedCapture(maxOutputBytes / 4)
        var commandExited = false
        var completed = false
        var starting = true

        fun result(code: Int, completion: CommandCompletion, error: String? = null): ShellExecResult {
            val stdout = out.snapshot()
            val stderr = err.snapshot()
            return ShellExecResult(
                command, code, stdout.first, stderr.first + listOfNotNull(error), tier,
                completion, stdout.second || stderr.second,
            )
        }

        suspend fun <T> await(future: Future<T>, until: Long): T {
            while (true) {
                context.ensureActive()
                val remaining = until - System.nanoTime()
                if (remaining <= 0) throw TimeoutException()
                try {
                    return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    if (System.nanoTime() >= until) throw TimeoutException()
                }
            }
        }

        try {
            val proc = await(workers.submit<Process> {
                val ensureMayStart = {
                    if (abandoned.get() || Thread.currentThread().isInterrupted) throw CommandNotStartedException("Запуск отменён до отправки команды")
                }
                ensureMayStart()
                val created = start(ensureMayStart)
                process.set(created)
                if (abandoned.get()) stopEventually(created)
                created
            }, deadline)
            starting = false
            val stdout = workers.submit { out.drain(proc.inputStream) }
            val stderr = workers.submit { err.drain(proc.errorStream) }
            // Remote Process implementations may block even in waitFor(timeout)/exitValue.
            // Await that RPC on a worker instead of trusting it to enforce our deadline.
            val exit = await(workers.submit<Int> { proc.waitFor() }, deadline)
            commandExited = true
            // A detached child may keep the descriptors open after its parent exits.
            val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs)
            await(stdout, drainDeadline)
            await(stderr, drainDeadline)
            completed = true
            return result(exit, CommandCompletion.EXITED)
        } catch (cancelled: CancellationException) {
            throw cancelled // The owner keeps the durable fence closed; cancellation is not a success.
        } catch (_: TimeoutException) {
            return result(124, CommandCompletion.UNKNOWN, "Таймаут. Остановка процесса запрошена, но завершение всех действий не подтверждено.")
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            val completion = when {
                starting && cause is CommandNotStartedException -> CommandCompletion.NOT_STARTED
                starting -> startFailureCompletion
                commandExited -> CommandCompletion.EXITED
                else -> CommandCompletion.UNKNOWN
            }
            return result(-1, completion, cause.message ?: "Ошибка транспорта команды")
        } finally {
            abandoned.set(true)
            // No unbounded close/wait on the caller thread (remote Process may be a dead Binder).
            process.get()?.let { stopEventually(it, terminate = !completed) }
            workers.shutdownNow()
            if (!completed) out.markIncomplete()
        }
    }

    private fun stopEventually(process: Process, terminate: Boolean = true) {
        Thread({
            if (terminate) runCatching { process.destroyForcibly() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }, "SwagDroid-command-stop").apply { isDaemon = true; start() }
    }

    private class BoundedCapture(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        private var truncated = false

        fun drain(input: InputStream) {
            try {
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return
                    synchronized(this) {
                        val take = minOf(read, (limit - bytes.size()).coerceAtLeast(0))
                        bytes.write(buffer, 0, take)
                        if (take < read) truncated = true
                    }
                    // Continue draining beyond the limit; do not deadlock a verbose child on its pipe.
                }
            } catch (_: Exception) {
                markIncomplete()
            }
        }

        @Synchronized fun markIncomplete() { truncated = true }
        @Synchronized fun snapshot(): Pair<List<String>, Boolean> {
            val text = bytes.toString(Charsets.UTF_8.name())
            val lines = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')
            return lines to truncated
        }
    }
}
