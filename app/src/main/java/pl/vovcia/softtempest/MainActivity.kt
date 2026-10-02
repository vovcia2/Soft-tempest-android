package pl.vovcia.softtempest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pl.vovcia.softtempest.databinding.ActivityMainBinding

/**
 * Control screen: overlay permission flow, Start/Stop switch, amplitude slider, mode selection,
 * and the limitations disclaimer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: NoiseSettings

    /** Set while we programmatically update the switch so the listener does not re-trigger. */
    private var updatingSwitch = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionState()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether granted or not, the service can run; the notification is just less visible.
            OverlayController.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = NoiseSettings(this)

        setupPermissionCard()
        setupAccessibilityCard()
        setupAmplitude()
        setupModes()
        setupSwitch()
        setupRestoreOnBoot()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshAccessibilityState()
        OverlayController.resync(this)
    }

    // ---- permission ---------------------------------------------------------------------------

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun setupPermissionCard() {
        binding.permissionButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun refreshPermissionState() {
        val granted = hasOverlayPermission()
        binding.permissionStatus.text =
            getString(if (granted) R.string.permission_granted else R.string.permission_missing)
        binding.permissionButton.isEnabled = !granted
        binding.overlaySwitch.isEnabled = OverlayController.canStart(this)
    }

    // ---- accessibility backend ----------------------------------------------------------------

    private fun setupAccessibilityCard() {
        binding.accessibilityButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun refreshAccessibilityState() {
        val connected = NoiseAccessibilityService.connected.value
        val enabled = connected || NoiseAccessibilityService.isEnabled(this)
        binding.accessibilityStatus.text = getString(
            when {
                connected -> R.string.a11y_status_connected
                enabled -> R.string.a11y_status_enabled_not_bound
                else -> R.string.a11y_status_disabled
            }
        )
        binding.accessibilityButton.text = getString(
            if (enabled) R.string.a11y_button_manage else R.string.a11y_button_enable
        )
        binding.accessibilityRestricted.visibility =
            if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        binding.overlaySwitch.isEnabled = OverlayController.canStart(this)
    }

    // ---- controls -----------------------------------------------------------------------------

    private fun setupSwitch() {
        binding.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) startOverlay() else OverlayController.stop(this)
        }
    }

    private fun setupRestoreOnBoot() {
        binding.restoreOnBootSwitch.isChecked = settings.restoreOnBoot
        binding.restoreOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            settings.restoreOnBoot = checked
        }
    }

    private fun startOverlay() {
        if (!OverlayController.canStart(this)) {
            Toast.makeText(this, R.string.toast_permission_required, Toast.LENGTH_SHORT).show()
            setSwitchChecked(false)
            return
        }
        // The foreground-service backend shows a notification; ask for the permission first.
        if (!NoiseAccessibilityService.connected.value &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        OverlayController.start(this)
    }

    private fun setupAmplitude() {
        val slider = binding.amplitudeSlider
        slider.value = settings.amplitude
        renderAmplitude(slider.value)
        slider.addOnChangeListener { _, value, fromUser ->
            renderAmplitude(value)
            if (fromUser) settings.amplitude = value
        }
    }

    private fun renderAmplitude(value: Float) {
        binding.amplitudeValue.text = getString(R.string.amplitude_value, (value * 100).toInt())
    }

    private fun setupModes() {
        val idForMode = mapOf(
            NoiseSettings.MODE_WHITE to binding.modeWhite.id,
            NoiseSettings.MODE_HFREQ_HORIZONTAL to binding.modeHfreq.id,
            NoiseSettings.MODE_TEMPORAL to binding.modeTemporal.id
        )
        val modeForId = idForMode.entries.associate { (mode, id) -> id to mode }

        binding.modeGroup.check(idForMode.getValue(settings.mode))
        renderModeDescription(settings.mode)
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForId[checkedId] ?: return@setOnCheckedChangeListener
            settings.mode = mode
            renderModeDescription(mode)
        }
    }

    private fun renderModeDescription(mode: Int) {
        binding.modeDescription.text = getString(
            when (mode) {
                NoiseSettings.MODE_HFREQ_HORIZONTAL -> R.string.mode_hfreq_desc
                NoiseSettings.MODE_TEMPORAL -> R.string.mode_temporal_desc
                else -> R.string.mode_white_desc
            }
        )
    }

    // ---- service state ------------------------------------------------------------------------

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(OverlayState.backend, NoiseAccessibilityService.connected) { b, c -> b to c }
                    .collect { (backend, _) ->
                        setSwitchChecked(backend != OverlayState.Backend.NONE)
                        binding.overlayStatus.text = getString(
                            when (backend) {
                                OverlayState.Backend.ACCESSIBILITY -> R.string.overlay_running_a11y
                                OverlayState.Backend.SYSTEM_ALERT_WINDOW -> R.string.overlay_running_saw
                                OverlayState.Backend.NONE -> R.string.overlay_stopped
                            }
                        )
                        refreshAccessibilityState()
                    }
            }
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        if (binding.overlaySwitch.isChecked == checked) return
        updatingSwitch = true
        binding.overlaySwitch.isChecked = checked
        updatingSwitch = false
    }
}
