package com.swagdroid.feature.dashboard

import com.swagdroid.core.model.BatteryPassport
import com.swagdroid.core.model.DevicePassport
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.MemoryPassport
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RootAdvisorReport
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.StoragePassport
import com.swagdroid.core.model.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthScoreCalculatorTest {

    private fun samplePassport(
        storageUsedGb: Long = 50L,
        storageTotalGb: Long = 128L,
        tempC: Float = 32.0f,
    ): DevicePassport {
        val gb = 1024L * 1024L * 1024L
        return DevicePassport(
            battery = BatteryPassport(
                levelPercent = 82,
                isCharging = false,
                temperatureCelsius = tempC,
                voltageMv = 4120,
                currentNowMicroAmps = -250_000L,
                chargeCounterMah = 3900,
                fullChargeEstimatedMah = 4756,
                designCapacityMah = 5000,
                estimatedWearHealthPercent = 95,
                cycleCount = 120,
                technology = "Li-Poly",
                healthStatusLabel = "Отличное",
            ),
            storage = StoragePassport(
                totalBytes = storageTotalGb * gb,
                usedBytes = storageUsedGb * gb,
                freeBytes = (storageTotalGb - storageUsedGb) * gb,
            ),
            memory = MemoryPassport(
                totalRamBytes = 12L * gb,
                availableRamBytes = 5L * gb,
                isLowMemory = false,
                zramTotalBytes = 4L * gb,
                zramUsedBytes = 1L * gb,
            ),
            advisor = RootAdvisorReport(
                brand = "Xiaomi",
                deviceModel = "2311DRK48G",
                codename = "duchamp",
                socHardware = "MT6897",
                androidRelease = "15",
                sdkInt = 35,
                securityPatch = "2026-09-01",
                kernelVersion = "6.1.75-android14-11",
                isGki2Compatible = true,
                gkiBranch = "android14-6.1",
                isAbSeamlessDevice = true,
                activeSlot = "_a",
                bootloaderLocked = true,
                verifiedBootState = "green",
                selinuxEnforcing = true,
                knoxTriggeredBit = null,
                recommendedStrategyTitle = "KernelSU",
                recommendedStrategyBody = "GKI 2.0",
                cautionNotes = emptyList(),
                guidedSteps = emptyList(),
            ),
        )
    }

    @Test
    fun `healthy device with ADB privilege and active checkpoint scores 100`() {
        val summary = HealthScoreCalculator.evaluate(
            passport = samplePassport(),
            privilege = PrivilegeState(activeTier = PrivilegeTier.ADB_SHIZUKU),
            snapshots = listOf(
                VaultSnapshot(
                    id = 1L,
                    title = "Baseline",
                    reason = "Init",
                    source = FeatureSource.MANUAL,
                    createdAtEpochMs = 1000L,
                    privilegeTierUsed = PrivilegeTier.ADB_SHIZUKU,
                    status = SnapshotStatus.ACTIVE,
                    operations = emptyList(),
                )
            ),
        )
        assertEquals(100, summary.score)
        assertTrue(summary.signals.isEmpty())
    }

    @Test
    fun `nearly full storage and overheating lower score and emit signals`() {
        val summary = HealthScoreCalculator.evaluate(
            passport = samplePassport(storageUsedGb = 122L, storageTotalGb = 128L, tempC = 44.5f),
            privilege = PrivilegeState(activeTier = PrivilegeTier.BASIC),
            snapshots = emptyList(),
        )
        assertTrue(summary.score <= 50)
        assertTrue(summary.signals.any { it.id == "storage_critical" })
        assertTrue(summary.signals.any { it.id == "thermal_critical" })
    }
}
