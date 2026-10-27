package com.swagdroid.core.privilege

import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.StateReadResult

interface PrivilegedCommandExecutor {
    fun currentTier(): PrivilegeTier
    fun currentUserId(): Int
    suspend fun currentBootId(): String?
    /** Non-null means commands cannot safely be started, e.g. an unconfirmed previous timeout. */
    suspend fun executionBlockReason(): String?

    suspend fun execute(command: String): ShellExecResult
    suspend fun query(query: SystemReadQuery): ShellExecResult = execute(query.command)
    suspend fun readSettingState(namespace: SettingNamespace, key: String): StateReadResult<String>
    suspend fun readPackageSnapshot(packageName: String): StateReadResult<PackageStateSnapshot>
    suspend fun readAppOpState(packageName: String, opName: String): StateReadResult<String>
    suspend fun writeSetting(namespace: SettingNamespace, key: String, value: String?): ShellExecResult
    suspend fun validatePackageWrite(packageName: String, target: PackageStateSnapshot): StateReadResult<Unit>
    suspend fun writePackageSnapshot(packageName: String, target: PackageStateSnapshot): ShellExecResult
    suspend fun writeAppOpMode(packageName: String, opName: String, mode: String): ShellExecResult

    // Compatibility accessors for feature modules: absence is nullable, read failure MUST propagate.
    // Their callers handle StateReadException as an unavailable diagnostic, never a healthy default.
    suspend fun readSetting(namespace: SettingNamespace, key: String): String? =
        readSettingState(namespace, key).valueOrNullIfMissing()

    suspend fun readPackageState(packageName: String): PackageStateTarget =
        readPackageSnapshot(packageName).requireValue().coarseState

    suspend fun readAppOpMode(packageName: String, opName: String): String =
        readAppOpState(packageName, opName).requireValue()
}
