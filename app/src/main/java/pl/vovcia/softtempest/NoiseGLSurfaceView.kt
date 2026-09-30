package pl.vovcia.softtempest

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView

/**
 * Transparent, always-on-top [GLSurfaceView] hosting [NoiseRenderer].
 *
 * The surface is created with an 8-bit alpha channel and placed above the (empty) window
 * content so that the compositor blends the noise over whatever is beneath the overlay window.
 */
class NoiseGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer = NoiseRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun applySettings(settings: NoiseSettings) {
        renderer.amplitude = settings.amplitude
        renderer.mode = settings.mode
    }
}
