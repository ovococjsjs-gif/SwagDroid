package com.swagdroid.core.privilege

data class DumpsysBatteryExtras(
    val chargeCounterUah: Long? = null,
    val cycleCount: Int? = null,
    val maxChargingCurrentUa: Long? = null,
)

object DumpsysAndKernelParsers {

    private val GKI_REGEX = Regex("""^(\d+\.\d+)\.\d+-android(\d+)-""")
    private val MEMINFO_KB_REGEX = Regex("""^([A-Za-z_()]+):\s+(\d+)\s*kB""", RegexOption.IGNORE_CASE)

    /**
     * Quotes an arbitrary string safely for POSIX sh single-quote execution.
     */
    fun shellQuote(raw: String): String {
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    /**
     * Parses ZRAM / Swap (SwapTotalBytes, SwapUsedBytes) from /proc/meminfo lines.
     */
    fun parseSwapFromMemInfo(lines: List<String>): Pair<Long, Long> {
        var swapTotalKb = 0L
        var swapFreeKb = 0L
        for (line in lines) {
            val match = MEMINFO_KB_REGEX.find(line.trim()) ?: continue
            val key = match.groupValues[1]
            val valueKb = match.groupValues[2].toLongOrNull() ?: continue
            when {
                key.equals("SwapTotal", ignoreCase = true) -> swapTotalKb = valueKb
                key.equals("SwapFree", ignoreCase = true) -> swapFreeKb = valueKb
            }
        }
        val totalBytes = swapTotalKb * 1024L
        val usedBytes = (swapTotalKb - swapFreeKb).coerceAtLeast(0L) * 1024L
        return totalBytes to usedBytes
    }

    /**
     * Parses extra battery telemetry from `dumpsys battery` output.
     */
    fun parseDumpsysBattery(lines: List<String>): DumpsysBatteryExtras {
        var chargeCounter: Long? = null
        var cycles: Int? = null
        var maxCurrent: Long? = null

        for (raw in lines) {
            val line = raw.trim()
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim()

            when (key) {
                "charge counter" -> chargeCounter = value.toLongOrNull()
                "cycle count", "mcyclecount" -> cycles = value.toIntOrNull()
                "max charging current" -> maxCurrent = value.toLongOrNull()
            }
        }
        return DumpsysBatteryExtras(
            chargeCounterUah = chargeCounter,
            cycleCount = cycles,
            maxChargingCurrentUa = maxCurrent,
        )
    }

    /**
     * Checks whether a Linux kernel release string follows Google's GKI 2.0 format
     * (Linux 5.10+ shipped with Android 12+), used by KernelSU / APatch LKM/GKI mode.
     */
    fun parseKernelGkiInfo(kernelRelease: String): Pair<Boolean, String?> {
        val trimmed = kernelRelease.trim()
        val match = GKI_REGEX.find(trimmed) ?: return false to null
        val kernelMajorMinor = match.groupValues[1]
        val androidVer = match.groupValues[2].toIntOrNull() ?: return false to null
        val parts = kernelMajorMinor.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false to null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val isGki2 = androidVer >= 12 && (major > 5 || (major == 5 && minor >= 10))
        return if (isGki2) {
            true to "android$androidVer-$kernelMajorMinor"
        } else {
            false to null
        }
    }

    /**
     * Identifies the Root solution from `su -v` output and known daemon paths.
     */
    fun identifyRootVersion(
        suVersionOutput: String,
        hasKsuDaemon: Boolean,
        hasApatchDaemon: Boolean,
    ): Pair<com.swagdroid.core.model.RootProvider, String?> {
        val text = suVersionOutput.trim()
        val upper = text.uppercase()
        return when {
            hasKsuDaemon || "KERNELSU" in upper ->
                com.swagdroid.core.model.RootProvider.KERNEL_SU to text.ifEmpty { "KernelSU" }
            hasApatchDaemon || "APATCH" in upper ->
                com.swagdroid.core.model.RootProvider.APATCH to text.ifEmpty { "APatch" }
            "MAGISK" in upper ->
                com.swagdroid.core.model.RootProvider.MAGISK to text
            text.isNotEmpty() ->
                com.swagdroid.core.model.RootProvider.GENERIC_SU to text
            else ->
                com.swagdroid.core.model.RootProvider.GENERIC_SU to null
        }
    }
}
