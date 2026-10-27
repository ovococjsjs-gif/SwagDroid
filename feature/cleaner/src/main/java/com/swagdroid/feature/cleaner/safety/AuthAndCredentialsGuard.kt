package com.swagdroid.feature.cleaner.safety

import com.swagdroid.feature.cleaner.model.CredentialShieldConfig

/**
 * Additional deny-lists for known account/credential paths and sensitive packages.
 * Passing these checks does not prove that a file is expendable or recoverable.
 * Final cleanup still requires a reviewed plan and an explicit irreversible-deletion consent.
 */
object AuthAndCredentialsGuard {

    /**
     * Hard-blocked path substrings: NEVER deletable under ANY configuration.
     */
    private val HARD_IMMUTABLE_PATH_MARKERS = listOf(
        "/data/system_ce/",
        "/data/system_de/",
        "accounts_ce.db",
        "accounts_de.db",
        "/data/misc/keystore",
        "/data/misc/credstore",
        "locksettings.db",
        "/shared_prefs/",
        "/databases/",
        "/no_backup/",
        "/app_webview/",
        "/cookies",
        "/login data",
        "/web data", // Chromium/WebView Autofill table!
        "/local storage/",
        "/session storage/",
        "/indexeddb/",
        "tgnet.dat",
        "userconfing.xml",
        "msgstore.db",
        "wa.db",
        "axolotl.db",
        "autofill",
        "passkey",
        "credential",
    )

    /**
     * Password managers, Autofill providers & 2FA Authenticator packages (protected by default).
     */
    val PASSWORD_AND_2FA_PACKAGES = setOf(
        "com.google.android.gms", // Google Password Manager & Autofill
        "com.android.credentialmanager",
        "com.android.keychain",
        "com.x8bit.bitwarden",
        "com.bitwarden.mobile",
        "proton.android.pass",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.agilebits.onepassword",
        "com.dashlane",
        "io.enpass.app",
        "com.samsung.android.samsungpass",
        "com.samsung.android.samsungpassautofill",
        "com.kaspersky.passwordmanager",
        "com.google.android.apps.authenticator2",
        "com.beemdevelopment.aegis",
        "com.twofasapp",
        "com.authy.authy",
        "com.azure.authenticator",
        "com.yandex.key",
        "org.fedorahosted.freeotp",
        "ru.nspk.mirpay",
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel",
    )

    /**
     * Web browsers & WebView engines whose sessions, OAuth tokens & form autofill are protected by default.
     */
    val BROWSER_AND_WEBVIEW_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.yandex.browser",
        "com.yandex.browser.lite",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.gx",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser",
        "com.google.android.webview",
        "com.android.webview",
    )

    /**
     * Vault & 2FA backup file extensions/names that must be preserved across Downloads & Documents.
     */
    private val PROTECTED_VAULT_EXTENSIONS = listOf(
        ".kdbx",
        ".kdb",
        ".keyx",
        ".2fas",
        ".aegis",
        ".1pux",
        ".ovpn",
        ".conf",
        ".pfx",
        ".p12",
        ".pem",
        ".jks",
        ".keystore",
    )

    private val PROTECTED_VAULT_NAME_TOKENS = listOf(
        "bitwarden_export",
        "protonpass",
        "keepass",
        "aegis-backup",
        "2fas-backup",
        "recovery-codes",
        "backup-codes",
        "passwords",
    )

    /**
     * Verifies a shell command NEVER wipes app user data / accounts (`pm clear` is strictly banned).
     */
    fun isShellCommandSafeForAccounts(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (Regex("\\bpm\\s+clear\\b").containsMatchIn(normalized)) return false
        if (Regex("\\bcmd\\s+package\\s+clear\\b").containsMatchIn(normalized)) return false
        if (HARD_IMMUTABLE_PATH_MARKERS.any { normalized.contains(it) }) return false
        return true
    }

    /**
     * Returns false for known protected paths/packages. A true result is NOT a safety guarantee.
     */
    fun isPathSafeToClean(
        rawPath: String,
        config: CredentialShieldConfig = CredentialShieldConfig(),
        packageOwner: String? = null,
        activeAutofillPackage: String? = null,
    ): Boolean {
        val norm = rawPath.replace('\\', '/').lowercase()
        val fileName = norm.substringAfterLast('/')

        // Contour 1: Hard architectural blocklist (NEVER bypassed)
        if (HARD_IMMUTABLE_PATH_MARKERS.any { norm.contains(it) }) {
            return false
        }

        val inferredPkg = packageOwner?.lowercase() ?: extractPackageFromAndroidPath(norm)

        // Contour 2A: Active system Autofill service + Account tokens
        if (config.protectAccountsAndAutofill) {
            if (activeAutofillPackage != null && inferredPkg == activeAutofillPackage.lowercase()) {
                return false
            }
            if (inferredPkg == "com.android.credentialmanager" || inferredPkg == "com.android.keychain") {
                return false
            }
        }

        // Contour 2B: Password Managers, 2FA Authenticators & Payment Wallets
        if (config.protectPasswordManagersAnd2Fa && inferredPkg != null) {
            if (inferredPkg in PASSWORD_AND_2FA_PACKAGES) {
                return false
            }
        }

        // Contour 2C: Browsers & WebView sessions / form autofill
        if (config.protectBrowsersAndWebViewSessions && inferredPkg != null) {
            if (inferredPkg in BROWSER_AND_WEBVIEW_PACKAGES) {
                return false
            }
        }

        // Contour 2D: Password vaults, 2FA exports, VPN & TLS certificates on storage
        if (config.protectVaultAnd2FaBackupFiles) {
            if (PROTECTED_VAULT_EXTENSIONS.any { fileName.endsWith(it) }) {
                return false
            }
            if (PROTECTED_VAULT_NAME_TOKENS.any { fileName.contains(it) }) {
                return false
            }
        }

        return true
    }

    /**
     * Extracts `<packageName>` from `/android/data/<pkg>/...`, `/android/media/<pkg>/...`, or `/data/data/<pkg>/...`.
     */
    fun extractPackageFromAndroidPath(normalizedLowerPath: String): String? {
        val prefixes = listOf(
            "/android/data/",
            "/android/obb/",
            "/android/media/",
            "/data/data/",
            "/data/user/0/",
            "/data/user_de/0/",
        )
        for (prefix in prefixes) {
            val idx = normalizedLowerPath.indexOf(prefix)
            if (idx >= 0) {
                val rest = normalizedLowerPath.substring(idx + prefix.length)
                val seg = rest.substringBefore('/').trim()
                if (seg.contains('.') && !seg.startsWith('.')) {
                    return seg
                }
            }
        }
        return null
    }
}
