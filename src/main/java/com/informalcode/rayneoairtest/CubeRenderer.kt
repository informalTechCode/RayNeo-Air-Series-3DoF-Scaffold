package com.informalcode.rayneoairtest

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CubeRenderer : GLSurfaceView.Renderer {

    private val vertices = floatArrayOf(
        -1f, -1f, -1f,
        1f, -1f, -1f,
        1f, 1f, -1f,
        -1f, 1f, -1f,
        -1f, -1f, 1f,
        1f, -1f, 1f,
        1f, 1f, 1f,
        -1f, 1f, 1f
    )

    private val indices = shortArrayOf(
        0, 1, 2, 0, 2, 3,
        4, 5, 6, 4, 6, 7,
        0, 1, 5, 0, 5, 4,
        2, 3, 7, 2, 7, 6,
        0, 3, 7, 0, 7, 4,
        1, 2, 6, 1, 6, 5
    )

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices)
            position(0)
        }

    private val indexBuffer: ShortBuffer =
        ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(indices)
            position(0)
        }

    private val projection = FloatArray(16)
    private val baseView = FloatArray(16)
    private val view = FloatArray(16)
    private val headViewRot = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    private val orientationLock = Any()
    private val orientation = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private val recenterReference = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z

    private var program = 0
    private var posHandle = -1
    private var mvpHandle = -1
    private var colorHandle = -1

    fun setOrientation(wxyz: FloatArray) {
        if (wxyz.size < 4) {
            return
        }
        synchronized(orientationLock) {
            normalizeInto(wxyz, orientation)
        }
    }

    fun recenter() {
        synchronized(orientationLock) {
            recenterReference[0] = orientation[0]
            recenterReference[1] = orientation[1]
            recenterReference[2] = orientation[2]
            recenterReference[3] = orientation[3]
        }
    }

    fun recenterYawOnly() {
        synchronized(orientationLock) {
            // Recenter only yaw. Including pitch/roll at init can make content jump vertically.
            val w = orientation[0]
            val x = orientation[1]
            val y = orientation[2]
            val z = orientation[3]
            val yaw = atan2(
                2f * (w * y + x * z),
                1f - 2f * (x * x + y * y)
            )
            val half = 0.5f * yaw
            recenterReference[0] = cos(half)
            recenterReference[1] = 0f
            recenterReference[2] = sin(half)
            recenterReference[3] = 0f
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projection, 0, 60f, aspect, 0.1f, 100f)
        Matrix.setLookAtM(
            baseView,
            0,
            0f, 0f, 4f,
            0f, 0f, 0f,
            0f, 1f, 0f
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val q = FloatArray(4)
        val qRef = FloatArray(4)
        synchronized(orientationLock) {
            q[0] = orientation[0]
            q[1] = orientation[1]
            q[2] = orientation[2]
            q[3] = orientation[3]
            qRef[0] = recenterReference[0]
            qRef[1] = recenterReference[1]
            qRef[2] = recenterReference[2]
            qRef[3] = recenterReference[3]
        }

        val qRel = multiplyQuat(conjugateQuat(qRef), q)

        // Camera rotation is the inverse of head pose.
        val qInv = conjugateQuat(qRel)
        quaternionToMatrix(qInv, headViewRot)
        Matrix.multiplyMM(view, 0, headViewRot, 0, baseView, 0)

        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, 0.8f, 0.8f, 0.8f)

        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(colorHandle, 0.2f, 0.85f, 0.95f, 1f)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun quaternionToMatrix(q: FloatArray, out: FloatArray) {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f

        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f

        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f

        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        return programId
    }

    private fun compileShader(type: Int, source: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)
        return shaderId
    }

    private fun conjugateQuat(q: FloatArray): FloatArray {
        return floatArrayOf(q[0], -q[1], -q[2], -q[3])
    }

    private fun multiplyQuat(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        )
    }

    private fun normalizeInto(src: FloatArray, dst: FloatArray) {
        val n = sqrt(
            src[0] * src[0] +
                src[1] * src[1] +
                src[2] * src[2] +
                src[3] * src[3]
        )
        if (n < 1e-6f) {
            dst[0] = 1f
            dst[1] = 0f
            dst[2] = 0f
            dst[3] = 0f
            return
        }
        dst[0] = src[0] / n
        dst[1] = src[1] / n
        dst[2] = src[2] / n
        dst[3] = src[3] / n
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvpMatrix;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
