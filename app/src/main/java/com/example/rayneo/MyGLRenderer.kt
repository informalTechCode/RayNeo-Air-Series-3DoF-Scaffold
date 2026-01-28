package com.example.rayneo

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer(private val headTracker: HeadTracker) : GLSurfaceView.Renderer {

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // Scratch arrays
    private val tempMatrix = FloatArray(16)
    private val inverseOrientation = FloatArray(16)

    private lateinit var square: Square

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        square = Square()
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Set the camera position (View matrix)
        // The HeadTracker gives us the rotation of the Head in World space.
        // The Camera is attached to the Head.
        // View Matrix = Inverse(HeadRotation * HeadTranslation). We ignore translation.
        // View Matrix = Transpose(HeadRotation) since it's a rotation matrix.

        val headRotation = headTracker.getRotationMatrix()
        Matrix.transposeM(inverseOrientation, 0, headRotation, 0)

        // We might need to adjust for coordinate system differences.
        // Android Sensor: Y points North/Up?
        // OpenGL: Y is Up, Z is out of screen (towards viewer), X is Right.
        // The Glasses likely follow a specific frame.
        // We'll trust the rotation matrix for now and verify if we can.

        System.arraycopy(inverseOrientation, 0, viewMatrix, 0, 16)

        // Draw the square
        // Calculate the projection and view transformation
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Model Matrix is Identity (Square at 0,0,-3 or something)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -3f) // Move square in front of camera

        Matrix.multiplyMM(tempMatrix, 0, vPMatrix, 0, modelMatrix, 0)

        square.draw(tempMatrix)
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        // this projection matrix is applied to object coordinates
        // in the onDrawFrame() method
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 100f)
    }
}

class Square {
    private val vertexShaderCode =
        "uniform mat4 uMVPMatrix;" +
        "attribute vec4 vPosition;" +
        "attribute vec2 aTexCoordinate;" +
        "varying vec2 vTexCoordinate;" +
        "void main() {" +
        "  gl_Position = uMVPMatrix * vPosition;" +
        "  vTexCoordinate = aTexCoordinate;" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
        "uniform sampler2D uTexture;" +
        "varying vec2 vTexCoordinate;" +
        "void main() {" +
        "  gl_FragColor = texture2D(uTexture, vTexCoordinate);" +
        "}"

    private var mProgram: Int
    private var vertexBuffer: FloatBuffer
    private var drawListBuffer: ShortBuffer
    private var textureBuffer: FloatBuffer
    private var textureHandle: Int = 0

    // number of coordinates per vertex in this array
    private val COORDS_PER_VERTEX = 3
    private var squareCoords = floatArrayOf(
         -1.0f,  1.0f, 0.0f,   // top left
         -1.0f, -1.0f, 0.0f,   // bottom left
          1.0f, -1.0f, 0.0f,   // bottom right
          1.0f,  1.0f, 0.0f    // top right
    )

    private val drawOrder = shortArrayOf(0, 1, 2, 0, 2, 3) // order to draw vertices

    private val textureCoords = floatArrayOf(
        0.0f, 0.0f, // Top left
        0.0f, 1.0f, // Bottom left
        1.0f, 1.0f, // Bottom right
        1.0f, 0.0f  // Top right
    )

    init {
        // Initialize buffers
        val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(squareCoords)
        vertexBuffer.position(0)

        val dlb = ByteBuffer.allocateDirect(drawOrder.size * 2)
        dlb.order(ByteOrder.nativeOrder())
        drawListBuffer = dlb.asShortBuffer()
        drawListBuffer.put(drawOrder)
        drawListBuffer.position(0)

        val tb = ByteBuffer.allocateDirect(textureCoords.size * 4)
        tb.order(ByteOrder.nativeOrder())
        textureBuffer = tb.asFloatBuffer()
        textureBuffer.put(textureCoords)
        textureBuffer.position(0)

        // Prepare shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        mProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        generateTexture()
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    private fun generateTexture() {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // Checkerboard
        for (x in 0 until 256) {
            for (y in 0 until 256) {
                if (((x / 32) + (y / 32)) % 2 == 0) {
                    bitmap.setPixel(x, y, Color.WHITE)
                } else {
                    bitmap.setPixel(x, y, Color.BLUE)
                }
            }
        }

        val textureHandleArr = IntArray(1)
        GLES20.glGenTextures(1, textureHandleArr, 0)

        if (textureHandleArr[0] != 0) {
            textureHandle = textureHandleArr[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(mProgram)

        val positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * 4, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoordinate")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        val mVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mVPMatrixHandle, 1, false, mvpMatrix, 0)

        val mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture")
        GLES20.glUniform1i(mSamplerHandle, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.size, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
}
