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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PickerState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberPickerState

class MainActivity : ComponentActivity() {

    private val started = mutableStateOf(false)
    private val remainingTimeSeconds = mutableStateOf(0)
    private val timeoutMinutes = mutableStateOf(60) // default = 1 h

    private var pendingTimeoutMinutes: Int = 8*60 // default = 4 h

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
                startHrServiceOnce(pendingTimeoutMinutes)
            } else {
                // Notifications disabled, but we can still start the service
                Log.w("MainActivity", "Notifications disabled - starting service anyway")
                startHrServiceOnce(pendingTimeoutMinutes)
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
        
        // Restore timer state
        timeoutMinutes.value = savedInstanceState?.getInt("timeout_minutes") ?: 60
        remainingTimeSeconds.value = savedInstanceState?.getInt("remaining_seconds") ?: 0
        
        // If service is running but we don't have timer state, estimate remaining time
        if (started.value && remainingTimeSeconds.value == 0 && timeoutMinutes.value > 0) {
            // Assume service just started, set full timeout
            remainingTimeSeconds.value = timeoutMinutes.value * 60
        }

        setContent {
            WearScreen(
                started = started.value,
                defaultTimeout = pendingTimeoutMinutes,
                timeoutMinutes = timeoutMinutes.value,
                remainingTimeSeconds = remainingTimeSeconds.value,
                onTimeoutChanged = { 
                    pendingTimeoutMinutes = it
                    timeoutMinutes.value = it
                },
                onStart = {
                    if (hasAllRequiredPerms()) {
                        startHrServiceOnce(pendingTimeoutMinutes)
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
                    remainingTimeSeconds.value = 0
                },
                onTimerUpdate = { newSeconds ->
                    remainingTimeSeconds.value = newSeconds
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started.value)
        outState.putInt("timeout_minutes", timeoutMinutes.value)
        outState.putInt("remaining_seconds", remainingTimeSeconds.value)
        super.onSaveInstanceState(outState)
    }

    private fun startHrServiceOnce(selectedTimeoutMinutes: Int) {
        if (started.value) return
        Log.d("MainActivity", "Starting HrBleService timeout=${selectedTimeoutMinutes}min")
        ContextCompat.startForegroundService(
            this,
            Intent(this, HrBleService::class.java).apply {
                if (selectedTimeoutMinutes > 0) {
                    putExtra(HrBleService.EXTRA_TIMEOUT_MINUTES, selectedTimeoutMinutes)
                }
            }
        )
        started.value = true
        timeoutMinutes.value = selectedTimeoutMinutes
        remainingTimeSeconds.value = selectedTimeoutMinutes * 60
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
    defaultTimeout: Int,
    timeoutMinutes: Int,
    remainingTimeSeconds: Int,
    onTimeoutChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTimerUpdate: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val labels = listOf("1 h", "4 h", "8 h", "24h", "Never")
    val values = listOf(60, 4*60, 8*60, 24*60, -1)

    val initialIndex = values.indexOf(defaultTimeout).let { if (it == -1) 1 else it }
    val pickerState: PickerState = rememberPickerState(
        initialNumberOfOptions = labels.size,
        initiallySelectedOption = initialIndex
    )

    // Reflect selection to parent
    LaunchedEffect(pickerState.selectedOption) {
        onTimeoutChanged(values[pickerState.selectedOption])
    }

    // Countdown timer logic
    LaunchedEffect(started, timeoutMinutes) {
        if (started && timeoutMinutes > 0) {
            var currentSeconds = remainingTimeSeconds
            while (currentSeconds > 0) {
                delay(1000) // Update every second
                currentSeconds--
                onTimerUpdate(currentSeconds)
            }
        }
    }

    // Handle timer reaching zero
    LaunchedEffect(remainingTimeSeconds, started) {
        if (started && timeoutMinutes > 0 && remainingTimeSeconds <= 0) {
            // Timer reached zero, stop the service
            onStop()
        }
    }

    // Helper function to format time
    fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%d:%02d", minutes, secs)
            else -> String.format("%ds", secs)
        }
    }

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

            // Timeout picker section (only show when not started)
            if (!started) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Auto-stop Timeout",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                
                Picker(
                    state = pickerState,
                    readOnly = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp) // bigger area for easier scrolling
                        .padding(horizontal = 8.dp)
                ) { ix ->
                    Text(
                        text = labels[ix],
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp),
                        color = if (ix == pickerState.selectedOption)
                            Color.White else Color.Gray
                    )
                }

                // Summary
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val hint = if (values[pickerState.selectedOption] > 0)
                        "Auto-stops after ${labels[pickerState.selectedOption]}"
                    else
                        "No auto-stop"
                    Text(
                        hint,
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                
                // Add extra spacing for better scrolling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
            } else {
                // Countdown timer section (only show when started)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Auto-stop Timer",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (timeoutMinutes > 0) {
                        Text(
                            text = formatTime(remainingTimeSeconds),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                    } else {
                        Text(
                            text = "No auto-stop",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                    }
                }
                
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val statusText = if (timeoutMinutes > 0) {
                        if (remainingTimeSeconds > 0) {
                            "Service will stop automatically"
                        } else {
                            "Service stopping now..."
                        }
                    } else {
                        "Service running indefinitely"
                    }
                    Text(
                        statusText,
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
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
