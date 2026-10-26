package com.swagdroid.backend.root

import android.content.Context
import android.content.pm.PackageManager
import com.swagdroid.api.ServerLaunchBackend
import com.swagdroid.api.SwagDroidIpcContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Опциональный мост (`ShizukuBridgeLauncher`):
 * Если у пользователя на телефоне УЖЕ установлен и запущен Shizuku или Sui,
 * мы не заставляем его заново проходить пейринг по Wi-Fi, а поднимаем наш `swagdroid_server`
 * напрямую через существующий Shizuku за 0.1 секунды!
 */
class ShizukuBridgeLauncher(
    private val context: Context,
) {
    fun isShizukuRunning(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    fun isShizukuPermissionGranted(): Boolean = runCatching {
        if (!isShizukuRunning()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission(requestCode: Int = 2001) {
        if (isShizukuRunning() && !isShizukuPermissionGranted()) {
            Shizuku.requestPermission(requestCode)
        }
    }

    /**
     * Запускает наш единый `swagdroid_server` через привилегированный процесс Shizuku.
     */
    suspend fun bootstrapServerViaShizuku(
        onLog: (String) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isShizukuRunning()) { "Сервис Shizuku не запущен на устройстве" }
            check(isShizukuPermissionGranted()) { "Не выдано разрешение Shizuku для SwagDroid" }

            onLog("[ShizukuBridge] Shizuku активен (uid=${Shizuku.getUid()}), запуск ${SwagDroidIpcContract.SERVER_NICE_NAME}...")
            val apkPath = context.applicationInfo.sourceDir
            val cmd = arrayOf(
                "sh",
                "-c",
                "setsid app_process -Djava.class.path=\"$apkPath\" /system/bin " +
                    "--nice-name=${SwagDroidIpcContract.SERVER_NICE_NAME} " +
                    "com.swagdroid.server.ServerMain --backend=${ServerLaunchBackend.SHIZUKU_BRIDGE.name} >/dev/null 2>&1 &",
            )

            // Вызов Shizuku.newProcess через публичный/скрытый метод API
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(null, cmd, null, null) as Process
            process.waitFor()
            onLog("[ShizukuBridge] Демон ${SwagDroidIpcContract.SERVER_NICE_NAME} запущен через мост Shizuku!")
        }
    }
}
