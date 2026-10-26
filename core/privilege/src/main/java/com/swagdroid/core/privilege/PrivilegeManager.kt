package com.swagdroid.core.privilege

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RootProvider
import com.swagdroid.core.model.ShizukuStatus
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivilegeManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_REQUEST_CODE = 0x57A6
        private val SU_CANDIDATE_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/ksud",
            "/data/adb/ap/bin/apd",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        PrivilegeState(
            isWirelessAdbSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        ),
    )
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private val isSuiInitialized: Boolean = runCatching {
        Sui.init(context.packageName)
    }.getOrDefault(false)

    private val pendingPermissionCallback = AtomicReference<((Boolean) -> Unit)?>(null)

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        scope.launch { refreshPrivilegeState(requestRootPopup = false) }
        pendingPermissionCallback.getAndSet(null)?.invoke(granted)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch { refreshPrivilegeState(requestRootPopup = false) }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch { refreshPrivilegeState(requestRootPopup = false) }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        scope.launch {
            refreshPrivilegeState(requestRootPopup = false)
        }
    }

    suspend fun refreshPrivilegeState(requestRootPopup: Boolean = false) = withContext(Dispatchers.IO) {
        _state.update { it.copy(isChecking = true) }

        val devEnabled = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1
        }.getOrDefault(false)

        val (shizukuStatus, shizukuUid) = inspectShizukuStatus()

        var rootProvider = RootProvider.NONE
        var rootVersion: String? = null

        val hasRootCandidate = SU_CANDIDATE_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
        val alreadyGrantedRoot = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)

        if (requestRootPopup || alreadyGrantedRoot || (hasRootCandidate && requestRootPopup)) {
            runCatching {
                val shell = Shell.getShell()
                if (shell.isRoot) {
                    val verRes = Shell.cmd("su -v").exec()
                    val ksuExists = Shell.cmd("[ -e /data/adb/ksu ]").exec().isSuccess
                    val apExists = Shell.cmd("[ -e /data/adb/ap ]").exec().isSuccess
                    val (provider, ver) = DumpsysAndKernelParsers.identifyRootVersion(
                        suVersionOutput = verRes.out.joinToString(" "),
                        hasKsuDaemon = ksuExists,
                        hasApatchDaemon = apExists,
                    )
                    rootProvider = provider
                    rootVersion = ver
                }
            }
        } else if (shizukuStatus == ShizukuStatus.READY && shizukuUid == 0) {
            rootProvider = RootProvider.MAGISK
            rootVersion = if (isSuiInitialized) "Sui (UID 0)" else "Shizuku Root (UID 0)"
        }

        val activeTier = when {
            rootProvider != RootProvider.NONE -> PrivilegeTier.ROOT
            shizukuStatus == ShizukuStatus.READY -> PrivilegeTier.ADB_SHIZUKU
            else -> PrivilegeTier.BASIC
        }

        _state.update {
            it.copy(
                activeTier = activeTier,
                shizukuStatus = shizukuStatus,
                shizukuUid = shizukuUid,
                isSuiActive = isSuiInitialized,
                rootProvider = rootProvider,
                rootVersion = rootVersion,
                isWirelessAdbSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                isDeveloperOptionsEnabled = devEnabled,
                isChecking = false,
                lastCheckEpochMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Requests Shizuku / Sui runtime authorization safely without leaking duplicate listeners.
     */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit = {}) {
        runCatching {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                onResult(false)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                scope.launch { refreshPrivilegeState(requestRootPopup = false) }
                onResult(true)
                return
            }
            pendingPermissionCallback.set(onResult)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }.onFailure {
            pendingPermissionCallback.set(null)
            onResult(false)
        }
    }

    /**
     * Triggers an explicit Root access prompt (`su`) via libsu.
     */
    suspend fun requestRootAccess(): Boolean {
        refreshPrivilegeState(requestRootPopup = true)
        return _state.value.activeTier == PrivilegeTier.ROOT
    }

    fun buildDeveloperOptionsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildAboutPhoneIntent(): Intent {
        return Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildOpenOrGetShizukuIntent(): Intent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        return (launchIntent ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"),
        )).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun inspectShizukuStatus(): Pair<ShizukuStatus, Int?> {
        val binderAlive = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (binderAlive) {
            // Always verify runtime permission explicitly (even when Sui binder is initialized!)
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            return if (granted) {
                val uid = runCatching { Shizuku.getUid() }.getOrNull()
                ShizukuStatus.READY to uid
            } else {
                ShizukuStatus.PERMISSION_REQUIRED to null
            }
        }

        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)

        return if (installed) {
            ShizukuStatus.INSTALLED_NOT_RUNNING to null
        } else {
            ShizukuStatus.NOT_INSTALLED to null
        }
    }
}
