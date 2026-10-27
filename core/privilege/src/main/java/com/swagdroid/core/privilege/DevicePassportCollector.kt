package com.swagdroid.core.privilege

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.swagdroid.core.model.BatteryPassport
import com.swagdroid.core.model.DevicePassport
import com.swagdroid.core.model.MemoryPassport
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.RootAdvisorReport
import com.swagdroid.core.model.StoragePassport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class DevicePassportCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: PrivilegedCommandExecutor,
) {

    suspend fun collect(): DevicePassport = withContext(Dispatchers.IO) {
        DevicePassport(
            battery = collectBatteryPassport(),
            storage = collectStoragePassport(),
            memory = collectMemoryPassport(),
            advisor = collectRootAdvisor(),
        )
    }

    private suspend fun collectBatteryPassport(): BatteryPassport {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val levelPct = if (level >= 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else 0

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10f
        val voltageRaw = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageMv = if (voltageRaw in 1..20) voltageRaw * 1000 else voltageRaw
        val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.ifBlank { "Li-Poly" } ?: "Li-Poly"
        val healthCode = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN

        val intentCycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        } else {
            null
        }

        val propChargeUah = batteryManager
            .getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0L && it != Long.MIN_VALUE }

        val currentNowUa = batteryManager
            .getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE }

        val dumpsysExtras = if (executor.currentTier() != PrivilegeTier.BASIC) {
            val res = executor.query(SystemReadQuery.Fixed.BATTERY)
            if (res.isSuccess && !res.outputTruncated) DumpsysAndKernelParsers.parseDumpsysBattery(res.stdout)
            else DumpsysBatteryExtras()
        } else {
            DumpsysBatteryExtras()
        }

        val finalChargeUah = dumpsysExtras.chargeCounterUah ?: propChargeUah
        val chargeMah = finalChargeUah?.let { (it / 1000L).toInt() }?.takeIf { it in 50..30_000 }
        val designMah = readFactoryDesignCapacityMah()

        val estimatedFullMah = if (chargeMah != null && levelPct >= 25) {
            ((chargeMah * 100.0) / levelPct.toDouble()).roundToInt()
        } else {
            null
        }

        val estimatedWearPct = if (estimatedFullMah != null && designMah != null && designMah > 500) {
            ((estimatedFullMah * 100.0) / designMah.toDouble()).roundToInt().coerceIn(30, 105)
        } else {
            null
        }

        val healthLabel = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Отличное"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Перегрев"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Требует замены"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Перенапряжение"
            BatteryManager.BATTERY_HEALTH_COLD -> "Переохлаждение"
            else -> "Норма"
        }

        return BatteryPassport(
            levelPercent = levelPct,
            isCharging = isCharging,
            temperatureCelsius = tempC,
            voltageMv = voltageMv,
            currentNowMicroAmps = currentNowUa,
            chargeCounterMah = chargeMah,
            fullChargeEstimatedMah = estimatedFullMah,
            designCapacityMah = designMah,
            estimatedWearHealthPercent = estimatedWearPct,
            cycleCount = dumpsysExtras.cycleCount ?: intentCycles,
            technology = tech,
            healthStatusLabel = healthLabel,
        )
    }

    private fun readFactoryDesignCapacityMah(): Int? {
        return runCatching {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val instance = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val cap = powerProfileClass.getMethod("getBatteryCapacity").invoke(instance) as Double
            cap.roundToInt().takeIf { it in 800..30_000 }
        }.getOrNull()
    }

    private fun collectStoragePassport(): StoragePassport {
        val statFs = StatFs(Environment.getDataDirectory().path)
        val total = statFs.totalBytes
        val free = statFs.availableBytes
        val used = (total - free).coerceAtLeast(0L)
        return StoragePassport(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free,
        )
    }

    private fun collectMemoryPassport(): MemoryPassport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val memLines = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
        val (zramTotal, zramUsed) = DumpsysAndKernelParsers.parseSwapFromMemInfo(memLines)

        return MemoryPassport(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            isLowMemory = memInfo.lowMemory,
            zramTotalBytes = zramTotal,
            zramUsedBytes = zramUsed,
        )
    }

    private suspend fun collectRootAdvisor(): RootAdvisorReport {
        val brand = Build.BRAND.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val codename = Build.DEVICE
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }
        val kernelRelease = System.getProperty("os.version") ?: "unknown"
        val (isGki2, gkiBranch) = DumpsysAndKernelParsers.parseKernelGkiInfo(kernelRelease)

        val abUpdate = readSystemProp("ro.build.ab_update").equals("true", ignoreCase = true)
        val slotSuffix = readSystemProp("ro.boot.slot_suffix").ifBlank { null }
        val flashLockedProp = readSystemProp("ro.boot.flash.locked")
        val bootloaderLocked = when (flashLockedProp) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val vbState = readSystemProp("ro.boot.verifiedbootstate").ifBlank { "unknown" }
        val knoxBit = readSystemProp("ro.boot.warranty_bit").ifBlank { null }

        val selinuxRes = executor.query(SystemReadQuery.Fixed.SELINUX)
        val selinuxEnforcing = when (selinuxRes.stdoutText.lowercase()) {
            "enforcing" -> true
            "permissive" -> false
            else -> null
        }

        val brandLower = Build.BRAND.lowercase()
        val (strategyTitle, strategyBody, cautions, steps) = buildBrandSpecificAdvice(
            brandLower = brandLower,
            model = model,
            codename = codename,
            isGki2 = isGki2,
            gkiBranch = gkiBranch,
            isAb = abUpdate,
            bootloaderLocked = bootloaderLocked,
            knoxBit = knoxBit,
        )

        return RootAdvisorReport(
            brand = brand,
            deviceModel = model,
            codename = codename,
            socHardware = soc,
            androidRelease = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "unknown",
            kernelVersion = kernelRelease,
            isGki2Compatible = isGki2,
            gkiBranch = gkiBranch,
            isAbSeamlessDevice = abUpdate,
            activeSlot = slotSuffix,
            bootloaderLocked = bootloaderLocked,
            verifiedBootState = vbState,
            selinuxEnforcing = selinuxEnforcing,
            knoxTriggeredBit = knoxBit,
            recommendedStrategyTitle = strategyTitle,
            recommendedStrategyBody = strategyBody,
            cautionNotes = cautions,
            guidedSteps = steps,
        )
    }

    private suspend fun readSystemProp(propName: String): String {
        val viaClass = runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java, String::class.java)
                .invoke(null, propName, "") as String
        }.getOrDefault("")
        if (viaClass.isNotBlank()) return viaClass.trim()

        val res = executor.query(SystemReadQuery.Property(propName))
        return if (res.isSuccess && !res.outputTruncated) res.stdoutText else ""
    }

    private data class BrandAdviceQuad(
        val title: String,
        val body: String,
        val cautions: List<String>,
        val steps: List<String>,
    )

    private fun buildBrandSpecificAdvice(
        brandLower: String,
        model: String,
        codename: String,
        isGki2: Boolean,
        gkiBranch: String?,
        isAb: Boolean,
        bootloaderLocked: Boolean?,
        knoxBit: String?,
    ): BrandAdviceQuad {
        val partitionTarget = when {
            isGki2 && isAb -> "init_boot.img (или boot.img)"
            else -> "boot.img"
        }
        val rootManagerRecommendation = if (isGki2) {
            "KernelSU (ветка $gkiBranch, режим LKM) или Magisk"
        } else {
            "Magisk (патчинг $partitionTarget из вашей точной версии прошивки)"
        }

        val cautions = mutableListOf(
            "Для 90% задач SwagDroid НЕ нужен разблокированный загрузчик — достаточно включить режим ADB / Shizuku прямо на телефоне за 45 секунд (банки и NFC продолжат работать).",
            "Первичная разблокировка загрузчика (Bootloader Unlock) принудительно стирает все пользовательские данные (Factory Reset).",
        )

        if ("samsung" in brandLower) {
            cautions += "Внимание Samsung Knox: разблокировка загрузчика необратимо пережигает e-fuse бит Knox (${knoxBit ?: "0x0 → 0x1"}), отключая Samsung Pay и Secure Folder (лечится только частично модулями KnoxPatch при активном руте)."
        } else if (brandLower in setOf("xiaomi", "redmi", "poco")) {
            cautions += "На Xiaomi HyperOS требуется привязка Mi-аккаунта в «Статус Mi Unlock» и подача заявки в Xiaomi Community (таймер ожидания 72–168 часов)."
        } else if (brandLower in setOf("huawei", "honor")) {
            cautions += "На современных Huawei / Honor официальная разблокировка загрузчика закрыта производителем — настоятельно рекомендуется использовать режим Tier 2 (Беспроводной ADB + Shizuku)."
        }

        val lockLabel = when (bootloaderLocked) {
            true -> "Сейчас загрузчик ЗАБЛОКИРОВАН (Locked)."
            false -> "Загрузчик уже РАЗБЛОКИРОВАН (Unlocked) — устройство готово к установке $rootManagerRecommendation!"
            null -> "Статус загрузчика: проверьте пункт «Заводская разблокировка (OEM Unlock)» в настройках разработчика."
        }

        val steps = listOf(
            "1. Сделайте резервную копию важных фото и чатов, если планируете разблокировать загрузчик впервые.",
            "2. В «Для разработчиков» включите «Заводская разблокировка (OEM Unlock)» и «Отладка по USB».",
            "3. Разблокируйте загрузчик штатным способом для $model (codename: $codename).",
            "4. Скачайте образ $partitionTarget строго от вашей текущей сборки ОС и пропатчите его через $rootManagerRecommendation.",
            "5. Прошейте пропатченный образ через fastboot / Odin, перезагрузитесь и нажмите «Подключить Root» в SwagDroid.",
            "6. Для работы банков и бесконтактной оплаты установите Zygisk + PlayIntegrityFix.",
        )

        return BrandAdviceQuad(
            title = "Рекомендация для $model ($codename): $rootManagerRecommendation",
            body = "$lockLabel Ядро ${if (isGki2) "поддерживает стандарт GKI 2.0 ($gkiBranch) — доступна чистая установка KernelSU на уровне ядра." else "не-GKI — оптимален классический Magisk через патчинг $partitionTarget."}",
            cautions = cautions,
            steps = steps,
        )
    }
}
