package com.swagdroid.core.vault

import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageEnabledSetting
import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.model.VaultStepJournal
import com.swagdroid.core.model.VaultStepStatus
import com.swagdroid.core.vault.db.SnapshotEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Must run with the real kotlinx.serialization plugin/runtime in Gradle, not an annotation shim. */
class SnapshotEntityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private fun snapshot() = VaultSnapshot(
        id = 7, title = "test", reason = "test", source = FeatureSource.OPTIMIZER,
        createdAtEpochMs = 123, privilegeTierUsed = PrivilegeTier.ADB_SHIZUKU,
        status = SnapshotStatus.ACTIVE,
        operations = listOf(VaultOperation.SettingChange(SettingNamespace.GLOBAL, "a", "old", "new")),
        journalVersion = 1, userId = 10, bootId = "kernel:00000000-0000-0000-0000-000000000001",
        steps = listOf(VaultStepJournal(0, VaultStepStatus.APPLIED)),
    )
    private fun entity() = SnapshotEntity.fromDomain(snapshot(), json)

    @Test fun `new journal round trips all scope and step information`() {
        assertEquals(snapshot(), entity().toDomain(json))
    }

    @Test fun `malformed operation payload is corrupt not active with empty rollback`() {
        val result = entity().copy(operationsJson = "{broken").toDomain(json)
        assertEquals(SnapshotStatus.CORRUPTED, result.status)
        assertTrue(result.unreadablePayload!!.contains("{broken"))
        assertNotNull(result.lastError)
    }

    @Test fun `empty operation list cannot become a successful rollback record`() {
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(operationsJson = "[]").toDomain(json).status)
    }

    @Test fun `unknown status does not default to active`() {
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(statusName = "FUTURE_STATUS").toDomain(json).status)
    }

    @Test fun `future journal version is retained but cannot be executed`() {
        val result = entity().copy(journalVersion = 999).toDomain(json)
        assertEquals(SnapshotStatus.CORRUPTED, result.status)
        assertNotNull(result.unreadablePayload)
    }

    @Test fun `legacy operations and prior status remain readable for manual review`() {
        val row = entity().copy(journalVersion = 0, stepsJson = "[]", userId = null, bootId = null)
        val decoded = row.toDomain(json)
        assertEquals(SnapshotStatus.LEGACY_UNVERIFIED, decoded.status)
        assertEquals(snapshot().operations, decoded.operations)
        assertTrue(decoded.unreadablePayload!!.contains(row.operationsJson))
        assertTrue(decoded.lastError!!.contains("ACTIVE"))
    }

    @Test fun `legacy rolled back label is not promoted to a verified rollback`() {
        val decoded = entity().copy(journalVersion = 0, stepsJson = "[]", statusName = "ROLLED_BACK").toDomain(json)
        assertEquals(SnapshotStatus.LEGACY_UNVERIFIED, decoded.status)
        assertTrue(decoded.lastError!!.contains("ROLLED_BACK"))
    }

    @Test fun `missing or malformed step journal cannot be interpreted as complete`() {
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(stepsJson = "[]").toDomain(json).status)
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(stepsJson = "not-json").toDomain(json).status)
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(stepsJson = "[{\"index\":2,\"status\":\"APPLIED\"}]").toDomain(json).status)
    }

    @Test fun `active and rolled back statuses require their corresponding step evidence`() {
        val planned = "[{\"index\":0,\"status\":\"PLANNED\"}]"
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(stepsJson = planned).toDomain(json).status)
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(statusName = "ROLLED_BACK").toDomain(json).status)
    }

    @Test fun `missing user or boot identity makes a new mutating record invalid`() {
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(userId = null).toDomain(json).status)
        assertEquals(SnapshotStatus.CORRUPTED, entity().copy(bootId = null).toDomain(json).status)
    }

    @Test fun `corrupt records cannot overwrite the original payload through domain serialization`() {
        val decoded = entity().copy(operationsJson = "{broken").toDomain(json)
        assertThrows(IllegalArgumentException::class.java) { SnapshotEntity.fromDomain(decoded, json) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotEntity.fromDomain(decoded.copy(status = SnapshotStatus.RECOVERY_REQUIRED), json) }
    }

    @Test fun `package flags and generation round trip without losing default state`() {
        val before = PackageStateSnapshot(PackageEnabledSetting.DEFAULT, false, true, 1_010_123, 1_000)
        val op = VaultOperation.PackageStateChange("com.example.app", before.coarseState, PackageStateTarget.SUSPENDED, before, before.forTarget(PackageStateTarget.SUSPENDED))
        val original = snapshot().copy(operations = listOf(op))
        assertEquals(original, SnapshotEntity.fromDomain(original, json).toDomain(json))
    }

    @Test fun `contradictory package flags cannot become an active record`() {
        val before = PackageStateSnapshot(PackageEnabledSetting.DEFAULT, false, true, 1_010_123, 1_000)
        val invalidAfter = before.copy(enabledSetting = PackageEnabledSetting.DISABLED_USER, effectivelyEnabled = true)
        val op = VaultOperation.PackageStateChange("com.example.app", before.coarseState, PackageStateTarget.DISABLED_USER, before, invalidAfter)
        val row = SnapshotEntity.fromDomain(snapshot().copy(operations = listOf(op)), json)
        assertEquals(SnapshotStatus.CORRUPTED, row.toDomain(json).status)
    }

}
