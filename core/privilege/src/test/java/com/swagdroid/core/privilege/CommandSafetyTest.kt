package com.swagdroid.core.privilege

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.StateReadException
import com.swagdroid.core.model.StateReadResult
import com.swagdroid.core.model.isDifferentBoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSafetyTest {
    private class Store : CommandFenceStore {
        var ticket: CommandTicket? = null
        var failRead = false
        var failWrite = false
        override fun read(): CommandTicket? { check(!failRead) { "read error" }; return ticket }
        override fun write(ticket: CommandTicket?) { check(!failWrite) { "write error" }; this.ticket = ticket }
    }
    private fun result(text: String, code: Int = 0, completion: CommandCompletion = CommandCompletion.EXITED, truncated: Boolean = false) =
        ShellExecResult("get", code, text.lines(), emptyList(), PrivilegeTier.ADB_SHIZUKU, completion, truncated)

    @Test fun `failed reads do not become default or missing values`() {
        val denied: StateReadResult<String> = StateReadResult.Unavailable("denied")
        assertThrows(StateReadException::class.java) { denied.valueOrNullIfMissing() }
        assertThrows(StateReadException::class.java) { denied.requireValue() }
        assertNull(StateReadResult.Missing.valueOrNullIfMissing())
        assertEquals("null", StateReadResult.Value("null").valueOrNullIfMissing())
        assertEquals("", StateReadResult.Value("").requireValue())
    }

    @Test fun `zero exit with unknown completion is not successful`() {
        assertFalse(result("", completion = CommandCompletion.UNKNOWN).isSuccess)
        assertFalse(result("", completion = CommandCompletion.NOT_STARTED).isSuccess)
        assertTrue(result("").isSuccess)
    }

    @Test fun `command ticket is persisted before work and known exit clears it`() {
        val store = Store(); val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        val ticket = fence.begin()
        assertEquals(ticket, store.ticket)
        assertNotNull(fence.blockingReason())
        fence.finish(ticket, CommandCompletion.EXITED)
        assertNull(store.ticket); assertNull(fence.blockingReason())
    }

    @Test fun `known failed launch can clear the fence without claiming success`() {
        val store = Store(); val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        fence.finish(fence.begin(), CommandCompletion.NOT_STARTED)
        assertNull(store.ticket)
    }

    @Test fun `timeout fence survives a new executor in the same boot`() {
        val store = Store(); val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        val ticket = fence.begin(); fence.finish(ticket, CommandCompletion.UNKNOWN)
        val restarted = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        assertNotNull(restarted.blockingReason())
        assertThrows(IllegalStateException::class.java) { restarted.begin() }
        assertEquals(ticket, store.ticket)
    }

    @Test fun `unfinished command after abrupt process death blocks new work`() {
        val store = Store(); CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }.begin()
        assertNotNull(CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }.blockingReason())
    }

    @Test fun `only a comparable changed boot marker releases an unfinished command`() {
        val store = Store(); CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }.begin()
        assertNotNull(CommandExecutionFence(store) { "count:2" }.blockingReason())
        assertNotNull(CommandExecutionFence(store) { null }.blockingReason())
        assertNull(CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000002" }.blockingReason())
        assertNull(store.ticket)
    }

    @Test fun `missing boot identity prevents starting rather than inventing a marker`() {
        val store = Store(); val fence = CommandExecutionFence(store) { null }
        assertNotNull(fence.blockingReason())
        assertThrows(IllegalStateException::class.java) { fence.begin() }
        assertNull(store.ticket)
    }

    @Test fun `failed fence read is not treated as an idle state`() {
        val store = Store().apply { failRead = true }
        val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        assertNotNull(fence.blockingReason())
        assertThrows(IllegalStateException::class.java) { fence.begin() }
    }

    @Test fun `failed durable fence write prevents starting`() {
        val store = Store().apply { failWrite = true }
        val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        assertThrows(IllegalStateException::class.java) { fence.begin() }
        assertNull(store.ticket)
    }

    @Test fun `failed fence completion preserves blocking state`() {
        val store = Store(); val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        val ticket = fence.begin(); store.failWrite = true
        assertThrows(IllegalStateException::class.java) { fence.finish(ticket, CommandCompletion.EXITED) }
        store.failWrite = false
        assertNotNull(fence.blockingReason()); assertEquals(ticket, store.ticket)
    }

    @Test fun `another ticket cannot be cleared by a stale callback`() {
        val store = Store(); val fence = CommandExecutionFence(store) { "kernel:00000000-0000-0000-0000-000000000001" }
        val old = fence.begin(); val newer = CommandTicket("new", "kernel:00000000-0000-0000-0000-000000000001"); store.ticket = newer
        assertThrows(IllegalStateException::class.java) { fence.finish(old, CommandCompletion.EXITED) }
        assertEquals(newer, store.ticket)
    }

    @Test fun `appop mode is read only from an exact unambiguous response`() {
        assertEquals(StateReadResult.Value("ignore"), SystemStateParsers.appOp(result("READ_CLIPBOARD: ignore; time=+2s"), "READ_CLIPBOARD"))
        assertEquals(StateReadResult.Value("default"), SystemStateParsers.appOp(result("No operations."), "READ_CLIPBOARD"))
    }

    @Test fun `appop errors empty truncated unknown and unrelated output are not default`() {
        for (response in listOf(result("", 1), result(""), result("different: allow"), result("No operations.", truncated = true), result("No operations.", completion = CommandCompletion.UNKNOWN))) {
            assertTrue(SystemStateParsers.appOp(response, "READ_CLIPBOARD") is StateReadResult.Unavailable)
        }
    }

    @Test fun `uid appop and duplicate mode records cannot masquerade as package mode`() {
        for (text in listOf("Uid mode: READ_CLIPBOARD: ignore", "Uid mode: READ_CLIPBOARD: ignore\nREAD_CLIPBOARD: allow", "READ_CLIPBOARD: allow\nREAD_CLIPBOARD: ignore")) {
            assertTrue(SystemStateParsers.appOp(result(text), "READ_CLIPBOARD") is StateReadResult.Unavailable)
        }
    }

    @Test fun `user scopes follow Android uid layout without hidden APIs or fixed user zero`() {
        assertEquals(0, AndroidUidScope.userId(10_123))
        assertEquals(10, AndroidUidScope.userId(1_010_123))
        assertEquals(150, AndroidUidScope.userId(15_010_123))
        assertThrows(IllegalArgumentException::class.java) { AndroidUidScope.userId(-1) }
    }

    @Test fun `boot marker comparison fails closed for missing or changed source`() {
        assertFalse(isDifferentBoot("count:1", "count:2"))
        assertFalse(isDifferentBoot("kernel:invalid", "kernel:also-invalid"))
        assertFalse(isDifferentBoot(null, "kernel:00000000-0000-0000-0000-000000000002"))
        assertFalse(isDifferentBoot("kernel:00000000-0000-0000-0000-000000000001", null))
        assertFalse(isDifferentBoot("kernel:00000000-0000-0000-0000-000000000001", "kernel:00000000-0000-0000-0000-000000000001"))
        assertFalse(isDifferentBoot("kernel:00000000-0000-0000-0000-000000000001", "count:2"))
        assertTrue(isDifferentBoot("kernel:00000000-0000-0000-0000-000000000001", "kernel:00000000-0000-0000-0000-000000000002"))
    }

    @Test fun `existing malformed preference file cannot look like a fresh idle fence`() {
        assertNull(CommandFenceRecord.decode(emptyMap<String, String>(), storageExists = false))
        assertThrows(IllegalStateException::class.java) { CommandFenceRecord.decode(emptyMap<String, String>(), storageExists = true) }
        assertThrows(IllegalStateException::class.java) { CommandFenceRecord.decode(mapOf("version" to "1", "state" to "pending"), true) }
        assertThrows(IllegalStateException::class.java) { CommandFenceRecord.decode(mapOf("version" to "1", "state" to "idle", "ticket" to "lost"), true) }
    }

    @Test fun `fence encoding has explicit idle and pending states`() {
        val ticket = CommandTicket("id", "kernel:00000000-0000-0000-0000-000000000001")
        assertEquals(ticket, CommandFenceRecord.decode(CommandFenceRecord.encode(ticket), true))
        assertNull(CommandFenceRecord.decode(CommandFenceRecord.encode(null), true))
    }


    @Test fun `read query constructors reject injection and preserve explicit user scope`() {
        assertThrows(IllegalArgumentException::class.java) { SystemReadQuery.Property("x; reboot") }
        assertThrows(IllegalArgumentException::class.java) { SystemReadQuery.AppOp(0, "pkg;bad", "READ_CLIPBOARD") }
        assertThrows(IllegalArgumentException::class.java) { SystemReadQuery.StandbyBucket(-1, "com.example.app") }
        assertTrue(SystemReadQuery.Packages(10, true).command.contains("--user 10"))
        assertTrue(SystemReadQuery.AppOp(10, "com.example.app", "READ_CLIPBOARD").command.contains("--user 10"))
    }

    @Test fun `failed or truncated read output cannot become an empty healthy diagnostic`() {
        assertThrows(StateReadException::class.java) { result("", 1).requireReadOutput() }
        assertThrows(StateReadException::class.java) { result("value", truncated = true).requireReadOutput() }
        assertEquals(listOf("value"), result("value").requireReadOutput().stdout)
    }

}
