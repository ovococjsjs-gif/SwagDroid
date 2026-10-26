package com.swagdroid.api

/**
 * Жёсткий предохранитель от «окирпичивания» телефона (Bootloop / Soft-Brick Guard).
 *
 * Проверяется КАК в клиентском UI, ТАК И принудительно внутри привилегированного `swagdroid_server`
 * перед любым вызовом отключения, заморозки или удаления пакета.
 */
object CriticalPackageGuard {

    /**
     * Фундаментальные системные пакеты, отключение которых гарантированно приводит к бутлупу,
     * чёрному экрану или потере возможности разблокировать устройство.
     */
    private val HARD_BLOCKED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.providers.contacts",
        "com.android.providers.downloads",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.inputdevices",
        "com.android.keychain",
        "com.android.shell",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.vpndialogs",
        "com.android.externalstorage",
        // Критичные компоненты производителей (Xiaomi / Samsung / OnePlus / Realme), вызывающие бутлуп:
        "com.miui.securitycenter",      // На MIUI/HyperOS удаление SecurityCenter вызывает вечный бутлуп!
        "com.xiaomi.finddevice",        // Вызывает краш SystemServer на MIUI/HyperOS при загрузке
        "com.miui.home",                // Стандартный рабочий стол MIUI (без альтернативного лаунчера)
        "com.sec.android.app.launcher", // Стандартный рабочий стол Samsung OneUI
        "com.samsung.android.incallui", // Экран вызова Samsung
        SwagDroidIpcContract.HOST_PACKAGE_NAME,
    )

    /**
     * Пакеты повышенного риска (требуют явного подтверждения, например GMS или системный дайлер).
     */
    private val HIGH_CAUTION_PACKAGES: Set<String> = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.android.mms",
        "com.google.android.dialer",
        "com.google.android.inputmethod.latin",
    )

    fun isHardBlocked(
        packageName: String,
        currentDefaultLauncher: String? = null,
        currentDefaultInputMethod: String? = null,
    ): Boolean {
        val normalized = packageName.trim()
        if (normalized in HARD_BLOCKED_PACKAGES) return true
        // Запрещаем отключать единственный активный лаунчер или активную клавиатуру
        if (!currentDefaultLauncher.isNullOrBlank() && normalized == currentDefaultLauncher) return true
        if (!currentDefaultInputMethod.isNullOrBlank() && normalized == currentDefaultInputMethod) return true
        return false
    }

    fun isHighCaution(packageName: String): Boolean =
        packageName.trim() in HIGH_CAUTION_PACKAGES

    fun enforceNotCritical(
        packageName: String,
        currentDefaultLauncher: String? = null,
        currentDefaultInputMethod: String? = null,
    ) {
        if (isHardBlocked(packageName, currentDefaultLauncher, currentDefaultInputMethod)) {
            throw SecurityException(
                "CriticalPackageGuard: Пакет '$packageName' является критически важным для запуска Android " +
                    "или работы оболочки. Его отключение/удаление заблокировано для защиты от бутлупа."
            )
        }
    }
}
