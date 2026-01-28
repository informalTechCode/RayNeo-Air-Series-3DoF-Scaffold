package com.example.rayneo

import android.opengl.Matrix

class HeadTracker {
    private val rotationMatrix = FloatArray(16)
    private var lastTimestamp: Long = 0

    init {
        Matrix.setIdentityM(rotationMatrix, 0)
    }

    fun reset() {
        synchronized(this) {
            Matrix.setIdentityM(rotationMatrix, 0)
            lastTimestamp = 0
        }
    }

    fun update(sample: ImuSample) {
        // Deprecated or unused given the other update method,
        // but keeping it if needed or overloading.
    }

    fun update(gyro: FloatArray, dt: Float) {
        // gyro is x, y, z in radians/sec

        // Create a rotation matrix from axis-angle
        val magnitude = Math.sqrt((gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]).toDouble()).toFloat()

        if (magnitude > 1e-5f) {
            val theta = magnitude * dt
            val axisX = gyro[0] / magnitude
            val axisY = gyro[1] / magnitude
            val axisZ = gyro[2] / magnitude

            val deltaRotation = FloatArray(16)
            // Matrix.setRotateM takes degrees
            Matrix.setRotateM(deltaRotation, 0, Math.toDegrees(theta.toDouble()).toFloat(), axisX, axisY, axisZ)

            val temp = FloatArray(16)
            synchronized(this) {
                // Apply rotation
                // New = Old * Delta (local rotation)
                Matrix.multiplyMM(temp, 0, rotationMatrix, 0, deltaRotation, 0)
                System.arraycopy(temp, 0, rotationMatrix, 0, 16)
            }
        }
    }

    fun getRotationMatrix(): FloatArray {
        synchronized(this) {
            val copy = FloatArray(16)
            System.arraycopy(rotationMatrix, 0, copy, 0, 16)
            return copy
        }
    }
}
