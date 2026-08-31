package com.example.optimalx.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline for widget mic / Quick Note taps.
 * Requests [Manifest.permission.RECORD_AUDIO] when needed, then starts [WidgetVoiceService].
 */
class WidgetVoiceLauncherActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVoiceServiceAndFinish()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        if (action != WidgetVoiceService.ACTION_START_VOICE &&
            action != WidgetVoiceService.ACTION_QUICK_NOTE
        ) {
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceServiceAndFinish()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceServiceAndFinish() {
        val action = intent.action ?: run {
            finish()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, WidgetVoiceService::class.java).apply { this.action = action },
        )
        finish()
    }
}
