package com.rayneo.airseries

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Parses RayNeo Air series sensor packets into a simple 3DoF pose. VID: 0x1BBB, PID: 0xAF50 HID
 * device with 64-byte interrupt packets
 */
class RayNeoPacketParser {
    private var packetCount = 0

    fun parse(buffer: ByteArray, size: Int): Pose3DoF? {
        packetCount++

        // Log first few packets and every 100th packet for debugging
        if (packetCount <= 5 || packetCount % 100 == 0) {
            logPacket(buffer, size)
        }

        if (size < MIN_PACKET_SIZE) {
            Log.w(TAG, "Packet too small: $size bytes (need $MIN_PACKET_SIZE)")
            return null
        }

        // Try to parse - these offsets are placeholders and need adjustment
        // based on the actual packet structure
        val yawRaw = readShort(buffer, OFFSET_YAW)
        val pitchRaw = readShort(buffer, OFFSET_PITCH)
        val rollRaw = readShort(buffer, OFFSET_ROLL)

        val yaw = yawRaw.toFloat() / SCALE_DIVISOR
        val pitch = pitchRaw.toFloat() / SCALE_DIVISOR
        val roll = rollRaw.toFloat() / SCALE_DIVISOR

        // Log parsed values occasionally
        if (packetCount <= 5 || packetCount % 100 == 0) {
            Log.d(
                    TAG,
                    "Parsed: yaw=$yaw, pitch=$pitch, roll=$roll (raw: $yawRaw, $pitchRaw, $rollRaw)"
            )
        }

        return Pose3DoF(yaw, pitch, roll)
    }

    private fun logPacket(buffer: ByteArray, size: Int) {
        val hexDump = StringBuilder()
        hexDump.append("Packet #$packetCount ($size bytes): ")
        for (i in 0 until minOf(size, 64)) {
            hexDump.append(String.format("%02X ", buffer[i]))
            if ((i + 1) % 16 == 0) hexDump.append("\n  ")
        }
        Log.d(TAG, hexDump.toString())
    }

    private fun readShort(buffer: ByteArray, offset: Int): Short {
        if (offset + 2 > buffer.size) return 0
        val byteBuffer = ByteBuffer.wrap(buffer, offset, 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        return byteBuffer.short
    }

    companion object {
        private const val TAG = "RayNeoParser"
        // These are placeholder values - need to determine actual offsets
        private const val MIN_PACKET_SIZE = 8 // Reduced to accept smaller packets
        private const val OFFSET_YAW = 0
        private const val OFFSET_PITCH = 2
        private const val OFFSET_ROLL = 4
        private const val SCALE_DIVISOR = 100.0f
    }
}

data class Pose3DoF(val yawDegrees: Float, val pitchDegrees: Float, val rollDegrees: Float) {
    fun asIntString(): String {
        return "${yawDegrees.roundToInt()}, ${pitchDegrees.roundToInt()}, ${rollDegrees.roundToInt()}"
    }
}
