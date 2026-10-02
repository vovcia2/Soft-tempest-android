package pl.vovcia.softtempest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the overlay window when the accessibility backend is not
 * available. The window is a `TYPE_APPLICATION_OVERLAY` (see [NoiseOverlayWindow]).
 */
class OverlayService : Service() {

    private lateinit var settings: NoiseSettings
    private var window: NoiseOverlayWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = NoiseSettings(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.overlayEnabled = false // explicit user stop: do not restore on boot
                stopOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startOverlay()
        }
        return START_STICKY
    }

    private fun startOverlay() {
        // Must be called promptly after startForegroundService(), even if we then decide to stop;
        // on API 34+ the type must match the manifest's foregroundServiceType.
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)

        if (NoiseAccessibilityService.connected.value) {
            Log.i(TAG, "Accessibility backend active; not starting system alert window")
            stopOverlay()
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot start")
            stopOverlay()
            stopSelf()
            return
        }

        settings.overlayEnabled = true

        if (window?.isShowing == true) return // already running; just refreshed the notification

        val w = window ?: createWindowContext().let { ctx ->
            NoiseOverlayWindow(
            uiContext = ctx,
            windowManager = ctx.getSystemService(WINDOW_SERVICE) as WindowManager,
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            trusted = false,
            settings = settings
            )
        }.also {
            it.onSettingsChanged = { updateNotification() }
            window = it
        }
        w.show()
        if (w.isShowing) {
            OverlayState.set(OverlayState.Backend.SYSTEM_ALERT_WINDOW)
        } else {
            stopOverlay()
            stopSelf()
        }
    }

    private fun stopOverlay() {
        window?.hide()
        OverlayState.clear(OverlayState.Backend.SYSTEM_ALERT_WINDOW)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    /**
     * On API 30+ windows should be added from a UI context bound to a display; a plain Service
     * context triggers StrictMode warnings and, on some OEM builds, wrong metrics.
     */
    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }
        }
        return this
    }

    // ---- notification -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pct = (settings.amplitude * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_noise)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, NoiseSettings.modeLabel(this, settings.mode), pct))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        if (window?.isShowing != true) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "pl.vovcia.softtempest.action.START"
        const val ACTION_STOP = "pl.vovcia.softtempest.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
