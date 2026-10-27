package com.swagdroid.core.privilege

import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.StateReadException

/** Closed, validated read-only queries. Never accept an arbitrary shell string on this path. */
sealed interface SystemReadQuery {
    val command: String

    enum class Fixed(override val command: String) : SystemReadQuery {
        KERNEL_BOOT_ID("cat /proc/sys/kernel/random/boot_id"),
        BATTERY("dumpsys battery"),
        DISK_STATS("dumpsys diskstats"),
        DEVICE_IDLE("dumpsys deviceidle"),
        IDLE_WHITELIST("dumpsys deviceidle whitelist"),
        STAGING_SESSIONS("pm list staging-sessions"),
        DISPLAY_DENSITY("wm density"),
        DISPLAY_SIZE("wm size"),
        AUDIO("dumpsys audio"),
        SELINUX("getenforce"),
        SWAPPINESS("cat /proc/sys/vm/swappiness"),
        SCHEDSTATS("cat /proc/sys/kernel/sched_schedstats"),
        DIRTY_RATIO("cat /proc/sys/vm/dirty_ratio"),
        DIRTY_BACKGROUND_RATIO("cat /proc/sys/vm/dirty_background_ratio"),
    }

    data class Property(val name: String) : SystemReadQuery {
        init { require(Regex("^[a-zA-Z0-9_.-]{1,200}$").matches(name)) }
        override val command: String get() = "getprop '$name'"
    }

    data class Packages(val userId: Int, val includeUninstalled: Boolean = false) : SystemReadQuery {
        init { require(userId >= 0) }
        override val command: String get() = "pm list packages --user $userId${if (includeUninstalled) " -u" else ""}"
    }

    data class AppOp(val userId: Int, val packageName: String, val opName: String) : SystemReadQuery {
        init {
            require(userId >= 0 && packagePattern.matches(packageName) && Regex("^[A-Z][A-Z0-9_]{0,100}$").matches(opName))
        }
        override val command: String get() = "cmd appops get --user $userId '$packageName' '$opName'"
    }

    data class MockLocations(val userId: Int) : SystemReadQuery {
        init { require(userId >= 0) }
        override val command: String get() = "cmd appops query-op --user $userId MOCK_LOCATION allow"
    }

    data class StandbyBucket(val userId: Int, val packageName: String) : SystemReadQuery {
        init { require(userId >= 0 && packagePattern.matches(packageName)) }
        override val command: String get() = "am get-standby-bucket --user $userId '$packageName'"
    }

    companion object {
        private val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*$")
    }
}

/** Compatibility bridge for diagnostics: incomplete/failed reads must not parse as an empty healthy state. */
fun ShellExecResult.requireReadOutput(): ShellExecResult {
    if (!isSuccess || outputTruncated) throw StateReadException(stderrText.ifBlank { "Ответ команды чтения недоступен или неполон ($exitCode)" })
    return this
}
