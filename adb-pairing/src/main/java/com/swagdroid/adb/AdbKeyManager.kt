package com.swagdroid.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Управление 2048-битной парой RSA-ключей для аутентификации в демоне `adbd`.
 *
 * Включает сериализацию публичного ключа в бинарную структуру Android `RSAPublicKey` (524 байта + суффикс),
 * которую ожидает пакет `A_AUTH (ADB_AUTH_RSAPUBLICKEY)` и процедура SPAKE2 PeerInfo при пейринге.
 */
class AdbKeyManager(
    private val storageDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "adb_keys"))

    private val privateKeyFile get() = File(storageDir.apply { mkdirs() }, "swagdroid_adbkey.pk8")
    private val publicKeyFile get() = File(storageDir.apply { mkdirs() }, "swagdroid_adbkey.pub")

    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateKeyFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(publicKeyFile.readBytes()))
                return KeyPair(pub, priv)
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_LENGTH_BITS)
        }
        val pair = kpg.generateKeyPair()
        privateKeyFile.writeBytes(pair.private.encoded)
        publicKeyFile.writeBytes(pair.public.encoded)
        return pair
    }

    fun getPrivateKey(): PrivateKey = getOrCreateKeyPair().private

    /**
     * Формирует строку `adbkey.pub` в формате `<Base64(AndroidRSAPublicKeyStruct)> swagdroid@local\u0000`,
     * передаваемую в `PeerInfo` при сопряжении и в `ADB_AUTH_RSAPUBLICKEY`.
     */
    fun getFormattedAdbPublicKeyPayload(): ByteArray {
        val rsaPub = getOrCreateKeyPair().public as RSAPublicKey
        val structBytes = encodeAndroidRsaPublicKeyStruct(rsaPub)
        val base64Key = Base64.encodeToString(structBytes, Base64.NO_WRAP)
        return "$base64Key swagdroid@local\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * Вычисляет 524-байтную структуру `RSAPublicKey` (формат `libcrypto_utils/android_pubkey.c`):
     * - `uint32_t modulus_size_words` (64 для 2048-бит)
     * - `uint32_t n0inv` (`-1 / n[0] mod 2^32`)
     * - `uint8_t modulus[256]` (little-endian)
     * - `uint8_t rr[256]` (`R^2 mod N`, little-endian)
     * - `uint32_t exponent` (65537)
     */
    internal fun encodeAndroidRsaPublicKeyStruct(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(KEY_LENGTH_BITS)
        val rr = r.multiply(r).mod(n)
        val n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32)

        val buffer = ByteBuffer.allocate(ANDROID_PUBKEY_STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(KEY_LENGTH_WORDS)
        buffer.putInt(n0inv.toInt())
        buffer.put(bigIntToLittleEndianPadded(n, KEY_LENGTH_BYTES))
        buffer.put(bigIntToLittleEndianPadded(rr, KEY_LENGTH_BYTES))
        buffer.putInt(publicKey.publicExponent.toInt())
        return buffer.array()
    }

    private fun bigIntToLittleEndianPadded(value: BigInteger, sizeBytes: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val result = ByteArray(sizeBytes)
        var srcIdx = bigEndian.size - 1
        var dstIdx = 0
        while (srcIdx >= 0 && dstIdx < sizeBytes) {
            result[dstIdx++] = bigEndian[srcIdx--]
        }
        return result
    }

    companion object {
        private const val KEY_LENGTH_BITS = 2048
        private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
        private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4
        private const val ANDROID_PUBKEY_STRUCT_SIZE = 4 + 4 + KEY_LENGTH_BYTES + KEY_LENGTH_BYTES + 4 // 524 bytes
    }
}
