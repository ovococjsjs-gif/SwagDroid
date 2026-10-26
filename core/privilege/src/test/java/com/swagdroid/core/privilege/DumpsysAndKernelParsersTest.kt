package com.swagdroid.core.privilege

import com.swagdroid.core.model.RootProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpsysAndKernelParsersTest {

    @Test
    fun `shellQuote safely escapes single quotes`() {
        assertEquals("'simple'", DumpsysAndKernelParsers.shellQuote("simple"))
        assertEquals("'it'\\''s safe; rm -rf /'", DumpsysAndKernelParsers.shellQuote("it's safe; rm -rf /"))
    }

    @Test
    fun `parseSwapFromMemInfo extracts SwapTotal and SwapUsed in bytes`() {
        val sample = listOf(
            "MemTotal:       11842100 kB",
            "MemFree:          452100 kB",
            "SwapTotal:       4194304 kB",
            "SwapFree:        3145728 kB",
        )
        val (totalBytes, usedBytes) = DumpsysAndKernelParsers.parseSwapFromMemInfo(sample)
        assertEquals(4194304L * 1024L, totalBytes)
        assertEquals(1048576L * 1024L, usedBytes)
    }

    @Test
    fun `parseDumpsysBattery extracts charge counter and cycle count`() {
        val sample = listOf(
            "Current Battery Service state:",
            "  AC powered: false",
            "  USB powered: true",
            "  Charge counter: 3845000",
            "  Cycle count: 214",
            "  Max charging current: 6000000",
        )
        val parsed = DumpsysAndKernelParsers.parseDumpsysBattery(sample)
        assertEquals(3845000L, parsed.chargeCounterUah)
        assertEquals(214, parsed.cycleCount)
        assertEquals(6000000L, parsed.maxChargingCurrentUa)
    }

    @Test
    fun `parseKernelGkiInfo detects GKI 2_0 kernels accurately`() {
        val (gki14, branch14) = DumpsysAndKernelParsers.parseKernelGkiInfo(
            "6.1.75-android14-11-g8a9c0f1234ab"
        )
        assertTrue(gki14)
        assertEquals("android14-6.1", branch14)

        val (legacy, legacyBranch) = DumpsysAndKernelParsers.parseKernelGkiInfo(
            "4.19.157-perf-g123456"
        )
        assertFalse(legacy)
        assertNull(legacyBranch)
    }

    @Test
    fun `identifyRootVersion detects KernelSU, APatch, and Magisk`() {
        val (ksu, _) = DumpsysAndKernelParsers.identifyRootVersion("11980:KERNELSU", false, false)
        assertEquals(RootProvider.KERNEL_SU, ksu)

        val (ap, _) = DumpsysAndKernelParsers.identifyRootVersion("10763:APatch", false, true)
        assertEquals(RootProvider.APATCH, ap)

        val (magisk, ver) = DumpsysAndKernelParsers.identifyRootVersion("28.1:MAGISKSU", false, false)
        assertEquals(RootProvider.MAGISK, magisk)
        assertEquals("28.1:MAGISKSU", ver)
    }
}
