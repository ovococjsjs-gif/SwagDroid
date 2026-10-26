package com.swagdroid.backend.root

import android.content.Context
import com.swagdroid.api.ServerLaunchBackend
import com.swagdroid.api.SwagDroidIpcContract
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Распознанный тип установленного Root-менеджера.
 */
enum class DetectedRootSolution(val displayName: String) {
    NONE("Root не обнаружен"),
    KERNEL_SU("KernelSU / KernelSU-Next (Ядерный Root)"),
    APATCH("APatch (KernelPatch)"),
    MAGISK("Magisk"),
    GENERIC_SU("Стандартный su-бинарник"),
}

/**
 * Запуск того же самого `swagdroid_server` (`com.swagdroid.server.ServerMain`)
 * через Root (`su`, `uid=0`) с использованием `libsu`.
 */
class RootServerLauncher(
    private val context: Context,
) {
    /**
     * Проверяет наличие Root-менеджера на устройстве без блокировки UI.
     */
    suspend fun detectRootSolution(): DetectedRootSolution = withContext(Dispatchers.IO) {
        val hasSuBinary = SU_SEARCH_PATHS.any { File(it, "su").exists() }
        if (!hasSuBinary) return@withContext DetectedRootSolution.NONE

        val shell = Shell.getShell()
        if (!shell.isRoot) return@withContext DetectedRootSolution.NONE

        val versionOutput = Shell.cmd("su -v ; ls -d /data/adb/ksu /data/adb/ap /data/adb/magisk 2>/dev/null")
            .exec()
            .out
            .joinToString("\n")
            .lowercase()

        when {
            "kernelsu" in versionOutput || "/data/adb/ksu" in versionOutput -> DetectedRootSolution.KERNEL_SU
            "apatch" in versionOutput || "/data/adb/ap" in versionOutput -> DetectedRootSolution.APATCH
            "magisk" in versionOutput || "/data/adb/magisk" in versionOutput -> DetectedRootSolution.MAGISK
            else -> DetectedRootSolution.GENERIC_SU
        }
    }

    /**
     * Запускает единый демон `swagdroid_server` от имени `uid=0` (`ROOT_SU`).
     */
    suspend fun launchServerViaSu(
        hostCertSha256: String? = null,
        onLog: (String) -> Unit = {},
    ): Result<DetectedRootSolution> = withContext(Dispatchers.IO) {
        runCatching {
            val solution = detectRootSolution()
            check(solution != DetectedRootSolution.NONE) {
                "Root-доступ (su) не обнаружен или пользователем отклонено окно выдачи прав Superuser"
            }

            onLog("[Root] Обнаружен ${solution.displayName}, запуск ${SwagDroidIpcContract.SERVER_NICE_NAME}...")
            val apkPath = context.applicationInfo.sourceDir
            val certArg = if (!hostCertSha256.isNullOrBlank()) "--cert-sha256=$hostCertSha256" else ""

            val cmd = buildString {
                append("setsid app_process ")
                append("-Djava.class.path=\"$apkPath\" ")
                append("/system/bin ")
                append("--nice-name=${SwagDroidIpcContract.SERVER_NICE_NAME} ")
                append("com.swagdroid.server.ServerMain ")
                append("--backend=${ServerLaunchBackend.ROOT_SU.name} ")
                if (certArg.isNotEmpty()) append("$certArg ")
                append(">/dev/null 2>&1 &")
            }

            val result = Shell.cmd(cmd).exec()
            check(result.isSuccess) {
                "Ошибка старта сервера через su (код ${result.code}): ${result.err.joinToString()}"
            }
            onLog("[Root] Команда старта демона успешно выполнена!")
            solution
        }
    }

    companion object {
        private val SU_SEARCH_PATHS = listOf(
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/vendor/bin",
            "/apex/com.android.runtime/bin",
            "/data/local/xbin",
            "/data/local/bin",
        )
    }
}
