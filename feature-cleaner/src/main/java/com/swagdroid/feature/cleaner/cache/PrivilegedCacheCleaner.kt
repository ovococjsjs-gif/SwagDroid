package com.swagdroid.feature.cleaner.cache

import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.SwagDroidIpcContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Результат проверки безопасности команды `pm clear --cache-only` на конкретной прошивке устройства.
 */
@Serializable
enum class CacheOnlyCapabilityStatus {
    UNVERIFIED,
    VERIFIED_SAFE,
    UNSAFE_OR_UNSUPPORTED,
}

/**
 * Отчёт об очистке стандартных кэшей с честным измерением освобождённого места До и После.
 */
@Serializable
data class CacheCleanVerificationReport(
    val cacheBytesBefore: Long,
    val cacheBytesAfter: Long,
    val actualFreedBytes: Long,
    val durationMillis: Long,
    val usedCacheOnlyPerPackage: Boolean,
    val remainingPackagesNeedingFallback: List<String>,
)

/**
 * Канареечная самопроверка (`CacheClearCanaryProbe`) и привилегированный очиститель кэша.
 *
 * Почему необходима канареечная проба:
 * В современном AOSP команда `cmd package clear --cache-only <pkg>` вызывает `deleteApplicationCacheFilesAsUser`,
 * но на некоторых модифицированных OEM-прошивках флаг `--cache-only` может быть проигнорирован, что приведёт
 * к полному сбросу данных приложения!
 * Поэтому перед тем как когда-либо вызвать `--cache-only` для чужого пакета, SwagDroid проверяет команду
 * НА САМОМ СЕБЕ:
 * 1. Создаёт маркерный файл в приватных данных `filesDir/canary_keep_userdata.marker` и тестовый файл в `cacheDir/canary_drop_cache.tmp`.
 * 2. Вызывает `service.clearPackageCacheOnly("com.swagdroid.app", 0)`.
 * 3. Проверяет, что `canary_drop_cache.tmp` исчез, а `canary_keep_userdata.marker` остался цел и невредим!
 */
class PrivilegedCacheCleaner(
    private val hostFilesDir: File,
    private val hostCacheDir: File,
) {
    @Volatile
    var capabilityStatus: CacheOnlyCapabilityStatus = CacheOnlyCapabilityStatus.UNVERIFIED
        private set

    suspend fun runSelfCanaryCapabilityProbe(
        service: ISwagDroidService,
        userId: Int = 0,
    ): CacheOnlyCapabilityStatus = withContext(Dispatchers.IO) {
        val dataMarker = File(hostFilesDir.apply { mkdirs() }, "canary_keep_userdata.marker")
        val cacheDummy = File(hostCacheDir.apply { mkdirs() }, "canary_drop_cache.tmp")

        runCatching {
            dataMarker.writeText("DO_NOT_DELETE_USER_DATA_CANARY")
            cacheDummy.writeBytes(ByteArray(4096) { 0x42 })

            val ok = service.clearPackageCacheOnly(SwagDroidIpcContract.HOST_PACKAGE_NAME, userId)
            val dataSurvived = dataMarker.exists() && dataMarker.readText() == "DO_NOT_DELETE_USER_DATA_CANARY"
            val cacheCleared = !cacheDummy.exists()

            capabilityStatus = if (ok && dataSurvived && cacheCleared) {
                CacheOnlyCapabilityStatus.VERIFIED_SAFE
            } else {
                CacheOnlyCapabilityStatus.UNSAFE_OR_UNSUPPORTED
            }
        }.onFailure {
            capabilityStatus = CacheOnlyCapabilityStatus.UNSAFE_OR_UNSUPPORTED
        } finally {
            runCatching { cacheDummy.delete() }
        }
        capabilityStatus
    }

    /**
     * Выполняет очистку стандартных кэшей через `trimAllCaches` (или через проверенный `clearPackageCacheOnly`)
     * с обязательным замером объёма кэшей ДО и ПОСЛЕ операции.
     */
    suspend fun cleanStandardCachesWithVerification(
        service: ISwagDroidService,
        targetPackages: List<String>,
        measureTotalCacheBytes: suspend (List<String>) -> Map<String, Long>,
        residualThresholdBytes: Long = 16L * 1024L * 1024L, // 16 MB
    ): CacheCleanVerificationReport = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val beforeMap = measureTotalCacheBytes(targetPackages)
        val totalBefore = beforeMap.values.sum()

        var usedPerPackage = false
        if (capabilityStatus == CacheOnlyCapabilityStatus.VERIFIED_SAFE && targetPackages.size <= 15) {
            usedPerPackage = true
            for (pkg in targetPackages) {
                service.clearPackageCacheOnly(pkg, 0)
            }
        } else {
            service.trimAllCaches(Long.MAX_VALUE)
        }

        val afterMap = measureTotalCacheBytes(targetPackages)
        val totalAfter = afterMap.values.sum()
        val freed = (totalBefore - totalAfter).coerceAtLeast(0L)

        // Определяем пакеты, чей кэш остался больше порога (например, системные приложения, которые trim-caches не трогает)
        val remaining = afterMap
            .filter { (_, bytes) -> bytes >= residualThresholdBytes }
            .keys
            .toList()

        CacheCleanVerificationReport(
            cacheBytesBefore = totalBefore,
            cacheBytesAfter = totalAfter,
            actualFreedBytes = freed,
            durationMillis = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
            usedCacheOnlyPerPackage = usedPerPackage,
            remainingPackagesNeedingFallback = remaining,
        )
    }
}
