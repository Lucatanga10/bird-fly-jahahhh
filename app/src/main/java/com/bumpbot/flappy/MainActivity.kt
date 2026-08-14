package com.bumpbot.flappy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumpbot.flappy.service.OverlayService
import com.bumpbot.flappy.service.TapAccessibilityService

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_MEDIA_PROJECTION = 1002
        const val REQUEST_NOTIFICATION = 1003
    }

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        btnStart = findViewById(R.id.btn_start)
        btnAccessibility = findViewById(R.id.btn_accessibility)

        btnStart.setOnClickListener { startBot() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }

        requestNotificationPermission()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
        }
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = TapAccessibilityService.instance != null

        val sb = StringBuilder()
        sb.appendLine(if (overlayOk) "● Overlay: granted" else "○ Overlay: needed")
        sb.appendLine(if (accessOk) "● Accessibility: enabled" else "○ Accessibility: enable it")
        statusText.text = sb.toString()

        btnStart.isEnabled = overlayOk
        btnAccessibility.isEnabled = !accessOk
    }

    private fun startBot() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Enable BumpBot in Accessibility", Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY -> {
                updateStatus()
                if (Settings.canDrawOverlays(this)) {
                    startBot()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("projectionData", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this, "Bot launched — switch to Bump", Toast.LENGTH_SHORT)
                        .show()
                    moveTaskToBack(true)
                }
            }
        }
    }
}
