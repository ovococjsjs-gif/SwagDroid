package com.swagdroid.feature.antivirus.layer1

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.BitSet
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Результат однопроходного вычисления всех трёх криптографических хэшей (`MD5`, `SHA-1`, `SHA-256`).
 */
@Serializable
data class MultiHashDigest(
    val md5Hex: String,
    val sha1Hex: String,
    val sha256Hex: String,
    val byteCount: Long,
)

/**
 * Вычисляет `MD5`, `SHA-1` и `SHA-256` одновременно за ОДИН проход чтения потока (`InputStream`),
 * исключая тройное чтение APK/DEX с флеш-памяти телефона.
 */
object SinglePassMultiHasher {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    fun hashStream(input: InputStream): MultiHashDigest {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L

        var read = input.read(buffer)
        while (read > 0) {
            md5.update(buffer, 0, read)
            sha1.update(buffer, 0, read)
            sha256.update(buffer, 0, read)
            totalBytes += read
            read = input.read(buffer)
        }

        return MultiHashDigest(
            md5Hex = md5.digest().toLowerHex(),
            sha1Hex = sha1.digest().toLowerHex(),
            sha256Hex = sha256.digest().toLowerHex(),
            byteCount = totalBytes,
        )
    }

    fun hashFile(file: File): MultiHashDigest =
        file.inputStream().buffered().use { hashStream(it) }

    private fun ByteArray.toLowerHex(): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }
}

/**
 * Компактный вероятностный фильтр Блума (Фаза 1 сигнатурного сканера в стиле `MaintainTeam/Hypatia`).
 *
 * Важнейшее правило:
 * - Если `mightContain(hash) == false` -> файл 100% отсутствует в сигнатурной базе (0% False Negatives).
 * - Если `mightContain(hash) == true` -> это лишь *кандидат*, который ОБЯЗАТЕЛЬНО проходит Фазу 2
 *   (`ExactHashShardIndex.verifyExactMatch`) перед вынесением вердикта `CONFIRMED`!
 */
class SignatureBloomFilter(
    private val numBits: Int,
    private val numHashFunctions: Int,
) {
    private val bitSet = BitSet(numBits)

    fun put(hexHash: String) {
        val normalized = hexHash.lowercase().trim()
        val (h1, h2) = computeDoubleHash64(normalized)
        for (i in 1..numHashFunctions) {
            val combinedHash = h1 + i * h2
            val bitIndex = ((combinedHash and Long.MAX_VALUE) % numBits).toInt()
            bitSet.set(bitIndex)
        }
    }

    fun mightContain(hexHash: String): Boolean {
        val normalized = hexHash.lowercase().trim()
        val (h1, h2) = computeDoubleHash64(normalized)
        for (i in 1..numHashFunctions) {
            val combinedHash = h1 + i * h2
            val bitIndex = ((combinedHash and Long.MAX_VALUE) % numBits).toInt()
            if (!bitSet.get(bitIndex)) return false
        }
        return true
    }

    private fun computeDoubleHash64(input: String): Pair<Long, Long> {
        var h1 = -0x340d631b7bdddcdbL // FNV-1a 64 offset
        var h2 = 0x100000001b3L
        for (ch in input) {
            val code = ch.code.toLong()
            h1 = (h1 xor code) * 0x100000001b3L
            h2 = (h2 * 31L) xor code
        }
        if (h2 == 0L) h2 = 0x5bd1e995L
        return h1 to h2
    }

    companion object {
        fun createOptimal(expectedInsertions: Int, falsePositiveRate: Double = 0.0001): SignatureBloomFilter {
            val n = expectedInsertions.coerceAtLeast(100)
            val m = (-n * ln(falsePositiveRate) / (ln(2.0) * ln(2.0))).roundToInt().coerceAtLeast(1024)
            val k = ((m.toDouble() / n) * ln(2.0)).roundToInt().coerceIn(2, 16)
            return SignatureBloomFilter(numBits = m, numHashFunctions = k)
        }
    }
}

/**
 * Запись о точной сигнатуре вредоносного файла во втором (подтверждающем) слое.
 */
@Serializable
data class ExactMalwareSignature(
    val hashHex: String,
    val hashType: String, // "sha256" | "md5" | "sha1"
    val threatFamily: String,
    val sourceFeed: String, // "MaintainTeam/Hypatia (ClamAV)" | "ESET malware-ioc" | "Echap Stalkerware" | "MalwareBazaar"
)

/**
 * Фаза 2: Точный шардированный по 2-символьному префиксу индекс хэшей (`00`..`ff`).
 *
 * Исключает математическую вероятность ложного срабатывания Bloom-фильтра.
 * Только совпадение в `ExactHashShardIndex` даёт право выставить `FindingConfidence.CONFIRMED`.
 */
class ExactHashShardIndex {
    // Ключ шарда: первые 2 hex-символа хэша -> таблица (полный хэш -> ExactMalwareSignature)
    private val shards = HashMap<String, MutableMap<String, ExactMalwareSignature>>(256)

    fun insert(signature: ExactMalwareSignature) {
        val norm = signature.hashHex.lowercase().trim()
        require(norm.length >= 32) { "Hash too short: $norm" }
        val shardKey = norm.substring(0, 2)
        val bucket = shards.getOrPut(shardKey) { HashMap() }
        bucket[norm] = signature.copy(hashHex = norm)
    }

    fun verifyExactMatch(hashHex: String): ExactMalwareSignature? {
        val norm = hashHex.lowercase().trim()
        if (norm.length < 32) return null
        val shardKey = norm.substring(0, 2)
        return shards[shardKey]?.get(norm)
    }
}

/**
 * Кэш результатов сканирования установленных APK (`ApkScanCache`),
 * чтобы не пересчитывать хэши гигабайтных игр при каждом запуске, если APK и версия БД не менялись.
 */
@Serializable
data class CachedApkScanEntry(
    val packageName: String,
    val versionCode: Long,
    val lastModifiedEpochMs: Long,
    val signatureDbVersion: Int,
    val baseApkSha256: String,
    val isCleanInLayer1: Boolean,
)

class ApkScanCache {
    private val memoryCache = HashMap<String, CachedApkScanEntry>()

    fun getIfValid(
        packageName: String,
        versionCode: Long,
        lastModifiedEpochMs: Long,
        signatureDbVersion: Int,
    ): CachedApkScanEntry? {
        val entry = memoryCache[packageName] ?: return null
        return if (
            entry.versionCode == versionCode &&
            entry.lastModifiedEpochMs == lastModifiedEpochMs &&
            entry.signatureDbVersion == signatureDbVersion
        ) {
            entry
        } else {
            null
        }
    }

    fun put(entry: CachedApkScanEntry) {
        memoryCache[entry.packageName] = entry
    }
}
