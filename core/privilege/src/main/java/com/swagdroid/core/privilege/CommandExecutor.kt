package com.swagdroid.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

interface PrivilegedCommandExecutor {
    fun currentTier(): PrivilegeTier
    suspend fun execute(command: String): ShellExecResult
    suspend fun readSetting(namespace: SettingNamespace, key: String): String?
    suspend fun writeSetting(namespace: SettingNamespace, key: String, value: String?): ShellExecResult
    suspend fun readPackageState(packageName: String): PackageStateTarget
    suspend fun writePackageState(packageName: String, target: PackageStateTarget): ShellExecResult
    suspend fun readAppOpMode(packageName: String, opName: String): String
    suspend fun writeAppOpMode(packageName: String, opName: String, mode: String): ShellExecResult
}

@Singleton
class AndroidCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
) : PrivilegedCommandExecutor {

    override fun currentTier(): PrivilegeTier = privilegeManager.state.value.activeTier

    override suspend fun execute(command: String): ShellExecResult = withContext(Dispatchers.IO) {
        val tier = privilegeManager.state.value.activeTier
        when (tier) {
            PrivilegeTier.ROOT -> executeViaLibsu(command)
            PrivilegeTier.ADB_SHIZUKU -> executeViaShizuku(command)
            PrivilegeTier.BASIC -> executeLocalUnprivileged(command)
        }
    }

    override suspend fun readSetting(namespace: SettingNamespace, key: String): String? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val direct = runCatching {
                when (namespace) {
                    SettingNamespace.GLOBAL -> Settings.Global.getString(resolver, key)
                    SettingNamespace.SECURE -> Settings.Secure.getString(resolver, key)
                    SettingNamespace.SYSTEM -> Settings.System.getString(resolver, key)
                }
            }.getOrNull()

            if (direct != null) return@withContext direct

            if (currentTier() != PrivilegeTier.BASIC) {
                val qKey = DumpsysAndKernelParsers.shellQuote(key)
                val res = execute("settings get ${namespace.cliName} $qKey")
                if (res.isSuccess) {
                    val out = res.stdoutText
                    if (out.isNotEmpty() && out != "null") return@withContext out
                }
            }
            null
        }

    override suspend fun writeSetting(
        namespace: SettingNamespace,
        key: String,
        value: String?,
    ): ShellExecResult {
        val qKey = DumpsysAndKernelParsers.shellQuote(key)
        val cmd = if (value == null) {
            "settings delete ${namespace.cliName} $qKey"
        } else {
            val qVal = DumpsysAndKernelParsers.shellQuote(value)
            "settings put ${namespace.cliName} $qKey $qVal"
        }
        return execute(cmd)
    }

    override suspend fun readPackageState(packageName: String): PackageStateTarget =
        withContext(Dispatchers.IO) {
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                val isSuspended = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
                when {
                    !appInfo.enabled -> PackageStateTarget.DISABLED_USER
                    isSuspended -> PackageStateTarget.SUSPENDED
                    else -> PackageStateTarget.ENABLED
                }
            }.getOrDefault(PackageStateTarget.ENABLED)
        }

    override suspend fun writePackageState(
        packageName: String,
        target: PackageStateTarget,
    ): ShellExecResult {
        val qPkg = DumpsysAndKernelParsers.shellQuote(packageName)
        val cmd = when (target) {
            PackageStateTarget.ENABLED ->
                "cmd package unsuspend --user 0 $qPkg >/dev/null 2>&1; pm enable --user 0 $qPkg"
            PackageStateTarget.DISABLED_USER ->
                "pm disable-user --user 0 $qPkg"
            PackageStateTarget.SUSPENDED ->
                "pm enable --user 0 $qPkg >/dev/null 2>&1; cmd package suspend --user 0 $qPkg"
        }
        return execute(cmd)
    }

    override suspend fun readAppOpMode(packageName: String, opName: String): String {
        val qPkg = DumpsysAndKernelParsers.shellQuote(packageName)
        val qOp = DumpsysAndKernelParsers.shellQuote(opName)
        val res = execute("cmd appops get $qPkg $qOp")
        if (!res.isSuccess) return "default"

        // Typical line: "READ_CLIPBOARD: allow; time=+2m14s ago" or "Uid mode: ignore"
        for (line in res.stdout) {
            val trimmed = line.trim()
            val afterColon = trimmed.substringAfter(':', "").trim()
            if (afterColon.isNotEmpty()) {
                val modeToken = afterColon.substringBefore(';').trim().lowercase()
                if (modeToken in setOf("allow", "ignore", "deny", "default", "foreground")) {
                    return modeToken
                }
            }
        }
        return "default"
    }

    override suspend fun writeAppOpMode(
        packageName: String,
        opName: String,
        mode: String,
    ): ShellExecResult {
        val qPkg = DumpsysAndKernelParsers.shellQuote(packageName)
        val qOp = DumpsysAndKernelParsers.shellQuote(opName)
        val qMode = DumpsysAndKernelParsers.shellQuote(mode)
        return execute("cmd appops set $qPkg $qOp $qMode")
    }

    private fun executeViaLibsu(command: String): ShellExecResult {
        val outList = ArrayList<String>()
        val errList = ArrayList<String>()
        val result = Shell.cmd(command).to(outList, errList).exec()
        return ShellExecResult(
            command = command,
            exitCode = result.code,
            stdout = outList,
            stderr = errList,
            executedAtTier = PrivilegeTier.ROOT,
        )
    }

    private suspend fun executeViaShizuku(command: String): ShellExecResult {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            ) as Process

            readProcessOutput(process, command, PrivilegeTier.ADB_SHIZUKU)
        }.getOrElse { error ->
            ShellExecResult(
                command = command,
                exitCode = -1,
                stdout = emptyList(),
                stderr = listOf(error.message ?: "Shizuku execution failed"),
                executedAtTier = PrivilegeTier.ADB_SHIZUKU,
            )
        }
    }

    private suspend fun executeLocalUnprivileged(command: String): ShellExecResult {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command).start()
            readProcessOutput(process, command, PrivilegeTier.BASIC)
        }.getOrElse { error ->
            ShellExecResult(
                command = command,
                exitCode = -1,
                stdout = emptyList(),
                stderr = listOf(error.message ?: "Local shell execution failed"),
                executedAtTier = PrivilegeTier.BASIC,
            )
        }
    }

    private suspend fun readProcessOutput(
        process: Process,
        command: String,
        tier: PrivilegeTier,
    ): ShellExecResult = coroutineScope {
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { it.toList() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { it.toList() }
        }
        val exitCode = process.waitFor()
        ShellExecResult(
            command = command,
            exitCode = exitCode,
            stdout = stdoutDeferred.await(),
            stderr = stderrDeferred.await(),
            executedAtTier = tier,
        )
    }
}
