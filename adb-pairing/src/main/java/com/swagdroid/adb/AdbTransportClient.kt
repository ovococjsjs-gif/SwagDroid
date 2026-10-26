package com.swagdroid.adb

import com.swagdroid.api.ServerLaunchBackend
import com.swagdroid.api.SwagDroidIpcContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Бинарный заголовок и пакет протокола ADB (`system/core/adb/adb.h`).
 *
 * Формат 24-байтного заголовка (Little-Endian):
 * - `command` (4 байта: `CNXN`, `AUTH`, `OPEN`, `WRTE`, `OKAY`, `CLSE`, `STLS`)
 * - `arg0` (4 байта)
 * - `arg1` (4 байта)
 * - `data_length` (4 байта)
 * - `data_check` (4 байта: сумма всех байтов payload)
 * - `magic` (4 байта: `command xor 0xFFFFFFFF`)
 */
data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun encodeToWire(): ByteArray {
        val header = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(computePayloadChecksum(payload))
        header.putInt(command xor -0x1)
        header.put(payload)
        return header.array()
    }

    companion object {
        const val HEADER_SIZE = 24
        const val CMD_SYNC = 0x434e5953
        const val CMD_CNXN = 0x4e584e43
        const val CMD_AUTH = 0x48545541
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257
        const val CMD_STLS = 0x534c5453

        const val ADB_VERSION = 0x01000001
        const val ADB_STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024

        const val AUTH_TYPE_TOKEN = 1
        const val AUTH_TYPE_SIGNATURE = 2
        const val AUTH_TYPE_RSAPUBLICKEY = 3

        fun computePayloadChecksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) {
                sum += (b.toInt() and 0xFF)
            }
            return sum
        }

        fun readFromStream(input: DataInputStream): AdbPacket {
            val headerBytes = ByteArray(HEADER_SIZE)
            input.readFully(headerBytes)
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val len = buf.int
            val check = buf.int
            val magic = buf.int
            require(magic == (cmd xor -0x1)) { "Invalid ADB packet magic for cmd=$cmd" }
            val payload = ByteArray(len.coerceIn(0, MAX_PAYLOAD))
            if (len > 0) {
                input.readFully(payload)
                require(computePayloadChecksum(payload) == check) { "ADB payload checksum mismatch" }
            }
            return AdbPacket(cmd, arg0, arg1, payload)
        }
    }
}

/**
 * Клиент локального подключения к порту `_adb-tls-connect._tcp` и запуска `swagdroid_server` через `app_process`.
 */
class AdbTransportClient(
    private val keyManager: AdbKeyManager,
) {
    /**
     * Подключается к найденному локальному порту Беспроводной отладки (`_adb-tls-connect._tcp`),
     * проходит взаимную TLS/RSA аутентификацию и запускает наш привилегированный `swagdroid_server`
     * в отдельной сессии (`setsid`), чтобы сервер продолжал работать даже при последующем отключении Wi-Fi.
     */
    suspend fun connectAndBootstrapServer(
        host: String,
        port: Int,
        hostApkPath: String,
        hostCertSha256: String? = null,
        onStepLog: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            onStepLog("[ADB] Установка соединения с $host:$port...")
            val sslContext = createTls13ContextWithClientKey()
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), 6_000)
            socket.soTimeout = 10_000
            socket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            socket.startHandshake()

            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)

            // 1. Отправляем CNXN
            val banner = "host::features=shell_v2,cmd,stat_v2\u0000".toByteArray(Charsets.UTF_8)
            val cnxn = AdbPacket(
                command = AdbPacket.CMD_CNXN,
                arg0 = AdbPacket.ADB_VERSION,
                arg1 = AdbPacket.MAX_PAYLOAD,
                payload = banner,
            )
            output.write(cnxn.encodeToWire())
            output.flush()

            var packet = AdbPacket.readFromStream(input)
            if (packet.command == AdbPacket.CMD_AUTH && packet.arg0 == AdbPacket.AUTH_TYPE_TOKEN) {
                onStepLog("[ADB] Получен AUTH_TOKEN, подписываем RSA-ключом...")
                val signedToken = signAdbAuthToken(packet.payload)
                val sigPacket = AdbPacket(
                    command = AdbPacket.CMD_AUTH,
                    arg0 = AdbPacket.AUTH_TYPE_SIGNATURE,
                    arg1 = 0,
                    payload = signedToken,
                )
                output.write(sigPacket.encodeToWire())
                output.flush()
                packet = AdbPacket.readFromStream(input)

                if (packet.command == AdbPacket.CMD_AUTH) {
                    onStepLog("[ADB] Передача публичного ключа ADB_AUTH_RSAPUBLICKEY...")
                    val pubKeyPacket = AdbPacket(
                        command = AdbPacket.CMD_AUTH,
                        arg0 = AdbPacket.AUTH_TYPE_RSAPUBLICKEY,
                        arg1 = 0,
                        payload = keyManager.getFormattedAdbPublicKeyPayload(),
                    )
                    output.write(pubKeyPacket.encodeToWire())
                    output.flush()
                    packet = AdbPacket.readFromStream(input)
                }
            }

            check(packet.command == AdbPacket.CMD_CNXN) {
                "Ожидался пакет CNXN от adbd, получен: 0x${packet.command.toString(16)}"
            }
            onStepLog("[ADB] Сессия adbd (uid=2000) успешно авторизована!")

            // 2. Формируем команду запуска единого `swagdroid_server` в отвязанной группе процессов (`setsid`)
            val certFlag = if (!hostCertSha256.isNullOrBlank()) "--cert-sha256=$hostCertSha256" else ""
            val bootstrapShellCmd = buildString {
                append("shell:setsid app_process ")
                append("-Djava.class.path=\"$hostApkPath\" ")
                append("/system/bin ")
                append("--nice-name=${SwagDroidIpcContract.SERVER_NICE_NAME} ")
                append("com.swagdroid.server.ServerMain ")
                append("--backend=${ServerLaunchBackend.WIRELESS_ADB_BUILTIN.name} ")
                if (certFlag.isNotEmpty()) append("$certFlag ")
                append(">/dev/null 2>&1 &\u0000")
            }

            onStepLog("[ADB] Запуск демона ${SwagDroidIpcContract.SERVER_NICE_NAME} через app_process...")
            val openPacket = AdbPacket(
                command = AdbPacket.CMD_OPEN,
                arg0 = 1,
                arg1 = 0,
                payload = bootstrapShellCmd.toByteArray(Charsets.UTF_8),
            )
            output.write(openPacket.encodeToWire())
            output.flush()

            val response = AdbPacket.readFromStream(input)
            socket.close()
            onStepLog("[ADB] Демон запущен (ответ потока: 0x${response.command.toString(16)})")
            "OK"
        }
    }

    /**
     * Подписывает 20-байтный SHA-1 токен от `adbd` с помощью PKCS#1 v1.5 ASN.1 префикса SHA-1.
     */
    private fun signAdbAuthToken(token20Bytes: ByteArray): ByteArray {
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        val dataToEncrypt = sha1DigestInfoPrefix + token20Bytes
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyManager.getPrivateKey())
        return cipher.doFinal(dataToEncrypt)
    }

    private fun createTls13ContextWithClientKey(): SSLContext {
        val trustAll = arrayOf<X509TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        return SSLContext.getInstance("TLSv1.3").apply {
            init(null, trustAll, SecureRandom())
        }
    }
}
