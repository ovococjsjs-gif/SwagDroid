package com.swagdroid.server

import android.os.Build
import android.os.Process
import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.PrivilegeTier
import com.swagdroid.api.ServerInfo
import com.swagdroid.api.ServerLaunchBackend
import com.swagdroid.api.SettingNamespace
import com.swagdroid.api.SwagDroidIpcContract
import com.swagdroid.server.compat.SystemServiceCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/**
 * Реализация Binder-сервиса `ISwagDroidService.Stub` внутри привилегированного процесса `swagdroid_server`.
 *
 * Независимо от того, запущен ли процесс через Беспроводную отладку (`uid=2000`), Shizuku-мост
 * или через Root (`uid=0`), все клиенты работают с одним и тем же контрактом.
 */
class SwagDroidServiceImpl(
    private val launchBackend: ServerLaunchBackend,
    private val systemCompat: SystemServiceCompat,
    private val callerVerifier: ClientCallerVerifier,
    private val writeSecureSettingsGranted: Boolean,
) : ISwagDroidService.Stub() {

    private val startedAtMillis = System.currentTimeMillis()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun getServerInfoJson(): String {
        callerVerifier.enforceAuthorizedCaller("getServerInfoJson")
        val myUid = Process.myUid()
        val tier = when (myUid) {
            Process.ROOT_UID -> PrivilegeTier.ROOT
            Process.SHELL_UID -> PrivilegeTier.ADB_SHELL
            else -> PrivilegeTier.NONE
        }
        val info = ServerInfo(
            versionCode = SwagDroidIpcContract.SERVER_VERSION_CODE,
            uid = myUid,
            pid = Process.myPid(),
            privilegeTier = tier,
            launchBackend = launchBackend,
            selinuxContext = systemCompat.getCurrentSelinuxContext(),
            sdkInt = Build.VERSION.SDK_INT,
            writeSecureSettingsGranted = writeSecureSettingsGranted,
            uptimeMillis = System.currentTimeMillis() - startedAtMillis,
        )
        return json.encodeToString(info)
    }

    override fun getInstalledPackagesJson(flags: Int, userId: Int): String {
        callerVerifier.enforceAuthorizedCaller("getInstalledPackagesJson")
        val packages = systemCompat.getInstalledPackages(flags, userId)
        return json.encodeToString(packages)
    }

    override fun setApplicationEnabledState(
        packageName: String,
        newState: Int,
        flags: Int,
        userId: Int,
    ): Boolean {
        callerVerifier.enforceAuthorizedCaller("setApplicationEnabledState")
        return systemCompat.setApplicationEnabledSetting(
            packageName = packageName,
            newState = newState,
            flags = flags,
            userId = userId,
            callingPackage = "com.android.shell",
        )
    }

    override fun uninstallPackageForUser(packageName: String, userId: Int, keepData: Boolean): Boolean {
        callerVerifier.enforceAuthorizedCaller("uninstallPackageForUser")
        return systemCompat.uninstallPackageForUser(packageName, userId, keepData)
    }

    override fun restoreExistingPackageForUser(packageName: String, userId: Int): Boolean {
        callerVerifier.enforceAuthorizedCaller("restoreExistingPackageForUser")
        return systemCompat.restoreExistingPackageForUser(packageName, userId)
    }

    override fun getAppOpMode(code: Int, uid: Int, packageName: String): Int {
        callerVerifier.enforceAuthorizedCaller("getAppOpMode")
        return systemCompat.getAppOpMode(code, uid, packageName)
    }

    override fun setAppOpMode(code: Int, uid: Int, packageName: String, mode: Int): Boolean {
        callerVerifier.enforceAuthorizedCaller("setAppOpMode")
        return systemCompat.setAppOpMode(code, uid, packageName, mode)
    }

    override fun getSystemSetting(namespace: String, key: String): String? {
        callerVerifier.enforceAuthorizedCaller("getSystemSetting")
        val ns = SettingNamespace.fromWire(namespace)
        return systemCompat.getSystemSetting(ns, key)
    }

    override fun putSystemSetting(namespace: String, key: String, value: String): Boolean {
        callerVerifier.enforceAuthorizedCaller("putSystemSetting")
        val ns = SettingNamespace.fromWire(namespace)
        return systemCompat.putSystemSetting(ns, key, value)
    }

    override fun trimAllCaches(freeStorageGoalBytes: Long): Long {
        callerVerifier.enforceAuthorizedCaller("trimAllCaches")
        return systemCompat.trimAllCaches(freeStorageGoalBytes)
    }

    override fun setDeviceIdleWhitelist(packageName: String, whitelisted: Boolean): Boolean {
        callerVerifier.enforceAuthorizedCaller("setDeviceIdleWhitelist")
        return systemCompat.setDeviceIdleWhitelist(packageName, whitelisted)
    }

    override fun isDeviceIdleWhitelisted(packageName: String): Boolean {
        callerVerifier.enforceAuthorizedCaller("isDeviceIdleWhitelisted")
        return systemCompat.isDeviceIdleWhitelisted(packageName)
    }

    override fun forceStopPackage(packageName: String, userId: Int): Boolean {
        callerVerifier.enforceAuthorizedCaller("forceStopPackage")
        return systemCompat.forceStopPackage(packageName, userId)
    }

    override fun setPackageSuspended(packageName: String, suspended: Boolean, userId: Int): Boolean {
        callerVerifier.enforceAuthorizedCaller("setPackageSuspended")
        return systemCompat.setPackageSuspended(packageName, suspended, userId)
    }

    override fun getActiveNetworkSocketsJson(): String {
        callerVerifier.enforceAuthorizedCaller("getActiveNetworkSocketsJson")
        val sockets = systemCompat.readActiveNetworkSockets()
        return json.encodeToString(sockets)
    }

    override fun getAllUserIds(): IntArray {
        callerVerifier.enforceAuthorizedCaller("getAllUserIds")
        return systemCompat.getAllUserIds()
    }

    override fun clearPackageCacheOnly(packageName: String, userId: Int): Boolean {
        callerVerifier.enforceAuthorizedCaller("clearPackageCacheOnly")
        return systemCompat.clearPackageCacheOnly(packageName, userId)
    }

    /**
     * Выполняет ТОЛЬКО строго разрешённые диагностические операции из белого списка.
     * Никакого произвольного `sh -c <user_input>`!
     */
    override fun executeWhitelistedDiagnostic(commandKey: String, args: Array<out String>?): String {
        callerVerifier.enforceAuthorizedCaller("executeWhitelistedDiagnostic")
        val safeArgs = args?.filter { it.matches(SAFE_ARG_REGEX) } ?: emptyList()

        val cmd: List<String> = when (commandKey) {
            "dumpsys_batterystats" -> listOf("/system/bin/dumpsys", "batterystats")
            "dumpsys_cpuinfo" -> listOf("/system/bin/dumpsys", "cpuinfo")
            "dumpsys_power" -> listOf("/system/bin/dumpsys", "power")
            "dumpsys_deviceidle" -> listOf("/system/bin/dumpsys", "deviceidle")
            "dumpsys_connectivity" -> listOf("/system/bin/dumpsys", "connectivity")
            "dumpsys_usagestats" -> listOf("/system/bin/dumpsys", "usagestats")
            "ls_data_local_tmp" -> listOf("/system/bin/ls", "-la", "/data/local/tmp")
            "compile_speed_profile" -> {
                val targetPkg = safeArgs.firstOrNull() ?: "-a"
                listOf("/system/bin/cmd", "package", "compile", "-m", "speed-profile", "-f", targetPkg)
            }
            "sm_fstrim" -> listOf("/system/bin/sm", "fstrim")
            "netstat_sockets" -> listOf("/system/bin/cat", "/proc/net/tcp", "/proc/net/tcp6")
            else -> throw SecurityException("Unknown or disallowed diagnostic commandKey: $commandKey")
        }
        return systemCompat.execCommandWithTimeout(cmd, timeoutSeconds = 20L)
    }

    override fun shutdownServer() {
        callerVerifier.enforceAuthorizedCaller("shutdownServer")
        exitProcess(0)
    }

    companion object {
        private val SAFE_ARG_REGEX = Regex("^[a-zA-Z0-9_.-]{1,128}$")
    }
}
