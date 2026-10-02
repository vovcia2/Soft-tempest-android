package pl.vovcia.softtempest

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * The full-screen, click-through noise window. Shared by both backends:
 *
 *  * [OverlayService] adds it as `TYPE_APPLICATION_OVERLAY` (needs `SYSTEM_ALERT_WINDOW`).
 *    Untrusted, so the window alpha must stay below the touch-obscuring threshold, and it is
 *    z-ordered below the keyguard, status bar and navigation bar.
 *  * [NoiseAccessibilityService] adds it as `TYPE_ACCESSIBILITY_OVERLAY`. Trusted, so full
 *    opacity is allowed and touches still pass through, and it sits above the lock screen.
 *
 * Rendering pauses while the screen is off, and for the untrusted backend also while the
 * keyguard is showing (the window is hidden beneath it anyway).
 */
class NoiseOverlayWindow(
    private val uiContext: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val trusted: Boolean,
    private val settings: NoiseSettings
) {
    private var view: NoiseTextureView? = null

    /** Called on the main thread whenever a setting changes while the window is showing. */
    var onSettingsChanged: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == NoiseSettings.KEY_AMPLITUDE || key == NoiseSettings.KEY_MODE) {
            view?.applySettings(settings)
            onSettingsChanged?.invoke()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> view?.pause()
                Intent.ACTION_SCREEN_ON -> if (trusted || !isKeyguardLocked()) view?.resume()
                Intent.ACTION_USER_PRESENT -> view?.resume()
            }
        }
    }

    fun show() {
        if (view != null) return
        val lp = buildLayoutParams()
        val v = NoiseTextureView(uiContext).also {
            it.renderer.windowAlpha = lp.alpha
            it.targetFps = defaultDisplay()?.refreshRate ?: 60f
            it.applySettings(settings)
        }
        try {
            windowManager.addView(v, lp)
        } catch (e: RuntimeException) {
            Log.e(TAG, "addView failed for window type $windowType", e)
            return
        }
        view = v
        if (!trusted && isKeyguardLocked()) v.pause()

        settings.registerListener(prefsListener)
        ContextCompat.registerReceiver(
            uiContext,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun hide() {
        val v = view ?: return
        view = null
        settings.unregisterListener(prefsListener)
        runCatching { uiContext.unregisterReceiver(screenReceiver) }
        v.pause()
        runCatching { windowManager.removeViewImmediate(v) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

    // ---- layout ---------------------------------------------------------------------------------

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Required for TextureView in a WindowManager-added window (no Activity here).
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "SoftTempestNoise"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Android 12+ drops "untrusted" touches that pass through an overlay window whose
        // *window* alpha (LayoutParams.alpha, not pixel content) exceeds the system threshold
        // (0.8 by default). FLAG_NOT_TOUCHABLE alone does not exempt us, so every other app,
        // the launcher included, would become unclickable. Stay just below the threshold; the
        // renderer compensates so the slider still maps to the effective opacity.
        // Trusted overlays (accessibility) are exempt and may use full opacity.
        lp.alpha = if (trusted) 1f else maxPassThroughWindowAlpha()
        // Ask for the panel's highest refresh rate: more frames per second means more independent
        // noise realisations per second (helps the temporal mode on 90/120 Hz panels).
        pickFastestDisplayMode()?.let { lp.preferredDisplayModeId = it }
        // The window's single buffer layer must stay the only obscuring surface of this UID; see
        // NoiseTextureView for why a SurfaceView would double-count against the touch threshold.
        return lp
    }

    /**
     * Highest window alpha at which touches still pass through to the apps underneath.
     * Slightly below the reported threshold to be safe against float rounding in the
     * input dispatcher.
     */
    private fun maxPassThroughWindowAlpha(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val threshold = runCatching {
            uiContext.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }.getOrDefault(DEFAULT_MAX_OBSCURING_OPACITY)
        return (threshold - 0.01f).coerceIn(0.05f, 1f)
    }

    private fun defaultDisplay(): Display? =
        uiContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun pickFastestDisplayMode(): Int? {
        val display = defaultDisplay() ?: return null
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return null
        return if (best.refreshRate > current.refreshRate + 0.5f) best.modeId else null
    }

    private fun isKeyguardLocked(): Boolean =
        uiContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private companion object {
        const val TAG = "NoiseOverlayWindow"
        const val DEFAULT_MAX_OBSCURING_OPACITY = 0.8f
    }
}
