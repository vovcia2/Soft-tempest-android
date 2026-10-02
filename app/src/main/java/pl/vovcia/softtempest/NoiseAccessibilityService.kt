package pl.vovcia.softtempest

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the noise window as a `TYPE_ACCESSIBILITY_OVERLAY`.
 *
 * Why an accessibility service: it is the only way for a third-party app to own a *trusted*
 * overlay. Trusted overlays are exempt from the untrusted-touch opacity cap (full amplitude
 * while staying click-through) and are z-ordered above the lock screen, status bar and
 * navigation bar. The system also starts enabled accessibility services at boot, before the
 * first unlock.
 *
 * The service deliberately requests no accessibility events and no window content access (see
 * `res/xml/accessibility_service_config.xml`); it cannot read anything on screen.
 *
 * While connected it takes over from [OverlayService], and it follows
 * [NoiseSettings.overlayEnabled] so the Start/Stop switch controls both backends.
 */
class NoiseAccessibilityService : AccessibilityService() {

    private lateinit var settings: NoiseSettings
    private var window: NoiseOverlayWindow? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == NoiseSettings.KEY_OVERLAY_ENABLED) sync()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = NoiseSettings(this)
        // The service's own WindowManager carries the accessibility window token; a window
        // context would not, and WindowManager rejects accessibility overlays without it.
        window = NoiseOverlayWindow(
            uiContext = this,
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            trusted = true,
            settings = settings
        )
        settings.registerListener(prefsListener)
        _connected.value = true
        Log.i(TAG, "Accessibility overlay backend connected")
        sync()
    }

    private fun sync() {
        val w = window ?: return
        if (settings.overlayEnabled) {
            // Take over from the system-alert-window backend if it is running.
            stopService(Intent(this, OverlayService::class.java))
            w.show()
            if (w.isShowing) OverlayState.set(OverlayState.Backend.ACCESSIBILITY)
        } else {
            w.hide()
            OverlayState.clear(OverlayState.Backend.ACCESSIBILITY)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!_connected.value) return
        _connected.value = false
        if (::settings.isInitialized) settings.unregisterListener(prefsListener)
        window?.hide()
        window = null
        OverlayState.clear(OverlayState.Backend.ACCESSIBILITY)
        Log.i(TAG, "Accessibility overlay backend disconnected")
    }

    companion object {
        private const val TAG = "NoiseA11yService"

        private val _connected = MutableStateFlow(false)

        /** True while the system has this service bound (enabled in Accessibility settings). */
        val connected: StateFlow<Boolean> = _connected

        /** Whether the user has enabled the service in Settings (it may not be bound yet). */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val self = ComponentName(context, NoiseAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }
}
