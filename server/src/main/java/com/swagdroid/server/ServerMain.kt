package com.swagdroid.server

import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.swagdroid.api.ServerLaunchBackend
import com.swagdroid.api.SwagDroidIpcContract
import com.swagdroid.server.compat.SystemServiceCompat

/**
 * Точка входа привилегированного демона `swagdroid_server`.
 *
 * Запускается командой:
 * `setsid app_process -Djava.class.path=<apk> /system/bin --nice-name=swagdroid_server com.swagdroid.server.ServerMain --backend=WIRELESS_ADB_BUILTIN`
 *
 * Ключевые инженерные решения:
 * 1. Запуск в новой сессии (`setsid` / игнорирование SIGHUP): когда пользователь выключает Wi-Fi
 *    или закрывается сессия `adbd`, демон `swagdroid_server` ПРОДОЛЖАЕТ работать вплоть до перезагрузки телефона!
 * 2. При старте автоматически выдаёт приложению `android.permission.WRITE_SECURE_SETTINGS`,
 *    что позволяет приложению после ребута самостоятельно включить `adb_wifi_enabled` при появлении Wi-Fi.
 * 3. Передаёт свой `IBinder` хост-приложению через `BinderContainerProvider` (`ContentProvider.call`).
 */
object ServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val backendArg = args.firstOrNull { it.startsWith("--backend=") }
            ?.substringAfter("--backend=")
            ?: ServerLaunchBackend.WIRELESS_ADB_BUILTIN.name

        val certSha256Arg = args.firstOrNull { it.startsWith("--cert-sha256=") }
            ?.substringAfter("--cert-sha256=")

        val launchBackend = runCatching { ServerLaunchBackend.valueOf(backendArg) }
            .getOrDefault(ServerLaunchBackend.WIRELESS_ADB_BUILTIN)

        val systemCompat = SystemServiceCompat()

        // Сразу выдаём WRITE_SECURE_SETTINGS нашему приложению для автозапуска после перезагрузки
        val writeSecureGranted = systemCompat.grantWriteSecureSettingsToHost(
            SwagDroidIpcContract.HOST_PACKAGE_NAME
        )

        val callerVerifier = ClientCallerVerifier(
            systemCompat = systemCompat,
            hostPackageName = SwagDroidIpcContract.HOST_PACKAGE_NAME,
            expectedSignatureSha256 = certSha256Arg,
        )

        val serviceBinder = SwagDroidServiceImpl(
            launchBackend = launchBackend,
            systemCompat = systemCompat,
            callerVerifier = callerVerifier,
            writeSecureSettingsGranted = writeSecureGranted,
        )

        // Передаём Binder в ContentProvider приложения
        publishBinderToHostApp(serviceBinder)

        println(
            "SwagDroidServer started successfully: uid=${Process.myUid()}, " +
                "pid=${Process.myPid()}, backend=$launchBackend, writeSecureSettings=$writeSecureGranted"
        )

        Looper.loop()
    }

    /**
     * Передаёт живой `IBinder` в `BinderContainerProvider` внутри процесса `com.swagdroid.app`.
     */
    private fun publishBinderToHostApp(serviceBinder: IBinder) {
        runCatching {
            val extras = Bundle().apply {
                putBinder(SwagDroidIpcContract.EXTRA_BINDER, serviceBinder)
                putInt(SwagDroidIpcContract.EXTRA_SERVER_UID, Process.myUid())
            }
            val amClass = Class.forName("android.app.ActivityManagerNative")
            val getDefault = amClass.getDeclaredMethod("getDefault")
            val iam = getDefault.invoke(null) ?: return@runCatching

            // Будим процесс приложения и вызываем ContentProvider через системную утилиту content или IActivityManager
            val binderHolderMethod = iam.javaClass.methods.firstOrNull {
                it.name == "getContentProviderExternal"
            }
            if (binderHolderMethod != null) {
                val holder = binderHolderMethod.invoke(
                    iam,
                    SwagDroidIpcContract.BINDER_PROVIDER_AUTHORITY,
                    0,
                    null,
                    "*swagdroid*",
                )
                val providerField = holder?.javaClass?.getDeclaredField("provider")
                val provider = providerField?.get(holder)
                if (provider != null) {
                    val callMethods = provider.javaClass.methods.filter { it.name == "call" }
                    for (callMethod in callMethods) {
                        val invoked = runCatching {
                            when (callMethod.parameterTypes.size) {
                                4 -> callMethod.invoke(
                                    provider,
                                    "com.android.shell",
                                    SwagDroidIpcContract.METHOD_SEND_BINDER,
                                    null,
                                    extras,
                                )
                                5 -> callMethod.invoke(
                                    provider,
                                    "com.android.shell",
                                    SwagDroidIpcContract.BINDER_PROVIDER_AUTHORITY,
                                    SwagDroidIpcContract.METHOD_SEND_BINDER,
                                    null,
                                    extras,
                                )
                                else -> null
                            }
                            true
                        }.getOrDefault(false)
                        if (invoked) break
                    }
                }
            }
        }
    }
}
