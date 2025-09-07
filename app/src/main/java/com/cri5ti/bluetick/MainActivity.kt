package com.cri5ti.bluetick

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

class MainActivity : ComponentActivity() {

    private val started = mutableStateOf(false)

    private val runtimePerms = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private fun hasAllRuntimePerms(): Boolean =
        runtimePerms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasAllRequiredPerms(): Boolean =
        hasAllRuntimePerms() // Only require runtime permissions, notifications are optional

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllRuntimePerms()) {
            if (areNotificationsEnabled()) {
                startHrServiceOnce()
            } else {
                // Notifications disabled, but we can still start the service
                Log.w("MainActivity", "Notifications disabled - starting service anyway")
                startHrServiceOnce()
                // Show a toast to inform user about notification settings
                android.widget.Toast.makeText(this, "Service started without notification icon. Enable notifications in Settings for status icon.", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check if service is actually running instead of relying on saved state
        started.value = isServiceRunning()

        setContent {
            WearScreen(
                started = started.value,
                onStart = {
                    if (hasAllRequiredPerms()) {
                        startHrServiceOnce()
                    } else {
                        // ask for permissions first; on grant we'll check notifications
                        permsLauncher.launch(runtimePerms)
                    }
                },
                onStop = {
                    // tell the service to stop
                    ContextCompat.startForegroundService(
                        this, Intent(this, HrBleService::class.java).apply {
                            action = HrBleService.ACTION_STOP
                        }
                    )
                    started.value = false
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started.value)
        super.onSaveInstanceState(outState)
    }

    private fun startHrServiceOnce() {
        if (started.value) return
        Log.d("MainActivity", "Starting HrBleService indefinitely")
        ContextCompat.startForegroundService(
            this,
            Intent(this, HrBleService::class.java)
        )
        started.value = true
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == HrBleService::class.java.name }
    }

    private fun areNotificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general app settings if notification settings fail
            Log.w("MainActivity", "Could not open notification settings, opening app settings", e)
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

}

/* ----------------------- Wear UI ----------------------- */

@Composable
private fun WearScreen(
    started: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Title
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Heart Rate Broadcast",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            // Start/Stop button comes first
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (!started) {
                    Chip(
                        onClick = onStart,
                        label = { Text("Start") },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                } else {
                    Chip(
                        onClick = onStop,
                        label = { Text("Stop") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }

            // Service status section (only show when started)
            if (started) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Service Status",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Running Indefinitely",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 16.sp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                    )
                }
                
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Service will run until manually stopped. Hourly reminders will be sent.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                    )
                }
                
                // Add extra spacing for better scrolling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
            }
        }
    }
}
