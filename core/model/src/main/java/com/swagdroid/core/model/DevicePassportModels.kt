package com.swagdroid.core.model

data class BatteryPassport(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Float,
    val voltageMv: Int,
    val currentNowMicroAmps: Long?,
    val chargeCounterMah: Int?,
    val fullChargeEstimatedMah: Int?,
    val designCapacityMah: Int?,
    val estimatedWearHealthPercent: Int?,
    val cycleCount: Int?,
    val technology: String,
    val healthStatusLabel: String,
)

data class StoragePassport(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val usedPercent: Int
        get() = (usedFraction * 100f).toInt()
}

data class MemoryPassport(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val isLowMemory: Boolean,
    val zramTotalBytes: Long,
    val zramUsedBytes: Long,
) {
    val usedRamBytes: Long get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)

    val usedRamFraction: Float
        get() = if (totalRamBytes > 0L) (usedRamBytes.toFloat() / totalRamBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val usedRamPercent: Int
        get() = (usedRamFraction * 100f).toInt()
}

data class RootAdvisorReport(
    val brand: String,
    val deviceModel: String,
    val codename: String,
    val socHardware: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val kernelVersion: String,
    val isGki2Compatible: Boolean,
    val gkiBranch: String?,
    val isAbSeamlessDevice: Boolean,
    val activeSlot: String?,
    val bootloaderLocked: Boolean?,
    val verifiedBootState: String,
    val selinuxEnforcing: Boolean?,
    val knoxTriggeredBit: String?,
    val recommendedStrategyTitle: String,
    val recommendedStrategyBody: String,
    val cautionNotes: List<String>,
    val guidedSteps: List<String>,
)

data class DevicePassport(
    val battery: BatteryPassport,
    val storage: StoragePassport,
    val memory: MemoryPassport,
    val advisor: RootAdvisorReport,
)
