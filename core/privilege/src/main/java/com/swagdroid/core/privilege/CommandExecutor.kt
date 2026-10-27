package com.swagdroid.core.privilege

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.PackageEnabledSetting
import com.swagdroid.core.model.PackageStateSnapshot
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.StateReadResult
import com.swagdroid.core.model.isValidBootId
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
) : PrivilegedCommandExecutor {
    private val commandMutex = Mutex()
    private val runner = BoundedProcessRunner()
    private val bootMutex = Mutex()
    @Volatile private var verifiedBootId: String? = null
    private val preferences = context.getSharedPreferences("command_execution_fence", Context.MODE_PRIVATE)
    private val fence = CommandExecutionFence(object : CommandFenceStore {
        override fun read(): CommandTicket? {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val exists = File(dir, "command_execution_fence.xml").exists() || File(dir, "command_execution_fence.xml.bak").exists()
            return CommandFenceRecord.decode(preferences.all, exists)
        }

        override fun write(ticket: CommandTicket?) {
            val edit = preferences.edit().clear()
            CommandFenceRecord.encode(ticket).forEach { (key, value) -> edit.putString(key, value) }
            check(edit.commit()) { "Защитный журнал команды не записан" }
        }
    }, { verifiedBootId })

    private val shizukuNewProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    override fun currentTier(): PrivilegeTier = privilegeManager.state.value.activeTier
    override fun currentUserId(): Int = AndroidUidScope.userId(Process.myUid())

    override suspend fun currentBootId(): String? = withContext(Dispatchers.IO) {
        verifiedBootId ?: bootMutex.withLock {
            verifiedBootId ?: run {
                val direct = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
                val directId = direct?.let { "kernel:${it.lowercase()}" }
                if (isValidBootId(directId)) {
                    verifiedBootId = directId
                } else if (currentTier() != PrivilegeTier.BASIC) {
                    // Fixed read-only bootstrap, never arbitrary user input and never a mutation.
                    // It can run while the fence is closed to verify whether a real reboot occurred.
                    val result = runTransport(SystemReadQuery.Fixed.KERNEL_BOOT_ID.command, currentTier(), 3_000)
                    val probed = "kernel:${result.stdoutText.lowercase()}"
                    if (result.isSuccess && !result.outputTruncated && isValidBootId(probed)) verifiedBootId = probed
                }
                verifiedBootId // Valid for this process lifetime; a kernel reboot also kills this process.
            }
        }
    }

    override suspend fun executionBlockReason(): String? {
        currentBootId()
        return fence.blockingReason()
    }

    override suspend fun execute(command: String): ShellExecResult = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val tier = currentTier()
            fun notStarted(reason: String) = ShellExecResult(command, -1, emptyList(), listOf(reason), tier, CommandCompletion.NOT_STARTED)
            currentBootId()
            fence.blockingReason()?.let { return@withLock notStarted(it) }
            val ticket = try { fence.begin() } catch (error: Exception) {
                return@withLock notStarted(error.message ?: "Журнал команды недоступен")
            }
            var completion = CommandCompletion.UNKNOWN
            var failure: Throwable? = null
            try {
                val timeout = if (command.contains("package compile") || command.contains("fstrim")) 180_000L else 35_000L
                val result = runTransport(command, tier, timeout)
                completion = result.completion
                if (result.completion == CommandCompletion.UNKNOWN) {
                    result.copy(stderr = result.stderr + "Новые команды и откат заблокированы до подтверждённой перезагрузки устройства.")
                } else result
            } catch (error: Exception) {
                failure = error
                throw error
            } finally {
                // On failure to commit, leave the fence unresolved and never report a verified success.
                try { fence.finish(ticket, completion) }
                catch (error: Exception) {
                    if (failure == null) throw error else failure.addSuppressed(error)
                }
            }
        }
    }

    override suspend fun query(query: SystemReadQuery): ShellExecResult = withContext(Dispatchers.IO) {
        // Read-only observations remain possible in BASIC mode and while mutation recovery is pending.
        // They do not clear or replace the durable mutation fence.
        runTransport(query.command, currentTier(), 35_000)
    }

    private suspend fun runTransport(command: String, tier: PrivilegeTier, timeout: Long): ShellExecResult =
        runner.run(command, tier, timeout, CommandCompletion.UNKNOWN) { ensureMayStart ->
            val standaloneRootGranted = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
            val useShizuku = tier == PrivilegeTier.ADB_SHIZUKU || (tier == PrivilegeTier.ROOT && !standaloneRootGranted)
            if (useShizuku) {
                val method = shizukuNewProcessMethod ?: throw CommandNotStartedException("Shizuku process bridge недоступен")
                val expectedUid = if (tier == PrivilegeTier.ROOT) 0 else 2000
                // All possibly blocking Binder preflight calls run inside the bounded startup worker.
                val authorized = runCatching {
                    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.getUid() == expectedUid
                }.getOrDefault(false)
                if (!authorized) throw CommandNotStartedException("Shizuku недоступен, UID изменился или разрешение отозвано")
                ensureMayStart() // Do not dispatch after a preflight RPC returned too late.
                method.invoke(null, arrayOf("sh", "-c", withExpectedUid(command, expectedUid)), null, null) as java.lang.Process
            } else {
                ensureMayStart()
                try {
                    if (tier == PrivilegeTier.ROOT) ProcessBuilder("su", "-c", withExpectedUid(command, 0)).start()
                    else ProcessBuilder("sh", "-c", command).start()
                } catch (error: java.io.IOException) {
                    throw CommandNotStartedException(error.message ?: "Не удалось запустить процесс")
                }
            }
        }

    override suspend fun readSettingState(namespace: SettingNamespace, key: String): StateReadResult<String> = withContext(Dispatchers.IO) {
        if (!SystemStateParsers.settingKeyPattern.matches(key)) return@withContext StateReadResult.Unavailable("Некорректное имя параметра")
        val uri = when (namespace) {
            SettingNamespace.GLOBAL -> Settings.Global.CONTENT_URI
            SettingNamespace.SECURE -> Settings.Secure.CONTENT_URI
            SettingNamespace.SYSTEM -> Settings.System.CONTENT_URI
        }
        try {
            // The provider is scoped to the same Android user as every write below.
            // A null cursor / exception is NOT an absent key. Literal "null" and empty strings remain values.
            val cursor = context.contentResolver.query(uri, arrayOf("value"), "name=?", arrayOf(key), null)
                ?: return@withContext StateReadResult.Unavailable("Settings provider не вернул данные для ${namespace.cliName}:$key")
            cursor.use {
                if (!it.moveToFirst()) return@withContext StateReadResult.Missing
                if (it.count != 1 || it.isNull(0)) return@withContext StateReadResult.Unavailable("Неоднозначное значение ${namespace.cliName}:$key")
                StateReadResult.Value(it.getString(0))
            }
        } catch (error: Exception) {
            StateReadResult.Unavailable("Не удалось прочитать ${namespace.cliName}:$key: ${error.message ?: "нет доступа"}")
        }
    }

    override suspend fun writeSetting(namespace: SettingNamespace, key: String, value: String?): ShellExecResult {
        if (!SystemStateParsers.settingKeyPattern.matches(key)) return invalid("Некорректное имя параметра")
        if (!SystemStateParsers.canRoundTripSettingValue(value)) return invalid("Значение слишком велико или содержит NUL; командный транспорт не поддерживает точную запись")
        val verb = if (value == null) "delete" else "put"
        val suffix = value?.let { " ${quote(it)}" }.orEmpty()
        return execute("settings --user ${currentUserId()} $verb ${namespace.cliName} ${quote(key)}$suffix")
    }

    override suspend fun readPackageSnapshot(packageName: String): StateReadResult<PackageStateSnapshot> = withContext(Dispatchers.IO) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val info = packageInfo.applicationInfo ?: return@withContext StateReadResult.Unavailable("Нет ApplicationInfo пакета")
            if (AndroidUidScope.userId(info.uid) != currentUserId()) {
                return@withContext StateReadResult.Unavailable("Пакет принадлежит другому Android-пользователю")
            }
            val enabled = when (context.packageManager.getApplicationEnabledSetting(packageName)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> PackageEnabledSetting.DEFAULT
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> PackageEnabledSetting.ENABLED
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> PackageEnabledSetting.DISABLED
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> PackageEnabledSetting.DISABLED_USER
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> PackageEnabledSetting.DISABLED_UNTIL_USED
                else -> return@withContext StateReadResult.Unavailable("Неизвестный enabled-state пакета")
            }
            if ((enabled == PackageEnabledSetting.ENABLED && !info.enabled) ||
                (enabled in setOf(PackageEnabledSetting.DISABLED, PackageEnabledSetting.DISABLED_USER, PackageEnabledSetting.DISABLED_UNTIL_USED) && info.enabled)
            ) return@withContext StateReadResult.Unavailable("Противоречивое состояние пакета; повторите чтение")
            StateReadResult.Value(PackageStateSnapshot(enabled, info.flags and ApplicationInfo.FLAG_SUSPENDED != 0, info.enabled, info.uid, packageInfo.lastUpdateTime))
        } catch (_: PackageManager.NameNotFoundException) {
            StateReadResult.Missing
        } catch (error: Exception) {
            StateReadResult.Unavailable("Пакет $packageName не прочитан: ${error.message ?: "нет доступа"}")
        }
    }

    override suspend fun validatePackageWrite(packageName: String, target: PackageStateSnapshot): StateReadResult<Unit> {
        if (!PACKAGE_NAME.matches(packageName) || !target.isConsistent) return StateReadResult.Unavailable("Некорректный пакет или противоречивый снимок состояния")
        val current = readPackageSnapshot(packageName)
        if (current !is StateReadResult.Value || current.value.uid != target.uid || current.value.lastUpdateTime != target.lastUpdateTime) {
            return StateReadResult.Unavailable("Пакет недоступен, обновлён или переустановлен; нужен новый план")
        }
        // DEFAULT can disable an app whose manifest changed. Only explicit ENABLED + unsuspend
        // is unconditionally non-disabling for a protected role.
        if (target.enabledSetting != PackageEnabledSetting.ENABLED || target.suspended) {
            try {
                val protected = mutableSetOf(context.packageName)
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapTo(protected) { it.activityInfo.packageName }
                readSetting(SettingNamespace.SECURE, "default_input_method")?.substringBefore('/')?.let(protected::add)
                readSetting(SettingNamespace.SECURE, "enabled_accessibility_services")
                    ?.split(':')?.map { it.substringBefore('/') }?.let(protected::addAll)
                if (packageName in protected) return StateReadResult.Unavailable("Нельзя отключить SwagDroid, домашний экран, активную клавиатуру или службу доступности")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return StateReadResult.Unavailable("Не удалось проверить критичные роли пакета: ${error.message}")
            }
        }
        return StateReadResult.Value(Unit)
    }

    override suspend fun writePackageSnapshot(packageName: String, target: PackageStateSnapshot): ShellExecResult {
        val validation = validatePackageWrite(packageName, target)
        if (validation is StateReadResult.Unavailable) return invalid(validation.reason)
        if (validation !is StateReadResult.Value) return invalid("Пакет недоступен")
        val user = currentUserId()
        val suspendCommand = if (target.suspended) "suspend" else "unsuspend"
        // && does not hide an earlier error. Vault journals and verifies both independent properties.
        return execute("cmd package $suspendCommand --user $user ${quote(packageName)} && pm ${target.enabledSetting.command} --user $user ${quote(packageName)}")
    }

    override suspend fun readAppOpState(packageName: String, opName: String): StateReadResult<String> {
        if (!PACKAGE_NAME.matches(packageName) || !APP_OP.matches(opName)) return StateReadResult.Unavailable("Некорректный AppOp или пакет")
        when (val packageState = readPackageSnapshot(packageName)) {
            StateReadResult.Missing -> return StateReadResult.Missing
            is StateReadResult.Unavailable -> return packageState
            is StateReadResult.Value -> Unit
        }
        return SystemStateParsers.appOp(query(SystemReadQuery.AppOp(currentUserId(), packageName, opName)), opName)
    }

    override suspend fun writeAppOpMode(packageName: String, opName: String, mode: String): ShellExecResult {
        if (!PACKAGE_NAME.matches(packageName) || !APP_OP.matches(opName) || mode !in SystemStateParsers.appOpModes) {
            return invalid("Некорректный AppOp, пакет или режим")
        }
        return execute("cmd appops set --user ${currentUserId()} ${quote(packageName)} ${quote(opName)} ${quote(mode)}")
    }

    private fun invalid(reason: String) = ShellExecResult("", -1, emptyList(), listOf(reason), currentTier(), CommandCompletion.NOT_STARTED)
    private fun quote(value: String): String = DumpsysAndKernelParsers.shellQuote(value)
    private fun withExpectedUid(command: String, uid: Int): String =
        """[ "$(id -u)" = $uid ] || { echo 'Privilege UID changed; command not dispatched' >&2; exit 126; };
$command"""

    companion object {
        private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*$")
        private val APP_OP = Regex("^[A-Z][A-Z0-9_]*$")
    }
}
