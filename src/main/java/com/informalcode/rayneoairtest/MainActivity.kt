package com.informalcode.rayneoairtest

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CubeRenderer
    private lateinit var statusText: TextView
    private lateinit var recenterButton: Button
    private lateinit var rayNeoSession: RayNeoSession
    private var shouldAutoRecenter = true
    private var autoRecenterAfterMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = CubeRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(20, 20, 20, 20)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Waiting for RayNeo Air 4 Pro..."
        }

        recenterButton = Button(this).apply {
            text = "Recenter"
            setOnClickListener {
                renderer.recenter()
                statusText.text = "Recentered"
                glView.requestRender()
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                glView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
            addView(
                recenterButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = 20
                    marginEnd = 20
                }
            )
        }
        setContentView(root)

        rayNeoSession = RayNeoSession(
            context = this,
            onStatus = { msg -> statusText.text = msg },
            onOrientation = { quat ->
                renderer.setOrientation(quat)
                if (shouldAutoRecenter && SystemClock.elapsedRealtime() >= autoRecenterAfterMs) {
                    renderer.recenterYawOnly()
                    shouldAutoRecenter = false
                    statusText.text = "Sensor initialized and centered"
                }
                glView.requestRender()
            }
        )
    }

    override fun onResume() {
        super.onResume()
        shouldAutoRecenter = true
        autoRecenterAfterMs = SystemClock.elapsedRealtime() + AUTO_RECENTER_DELAY_MS
        glView.onResume()
        glView.requestRender()
        rayNeoSession.start()
    }

    override fun onPause() {
        rayNeoSession.stop()
        glView.onPause()
        super.onPause()
    }

    private companion object {
        const val AUTO_RECENTER_DELAY_MS = 2000L
    }
}
