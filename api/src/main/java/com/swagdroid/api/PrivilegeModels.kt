package com.swagdroid.api

import kotlinx.serialization.Serializable

/**
 * Уровень привилегий («Лестница привилегий» SwagDroid).
 */
@Serializable
enum class PrivilegeTier(
    val uid: Int,
    val displayNameRu: String,
    val capabilityPercent: Int,
) {
    NONE(
        uid = -1,
        displayNameRu = "Tier 0: Стандартный (Без повышенных прав)",
        capabilityPercent = 30,
    ),
    ADB_SHELL(
        uid = 2000,
        displayNameRu = "Tier 1: Привилегированный Shell (uid=2000, без ПК и без потери гарантии)",
        capabilityPercent = 88,
    ),
    ROOT(
        uid = 0,
        displayNameRu = "Tier 2: Полный Root (uid=0, Magisk / KernelSU / APatch)",
        capabilityPercent = 100,
    ),
}

/**
 * Источник, через который был запущен единый процесс `swagdroid_server`.
 */
@Serializable
enum class ServerLaunchBackend {
    NONE,
    WIRELESS_ADB_BUILTIN,
    SHIZUKU_BRIDGE,
    ROOT_SU,
}

/**
 * Информация о запущенном экземпляре `swagdroid_server`.
 */
@Serializable
data class ServerInfo(
    val versionCode: Int,
    val uid: Int,
    val pid: Int,
    val privilegeTier: PrivilegeTier,
    val launchBackend: ServerLaunchBackend,
    val selinuxContext: String,
    val sdkInt: Int,
    val writeSecureSettingsGranted: Boolean,
    val uptimeMillis: Long,
)

/**
 * Пространство имён системных настроек Android.
 */
@Serializable
enum class SettingNamespace(val wireName: String) {
    GLOBAL("global"),
    SECURE("secure"),
    SYSTEM("system");

    companion object {
        fun fromWire(value: String): SettingNamespace =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown SettingNamespace: $value")
    }
}

/**
 * Краткая сводка об установленном пакете, возвращаемая сервером.
 */
@Serializable
data class PackageSummary(
    val packageName: String,
    val uid: Int,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val enabledState: Int,
    val isSuspended: Boolean,
    val hasLauncherIcon: Boolean,
    val hasDisabledLauncherComponent: Boolean = false,
    val installerPackageName: String? = null,
    val signingCertSha256: String? = null,
    val sourceApkPath: String? = null,
    val splitApkPaths: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val grantedPermissions: List<String> = emptyList(),
    val requestsOverlayPermission: Boolean,
    val isCriticalProtected: Boolean,
)

/**
 * Запись об активном сетевом соединении из `/proc/net/tcp` или `/proc/net/tcp6`,
 * сопоставленная с UID приложения.
 */
@Serializable
data class ActiveSocketEntry(
    val protocol: String,
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val stateCode: Int,
    val ownerUid: Int,
)

/**
 * Константы для обмена Binder через ContentProvider (по аналогии с Shizuku BinderContainer).
 */
object SwagDroidIpcContract {
    const val HOST_PACKAGE_NAME = "com.swagdroid.app"
    const val BINDER_PROVIDER_AUTHORITY = "com.swagdroid.app.binder.provider"
    const val METHOD_SEND_BINDER = "swagdroid_send_binder"
    const val METHOD_GET_BINDER = "swagdroid_get_binder"
    const val EXTRA_BINDER = "com.swagdroid.api.EXTRA_BINDER"
    const val EXTRA_SERVER_UID = "com.swagdroid.api.EXTRA_SERVER_UID"
    const val SERVER_NICE_NAME = "swagdroid_server"
    const val SERVER_VERSION_CODE = 1
}
