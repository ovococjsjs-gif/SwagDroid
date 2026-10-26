package com.swagdroid

import android.app.Application
import android.os.Build
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class SwagDroidApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installOfflineCrashBlackBox()
    }

    /**
     * Installs a 100% local, zero-telemetry uncaught exception black-box logger (`swag_last_crash.log`)
     * so users can inspect or share OEM-specific binder/ROM diagnostics without any cloud SDK.
     */
    private fun installOfflineCrashBlackBox() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("=== SwagDroid v${BuildConfig.VERSION_NAME} Offline Crash Black-Box ===")
                    appendLine("Timestamp: $ts")
                    appendLine("Device: ${Build.BRAND} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine(sw.toString().take(16_384))
                }
                File(filesDir, "swag_last_crash.log").writeText(report, Charsets.UTF_8)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20),
            )
        }
    }
}
