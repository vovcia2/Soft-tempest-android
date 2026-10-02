package pl.vovcia.softtempest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Transparent [TextureView] hosting [NoiseRenderer] on a dedicated EGL / OpenGL ES 3.0 thread.
 *
 * Why not GLSurfaceView: a SurfaceView owns a separate compositor layer. On Android 12+ the
 * input dispatcher counts every buffer-backed layer of our UID that sits above the touched app
 * as an obscuring window and combines their opacities (1 - (1-a)(1-b)). A window at 0.79 plus a
 * SurfaceView layer at 0.79 gives 0.96, above the 0.8 untrusted-touch threshold, so touches to
 * other apps are dropped. A TextureView is composited into the window's own buffer, so there is
 * exactly one layer and the window alpha alone decides whether touches pass through.
 */
class NoiseTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    val renderer = NoiseRenderer()

    private var thread: RenderThread? = null

    /** Frames per second to target; set from the display refresh rate by the service. */
    @Volatile var targetFps: Float = 60f

    init {
        isOpaque = false // allow the transparent clear colour to show the content underneath
        surfaceTextureListener = this
    }

    fun applySettings(settings: NoiseSettings) {
        renderer.amplitude = settings.amplitude
        renderer.mode = settings.mode
    }

    fun pause() = thread?.setPaused(true)
    fun resume() = thread?.setPaused(false)

    // ---- SurfaceTextureListener ---------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread = RenderThread(surface, renderer, this).also {
            it.setSize(width, height)
            it.start()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.setSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.shutdown()
        thread = null
        return true // we no longer use the SurfaceTexture; the view may release it
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    // ---- GL thread ----------------------------------------------------------------------------

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private val renderer: NoiseRenderer,
        private val view: NoiseTextureView
    ) : Thread("NoiseGL") {

        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        private var running = true
        private var paused = false
        private var width = 1
        private var height = 1
        private var sizeDirty = true

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun setSize(w: Int, h: Int) = lock.withLock {
            width = w; height = h; sizeDirty = true
            cond.signalAll()
        }

        fun setPaused(value: Boolean) = lock.withLock {
            paused = value
            cond.signalAll()
        }

        fun shutdown() {
            lock.withLock {
                running = false
                cond.signalAll()
            }
            join(2_000)
        }

        override fun run() {
            if (!initEgl()) {
                Log.e(TAG, "EGL initialisation failed; overlay will stay transparent")
                return
            }
            renderer.onSurfaceCreated()
            try {
                loop()
            } finally {
                renderer.onSurfaceDestroyed()
                releaseEgl()
            }
        }

        private fun loop() {
            while (true) {
                var w = 0
                var h = 0
                var resize = false
                lock.withLock {
                    while (running && paused) cond.await()
                    if (!running) return
                    if (sizeDirty) {
                        resize = true; sizeDirty = false
                        w = width; h = height
                    }
                }
                if (resize) renderer.onSurfaceChanged(w, h)

                val t0 = System.nanoTime()
                renderer.onDrawFrame()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    lock.withLock { if (!running) return }
                    sleep(16)
                    continue
                }
                // Safety net: the consumer normally paces us at the display rate, but never spin
                // faster than the target frame rate (battery).
                val periodNs = (1_000_000_000.0 / view.targetFps.coerceIn(10f, 240f)).toLong()
                val remainingNs = periodNs - (System.nanoTime() - t0)
                if (remainingNs > 500_000L) {
                    sleep(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
                }
            }
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                Log.e(TAG, "No RGBA8888 OpenGL ES 3.0 EGL config available")
                return false
            }
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surfaceTexture, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun releaseEgl() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        private companion object {
            const val TAG = "NoiseGL"
        }
    }
}
