package com.example.rayneo

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RayNeoDriverTest {

    @Test
    fun testParsePacket() {
        val buffer = ByteArray(64)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buffer[0] = 0x99.toByte()
        buffer[1] = 0x65.toByte() // IMU DATA

        // Fill with some data
        bb.putFloat(4, 1.0f) // acc x
        bb.putFloat(8, 2.0f) // acc y
        bb.putFloat(12, 3.0f) // acc z

        bb.putFloat(16, 10.0f) // gyro x
        bb.putFloat(20, 20.0f) // gyro y
        bb.putFloat(24, 30.0f) // gyro z

        bb.putFloat(28, 36.5f) // temp

        bb.putFloat(32, 0.1f) // mag 0
        bb.putFloat(36, 0.2f) // mag 1

        bb.putInt(40, 12345) // tick

        bb.putFloat(44, 5.0f) // psensor
        bb.putFloat(48, 100.0f) // lsensor
        bb.putFloat(52, 0.3f) // mag 2

        val sample = RayNeoDriver.parsePacket(buffer)

        assertNotNull(sample)
        assertEquals(1.0f, sample!!.acc[0], 0.001f)
        assertEquals(2.0f, sample.acc[1], 0.001f)
        assertEquals(3.0f, sample.acc[2], 0.001f)

        assertEquals(10.0f, sample.gyroDps[0], 0.001f)
        assertEquals(20.0f, sample.gyroDps[1], 0.001f)
        assertEquals(30.0f, sample.gyroDps[2], 0.001f)

        assertEquals(0.1f, sample.magnet[0], 0.001f)
        assertEquals(0.2f, sample.magnet[1], 0.001f)
        assertEquals(0.3f, sample.magnet[2], 0.001f)

        assertEquals(12345L, sample.tick)
    }

    @Test
    fun testParseInvalidPacket() {
        val buffer = ByteArray(64)
        buffer[0] = 0x00
        val sample = RayNeoDriver.parsePacket(buffer)
        assertNull(sample)
    }

    @Test
    fun testEnableImuCommand() {
        val cmd = RayNeoDriver.getEnableImuCommand()
        assertEquals(64, cmd.size)
        assertEquals(0x66.toByte(), cmd[0])
        assertEquals(0x01.toByte(), cmd[1])
        assertEquals(0x00.toByte(), cmd[2])
    }
}
