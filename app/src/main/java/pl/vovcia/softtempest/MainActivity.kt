package pl.vovcia.softtempest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
            OverlayService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = NoiseSettings(this)

        setupPermissionCard()
        setupAmplitude()
        setupModes()
        setupSwitch()
        setupRestoreOnBoot()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
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
        binding.overlaySwitch.isEnabled = granted
    }

    // ---- controls -----------------------------------------------------------------------------

    private fun setupSwitch() {
        binding.overlaySwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (checked) startOverlay() else OverlayService.stop(this)
        }
    }

    private fun setupRestoreOnBoot() {
        binding.restoreOnBootSwitch.isChecked = settings.restoreOnBoot
        binding.restoreOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            settings.restoreOnBoot = checked
        }
    }

    private fun startOverlay() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, R.string.toast_permission_required, Toast.LENGTH_SHORT).show()
            setSwitchChecked(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        OverlayService.start(this)
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
                OverlayService.running.collect { running ->
                    setSwitchChecked(running)
                    binding.overlayStatus.text =
                        getString(if (running) R.string.overlay_running else R.string.overlay_stopped)
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
