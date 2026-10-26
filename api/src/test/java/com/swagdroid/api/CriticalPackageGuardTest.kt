package com.swagdroid.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalPackageGuardTest {

    @Test
    fun `hard blocked system packages cannot be disabled or uninstalled`() {
        assertTrue(CriticalPackageGuard.isHardBlocked("android"))
        assertTrue(CriticalPackageGuard.isHardBlocked("com.android.systemui"))
        assertTrue(CriticalPackageGuard.isHardBlocked("com.miui.securitycenter"))
        assertTrue(CriticalPackageGuard.isHardBlocked(SwagDroidIpcContract.HOST_PACKAGE_NAME))
    }

    @Test
    fun `current default launcher and keyboard are dynamically protected`() {
        assertTrue(
            CriticalPackageGuard.isHardBlocked(
                packageName = "com.teslacoilsw.launcher",
                currentDefaultLauncher = "com.teslacoilsw.launcher",
            )
        )
        assertFalse(
            CriticalPackageGuard.isHardBlocked(
                packageName = "com.miui.msa.global",
                currentDefaultLauncher = "com.teslacoilsw.launcher",
            )
        )
    }

    @Test(expected = SecurityException::class)
    fun `enforceNotCritical throws SecurityException on critical package`() {
        CriticalPackageGuard.enforceNotCritical("com.android.systemui")
    }
}
