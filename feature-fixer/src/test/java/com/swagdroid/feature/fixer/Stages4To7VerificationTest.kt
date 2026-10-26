package com.swagdroid.feature.fixer

import com.swagdroid.adb.OemVendorProfile
import com.swagdroid.feature.adblock.BindhostsAndIfwLabEngine
import com.swagdroid.feature.adblock.PrivateDnsProviderProfile
import com.swagdroid.feature.optimizer.DebloatSafetyBadge
import com.swagdroid.feature.optimizer.OptimizerCatalogEngine
import com.swagdroid.core.rollback.RollbackJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class Stages4To7VerificationTest {

    @Test
    fun `stage4 NextDNS requires valid user config ID and IFW lab blocks GMS and Consent components`() {
        val nextDns = PrivateDnsProviderProfile.NextDnsCustom("a1b2c3")
        assertEquals("a1b2c3.dns.nextdns.io", nextDns.resolveDotHostname())

        val ifwEngine = BindhostsAndIfwLabEngine()
        // Попытка применить IFW к Google Play Services должна выбрасывать ошибку
        val gmsResult = ifwEngine.generateSingleAppIfwRuleXml(
            targetPackage = "com.google.android.gms",
            adActivityComponents = listOf("com.google.android.gms.ads.AdActivity"),
        )
        assertTrue(gmsResult.isFailure)

        // Для обычной игры ConsentActivity отфильтровывается, а AdActivity попадает в XML
        val gameResult = ifwEngine.generateSingleAppIfwRuleXml(
            targetPackage = "com.example.puzzle",
            adActivityComponents = listOf(
                "com.google.android.gms.ads.AdActivity",
                "com.google.ads.consent.ConsentActivity",
            ),
        )
        assertTrue(gameResult.isSuccess)
        val xml = gameResult.getOrThrow()
        assertTrue(xml.contains("com.example.puzzle/com.google.android.gms.ads.AdActivity"))
        assertFalse(xml.contains("ConsentActivity"))

        // Проверка детектора падения в exit-info после применения IFW
        assertTrue(
            ifwEngine.didAppCrashAfterIfwRule(
                exitInfoDump = "ApplicationExitInfo #0: reason=CRASH subreason=0 status=0",
                ruleAppliedAtEpochMs = 1700000000000L,
            )
        )
    }

    @Test
    fun `stage5 toggle passports check OEM vendor compatibility and debloater enforces 5-color badges`() {
        val tempDir = Files.createTempDirectory("swagdroid_opt_test").toFile()
        try {
            val catalog = OptimizerCatalogEngine(RollbackJournal(tempDir))
            val ramPlusToggle = catalog.togglePassports.first { it.id == "samsung_ram_plus_zram_target" }

            assertTrue(ramPlusToggle.isSupportedOnDevice(OemVendorProfile.SAMSUNG_ONEUI, 34))
            assertFalse(ramPlusToggle.isSupportedOnDevice(OemVendorProfile.XIAOMI_HYPEROS_MIUI, 34))

            assertEquals(
                DebloatSafetyBadge.UNSAFE_PROTECTED,
                catalog.resolveSafetyBadgeForPackage("com.miui.securitycenter"),
            )
            assertEquals(
                DebloatSafetyBadge.RECOMMENDED,
                catalog.resolveSafetyBadgeForPackage("com.miui.msa.global"),
            )
            assertEquals(
                DebloatSafetyBadge.EXPERT,
                catalog.resolveSafetyBadgeForPackage("com.samsung.android.game.gos"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `stage6 telephony doctor masks IMSI ICCID phone numbers and Cell ID in reports`() {
        val doctor = TelephonyDoctorEngine()
        val rawDump = """
            SubscriberId=250019876543210
            IccId=89701012345678901234
            Line1Number=+79991234567
            CellIdentityLte:{ mCid=12345678 mTac=4321 }
        """.trimIndent()

        val scrubbed = doctor.maskSensitiveTelephonyReport(rawDump)
        assertFalse(scrubbed.contains("250019876543210"))
        assertFalse(scrubbed.contains("89701012345678901234"))
        assertFalse(scrubbed.contains("+79991234567"))
        assertFalse(scrubbed.contains("mCid=12345678"))
        assertTrue(scrubbed.contains("REDACTED_ICCID"))
        assertTrue(scrubbed.contains("REDACTED_IMSI"))
        assertTrue(scrubbed.contains("REDACTED_PHONE"))
        assertTrue(scrubbed.contains("REDACTED_CELL"))
    }

    @Test
    fun `stage7 semantic setup export and import filters unsupported packages on new phone`() {
        val engine = HardwarePassportAndPresetsEngine()
        val exported = SemanticSetupExportBundle(
            schema = 1,
            exportedFromDeviceModel = "Xiaomi 14",
            exportedAtEpochMs = 1_700_000_000_000L,
            adblockMode = "PRIVATE_DNS_DOT",
            adblockProviderId = "adguard_dns",
            desiredToggles = mapOf("animations_snappy_0_5x" to true),
            desiredSuspendedPackages = listOf("com.miui.msa.global", "com.facebook.katana"),
        )
        val rawJson = engine.exportSemanticSetupToJson(exported)

        // Импортируем на Samsung, где нет com.miui.msa.global, но есть com.facebook.katana
        val (sanitized, warnings) = engine.parseAndValidateImportedSetup(
            rawJson = rawJson,
            targetVendor = OemVendorProfile.SAMSUNG_ONEUI,
            targetSdkInt = 35,
            installedPackagesOnTarget = setOf("com.facebook.katana"),
        ).getOrThrow()

        assertEquals(listOf("com.facebook.katana"), sanitized.desiredSuspendedPackages)
        assertEquals(1, warnings.size)
    }
}
