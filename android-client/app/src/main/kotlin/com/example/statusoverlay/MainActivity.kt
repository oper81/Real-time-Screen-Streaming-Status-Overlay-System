package com.example.statusoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mediaProjectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_MEDIA_PROJECTION = 1002
        const val WEBSOCKET_URL = "wss://YOUR-DOMAIN/ws?role=device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.grantPermissionButton).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            if (hasOverlayPermission()) {
                // Вместо прямого запуска сначала запрашиваем разрешение на захват экрана
                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION
                )
            } else {
                statusText.text = "Сначала выдайте разрешение на оверлей"
            }
        }

        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        } else {
            statusText.text = "Разрешение уже выдано"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            updateStatusText()
        }
        // Если пользователь одобрил трансляцию экрана
        else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startOverlayService(resultCode, data)
            } else {
                statusText.text = "Отказ в захвате экрана"
            }
        }
    }

    private fun updateStatusText() {
        statusText.text = if (hasOverlayPermission()) {
            "Статус: разрешение выдано ✓"
        } else {
            "Статус: разрешение не выдано"
        }
    }

    private fun startOverlayService(resultCode: Int, captureIntent: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("CAPTURE_INTENT", captureIntent)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
