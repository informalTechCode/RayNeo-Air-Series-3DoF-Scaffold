package com.rayneo.airseries

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Parses RayNeo Air series sensor packets into a simple 3DoF pose.
 *
 * TODO: Replace the offsets and scaling values below with the accurate ones from
 * RayNeo-Air-3S-Pro-OpenVR/driver_rayneo.cpp once you have access to it.
 */
class RayNeoPacketParser {
    fun parse(buffer: ByteArray, size: Int): Pose3DoF? {
        if (size < MIN_PACKET_SIZE) return null
        val yawRaw = readShort(buffer, OFFSET_YAW)
        val pitchRaw = readShort(buffer, OFFSET_PITCH)
        val rollRaw = readShort(buffer, OFFSET_ROLL)
        val yaw = yawRaw.toFloat() / SCALE_DIVISOR
        val pitch = pitchRaw.toFloat() / SCALE_DIVISOR
        val roll = rollRaw.toFloat() / SCALE_DIVISOR
        return Pose3DoF(yaw, pitch, roll)
    }

    private fun readShort(buffer: ByteArray, offset: Int): Short {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        return byteBuffer.short
    }

    companion object {
        private const val MIN_PACKET_SIZE = 32
        private const val OFFSET_YAW = 12
        private const val OFFSET_PITCH = 14
        private const val OFFSET_ROLL = 16
        private const val SCALE_DIVISOR = 100.0f
    }
}

data class Pose3DoF(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float
) {
    fun asIntString(): String {
        return "${yawDegrees.roundToInt()}, ${pitchDegrees.roundToInt()}, ${rollDegrees.roundToInt()}"
    }
}
