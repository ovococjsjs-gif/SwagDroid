package com.swagdroid.feature.fixer

import com.swagdroid.feature.fixer.catalog.FixerCatalog
import com.swagdroid.feature.fixer.engine.FixerDiagnosticEngine
import com.swagdroid.feature.fixer.model.PhoneSubsystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixerCatalogTest {

    @Test
    fun `all playbooks have unique ids and cover all 12 subsystems`() {
        val all = FixerCatalog.allPlaybooks
        assertTrue("Expected at least 50 real playbooks, got ${all.size}", all.size >= 50)

        val uniqueIds = all.map { it.id }.toSet()
        assertEquals("Duplicate playbook IDs found!", all.size, uniqueIds.size)

        for (subsystem in PhoneSubsystem.entries) {
            val count = all.count { it.subsystem == subsystem }
            assertTrue("Subsystem $subsystem has too few playbooks ($count)", count >= 3)
        }
    }

    @Test
    fun `symptom search finds expected playbooks in everyday Russian`() {
        val all = FixerCatalog.allPlaybooks
        assertTrue(all.any { it.id == "net_captive_portal_mirrors" && it.matchesQuery("без интернета") })
        assertTrue(all.any { it.id == "pkg_ghost_uninstalled_conflict" && it.matchesQuery("не ставится апк") })
        assertTrue(all.any { it.id == "disp_grayscale_inversion_colors" && it.matchesQuery("серый экран") })
        assertTrue(all.any { it.id == "aud_stereo_balance_and_mono_bug" && it.matchesQuery("один наушник тише") })
        assertTrue(all.any { it.id == "inp_triple_tap_magnification_lag" && it.matchesQuery("input lag") })
        assertTrue(all.any { it.id == "oem_xiaomi_msa_ads_cure" && it.matchesQuery("реклама сяоми") })
    }

    @Test
    fun `parseGhostUninstalledPackages isolates DELETE_KEEP_DATA remnants`() {
        val allWithUninstalled = """
            package:com.android.settings
            package:com.whatsapp
            package:com.example.modgame
            package:org.telegram.messenger
        """.trimIndent()

        val activeInstalled = """
            package:com.android.settings
            package:org.telegram.messenger
        """.trimIndent()

        val ghosts = FixerDiagnosticEngine.parseGhostUninstalledPackages(allWithUninstalled, activeInstalled)
        assertEquals(listOf("com.example.modgame", "com.whatsapp"), ghosts)
    }

    @Test
    fun `parseWmOverride extracts override density and size only when present`() {
        val stockDensity = "Physical density: 440\n"
        assertNull(FixerDiagnosticEngine.parseWmOverride(stockDensity))

        val overriddenDensity = "Physical density: 440\nOverride density: 520\n"
        assertEquals("520", FixerDiagnosticEngine.parseWmOverride(overriddenDensity))

        val overriddenSize = "Physical size: 1080x2400\nOverride size: 720x1600\n"
        assertEquals("720x1600", FixerDiagnosticEngine.parseWmOverride(overriddenSize))
    }

    @Test
    fun `parseStagingSessionIds extracts session numbers`() {
        val raw = """
            sessionId = 10492811, isReady = false
            29817412: com.heavy.game
        """.trimIndent()
        assertEquals(listOf("10492811", "29817412"), FixerDiagnosticEngine.parseStagingSessionIds(raw))
    }
}
