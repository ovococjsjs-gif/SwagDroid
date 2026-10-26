package com.swagdroid.feature.optimizer

import com.swagdroid.feature.optimizer.data.UadCatalogRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
