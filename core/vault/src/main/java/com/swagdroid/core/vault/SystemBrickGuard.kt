package com.swagdroid.core.vault

import com.swagdroid.core.model.PackageStateTarget

/**
 * Immutable Anti-Bootloop Shield (`SystemBrickGuard`).
 *
 * Prevents ANY module (Debloater, Quarantine, Fixer, or imported JSON profiles) from ever
 * disabling or suspending boot-critical Android framework packages, package installers,
 * telephony core, privilege managers (Shizuku/Sui/Magisk/KernelSU/APatch), or SwagDroid itself.
 */
object SystemBrickGuard {

    val PROTECTED_CORE_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.providers.downloads",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.ext.services",
        "com.google.android.ext.services",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.shell",
        "com.android.inputmethod.latin",
        "com.swagdroid",
        "moe.shizuku.privileged.api",
        "rikka.sui",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.bmax.apatch",
    )

    private val SAFE_PKG_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun validatePackageMutation(
        packageName: String,
        targetState: PackageStateTarget,
    ): String? {
        val clean = packageName.trim()
        if (!SAFE_PKG_NAME_REGEX.matches(clean)) {
            return "Отклонено защитой целостности: некорректный идентификатор пакета «$clean»"
        }
        if (targetState != PackageStateTarget.ENABLED && clean.lowercase() in PROTECTED_CORE_PACKAGES) {
            return "Защита от бутлупа (SystemBrickGuard): критический системный пакет «$clean» запрещено отключать или замораживать!"
        }
        return null
    }
}
