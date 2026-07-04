package com.captionoverlay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.captionoverlay.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionUi() }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupLanguageSpinner()

        binding.btnGrantMic.setOnClickListener {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnGrantOverlay.setOnClickListener {
            val uri = Uri.parse("package:$packageName")
            overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        }

        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) stopOverlay() else startOverlay()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun setupLanguageSpinner() {
        val labels = prefs.supportedLanguages.map { it.second }
        binding.spinnerLanguage.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val currentIndex = prefs.supportedLanguages.indexOfFirst { it.first == prefs.language }
        binding.spinnerLanguage.setSelection(if (currentIndex >= 0) currentIndex else 0)
        binding.spinnerLanguage.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long
                ) {
                    prefs.language = prefs.supportedLanguages[position].first
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun refreshPermissionUi() {
        val micOk = hasMicPermission()
        val overlayOk = hasOverlayPermission()

        binding.btnGrantMic.isEnabled = !micOk
        binding.btnGrantMic.text = if (micOk) getString(R.string.perm_mic_granted)
        else getString(R.string.perm_mic_grant)

        binding.btnGrantOverlay.isEnabled = !overlayOk
        binding.btnGrantOverlay.text = if (overlayOk) getString(R.string.perm_overlay_granted)
        else getString(R.string.perm_overlay_grant)

        binding.btnToggleService.isEnabled = micOk && overlayOk
        binding.btnToggleService.text =
            if (isServiceRunning) getString(R.string.stop_captioning) else getString(R.string.start_captioning)
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        refreshPermissionUi()
        moveTaskToBack(true)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        isServiceRunning = false
        refreshPermissionUi()
    }

    companion object {
        // Simple in-process flag; good enough since this is a single-app overlay toggle.
        var isServiceRunning = false
    }
}
