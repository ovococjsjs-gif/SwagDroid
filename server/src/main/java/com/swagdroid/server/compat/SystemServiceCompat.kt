package com.swagdroid.server.compat

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.swagdroid.api.ActiveSocketEntry
import com.swagdroid.api.CriticalPackageGuard
import com.swagdroid.api.PackageSummary
import com.swagdroid.api.SettingNamespace
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Тонкий слой совместимости (`compat/`) для прямого вызова системных Binder-сервисов Android
 * (`IPackageManager`, `IAppOpsService`, `IDeviceIdleController`, `IActivityManager`)
 * с учётом различий сигнатур в Android 10–16 (API 29–36) и надёжным fallback на `/system/bin/cmd`.
 */
class SystemServiceCompat {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }
        }
    }

    private fun getSystemServiceBinder(name: String): IBinder? = runCatching {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getDeclaredMethod("getService", String::class.java)
        getService.invoke(null, name) as? IBinder
    }.getOrNull()

    private fun getIPackageManager(): Any? = runCatching {
        val binder = getSystemServiceBinder("package") ?: return@runCatching null
        val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)
    }.getOrNull()

    private fun getIAppOpsService(): Any? = runCatching {
        val binder = getSystemServiceBinder("appops") ?: return@runCatching null
        val stubClass = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
        val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)
    }.getOrNull()

    private fun getIDeviceIdleController(): Any? = runCatching {
        val binder = getSystemServiceBinder("deviceidle") ?: return@runCatching null
        val stubClass = Class.forName("android.os.IDeviceIdleController\$Stub")
        val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)
    }.getOrNull()

    /**
     * Получает UID пакета через `IPackageManager.getPackageUid` с учётом смены сигнатуры в API 33+.
     */
    fun getPackageUid(packageName: String, userId: Int = 0): Int {
        val ipm = getIPackageManager()
        if (ipm != null) {
            runCatching {
                val method = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ (API 33+): getPackageUid(String packageName, long flags, int userId)
                    ipm.javaClass.getMethod(
                        "getPackageUid",
                        String::class.java,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).also { return it.invoke(ipm, packageName, 0L, userId) as Int }
                } else {
                    // Android 10-12 (API 29-32): getPackageUid(String packageName, int flags, int userId)
                    ipm.javaClass.getMethod(
                        "getPackageUid",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).also { return it.invoke(ipm, packageName, 0, userId) as Int }
                }
            }
        }
        // Fallback через cmd package
        val out = execCommandWithTimeout(listOf("/system/bin/cmd", "package", "list", "packages", "-U", packageName))
        val match = Regex("package:${Regex.escape(packageName)}\\s+uid:(\\d+)").find(out)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Вычисляет SHA-256 отпечаток сертификата подписи пакета для защиты от подмены клиента.
     */
    fun getPackageSigningCertSha256(packageName: String, userId: Int = 0): String? = runCatching {
        val ipm = getIPackageManager() ?: return@runCatching null
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val pkgInfo = invokeGetPackageInfo(ipm, packageName, flags, userId) ?: return@runCatching null
        val signatures = pkgInfo.signingInfo?.apkContentsSigners
        val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun getInstalledPackages(flags: Int, userId: Int): List<PackageSummary> {
        val ipm = getIPackageManager()
        if (ipm != null) {
            runCatching {
                val parceledSlice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val m = ipm.javaClass.getMethod(
                        "getInstalledPackages",
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    m.invoke(ipm, flags.toLong(), userId)
                } else {
                    val m = ipm.javaClass.getMethod(
                        "getInstalledPackages",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    m.invoke(ipm, flags, userId)
                }
                if (parceledSlice != null) {
                    val getListMethod = parceledSlice.javaClass.getMethod("getList")
                    val pkgList = getListMethod.invoke(parceledSlice) as? List<PackageInfo> ?: emptyList()
                    return pkgList.map { info -> mapPackageInfoToSummary(info) }
                }
            }
        }
        return emptyList()
    }

    private fun mapPackageInfoToSummary(info: PackageInfo): PackageSummary {
        val appInfo = info.applicationInfo
        val appFlags = appInfo?.flags ?: 0
        val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val isSuspended = (appFlags and ApplicationInfo.FLAG_SUSPENDED) != 0
        val reqPerms = info.requestedPermissions?.toList() ?: emptyList()
        val reqPermFlags = info.requestedPermissionsFlags ?: IntArray(0)
        val grantedPerms = reqPerms.filterIndexed { idx, _ ->
            idx < reqPermFlags.size && (reqPermFlags[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }
        return PackageSummary(
            packageName = info.packageName,
            uid = appInfo?.uid ?: -1,
            versionName = info.versionName ?: "unknown",
            versionCode = info.longVersionCode,
            isSystemApp = isSystem,
            isUpdatedSystemApp = isUpdatedSystem,
            enabledState = if (appInfo?.enabled == true) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            },
            isSuspended = isSuspended,
            hasLauncherIcon = true,
            sourceApkPath = appInfo?.sourceDir,
            splitApkPaths = appInfo?.splitSourceDirs?.toList() ?: emptyList(),
            requestedPermissions = reqPerms,
            grantedPermissions = grantedPerms,
            requestsOverlayPermission = "android.permission.SYSTEM_ALERT_WINDOW" in reqPerms,
            isCriticalProtected = CriticalPackageGuard.isHardBlocked(info.packageName),
        )
    }

    private fun invokeGetPackageInfo(ipm: Any, packageName: String, flags: Int, userId: Int): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val m = ipm.javaClass.getMethod(
                    "getPackageInfo",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(ipm, packageName, flags.toLong(), userId) as? PackageInfo
            } else {
                val m = ipm.javaClass.getMethod(
                    "getPackageInfo",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(ipm, packageName, flags, userId) as? PackageInfo
            }
        }.getOrNull()

    /**
     * Прямой вызов `IPackageManager.setApplicationEnabledSetting` с fallback на `cmd package`.
     */
    fun setApplicationEnabledSetting(
        packageName: String,
        newState: Int,
        flags: Int,
        userId: Int,
        callingPackage: String,
    ): Boolean {
        CriticalPackageGuard.enforceNotCritical(packageName)
        val ipm = getIPackageManager()
        if (ipm != null) {
            val directSuccess = runCatching {
                val m = ipm.javaClass.getMethod(
                    "setApplicationEnabledSetting",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                m.invoke(ipm, packageName, newState, flags, userId, callingPackage)
                true
            }.getOrDefault(false)
            if (directSuccess) return true
        }

        val stateCmd = when (newState) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "enable"
            else -> "disable-user"
        }
        val out = execCommandWithTimeout(
            listOf("/system/bin/cmd", "package", stateCmd, "--user", userId.toString(), packageName)
        )
        return out.contains("new state", ignoreCase = true)
    }

    /**
     * Удаление пакета для пользователя (`pm uninstall --user <userId> [-k] <pkg>`).
     * APK системного приложения остаётся в `/system`, поэтому его всегда можно вернуть через `restoreExistingPackageForUser`.
     */
    fun uninstallPackageForUser(packageName: String, userId: Int, keepData: Boolean): Boolean {
        CriticalPackageGuard.enforceNotCritical(packageName)
        val cmd = buildList {
            add("/system/bin/cmd")
            add("package")
            add("uninstall")
            if (keepData) add("-k")
            add("--user")
            add(userId.toString())
            add(packageName)
        }
        val out = execCommandWithTimeout(cmd)
        return out.contains("Success", ignoreCase = true)
    }

    /**
     * Восстановление ранее удалённого системного пакета (`cmd package install-existing --user <userId> <pkg>`).
     */
    fun restoreExistingPackageForUser(packageName: String, userId: Int): Boolean {
        val out = execCommandWithTimeout(
            listOf("/system/bin/cmd", "package", "install-existing", "--user", userId.toString(), packageName)
        )
        return out.contains("installed for user", ignoreCase = true)
    }

    /**
     * Чтение режима AppOps через `IAppOpsService.checkOperation`.
     */
    fun getAppOpMode(code: Int, uid: Int, packageName: String): Int {
        val appOps = getIAppOpsService()
        if (appOps != null) {
            runCatching {
                val m = appOps.javaClass.getMethod(
                    "checkOperation",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                return m.invoke(appOps, code, uid, packageName) as Int
            }
        }
        return 0
    }

    /**
     * Изменение режима AppOps напрямую через `IAppOpsService.setMode`.
     */
    fun setAppOpMode(code: Int, uid: Int, packageName: String, mode: Int): Boolean {
        CriticalPackageGuard.enforceNotCritical(packageName)
        val appOps = getIAppOpsService()
        if (appOps != null) {
            val ok = runCatching {
                val m = appOps.javaClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(appOps, code, uid, packageName, mode)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Чтение системной настройки (`Settings.Global / Secure / System`).
     */
    fun getSystemSetting(namespace: SettingNamespace, key: String): String? {
        require(key.matches(SAFE_SETTING_KEY_REGEX)) { "Invalid setting key: $key" }
        val out = execCommandWithTimeout(
            listOf("/system/bin/cmd", "settings", "get", namespace.wireName, key)
        ).trim()
        return if (out == "null" || out.isEmpty()) null else out
    }

    /**
     * Запись системной настройки (`Settings.Global / Secure / System`).
     */
    fun putSystemSetting(namespace: SettingNamespace, key: String, value: String): Boolean {
        require(key.matches(SAFE_SETTING_KEY_REGEX)) { "Invalid setting key: $key" }
        val cmd = if (value.isEmpty()) {
            listOf("/system/bin/cmd", "settings", "delete", namespace.wireName, key)
        } else {
            listOf("/system/bin/cmd", "settings", "put", namespace.wireName, key, value)
        }
        execCommandWithTimeout(cmd)
        return true
    }

    /**
     * Мгновенная очистка кэша всех приложений через `IPackageManager.freeStorageAndNotify` / `pm trim-caches`.
     */
    fun trimAllCaches(freeStorageGoalBytes: Long = Long.MAX_VALUE): Long {
        val ipm = getIPackageManager()
        if (ipm != null) {
            val invoked = runCatching {
                val m = ipm.javaClass.getMethod(
                    "freeStorageAndNotify",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Class.forName("android.content.pm.IPackageDataObserver"),
                )
                m.invoke(ipm, null, freeStorageGoalBytes, null)
                true
            }.getOrDefault(false)
            if (invoked) return freeStorageGoalBytes
        }
        execCommandWithTimeout(
            listOf("/system/bin/cmd", "package", "trim-caches", "999999999999999")
        )
        return freeStorageGoalBytes
    }

    /**
     * Управление белым списком энергосбережения Doze через `IDeviceIdleController`.
     */
    fun setDeviceIdleWhitelist(packageName: String, whitelisted: Boolean): Boolean {
        val controller = getIDeviceIdleController()
        if (controller != null) {
            val ok = runCatching {
                val methodName = if (whitelisted) "addPowerSaveWhitelistApp" else "removePowerSaveWhitelistApp"
                val m = controller.javaClass.getMethod(methodName, String::class.java)
                m.invoke(controller, packageName)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val op = if (whitelisted) "+$packageName" else "-$packageName"
        execCommandWithTimeout(listOf("/system/bin/dumpsys", "deviceidle", "whitelist", op))
        return true
    }

    fun isDeviceIdleWhitelisted(packageName: String): Boolean {
        val controller = getIDeviceIdleController()
        if (controller != null) {
            runCatching {
                val m = controller.javaClass.getMethod("isPowerSaveWhitelistApp", String::class.java)
                return m.invoke(controller, packageName) as Boolean
            }
        }
        val out = execCommandWithTimeout(listOf("/system/bin/dumpsys", "deviceidle", "whitelist"))
        return out.lines().any { it.contains(",$packageName,") }
    }

    /**
     * Принудительная остановка всех процессов пакета (`IActivityManager.forceStopPackage` / `am force-stop`).
     */
    fun forceStopPackage(packageName: String, userId: Int): Boolean {
        CriticalPackageGuard.enforceNotCritical(packageName)
        execCommandWithTimeout(
            listOf("/system/bin/cmd", "activity", "force-stop", "--user", userId.toString(), packageName)
        )
        return true
    }

    /**
     * Заморозка / разморозка пакета через `cmd package suspend / unsuspend`.
     */
    fun setPackageSuspended(packageName: String, suspended: Boolean, userId: Int): Boolean {
        CriticalPackageGuard.enforceNotCritical(packageName)
        val subCmd = if (suspended) "suspend" else "unsuspend"
        val out = execCommandWithTimeout(
            listOf("/system/bin/cmd", "package", subCmd, "--user", userId.toString(), packageName)
        )
        return out.contains("new suspended state", ignoreCase = true) || !out.contains("Error", ignoreCase = true)
    }

    /**
     * Чтение активных TCP/TCP6 соединений из `/proc/net/tcp` и `/proc/net/tcp6`
     * с привязкой к `ownerUid` (доступно для `shell` uid=2000 и `root` uid=0).
     */
    fun readActiveNetworkSockets(): List<ActiveSocketEntry> {
        val results = mutableListOf<ActiveSocketEntry>()
        parseProcNetFile(File("/proc/net/tcp"), "tcp", results)
        parseProcNetFile(File("/proc/net/tcp6"), "tcp6", results)
        return results
    }

    /**
     * Получает список всех профилей пользователей на устройстве (основной 0, рабочий профиль 10, клоны 999).
     * Необходимо, чтобы CorpseFinder не считал «трупом» папку приложения, установленного во втором профиле!
     */
    fun getAllUserIds(): IntArray {
        val out = execCommandWithTimeout(listOf("/system/bin/cmd", "user", "list"))
        val regex = Regex("UserInfo\\{(\\d+):")
        val ids = regex.findAll(out)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
        return if (ids.isEmpty()) intArrayOf(0) else ids.toIntArray()
    }

    /**
     * Выборочная очистка ТОЛЬКО кэша конкретного приложения (`cmd package clear --cache-only --user <userId> <pkg>`).
     */
    fun clearPackageCacheOnly(packageName: String, userId: Int): Boolean {
        val out = execCommandWithTimeout(
            listOf(
                "/system/bin/cmd",
                "package",
                "clear",
                "--cache-only",
                "--user",
                userId.toString(),
                packageName,
            )
        )
        return out.contains("Success", ignoreCase = true)
    }

    private fun parseProcNetFile(
        file: File,
        protocol: String,
        out: MutableList<ActiveSocketEntry>,
    ) {
        if (!file.canRead()) return
        runCatching {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8) {
                        val localParts = parts[1].split(":")
                        val remParts = parts[2].split(":")
                        val stateCode = parts[3].toIntOrNull(16) ?: 0
                        val uid = parts[7].toIntOrNull() ?: -1
                        if (localParts.size == 2 && remParts.size == 2) {
                            val localPort = localParts[1].toIntOrNull(16) ?: 0
                            val remotePort = remParts[1].toIntOrNull(16) ?: 0
                            // Оставляем только не-loopback соединения или активные ESTABLISHED (0x01) / SYN_SENT (0x02)
                            if (remotePort > 0) {
                                out += ActiveSocketEntry(
                                    protocol = protocol,
                                    localAddress = decodeHexIp(localParts[0]),
                                    localPort = localPort,
                                    remoteAddress = decodeHexIp(remParts[0]),
                                    remotePort = remotePort,
                                    stateCode = stateCode,
                                    ownerUid = uid,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun decodeHexIp(hex: String): String {
        if (hex.length == 8) {
            val b0 = hex.substring(6, 8).toIntOrNull(16) ?: 0
            val b1 = hex.substring(4, 6).toIntOrNull(16) ?: 0
            val b2 = hex.substring(2, 4).toIntOrNull(16) ?: 0
            val b3 = hex.substring(0, 2).toIntOrNull(16) ?: 0
            return "$b0.$b1.$b2.$b3"
        }
        if (hex.length == 32 && hex.startsWith("0000000000000000FFFF0000", ignoreCase = true)) {
            return decodeHexIp(hex.substring(24, 32))
        }
        return hex
    }

    /**
     * Выдаёт хост-приложению `WRITE_SECURE_SETTINGS` сразу при старте `swagdroid_server`.
     * Это ключевой механизм для честного автозапуска после перезагрузки без ПК!
     */
    fun grantWriteSecureSettingsToHost(hostPackageName: String): Boolean {
        val out = execCommandWithTimeout(
            listOf(
                "/system/bin/cmd",
                "package",
                "grant",
                hostPackageName,
                "android.permission.WRITE_SECURE_SETTINGS",
            )
        )
        return out.isEmpty() || !out.contains("Exception", ignoreCase = true)
    }

    fun getCurrentSelinuxContext(): String =
        execCommandWithTimeout(listOf("/system/bin/id", "-Z")).trim().ifEmpty { "u:r:shell:s0" }

    fun execCommandWithTimeout(command: List<String>, timeoutSeconds: Long = 10L): String = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        output.toString()
    }.getOrDefault("")

    companion object {
        private val SAFE_SETTING_KEY_REGEX = Regex("^[a-zA-Z0-9_.-]{1,128}$")
    }
}
