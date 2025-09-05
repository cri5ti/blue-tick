package com.cri5ti.bluetick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.util.Log

class MainActivity : ComponentActivity() {

    private var started = false

    // Only include the ones that *actually* require a prompt on Wear
    private val runtimePerms = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    // Ask for notifications optionally, but don't block on it
    private val optionalPerms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private fun hasAllRuntimePerms(): Boolean =
        runtimePerms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // If permanently denied, no dialog will appear — handle below
        if (hasAllRuntimePerms()) startHrServiceOnce()
        else handlePermanentDenials()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean("started") ?: false

        // 1) Request optional notifications (don’t wait on result)
        if (optionalPerms.isNotEmpty()) {
            @Suppress("MissingPermission")
            permsLauncher.launch(optionalPerms)
        }

        // 2) Request the *real* runtime perms needed before starting the service
        if (hasAllRuntimePerms()) {
            startHrServiceOnce()
        } else {
            permsLauncher.launch(runtimePerms)
        }

        setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Broadcasting heart rate…")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        super.onSaveInstanceState(outState)
    }

    private fun startHrServiceOnce() {
        if (started) return
        Log.d("MainActivity", "Starting HrBleService")
        ContextCompat.startForegroundService(this, Intent(this, HrBleService::class.java))
        started = true
    }

    private fun handlePermanentDenials() {
        // If a permission was denied with “Don’t ask again”, the dialog won’t show.
        // On Wear OS this can happen easily.
        val needsSettings = runtimePerms.any { perm ->
            // If we *don't* have it AND the system says "don't show rationale", it's likely permanently denied
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(perm)
        }
        if (needsSettings) {
            // Send user to app settings to grant manually
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            // Not permanently denied; you can relaunch permsLauncher again if you want
            permsLauncher.launch(runtimePerms)
        }
    }
}
