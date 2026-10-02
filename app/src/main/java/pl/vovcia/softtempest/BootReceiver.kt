package pl.vovcia.softtempest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Restores the overlay after a reboot or an app update if the user last left it running.
 *
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are exempt from the background foreground-service
 * start restrictions, and the `specialUse` type is allowed from BOOT_COMPLETED on Android 15+.
 * The overlay permission can be revoked while the app is not running, so it is re-checked here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> restore(context)
        }
    }

    private fun restore(context: Context) {
        val settings = NoiseSettings(context)
        if (!settings.restoreOnBoot || !settings.overlayEnabled) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission revoked; not restoring overlay")
            return
        }
        Log.i(TAG, "Restoring noise overlay")
        OverlayService.start(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
