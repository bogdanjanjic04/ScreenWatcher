package com.screenwatch

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Thin coordinator activity.
 * All UI lives in the persistent notification. MainActivity only handles:
 *  1. Draw-over-apps permission check (needed for overlay region selector)
 *  2. MediaProjection permission dialog (must come from an Activity)
 *  3. Sensitivity slider + Start/Stop buttons as a fallback UI
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var tvStatus: TextView
    private lateinit var seekbar: SeekBar
    private lateinit var tvThreshold: TextView
    private lateinit var btnSelect: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Step 1: ask for Draw Over Apps permission, then show overlay
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            sendToService(ScreenMonitorService.ACTION_SELECT_REGION)
            tvStatus.text = "Draw overlay selector shown — switch to your target screen."
        } else {
            tvStatus.text = "Permission denied. 'Draw over other apps' is required for the overlay selector."
        }
    }

    // Step 2: MediaProjection permission dialog result → forward token to service
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val threshold = seekbar.progress.coerceAtLeast(1)
            val i = Intent(this, ScreenMonitorService::class.java).apply {
                action = ScreenMonitorService.ACTION_START_MONITOR
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("threshold", threshold)
            }
            startServiceCompat(i)
            tvStatus.text = "Monitoring started. You can close this screen."
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
        } else {
            tvStatus.text = "Screen capture permission denied."
        }
    }

    // Android 13+ notification permission
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        tvStatus     = findViewById(R.id.tvStatus)
        seekbar      = findViewById(R.id.seekbarThreshold)
        tvThreshold  = findViewById(R.id.tvThreshold)
        btnSelect    = findViewById(R.id.btnSelect)
        btnStart     = findViewById(R.id.btnStart)
        btnStop      = findViewById(R.id.btnStop)

        seekbar.max      = 200
        seekbar.progress = 10
        tvThreshold.text = "Min changed pixels: 10"
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                tvThreshold.text = "Min changed pixels: ${v.coerceAtLeast(1)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Make sure the service is running so the notification appears
        startServiceCompat(Intent(this, ScreenMonitorService::class.java))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        btnSelect.setOnClickListener { requestOverlayThenSelect() }

        btnStart.setOnClickListener {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            sendToService(ScreenMonitorService.ACTION_STOP_SERVICE)
            tvStatus.text = "Stopped."
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }
    }

    private fun requestOverlayThenSelect() {
        if (Settings.canDrawOverlays(this)) {
            sendToService(ScreenMonitorService.ACTION_SELECT_REGION)
            tvStatus.text = "Overlay selector shown — navigate to the screen you want to watch, then drag to select."
        } else {
            tvStatus.text = "Grant 'Draw over other apps' permission to use the overlay selector."
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
    }

    private fun sendToService(action: String) {
        startServiceCompat(Intent(this, ScreenMonitorService::class.java).apply { this.action = action })
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
