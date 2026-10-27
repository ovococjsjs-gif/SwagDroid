package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.engine.CleanerScanAndPurgeEngine
import com.swagdroid.feature.cleaner.model.CredentialShieldConfig
import com.swagdroid.feature.cleaner.safety.AuthAndCredentialsGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CleanerSafetyAndEnginesTest {

    @Test
    fun `default CredentialShieldConfig enables all account password and autofill guards by default`() {
        val defaultShield = CredentialShieldConfig()
        assertTrue(defaultShield.protectAccountsAndAutofill)
        assertTrue(defaultShield.protectPasswordManagersAnd2Fa)
        assertTrue(defaultShield.protectBrowsersAndWebViewSessions)
        assertTrue(defaultShield.protectVaultAnd2FaBackupFiles)
    }

    @Test
    fun `Contour 1 hard-blocks pm clear cookies autofill and account DBs even if all toggles off`() {
        val allTogglesOff = CredentialShieldConfig(
            protectAccountsAndAutofill = false,
            protectPasswordManagersAnd2Fa = false,
            protectBrowsersAndWebViewSessions = false,
            protectVaultAnd2FaBackupFiles = false,
        )

        assertFalse(AuthAndCredentialsGuard.isShellCommandSafeForAccounts("pm clear com.telegram.messenger"))
        assertFalse(AuthAndCredentialsGuard.isShellCommandSafeForAccounts("cmd package clear com.android.chrome"))
        assertTrue(AuthAndCredentialsGuard.isShellCommandSafeForAccounts("pm trim-caches 999999999999999999"))

        val forbiddenPaths = listOf(
            "/data/system_ce/0/accounts_ce.db",
            "/data/data/com.example.app/shared_prefs/auth_token.xml",
            "/data/data/com.android.chrome/app_webview/Default/Cookies",
            "/data/data/com.android.chrome/app_webview/Default/Login Data",
            "/data/data/com.android.chrome/app_webview/Default/Web Data",
            "/data/data/org.telegram.messenger/files/tgnet.dat",
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15",
        )
        for (p in forbiddenPaths) {
            assertFalse("Expected hard block on $p", AuthAndCredentialsGuard.isPathSafeToClean(p, allTogglesOff))
        }
    }

    @Test
    fun `default Contour 2 protects browsers password managers 2FA and kdbx vaults while allowing real cache`() {
        val shield = CredentialShieldConfig()

        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Android/data/com.android.chrome/cache/img01.tmp",
                shield,
            ),
        )
        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Android/data/com.x8bit.bitwarden/cache/temp.bin",
                shield,
            ),
        )
        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Android/data/com.yandex.key/cache/icon.png",
                shield,
            ),
        )
        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Download/personal_vault.kdbx",
                shield,
            ),
        )
        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Download/2fas-backup-2026.2fas",
                shield,
            ),
        )
        assertFalse(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Android/data/my.custom.autofill/cache/a.tmp",
                shield,
                activeAutofillPackage = "my.custom.autofill",
            ),
        )

        // These paths are not known credential paths; that does NOT prove they are safe to delete.
        assertTrue(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Android/data/com.heavy.shooter/cache/shader_01.tmp",
                shield,
            ),
        )
        assertTrue(
            AuthAndCredentialsGuard.isPathSafeToClean(
                "/sdcard/Telegram/Telegram Video/round_video_991.mp4",
                shield,
            ),
        )
    }

    @Test
    fun `CorpseFinder flags uninstalled game folder and spares installed or password manager packages`() {
        val installed = setOf("org.telegram.messenger", "com.whatsapp")
        val shield = CredentialShieldConfig()

        assertTrue(
            CleanerScanAndPurgeEngine.isOrphanCorpseCandidate("com.old.uninstalledgame", installed, shield),
        )
        assertFalse(
            CleanerScanAndPurgeEngine.isOrphanCorpseCandidate("org.telegram.messenger", installed, shield),
        )
        assertFalse(
            CleanerScanAndPurgeEngine.isOrphanCorpseCandidate("com.x8bit.bitwarden", installed, shield),
        )
    }

    @Test
    fun `Deduplicator keeps clean original name over numbered copy and computes dHash Hamming distance`() {
        val copy = File("/tmp/IMG_2026 (1).jpg")
        val orig = File("/tmp/IMG_2026.jpg")
        val sorted = CleanerScanAndPurgeEngine.sortKeepBestOriginalFirst(listOf(copy, orig))
        assertEquals("IMG_2026.jpg", sorted.first().name)

        val lumaA = IntArray(72) { idx -> idx * 3 }
        val lumaIdentical = IntArray(72) { idx -> idx * 3 }
        val h1 = CleanerScanAndPurgeEngine.computeDHashFrom9x8Luma(lumaA)
        val h2 = CleanerScanAndPurgeEngine.computeDHashFrom9x8Luma(lumaIdentical)
        assertEquals(0, CleanerScanAndPurgeEngine.hammingDistance64(h1, h2))
    }
}
