package com.swagdroid.core.vault

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.StateReadResult
import com.swagdroid.core.model.VaultOperation
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.model.VaultStepJournal
import com.swagdroid.core.model.VaultStepStatus
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.vault.db.MIGRATION_1_2
import com.swagdroid.core.vault.db.SwagVaultDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android SQLite + Room schema validation, isolated from the application's real DB/settings. */
@RunWith(AndroidJUnit4::class)
class VaultMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var name: String
    private var database: SwagVaultDatabase? = null
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val oldOperations = """[{"type":"setting_change","namespace":"GLOBAL","key":"swag_ci_fixture","previousValue":"old","newValue":"new","summaryRu":"synthetic fixture"}]"""
    private val corruptPayload = "{original broken JSON - must not be replaced}"

    @Before fun isolatedDatabase() {
        context = ApplicationProvider.getApplicationContext()
        name = "swag-ci-vault-${UUID.randomUUID()}.db"
    }

    @After fun closeAndDeleteOnlyFixtureDatabase() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test fun realRoomMigrationPreservesLegacyRowsAndRawCorruption() = runBlocking {
        createVersionOne()
        val db = openVersionTwo()
        val rows = db.snapshotDao().getAllNewestFirst()
        assertEquals(listOf(15L, 12L, 7L), rows.map { it.id })
        assertEquals(oldOperations, rows.single { it.id == 7L }.operationsJson)
        assertEquals("ACTIVE", rows.single { it.id == 7L }.statusName)
        assertEquals("FAILED", rows.single { it.id == 12L }.statusName)
        assertEquals(corruptPayload, rows.single { it.id == 15L }.operationsJson)
        assertTrue(rows.all { it.journalVersion == 0 && it.stepsJson == "[]" && it.userId == null && it.bootId == null })
        assertEquals(SnapshotStatus.LEGACY_UNVERIFIED, rows.single { it.id == 7L }.toDomain(json).status)
        assertEquals(SnapshotStatus.LEGACY_UNVERIFIED, rows.single { it.id == 12L }.toDomain(json).status)
        val corrupt = rows.single { it.id == 15L }.toDomain(json)
        assertEquals(SnapshotStatus.CORRUPTED, corrupt.status)
        assertTrue(corrupt.unreadablePayload!!.contains(corruptPayload))
        db.openHelper.readableDatabase.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
        db.close()
        val reopened = openVersionTwo().snapshotDao().getById(7)!!
        assertEquals(oldOperations, reopened.operationsJson)
        assertEquals(SnapshotStatus.LEGACY_UNVERIFIED, reopened.toDomain(json).status)
    }

    @Test fun migratedLegacyAndCorruptRecordsCannotDispatchRollbackCommands() = runBlocking {
        createVersionOne()
        val store = RoomVaultStore(openVersionTwo().snapshotDao(), json)
        val manager = StateVaultManager(store, NoCommandsExpected(), VaultExporter { "not-used" })
        for (id in listOf(7L, 12L, 15L)) {
            val result = manager.rollbackSnapshot(id)
            assertTrue(result is VaultTransactionResult.Failure)
        }
        assertEquals(corruptPayload, database!!.snapshotDao().getById(15)!!.operationsJson)
    }

    @Test fun writeAheadStepAndScopeSurviveDatabaseReopen() = runBlocking {
        val store = RoomVaultStore(openVersionTwo().snapshotDao(), json)
        val prepared = snapshot(SnapshotStatus.PREPARED, listOf(VaultStepJournal(0)))
        val id = store.insertSnapshot(prepared)
        store.updateSnapshot(prepared.copy(
            id = id,
            status = SnapshotStatus.APPLYING,
            steps = listOf(VaultStepJournal(0, VaultStepStatus.STARTED)),
        ))
        database!!.close()
        val restored = RoomVaultStore(openVersionTwo().snapshotDao(), json).getSnapshotById(id)!!
        assertEquals(SnapshotStatus.APPLYING, restored.status)
        assertEquals(VaultStepStatus.STARTED, restored.steps.single().status)
        assertEquals(prepared.userId, restored.userId)
        assertEquals(prepared.bootId, restored.bootId)
        assertEquals(prepared.operations, restored.operations)
    }

    @Test fun failedPartialUndoKeepsPerStepStatusAfterReopen() = runBlocking {
        val store = RoomVaultStore(openVersionTwo().snapshotDao(), json)
        val partial = snapshot(SnapshotStatus.FAILED, listOf(
            VaultStepJournal(0, VaultStepStatus.REVERT_FAILED, "synthetic failure"),
            VaultStepJournal(1, VaultStepStatus.REVERTED),
        )).copy(
            operations = listOf(
                VaultOperation.SettingChange(SettingNamespace.GLOBAL, "swag_ci_a", "old-a", "new-a"),
                VaultOperation.SettingChange(SettingNamespace.GLOBAL, "swag_ci_b", "old-b", "new-b"),
            ),
            lastError = "synthetic failure",
        )
        val id = store.insertSnapshot(partial)
        database!!.close()
        val restored = RoomVaultStore(openVersionTwo().snapshotDao(), json).getSnapshotById(id)
        assertNotNull(restored)
        assertEquals(SnapshotStatus.FAILED, restored!!.status)
        assertEquals(partial.steps, restored.steps)
        assertEquals("synthetic failure", restored.lastError)
    }

    private fun openVersionTwo(): SwagVaultDatabase = Room.databaseBuilder(context, SwagVaultDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2)
        .build()
        .also { database = it }

    private fun createVersionOne() {
        // Exact v1 table shape from the audited revision; no dependency on a guessed Room identity hash.
        // Room's actual onUpgrade and generated v2 schema validation run when this DB is opened above.
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE vault_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL, reason TEXT NOT NULL, sourceName TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL, rolledBackAtEpochMs INTEGER,
                privilegeTierName TEXT NOT NULL, statusName TEXT NOT NULL, operationsJson TEXT NOT NULL
            )""")
            insertLegacy(db, 7, "ACTIVE", oldOperations)
            insertLegacy(db, 12, "FAILED", oldOperations)
            insertLegacy(db, 15, "ACTIVE", corruptPayload)
            db.version = 1
        }
    }

    private fun insertLegacy(db: SQLiteDatabase, id: Long, status: String, payload: String) {
        db.execSQL(
            "INSERT INTO vault_snapshots VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id, "fixture-$id", "synthetic; no system changes", "FIXER", 100L, null, "ADB_SHIZUKU", status, payload),
        )
    }

    private fun snapshot(status: SnapshotStatus, steps: List<VaultStepJournal>) = VaultSnapshot(
        title = "synthetic", reason = "Database-only fixture; never executed", source = FeatureSource.MANUAL,
        createdAtEpochMs = 100, privilegeTierUsed = PrivilegeTier.ADB_SHIZUKU,
        status = status,
        operations = listOf(VaultOperation.SettingChange(SettingNamespace.GLOBAL, "swag_ci_fixture", "old", "new")),
        journalVersion = 1, userId = 0, bootId = "kernel:00000000-0000-0000-0000-000000000001", steps = steps,
    )

    private class NoCommandsExpected : PrivilegedCommandExecutor {
        private fun unexpected(): Nothing = throw AssertionError("Untrusted migrated record tried to access the OS executor")
        override fun currentTier(): PrivilegeTier = unexpected()
        override fun currentUserId(): Int = unexpected()
        override suspend fun currentBootId(): String? = unexpected()
        override suspend fun executionBlockReason(): String? = unexpected()
        override suspend fun execute(command: String): ShellExecResult = unexpected()
        override suspend fun readSettingState(namespace: SettingNamespace, key: String): StateReadResult<String> = unexpected()
        override suspend fun readPackageSnapshot(packageName: String): StateReadResult<PackageStateSnapshot> = unexpected()
        override suspend fun readAppOpState(packageName: String, opName: String): StateReadResult<String> = unexpected()
        override suspend fun writeSetting(namespace: SettingNamespace, key: String, value: String?): ShellExecResult = unexpected()
        override suspend fun validatePackageWrite(packageName: String, target: PackageStateSnapshot): StateReadResult<Unit> = unexpected()
        override suspend fun writePackageSnapshot(packageName: String, target: PackageStateSnapshot): ShellExecResult = unexpected()
        override suspend fun writeAppOpMode(packageName: String, opName: String, mode: String): ShellExecResult = unexpected()
    }
}
