package pl.vovcia.softtempest

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Single entry point for Start/Stop. Persists the user's choice and routes it to whichever
 * backend is available: the accessibility overlay if its service is connected, otherwise the
 * foreground service with a system alert window.
 */
object OverlayController {

    fun start(context: Context) {
        NoiseSettings(context).overlayEnabled = true
        if (NoiseAccessibilityService.connected.value) return // it reacts to the setting
        if (Settings.canDrawOverlays(context)) OverlayService.start(context)
    }

    fun stop(context: Context) {
        NoiseSettings(context).overlayEnabled = false // the accessibility backend reacts to this
        context.stopService(Intent(context, OverlayService::class.java))
    }

    /**
     * Re-attach the overlay if the user wants it running but no backend currently shows it,
     * e.g. after the accessibility service was disabled or the process was killed. Must be
     * called from a foreground context (an Activity) so a foreground service may be started.
     */
    fun resync(context: Context) {
        val settings = NoiseSettings(context)
        if (!settings.overlayEnabled) return
        if (OverlayState.backend.value != OverlayState.Backend.NONE) return
        if (NoiseAccessibilityService.connected.value) return
        if (Settings.canDrawOverlays(context)) OverlayService.start(context)
    }

    /** Whether any backend can show the overlay right now. */
    fun canStart(context: Context): Boolean =
        NoiseAccessibilityService.connected.value || Settings.canDrawOverlays(context)
}
