package com.example.rayneo

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ImuSample(
    val acc: FloatArray, // x, y, z
    val gyroDps: FloatArray, // x, y, z
    val gyroRad: FloatArray, // x, y, z
    val magnet: FloatArray, // x, y, z
    val temperature: Float,
    val tick: Long, // Use Long for unsigned int
    val psensor: Float,
    val lsensor: Float,
    val valid: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImuSample

        if (!acc.contentEquals(other.acc)) return false
        if (!gyroDps.contentEquals(other.gyroDps)) return false
        if (!gyroRad.contentEquals(other.gyroRad)) return false
        if (!magnet.contentEquals(other.magnet)) return false
        if (temperature != other.temperature) return false
        if (tick != other.tick) return false
        if (psensor != other.psensor) return false
        if (lsensor != other.lsensor) return false
        if (valid != other.valid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = acc.contentHashCode()
        result = 31 * result + gyroDps.contentHashCode()
        result = 31 * result + gyroRad.contentHashCode()
        result = 31 * result + magnet.contentHashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + tick.hashCode()
        result = 31 * result + psensor.hashCode()
        result = 31 * result + lsensor.hashCode()
        result = 31 * result + valid.hashCode()
        return result
    }
}

object RayNeoDriver {
    const val VID = 0x1BBB
    const val PID = 0xAF50

    private const val RAYNEO_PROTO_ACK_IMU_DATA: Byte = 0x65
    private const val DEG_TO_RAD = 0.0174532925f

    fun getEnableImuCommand(): ByteArray {
        val frame = ByteArray(64)
        frame[0] = 0x66.toByte()
        frame[1] = 0x01.toByte() // kCmdImuOn
        frame[2] = 0x00.toByte()
        // Remainder is 0
        return frame
    }

    fun parsePacket(buffer: ByteArray): ImuSample? {
        if (buffer.size < 64) return null
        if (buffer[0] != 0x99.toByte()) return null

        val type = buffer[1]
        if (type != RAYNEO_PROTO_ACK_IMU_DATA) return null

        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        // Offsets:
        // 4: acc (3 floats)
        // 16: gyroDps (3 floats)
        // 28: temp (float)
        // 32: mag[0] (float)
        // 36: mag[1] (float)
        // 40: tick (uint32)
        // 44: psensor (float)
        // 48: lsensor (float)
        // 52: mag[2] (float)

        val acc = FloatArray(3)
        acc[0] = bb.getFloat(4)
        acc[1] = bb.getFloat(8)
        acc[2] = bb.getFloat(12)

        val gyroDps = FloatArray(3)
        gyroDps[0] = bb.getFloat(16)
        gyroDps[1] = bb.getFloat(20)
        gyroDps[2] = bb.getFloat(24)

        val temperature = bb.getFloat(28)
        val mag0 = bb.getFloat(32)
        val mag1 = bb.getFloat(36)

        val tick = bb.getInt(40).toLong() and 0xFFFFFFFFL

        val psensor = bb.getFloat(44)
        val lsensor = bb.getFloat(48)
        val mag2 = bb.getFloat(52)

        val magnet = FloatArray(3)
        magnet[0] = mag0
        magnet[1] = mag1
        magnet[2] = mag2

        val gyroRad = FloatArray(3)
        gyroRad[0] = gyroDps[0] * DEG_TO_RAD
        gyroRad[1] = gyroDps[1] * DEG_TO_RAD
        gyroRad[2] = gyroDps[2] * DEG_TO_RAD

        return ImuSample(
            acc = acc,
            gyroDps = gyroDps,
            gyroRad = gyroRad,
            magnet = magnet,
            temperature = temperature,
            tick = tick,
            psensor = psensor,
            lsensor = lsensor
        )
    }
}
