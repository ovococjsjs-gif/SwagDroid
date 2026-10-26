package com.swagdroid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files

class AdbPacketAndKeyTest {

    @Test
    fun `AdbPacket roundtrip encodes and decodes 24-byte header and payload checksum`() {
        val payload = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
        val original = AdbPacket(
            command = AdbPacket.CMD_CNXN,
            arg0 = AdbPacket.ADB_VERSION,
            arg1 = AdbPacket.MAX_PAYLOAD,
            payload = payload,
        )

        val wireBytes = original.encodeToWire()
        val decoded = AdbPacket.readFromStream(DataInputStream(ByteArrayInputStream(wireBytes)))

        assertEquals(original.command, decoded.command)
        assertEquals(original.arg0, decoded.arg0)
        assertEquals(original.arg1, decoded.arg1)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `AdbKeyManager encodes 524-byte Android RSAPublicKey struct`() {
        val tempDir: File = Files.createTempDirectory("swagdroid_adb_test").toFile()
        try {
            val keyManager = AdbKeyManager(tempDir)
            val pair = keyManager.getOrCreateKeyPair()
            val structBytes = keyManager.encodeAndroidRsaPublicKeyStruct(
                pair.public as java.security.interfaces.RSAPublicKey
            )
            assertEquals(524, structBytes.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
