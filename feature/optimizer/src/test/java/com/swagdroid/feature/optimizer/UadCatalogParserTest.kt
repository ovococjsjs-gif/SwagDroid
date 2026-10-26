package com.swagdroid.feature.optimizer

import com.swagdroid.feature.optimizer.data.UadCatalogRepository
import com.swagdroid.feature.optimizer.engine.SystemTweaksController
import com.swagdroid.feature.optimizer.model.OptimizerPortableProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UadCatalogParserTest {

    @Test
    fun `uad_catalog json parses 4000+ entries with zero Unsafe packages`() {
        val assetFile = listOf(
            File("src/main/assets/uad_catalog.json"),
            File("feature/optimizer/src/main/assets/uad_catalog.json"),
        ).firstOrNull { it.exists() }

        assertNotNull("uad_catalog.json must exist in assets", assetFile)
        val raw = assetFile!!.readText(Charsets.UTF_8)
        val entries = UadCatalogRepository.parseCatalogJson(raw)

        assertTrue("Expected > 4000 curated UAD-ng entries, got ${entries.size}", entries.size > 4000)
        assertFalse(
            "Unsafe bootloop-prone entries must never be bundled",
            entries.any { it.removal.equals("Unsafe", ignoreCase = true) },
        )

        val byId = entries.associateBy { it.id }
        val msa = byId["com.miui.msa.global"]
        assertNotNull(msa)
        assertTrue(msa!!.ru && msa.desc.contains("MSA"))

        val gos = byId["com.samsung.android.game.gos"]
        assertNotNull(gos)
        assertTrue(gos!!.ru && gos.desc.contains("GOS"))

        val fbSys = byId["com.facebook.system"]
        assertNotNull(fbSys)
        assertTrue(fbSys!!.ru)
    }

    @Test
    fun `portable JSON profile roundtrips tweaks and suspended packages cleanly`() {
        val original = OptimizerPortableProfile(
            profileName = "Мой пресет Xiaomi",
            exportedAtEpochMs = 1_700_000_000L,
            animationScale = "0.5",
            privateDnsMode = "hostname",
            privateDnsHost = "dns.adguard-dns.com",
            cachedAppsFreezerEnabled = true,
            phantomKillerDisabled = true,
            hiddenRadioScanDisabled = true,
            errorTelemetryDisabled = true,
            hidePasswordChars = true,
            disableWindowBlurs = true,
            hiddenStatusBarIcons = listOf("alarm_clock", "nfc", "volte"),
            suspendedPackages = listOf("com.miui.analytics", "com.miui.msa.global"),
        )
        val json = SystemTweaksController.encodePortableProfile(original)
        val decoded = SystemTweaksController.decodePortableProfile(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `mutateIconBlacklist adds removes and normalizes tokens`() {
        val step1 = SystemTweaksController.mutateIconBlacklist("", "nfc", hide = true)
        assertEquals("nfc", step1)

        val step2 = SystemTweaksController.mutateIconBlacklist(step1!!, "alarm_clock", hide = true)
        assertEquals("alarm_clock,nfc", step2)

        val step3 = SystemTweaksController.mutateIconBlacklist(step2!!, "nfc", hide = false)
        assertEquals("alarm_clock", step3)

        val step4 = SystemTweaksController.mutateIconBlacklist(step3!!, "alarm_clock", hide = false)
        assertNull(step4)
    }
}
