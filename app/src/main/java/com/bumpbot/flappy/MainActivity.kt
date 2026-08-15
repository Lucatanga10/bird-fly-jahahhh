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

    private var statusText: TextView? = null
    private var btnStart: Button? = null
    private var btnAccessibility: Button? = null
    private var btnFixSamsung: Button? = null
    private var btnSecurity: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            statusText = findViewById(R.id.status_text)
            btnStart = findViewById(R.id.btn_start)
            btnAccessibility = findViewById(R.id.btn_accessibility)
            btnFixSamsung = findViewById(R.id.btn_fix_samsung)
            btnSecurity = findViewById(R.id.btn_security)

            btnStart?.setOnClickListener { startBot() }
            btnAccessibility?.setOnClickListener { openAccessibilitySettings() }
            btnFixSamsung?.setOnClickListener { openAppInfo() }
            btnSecurity?.setOnClickListener { openSecuritySettings() }

            requestNotificationPermission()
            updateStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore init: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateStatus()
        } catch (_: Exception) {}
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
        sb.appendLine(if (overlayOk) "● Overlay: OK" else "○ Overlay: da attivare")
        sb.appendLine(if (accessOk) "● Accessibility: OK" else "○ Accessibility: da attivare")

        if (overlayOk && accessOk) {
            sb.appendLine()
            sb.appendLine("Tutto pronto! Premi Launch Bot")
        } else if (!accessOk) {
            sb.appendLine()
            sb.appendLine("Samsung: premi il bottone arancione,")
            sb.appendLine("aspetta i 3 puntini in alto a destra,")
            sb.appendLine("poi 'Consenti impostazioni con restrizioni'")
        }
        statusText?.text = sb.toString()

        btnAccessibility?.isEnabled = !accessOk

        val showSamsungFix = !accessOk
        btnFixSamsung?.visibility = if (showSamsungFix) android.view.View.VISIBLE else android.view.View.GONE
        btnSecurity?.visibility = if (showSamsungFix) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startBot() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_OVERLAY)
            } catch (e: Exception) {
                Toast.makeText(this, "Errore overlay: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Errore proiezione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Attiva BumpBot nella lista", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Aspetta i 3 puntini ⋮ in alto a destra, poi 'Consenti impostazioni con restrizioni'",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent("android.settings.SECURITY_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
        Toast.makeText(
            this,
            "Cerca: Altre impostazioni di sicurezza > Impostazioni con restrizioni",
            Toast.LENGTH_LONG
        ).show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        try {
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
                        Toast.makeText(this, "Bot avviato — vai su Bump", Toast.LENGTH_SHORT)
                            .show()
                        moveTaskToBack(true)
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
