package pl.vovcia.softtempest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that owns the overlay window.
 *
 * The window is a `TYPE_APPLICATION_OVERLAY` that is click-through (`FLAG_NOT_TOUCHABLE`),
 * never takes focus, and covers the whole screen including the status/navigation bar areas.
 * A [NoiseGLSurfaceView] inside it renders the noise continuously on the GPU.
 *
 * Rendering is paused while the screen is off to save battery; there is nothing to mask then.
 */
class OverlayService : Service() {

    /** UI context the overlay window is created from (a window context on API 30+). */
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var settings: NoiseSettings
    private var glView: NoiseGLSurfaceView? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        glView?.applySettings(settings)
        updateNotification()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> glView?.onPause()
                Intent.ACTION_SCREEN_ON -> glView?.onResume()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowContext = createWindowContext()
        windowManager = windowContext.getSystemService(WINDOW_SERVICE) as WindowManager
        settings = NoiseSettings(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot start")
            stopOverlay()
            stopSelf()
            return
        }

        if (glView != null) return // already running; just refreshed the notification

        val lp = buildLayoutParams()
        val view = NoiseGLSurfaceView(windowContext).also {
            it.renderer.windowAlpha = lp.alpha
            it.applySettings(settings)
        }
        windowManager.addView(view, lp)
        glView = view

        settings.registerListener(prefsListener)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        _running.value = true
    }

    private fun stopOverlay() {
        glView?.let { view ->
            settings.unregisterListener(prefsListener)
            runCatching { unregisterReceiver(screenReceiver) }
            view.onPause()
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        glView = null
        _running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "SoftTempestNoise"
        // Android 12+ drops "untrusted" touches that pass through an overlay window whose
        // *window* alpha (LayoutParams.alpha, not pixel content) exceeds the system threshold
        // (0.8 by default). FLAG_NOT_TOUCHABLE alone does not exempt us, so every other app,
        // the launcher included, would become unclickable. Stay just below the threshold; the
        // renderer compensates so the slider still maps to the effective opacity.
        lp.alpha = maxPassThroughWindowAlpha()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Ask for the panel's highest refresh rate: more frames per second means more independent
        // noise realisations per second (helps the temporal mode on 90/120 Hz panels).
        pickFastestDisplayMode()?.let { lp.preferredDisplayModeId = it }
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
            getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }.getOrDefault(DEFAULT_MAX_OBSCURING_OPACITY)
        return (threshold - 0.01f).coerceIn(0.05f, 1f)
    }

    private fun defaultDisplay(): Display? =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * On API 30+ windows should be added from a UI context bound to a display; a plain Service
     * context triggers StrictMode warnings and, on some OEM builds, wrong metrics.
     */
    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = defaultDisplay()
            if (display != null) {
                return createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }
        }
        return this
    }

    private fun pickFastestDisplayMode(): Int? {
        val display = defaultDisplay() ?: return null
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return null
        return if (best.refreshRate > current.refreshRate + 0.5f) best.modeId else null
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
        if (glView == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_MAX_OBSCURING_OPACITY = 0.8f

        const val ACTION_START = "pl.vovcia.softtempest.action.START"
        const val ACTION_STOP = "pl.vovcia.softtempest.action.STOP"

        private val _running = MutableStateFlow(false)

        /** Whether the overlay window is currently attached. Observed by [MainActivity]. */
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
