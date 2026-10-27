package com.swagdroid.core.vault

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageEnabledSetting
import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.StateReadResult
import com.swagdroid.core.model.VaultExportBundle
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.model.VaultStepStatus
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateVaultManagerTest {
    private class Store : VaultStore {
        val rows = MutableStateFlow<List<VaultSnapshot>>(emptyList())
        val trace = mutableListOf<VaultSnapshot>()
        var nextId = 1L
        var updateCount = 0
        var failUpdate: Int? = null
        var failInsert = false
        override fun observeSnapshots(): Flow<List<VaultSnapshot>> = rows
        override suspend fun getSnapshotById(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun getSnapshotsNewestFirst() = rows.value.sortedByDescending { it.id }
        override suspend fun insertSnapshot(snapshot: VaultSnapshot): Long {
            if (failInsert) error("storage unavailable")
            val id = nextId++
            put(snapshot.copy(id = id))
            return id
        }
        override suspend fun updateSnapshot(snapshot: VaultSnapshot) {
            updateCount++
            if (updateCount == failUpdate) error("journal update failed")
            check(rows.value.any { it.id == snapshot.id })
            put(snapshot)
        }
        fun put(snapshot: VaultSnapshot) {
            val copy = snapshot.copy(steps = snapshot.steps.toList(), operations = snapshot.operations.toList())
            rows.value = rows.value.filterNot { it.id == snapshot.id } + copy
            trace += copy
        }
        override suspend fun deleteSnapshot(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        fun last() = rows.value.maxBy { it.id }
    }

    private data class Write(val key: String, val value: Any?)
    private class Executor : PrivilegedCommandExecutor {
        var tier = PrivilegeTier.ADB_SHIZUKU
        var user = 0
        var boot: String? = "kernel:00000000-0000-0000-0000-000000000001"
        var blocked: String? = null
        val values = mutableMapOf<String, Any?>()
        val readFailures = mutableSetOf<String>()
        val writes = mutableListOf<Write>()
        var rawCommandCount = 0
        var readCount = 0
        var onRead: ((String) -> Unit)? = null
        var onWrite: (suspend (Write) -> ShellExecResult?)? = null
        var packageGuard: ((PackageStateSnapshot) -> String?)? = null
        override fun currentTier() = tier
        override fun currentUserId() = user
        override suspend fun currentBootId() = boot
        override suspend fun executionBlockReason() = blocked
        fun result(code: Int = 0, completion: CommandCompletion = CommandCompletion.EXITED) =
            ShellExecResult("test", code, emptyList(), if (code == 0) emptyList() else listOf("test failure"), tier, completion)
        override suspend fun execute(command: String): ShellExecResult { rawCommandCount++; return result() }
        private fun read(key: String): StateReadResult<Any> {
            readCount++
            onRead?.invoke(key)
            if (key in readFailures) return StateReadResult.Unavailable("denied: $key")
            return values[key]?.let { StateReadResult.Value(it) } ?: StateReadResult.Missing
        }
        @Suppress("UNCHECKED_CAST")
        override suspend fun readSettingState(namespace: SettingNamespace, key: String) = read("${namespace.cliName}:$key") as StateReadResult<String>
        @Suppress("UNCHECKED_CAST")
        override suspend fun readPackageSnapshot(packageName: String) = read("pkg:$packageName") as StateReadResult<PackageStateSnapshot>
        @Suppress("UNCHECKED_CAST")
        override suspend fun readAppOpState(packageName: String, opName: String): StateReadResult<String> = read("op:$packageName:$opName") as StateReadResult<String>
        private suspend fun write(key: String, value: Any?): ShellExecResult {
            val request = Write(key, value)
            writes += request
            onWrite?.invoke(request)?.let { return it }
            if (value == null) values.remove(key) else values[key] = value
            return result()
        }
        override suspend fun writeSetting(namespace: SettingNamespace, key: String, value: String?) = write("${namespace.cliName}:$key", value)
        override suspend fun validatePackageWrite(packageName: String, target: PackageStateSnapshot): StateReadResult<Unit> =
            packageGuard?.invoke(target)?.let { StateReadResult.Unavailable(it) } ?: StateReadResult.Value(Unit)
        override suspend fun writePackageSnapshot(packageName: String, target: PackageStateSnapshot) = write("pkg:$packageName", target)
        override suspend fun writeAppOpMode(packageName: String, opName: String, mode: String) = write("op:$packageName:$opName", mode)
    }

    private class Fixture {
        val store = Store()
        val exec = Executor()
        var exported: VaultExportBundle? = null
        private val exporter = VaultExporter { exported = it; "test-export" }
        val manager = StateVaultManager(store, exec, exporter)
        fun restartManager() = StateVaultManager(store, exec, exporter)
        suspend fun apply(vararg specs: VaultMutationSpec) = manager.applyTransaction("test", "test", FeatureSource.FIXER, specs.toList())
    }
    private fun setting(key: String, value: String?) = VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, key, value)
    private fun VaultTransactionResult.failure() = this as VaultTransactionResult.Failure
    private fun VaultTransactionResult.success() = this as VaultTransactionResult.Success

    @Test fun `confirmed setting changes restore exact old values and missing keys`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "  old value  "
        val applied = f.apply(setting("a", "new"), setting("missing", "null")).success()
        assertEquals(SnapshotStatus.ACTIVE, f.store.last().status)
        assertEquals("null", f.exec.values["global:missing"])
        f.manager.rollbackSnapshot(applied.snapshotId).success()
        assertEquals("  old value  ", f.exec.values["global:a"])
        assertFalse(f.exec.values.containsKey("global:missing"))
        assertEquals(SnapshotStatus.ROLLED_BACK, f.store.last().status)
    }

    @Test fun `literal null and empty strings are values not absence`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "null"; f.exec.values["global:b"] = ""
        val id = f.apply(setting("a", "x"), setting("b", "y")).success().snapshotId
        f.manager.rollbackSnapshot(id).success()
        assertEquals("null", f.exec.values["global:a"])
        assertEquals("", f.exec.values["global:b"])
    }

    @Test fun `failed before read prevents both snapshot creation and mutation`() = runBlocking {
        val f = Fixture(); f.exec.readFailures += "global:a"
        assertEquals(VaultRecovery.NO_CHANGES, f.apply(setting("a", "x")).failure().recovery)
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `missing package is never fabricated as enabled`() = runBlocking {
        val f = Fixture()
        val result = f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.SUSPENDED)).failure()
        assertEquals(VaultRecovery.NO_CHANGES, result.recovery)
        assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `unavailable appop is not default and prevents the whole transaction`() = runBlocking {
        val f = Fixture(); f.exec.readFailures += "op:com.example.app:READ_CLIPBOARD"
        f.apply(setting("a", "x"), VaultMutationSpec.MutateAppOp("com.example.app", "READ_CLIPBOARD", "ignore")).failure()
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `whole opaque shell preset is rejected before any reads writes or shell commands`() = runBlocking {
        val f = Fixture()
        val result = f.apply(setting("a", "x"), VaultMutationSpec.MutateCustomShell("danger", "true", "unverifiable")).failure()
        assertEquals(VaultRecovery.NO_CHANGES, result.recovery)
        assertEquals(0, f.exec.readCount); assertEquals(0, f.exec.rawCommandCount); assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `duplicate resources are rejected rather than capturing contradictory before states`() = runBlocking {
        val f = Fixture()
        f.apply(setting("a", "x"), setting("a", "y")).failure()
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `write ahead journal precedes every apply and inverse command`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        f.exec.onWrite = {
            assertTrue(f.store.last().steps.single().status in setOf(VaultStepStatus.STARTED, VaultStepStatus.REVERTING))
            null
        }
        val id = f.apply(setting("a", "new")).success().snapshotId
        assertEquals(SnapshotStatus.PREPARED, f.store.trace.first().status)
        assertEquals(VaultStepStatus.PLANNED, f.store.trace.first().steps.single().status)
        f.manager.rollbackSnapshot(id).success()
        assertTrue(f.store.trace.any { it.steps.single().status == VaultStepStatus.REVERTING })
    }

    @Test fun `failed initial insert dispatches no command`() = runBlocking {
        val f = Fixture(); f.store.failInsert = true
        assertEquals(VaultRecovery.NO_CHANGES, f.apply(setting("a", "x")).failure().recovery)
        assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `failed write ahead update dispatches no command`() = runBlocking {
        val f = Fixture(); f.store.failUpdate = 1
        f.apply(setting("a", "x")).failure()
        assertTrue(f.exec.writes.isEmpty())
        assertEquals(SnapshotStatus.RECOVERY_REQUIRED, f.store.last().status)
    }

    @Test fun `zero exit code without effect is failure not active snapshot`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        f.exec.onWrite = { f.exec.result() } // OS ignores the write.
        val result = f.apply(setting("a", "new")).failure()
        assertEquals(VaultRecovery.ORIGINAL_CONFIRMED, result.recovery)
        assertEquals(SnapshotStatus.ROLLED_BACK, f.store.last().status)
        assertEquals("old", f.exec.values["global:a"])
    }

    @Test fun `failed step which already changed the setting is compensated too`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        f.exec.onWrite = { if (it.value == "new") { f.exec.values[it.key] = it.value; f.exec.result(1) } else null }
        val result = f.apply(setting("a", "new")).failure()
        assertEquals(VaultRecovery.ORIGINAL_CONFIRMED, result.recovery)
        assertEquals(listOf("new", "old"), f.exec.writes.map { it.value })
        assertEquals("old", f.exec.values["global:a"])
    }

    @Test fun `partially changed package step is reverted before prior settings`() = runBlocking {
        val f = Fixture()
        val before = PackageStateSnapshot(PackageEnabledSetting.DISABLED_USER, true, false, 10_123, 1_000)
        f.exec.values["global:a"] = "old"; f.exec.values["pkg:com.example.app"] = before
        f.exec.onWrite = { request ->
            if (request.key.startsWith("pkg:") && request.value != before) {
                f.exec.values[request.key] = before.copy(suspended = false) // unsuspend succeeded; enable failed.
                f.exec.result(1)
            } else null
        }
        val result = f.apply(setting("a", "new"), VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.ENABLED)).failure()
        assertEquals(VaultRecovery.ORIGINAL_CONFIRMED, result.recovery)
        assertEquals(before, f.exec.values["pkg:com.example.app"])
        assertEquals(listOf("global:a", "pkg:com.example.app", "pkg:com.example.app", "global:a"), f.exec.writes.map { it.key })
    }

    @Test fun `package restore preserves DEFAULT rather than forcing ENABLED`() = runBlocking {
        val f = Fixture(); val before = PackageStateSnapshot(PackageEnabledSetting.DEFAULT, false, true, 10_123, 1_000)
        f.exec.values["pkg:com.example.app"] = before
        val id = f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.DISABLED_USER)).success().snapshotId
        f.manager.rollbackSnapshot(id).success()
        assertEquals(before, f.exec.values["pkg:com.example.app"])
    }

    @Test fun `suspending an already disabled package never enables it`() = runBlocking {
        val f = Fixture(); val before = PackageStateSnapshot(PackageEnabledSetting.DISABLED_UNTIL_USED, false, false, 10_123, 1_000)
        f.exec.values["pkg:com.example.app"] = before
        f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.SUSPENDED)).success()
        assertEquals(before.copy(suspended = true), f.exec.values["pkg:com.example.app"])
    }

    @Test fun `timeout does not start inverse commands or later steps`() = runBlocking {
        val f = Fixture(); f.exec.onWrite = { f.exec.values[it.key] = it.value; f.exec.result(124, CommandCompletion.UNKNOWN) }
        val result = f.apply(setting("a", "x"), setting("b", "y")).failure()
        assertEquals(VaultRecovery.NEEDS_RECOVERY, result.recovery)
        assertEquals(1, f.exec.writes.size)
        assertEquals(VaultStepStatus.OUTCOME_UNKNOWN, f.store.last().steps.first().status)
        assertEquals(VaultStepStatus.PLANNED, f.store.last().steps.last().status)
        assertEquals(SnapshotStatus.RECOVERY_REQUIRED, f.store.last().status)
    }

    @Test fun `unknown command prevents same boot recovery and survives manager restart`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        f.exec.onWrite = { f.exec.values[it.key] = it.value; f.exec.result(124, CommandCompletion.UNKNOWN) }
        val id = f.apply(setting("a", "new")).failure().snapshotId!!
        f.exec.onWrite = null
        f.restartManager().rollbackSnapshot(id).failure()
        assertEquals(1, f.exec.writes.size)
        f.exec.boot = "kernel:00000000-0000-0000-0000-000000000002"
        f.restartManager().rollbackSnapshot(id).success()
        assertEquals("old", f.exec.values["global:a"])
    }

    @Test fun `change of boot marker source does not authorize recovery`() = runBlocking {
        val f = Fixture(); f.exec.onWrite = { f.exec.result(124, CommandCompletion.UNKNOWN) }
        val id = f.apply(setting("a", "new")).failure().snapshotId!!
        f.exec.boot = "count:2"; f.exec.onWrite = null
        f.manager.rollbackSnapshot(id).failure()
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `pending recovery blocks new changes to unrelated resources`() = runBlocking {
        val f = Fixture(); f.exec.onWrite = { f.exec.result(124, CommandCompletion.UNKNOWN) }
        f.apply(setting("a", "new")).failure()
        f.exec.onWrite = null
        f.apply(setting("unrelated", "value")).failure()
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `executor fence refusal is not reported as an automatic rollback`() = runBlocking {
        val f = Fixture(); f.exec.blocked = "unfinished root command"
        assertEquals(VaultRecovery.NO_CHANGES, f.apply(setting("a", "new")).failure().recovery)
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `external value changes are not overwritten during undo`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        val id = f.apply(setting("a", "new")).success().snapshotId
        f.exec.values["global:a"] = "user-choice"
        f.manager.rollbackSnapshot(id).failure()
        assertEquals("user-choice", f.exec.values["global:a"])
        assertEquals(1, f.exec.writes.size)
        assertEquals(SnapshotStatus.FAILED, f.store.last().status)
    }

    @Test fun `newer overlapping snapshots must be rolled back first`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        val first = f.apply(setting("a", "first")).success().snapshotId
        val second = f.apply(setting("a", "second")).success().snapshotId
        f.manager.rollbackSnapshot(first).failure()
        assertEquals("second", f.exec.values["global:a"])
        f.manager.rollbackSnapshot(second).success()
        f.manager.rollbackSnapshot(first).success()
        assertEquals("old", f.exec.values["global:a"])
    }

    @Test fun `independent newer snapshots do not block undo`() = runBlocking {
        val f = Fixture()
        val first = f.apply(setting("a", "x")).success().snapshotId
        f.apply(setting("b", "y")).success()
        f.manager.rollbackSnapshot(first).success()
        assertEquals("y", f.exec.values["global:b"])
        assertFalse(f.exec.values.containsKey("global:a"))
    }

    @Test fun `failed undo can be retried without repeating completed inverse steps`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old-a"; f.exec.values["global:b"] = "old-b"
        val id = f.apply(setting("a", "new-a"), setting("b", "new-b")).success().snapshotId
        f.exec.onWrite = { if (it.key == "global:a") f.exec.result(1) else null }
        f.manager.rollbackSnapshot(id).failure()
        assertEquals(VaultStepStatus.REVERTED, f.store.last().steps[1].status)
        f.exec.onWrite = null; f.exec.values["global:b"] = "later-user-choice"
        f.manager.rollbackSnapshot(id).success()
        assertEquals(1, f.exec.writes.count { it.key == "global:b" && it.value == "old-b" })
        assertEquals("later-user-choice", f.exec.values["global:b"])
    }

    @Test fun `batch stops on failure and reports remaining snapshots instead of success`() = runBlocking {
        val f = Fixture()
        f.apply(setting("a", "x")).success(); f.apply(setting("b", "y")).success()
        f.exec.values["global:b"] = "external"
        val report = f.manager.rollbackAllActive()
        assertEquals(2, report.eligibleCount); assertEquals(0, report.restoredCount)
        assertEquals(1, report.failures.size); assertEquals(1, report.notAttemptedCount)
        assertEquals("x", f.exec.values["global:a"])
    }

    @Test fun `batch includes failed snapshots and retries after cause is resolved`() = runBlocking {
        val f = Fixture(); val id = f.apply(setting("a", "x")).success().snapshotId
        f.exec.values["global:a"] = "external"; f.manager.rollbackSnapshot(id).failure()
        f.exec.values["global:a"] = "x"
        val report = f.manager.rollbackAllActive()
        assertEquals(1, report.eligibleCount); assertEquals(1, report.restoredCount); assertTrue(report.failures.isEmpty())
    }

    @Test fun `different user cannot restore a snapshot`() = runBlocking {
        val f = Fixture(); val id = f.apply(setting("a", "x")).success().snapshotId
        f.exec.user = 10
        f.manager.rollbackSnapshot(id).failure()
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `secondary user is captured rather than hard coded to zero`() = runBlocking {
        val f = Fixture(); f.exec.user = 10
        f.apply(setting("a", "x")).success()
        assertEquals(10, f.store.last().userId)
    }

    @Test fun `critical packages including debug application are denied before mutation`() = runBlocking {
        val f = Fixture()
        for (pkg in listOf("com.android.systemui", "com.android.settings", "moe.shizuku.privileged.api", "com.swagdroid", "com.swagdroid.debug")) {
            f.apply(VaultMutationSpec.MutatePackageState(pkg, PackageStateTarget.DISABLED_USER)).failure()
        }
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `read only checkpoint is not an automatic restore point`() = runBlocking {
        val f = Fixture(); val id = f.manager.createBaselineCheckpoint().success().snapshotId
        assertEquals(SnapshotStatus.CHECKPOINT, f.store.last().status)
        assertEquals(9, f.store.last().operations.size)
        f.manager.rollbackSnapshot(id).failure()
        assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `checkpoint with an unreadable field is not stored as a partial success`() = runBlocking {
        val f = Fixture(); f.exec.readFailures += "global:private_dns_mode"
        f.manager.createBaselineCheckpoint().failure()
        assertTrue(f.store.rows.value.isEmpty()); assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `legacy and corrupt snapshots are not executed and remain exportable`() = runBlocking {
        val f = Fixture(); val id = f.apply(setting("a", "x")).success().snapshotId
        f.store.put(f.store.last().copy(journalVersion = 0, status = SnapshotStatus.LEGACY_UNVERIFIED, steps = emptyList()))
        f.manager.rollbackSnapshot(id).failure()
        f.store.put(f.store.last().copy(status = SnapshotStatus.CORRUPTED, operations = emptyList(), unreadablePayload = "broken-data"))
        f.manager.rollbackSnapshot(id).failure()
        f.manager.exportSnapshotsJson("device")
        assertEquals("broken-data", f.exported!!.snapshots.single().unreadablePayload)
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `failure to persist a completed step is never reported as success`() = runBlocking {
        val f = Fixture(); f.store.failUpdate = 2
        f.apply(setting("a", "x")).failure()
        assertEquals(VaultStepStatus.OUTCOME_UNKNOWN, f.store.last().steps.single().status)
        f.manager.rollbackSnapshot(f.store.last().id).failure()
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `cancellation journals uncertainty and does not silently compensate`() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>()
        f.exec.onWrite = { f.exec.values[it.key] = it.value; entered.complete(Unit); awaitCancellation() }
        val job = launch { f.apply(setting("a", "x")) }
        entered.await(); job.cancelAndJoin()
        assertEquals(SnapshotStatus.RECOVERY_REQUIRED, f.store.last().status)
        assertEquals(VaultStepStatus.OUTCOME_UNKNOWN, f.store.last().steps.single().status)
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `cancellation in automatic undo does not overwrite the newer undo journal`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        f.exec.onWrite = {
            if (it.value == "new") { f.exec.values[it.key] = it.value; f.exec.result(1) }
            else throw CancellationException("during inverse")
        }
        try { f.apply(setting("a", "new")); error("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(VaultStepStatus.OUTCOME_UNKNOWN, f.store.last().steps.single().status)
        f.exec.onWrite = null
        f.manager.rollbackSnapshot(f.store.last().id).failure()
        assertEquals(2, f.exec.writes.size)
    }

    @Test fun `precondition drift before dispatch leaves external change alone`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        var reads = 0
        f.exec.onRead = { if (it == "global:a" && ++reads == 2) f.exec.values[it] = "external" }
        assertEquals(VaultRecovery.NO_CHANGES, f.apply(setting("a", "new")).failure().recovery)
        assertEquals("external", f.exec.values["global:a"])
        assertTrue(f.exec.writes.isEmpty())
    }

    @Test fun `unreadable postcondition cannot produce an active snapshot`() = runBlocking {
        val f = Fixture(); f.exec.onWrite = { f.exec.values[it.key] = it.value; f.exec.readFailures += it.key; f.exec.result() }
        val result = f.apply(setting("a", "x")).failure()
        assertEquals(VaultRecovery.NEEDS_RECOVERY, result.recovery)
        assertEquals(SnapshotStatus.FAILED, f.store.last().status)
        assertEquals(1, f.exec.writes.size)
    }

    @Test fun `undo with a zero exit but wrong state remains failed`() = runBlocking {
        val f = Fixture(); f.exec.values["global:a"] = "old"
        val id = f.apply(setting("a", "new")).success().snapshotId
        f.exec.onWrite = { f.exec.result() }
        f.manager.rollbackSnapshot(id).failure()
        assertEquals(SnapshotStatus.FAILED, f.store.last().status)
        assertEquals("new", f.exec.values["global:a"])
    }

    @Test fun `already rolled back snapshot is idempotent and never undoes later user changes`() = runBlocking {
        val f = Fixture(); val id = f.apply(setting("a", "x")).success().snapshotId
        f.manager.rollbackSnapshot(id).success(); f.exec.values["global:a"] = "user"
        assertEquals(0, f.manager.rollbackSnapshot(id).success().appliedCount)
        assertEquals("user", f.exec.values["global:a"])
        assertEquals(2, f.exec.writes.size)
    }

    @Test fun `active journal cannot be deleted`() = runBlocking {
        val f = Fixture(); val id = f.apply(setting("a", "x")).success().snapshotId
        try { f.manager.deleteSnapshotRecord(id); error("expected refusal") } catch (_: IllegalStateException) { }
        assertNotNull(f.store.getSnapshotById(id))
        f.manager.rollbackSnapshot(id).success(); f.manager.deleteSnapshotRecord(id)
        assertNull(f.store.getSnapshotById(id))
    }

    @Test fun `unsupported AppOps mode prevents earlier valid mutations`() = runBlocking {
        val f = Fixture()
        f.apply(setting("a", "x"), VaultMutationSpec.MutateAppOp("com.example.app", "READ_CLIPBOARD", "not-a-mode")).failure()
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `unrestorable setting values are rejected before changing the OS`() = runBlocking {
        for (old in listOf("old\u0000value", "x".repeat(20_000))) {
            val f = Fixture(); f.exec.values["global:a"] = old
            assertEquals(VaultRecovery.NO_CHANGES, f.apply(setting("a", "new")).failure().recovery)
            assertEquals(old, f.exec.values["global:a"])
            assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
        }
        val f = Fixture()
        f.apply(setting("a", "new\u0000value")).failure()
        assertTrue(f.exec.writes.isEmpty())
    }


    @Test fun `unsafe inverse is rejected before promising a reversible critical package repair`() = runBlocking {
        val f = Fixture()
        f.exec.values["pkg:com.android.systemui"] = PackageStateSnapshot(PackageEnabledSetting.DISABLED_USER, false, false, 10_123, 1_000)
        f.apply(VaultMutationSpec.MutatePackageState("com.android.systemui", PackageStateTarget.ENABLED)).failure()
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `known completed failed rollback does not permanently lock unrelated tools`() = runBlocking {
        val f = Fixture()
        val id = f.apply(setting("a", "x")).success().snapshotId
        f.exec.values["global:a"] = "external"
        f.manager.rollbackSnapshot(id).failure()
        f.apply(setting("b", "new")).success()
        assertEquals("external", f.exec.values["global:a"])
        assertEquals("new", f.exec.values["global:b"])
    }


    @Test fun `package update invalidates undo before any inverse mutation`() = runBlocking {
        val f = Fixture(); val before = PackageStateSnapshot(PackageEnabledSetting.DEFAULT, false, true, 10_123, 1_000)
        f.exec.values["pkg:com.example.app"] = before
        val id = f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.SUSPENDED)).success().snapshotId
        val updated = before.copy(suspended = true, lastUpdateTime = 2_000)
        f.exec.values["pkg:com.example.app"] = updated
        f.manager.rollbackSnapshot(id).failure()
        assertEquals(updated, f.exec.values["pkg:com.example.app"]); assertEquals(1, f.exec.writes.size)
    }

    @Test fun `runtime guard checks the inverse before promising a reversible operation`() = runBlocking {
        val f = Fixture()
        f.exec.values["pkg:com.example.app"] = PackageStateSnapshot(PackageEnabledSetting.DISABLED_USER, false, false, 10_123, 1_000)
        f.exec.packageGuard = { if (!it.effectivelyEnabled) "active role cannot be disabled" else null }
        f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.ENABLED)).failure()
        assertTrue(f.exec.writes.isEmpty()); assertTrue(f.store.rows.value.isEmpty())
    }

    @Test fun `no op does not force package default to enabled or undo later changes`() = runBlocking {
        val f = Fixture(); val before = PackageStateSnapshot(PackageEnabledSetting.DEFAULT, false, true, 10_123, 1_000)
        f.exec.values["pkg:com.example.app"] = before
        val id = f.apply(VaultMutationSpec.MutatePackageState("com.example.app", PackageStateTarget.ENABLED)).success().snapshotId
        assertTrue(f.exec.writes.isEmpty())
        val later = before.copy(lastUpdateTime = 2_000)
        f.exec.values["pkg:com.example.app"] = later
        f.manager.rollbackSnapshot(id).success()
        assertTrue(f.exec.writes.isEmpty()); assertEquals(later, f.exec.values["pkg:com.example.app"])
    }

    @Test fun `interruption never rewrites a newly corrupted record with a stale decoded snapshot`() = runBlocking {
        val f = Fixture()
        f.exec.onWrite = {
            f.store.put(f.store.last().copy(status = SnapshotStatus.CORRUPTED, operations = emptyList(), unreadablePayload = "keep-this-raw-data"))
            error("storage damaged during command")
        }
        f.apply(setting("a", "new")).failure()
        assertEquals(SnapshotStatus.CORRUPTED, f.store.last().status)
        assertEquals("keep-this-raw-data", f.store.last().unreadablePayload)
    }


    @Test fun `per package AppOps mode is verified and restored`() = runBlocking {
        val f = Fixture(); val key = "op:com.example.app:READ_CLIPBOARD"
        f.exec.values[key] = "foreground"
        val id = f.apply(VaultMutationSpec.MutateAppOp("com.example.app", "READ_CLIPBOARD", "ignore")).success().snapshotId
        assertEquals("ignore", f.exec.values[key])
        f.manager.rollbackSnapshot(id).success()
        assertEquals("foreground", f.exec.values[key])
        assertEquals(SnapshotStatus.ROLLED_BACK, f.store.last().status)
    }

}
