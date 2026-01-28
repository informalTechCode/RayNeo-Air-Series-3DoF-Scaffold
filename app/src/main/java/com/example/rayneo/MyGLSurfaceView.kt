package com.example.rayneo

import android.content.Context
import android.opengl.GLSurfaceView

class MyGLSurfaceView(context: Context, headTracker: HeadTracker) : GLSurfaceView(context) {

    private val renderer: MyGLRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer(headTracker)
        setRenderer(renderer)
        // Render only when there is a change in the drawing data?
        // For smooth head tracking, continuous rendering is usually better.
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
}
