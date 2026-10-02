package pl.vovcia.softtempest

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent user settings backed by [SharedPreferences].
 *
 * The overlay service observes this store, so a change made in [MainActivity] is picked up
 * by the running renderer without restarting the service.
 */
class NoiseSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Overlay opacity / noise amplitude in `[0, 1]`. */
    var amplitude: Float
        get() = prefs.getFloat(KEY_AMPLITUDE, DEFAULT_AMPLITUDE).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_AMPLITUDE, value.coerceIn(0f, 1f)).apply()

    /** One of [MODE_WHITE], [MODE_HFREQ_HORIZONTAL], [MODE_TEMPORAL]. */
    var mode: Int
        get() = prefs.getInt(KEY_MODE, DEFAULT_MODE).coerceIn(MODE_WHITE, MODE_TEMPORAL)
        set(value) = prefs.edit().putInt(KEY_MODE, value.coerceIn(MODE_WHITE, MODE_TEMPORAL)).apply()

    /**
     * Whether the user last left the overlay running. Set by [OverlayService] on start and on an
     * explicit stop (not on a system kill), so a reboot can restore the user's last choice.
     */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /** Restore [overlayEnabled] after boot (and after the app is updated). */
    var restoreOnBoot: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_ON_BOOT, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val PREFS_NAME = "noise_settings"
        const val KEY_AMPLITUDE = "amplitude"
        const val KEY_MODE = "mode"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_RESTORE_ON_BOOT = "restore_on_boot"

        const val MODE_WHITE = 0
        const val MODE_HFREQ_HORIZONTAL = 1
        const val MODE_TEMPORAL = 2

        const val DEFAULT_AMPLITUDE = 0.35f
        const val DEFAULT_MODE = MODE_HFREQ_HORIZONTAL

        fun modeLabel(context: Context, mode: Int): String = when (mode) {
            MODE_HFREQ_HORIZONTAL -> context.getString(R.string.mode_hfreq)
            MODE_TEMPORAL -> context.getString(R.string.mode_temporal)
            else -> context.getString(R.string.mode_white)
        }
    }
}
