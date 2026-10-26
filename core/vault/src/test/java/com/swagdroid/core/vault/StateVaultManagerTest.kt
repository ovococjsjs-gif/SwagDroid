package com.swagdroid.core.vault

import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateVaultManagerTest {

    private class FakeVaultStore : VaultStore {
        private var nextId = 1L
        private val items = MutableStateFlow<List<VaultSnapshot>>(emptyList())

        override fun observeSnapshots(): Flow<List<VaultSnapshot>> = items

        override suspend fun getSnapshotById(id: Long): VaultSnapshot? =
            items.value.firstOrNull { it.id == id }

        override suspend fun getActiveSnapshotsNewestFirst(): List<VaultSnapshot> =
            items.value
                .filter { it.status == SnapshotStatus.ACTIVE }
                .sortedWith(compareByDescending<VaultSnapshot> { it.createdAtEpochMs }.thenByDescending { it.id })

        override suspend fun saveSnapshot(snapshot: VaultSnapshot): Long {
            val assignedId = if (snapshot.id == 0L) nextId++ else snapshot.id
            val stored = snapshot.copy(id = assignedId)
            items.value = listOf(stored) + items.value.filterNot { it.id == assignedId }
            return assignedId
        }

        override suspend fun updateSnapshotStatus(
            id: Long,
            status: SnapshotStatus,
            rolledBackAtEpochMs: Long?,
        ) {
            items.value = items.value.map {
                if (it.id == id) it.copy(status = status, rolledBackAtEpochMs = rolledBackAtEpochMs)
                else it
            }
        }

        override suspend fun deleteSnapshot(id: Long) {
            items.value = items.value.filterNot { it.id == id }
        }
    }

    private class FakeCommandExecutor : PrivilegedCommandExecutor {
        val settingsMap = mutableMapOf<String, String?>()
        val packageStates = mutableMapOf<String, PackageStateTarget>()
        val appOps = mutableMapOf<String, String>()
        var failOnSettingKey: String? = null

        override fun currentTier(): PrivilegeTier = PrivilegeTier.ADB_SHIZUKU

        override suspend fun execute(command: String): ShellExecResult =
            ShellExecResult(command, 0, listOf("ok"), emptyList(), PrivilegeTier.ADB_SHIZUKU)

        override suspend fun readSetting(namespace: SettingNamespace, key: String): String? =
            settingsMap["${namespace.cliName}:$key"]

        override suspend fun writeSetting(
            namespace: SettingNamespace,
            key: String,
            value: String?,
        ): ShellExecResult {
            if (key == failOnSettingKey) {
                return ShellExecResult("settings put $key", 1, emptyList(), listOf("Permission denied"), PrivilegeTier.ADB_SHIZUKU)
            }
            val mapKey = "${namespace.cliName}:$key"
            if (value == null) settingsMap.remove(mapKey) else settingsMap[mapKey] = value
            return ShellExecResult("settings put $key", 0, emptyList(), emptyList(), PrivilegeTier.ADB_SHIZUKU)
        }

        override suspend fun readPackageState(packageName: String): PackageStateTarget =
            packageStates[packageName] ?: PackageStateTarget.ENABLED

        override suspend fun writePackageState(
            packageName: String,
            target: PackageStateTarget,
        ): ShellExecResult {
            packageStates[packageName] = target
            return ShellExecResult("pm $packageName", 0, emptyList(), emptyList(), PrivilegeTier.ADB_SHIZUKU)
        }

        override suspend fun readAppOpMode(packageName: String, opName: String): String =
            appOps["$packageName:$opName"] ?: "allow"

        override suspend fun writeAppOpMode(
            packageName: String,
            opName: String,
            mode: String,
        ): ShellExecResult {
            appOps["$packageName:$opName"] = mode
            return ShellExecResult("appops $packageName", 0, emptyList(), emptyList(), PrivilegeTier.ADB_SHIZUKU)
        }
    }

    private val json = Json { prettyPrint = true; classDiscriminator = "type" }

    @Test
    fun `applyTransaction captures before state and rollback restores exact values`() = runTest {
        val store = FakeVaultStore()
        val exec = FakeCommandExecutor().apply {
            settingsMap["global:window_animation_scale"] = "1.0"
            // global:captive_portal_https_url starts unset (null)
        }
        val manager = StateVaultManager(store, exec, json)

        val applyRes = manager.applyTransaction(
            title = "Плавные анимации + Фикс Wi-Fi",
            reason = "Тест транзакции",
            source = FeatureSource.FIXER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "captive_portal_https_url", "https://cp.cloudflare.com/generate_204"),
                VaultMutationSpec.MutateAppOp("com.example.spy", "READ_CLIPBOARD", "ignore"),
            ),
        )

        assertTrue(applyRes is VaultTransactionResult.Success)
        val snapshotId = (applyRes as VaultTransactionResult.Success).snapshotId
        assertEquals("0.5", exec.settingsMap["global:window_animation_scale"])
        assertEquals("https://cp.cloudflare.com/generate_204", exec.settingsMap["global:captive_portal_https_url"])
        assertEquals("ignore", exec.appOps["com.example.spy:READ_CLIPBOARD"])

        val rollbackRes = manager.rollbackSnapshot(snapshotId)
        assertTrue(rollbackRes is VaultTransactionResult.Success)
        assertEquals("1.0", exec.settingsMap["global:window_animation_scale"])
        assertNull(exec.settingsMap["global:captive_portal_https_url"])
        assertEquals("allow", exec.appOps["com.example.spy:READ_CLIPBOARD"])
        assertEquals(SnapshotStatus.ROLLED_BACK, store.getSnapshotById(snapshotId)?.status)
    }

    @Test
    fun `partial failure automatically unwinds already applied mutations`() = runTest {
        val store = FakeVaultStore()
        val exec = FakeCommandExecutor().apply {
            settingsMap["global:animator_duration_scale"] = "1.0"
            failOnSettingKey = "restricted_oem_key"
        }
        val manager = StateVaultManager(store, exec, json)

        val res = manager.applyTransaction(
            title = "Тест частичного сбоя",
            reason = "Шаг 2 должен упасть и откатить шаг 1",
            source = FeatureSource.OPTIMIZER,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.5"),
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "restricted_oem_key", "1"),
            ),
        )

        assertTrue(res is VaultTransactionResult.Failure)
        assertTrue((res as VaultTransactionResult.Failure).autoReverted)
        // Step 1 must be restored back to 1.0!
        assertEquals("1.0", exec.settingsMap["global:animator_duration_scale"])
    }

    @Test
    fun `SystemBrickGuard blocks disabling critical Android and privilege packages`() = runTest {
        val store = FakeVaultStore()
        val exec = FakeCommandExecutor()
        val manager = StateVaultManager(store, exec, json)

        for (criticalPkg in listOf("com.android.systemui", "com.android.settings", "moe.shizuku.privileged.api", "com.swagdroid")) {
            val res = manager.applyTransaction(
                title = "Опасная попытка заморозки $criticalPkg",
                reason = "Проверка защиты от бутлупа",
                source = FeatureSource.OPTIMIZER,
                mutations = listOf(
                    VaultMutationSpec.MutatePackageState(criticalPkg, PackageStateTarget.DISABLED_USER),
                ),
            )
            assertTrue("Expected failure for $criticalPkg", res is VaultTransactionResult.Failure)
            assertNull(exec.packageStates[criticalPkg])
        }
    }
}
