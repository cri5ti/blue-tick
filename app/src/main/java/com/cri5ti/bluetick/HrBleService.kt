// HrBleService.kt
package com.cri5ti.bluetick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HrBleService : Service() {

    // ---------- Constants ----------
    companion object {
        const val TAG = "HrBle"
        const val CHANNEL_ID = "hr_ble"
        const val ACTION_STOP = "com.cri5ti.bluetick.ACTION_STOP"

        val HRS_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB") // Heart Rate Service
        val HRM_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB") // Heart Rate Measurement
        val BSL_UUID: UUID = UUID.fromString("00002A38-0000-1000-8000-00805F9B34FB") // Body Sensor Location
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB") // Client Char Config
    }

    // ---------- Scope ----------
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Coroutine error", e)
        }
    )

    // ---------- System services ----------
    private val btManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter get() = btManager.adapter
    private val advertiser get() = adapter?.bluetoothLeAdvertiser

    // ---------- BLE state ----------
    private var gattServer: BluetoothGattServer? = null
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var lastHrPayload: ByteArray = byteArrayOf(0x00, 0x00) // default flags=0,bpm=0
    private var advAttempts = 0

    // ---------- Health Services ----------
    private val measureClient: MeasureClient by lazy {
        HealthServices.getClient(this).measureClient
    }
    private var hsRegistered = false
    private var btReceiverRegistered = false

    // ---------- Ongoing Activity ----------
    private var ongoingActivity: OngoingActivity? = null

    // ---------- Hourly Reminder ----------
    private var reminderJob: kotlinx.coroutines.Job? = null

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "HS availability: $dataType -> $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt() ?: return
            pushHr(bpm)
        }
    }

    // ---------- Lifecycle ----------
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        // Listen for BT on/off to recreate GATT/advertising
        if (!btReceiverRegistered) {
            registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            btReceiverRegistered = true
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ensureBleReadyOrStop()) return START_NOT_STICKY

        // Get the notification from the OngoingActivity setup
        val foregroundNotification = startOngoingActivity()
        
        // Use that notification to start the foreground service
        startForeground(101, foregroundNotification)
        Log.d(TAG, "Service started as foreground with OngoingActivity")

        // Build/refresh GATT each (re)start
        startGatt()

        // Start (or retry) advertising
        startAdvertisingWithRetry()
        
        // Start hourly reminder notifications
        startHourlyReminders()
        
        Log.d(TAG, "Service started with flags: $flags, startId: $startId")

        // Health Services: start later when first client subscribes (via CCCD)
        return START_STICKY
    }


    override fun onDestroy() {
        Log.d(TAG, "onDestroy")

        stopHeartRateStream()
        stopHourlyReminders()

        val hasAdvertise = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

        if (hasAdvertise) {
            runCatching {
                advertiser?.stopAdvertising(advCallback)
            }.onFailure {
                Log.w(TAG, "Error stopping advertiser", it)
            }
        } else {
            Log.w(TAG, "No permission to stop advertising")
        }

        gattServer?.close()
        gattServer = null
        // Cleanup
        serviceScope.cancel()
        if (btReceiverRegistered) {
            unregisterReceiver(btReceiver)
            btReceiverRegistered = false
        }
        
        // OngoingActivity is automatically managed by the system
        // No need to explicitly cancel it
        ongoingActivity = null
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---------- Ongoing Activity ----------
    private fun startOngoingActivity(): Notification {
        Log.d(TAG, "Starting OngoingActivity setup...")
        
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, 
            "HR Broadcast", 
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Heart rate broadcasting service"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel $CHANNEL_ID created with importance HIGH")

        // Create touch intent to open MainActivity
        val touchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val touchPi = PendingIntent.getActivity(
            this, 0, touchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Log.d(TAG, "Touch intent created for MainActivity")

        // Create the *base* notification builder
        val notificationCompatBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Broadcast")
            .setContentText("BLE service running")
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true) // Required for ongoing activity
        Log.d(TAG, "NotificationCompat.Builder created with small icon: ${R.drawable.ic_notification_heart}")

        // Create OngoingActivity using the builder
        Log.d(TAG, "Creating OngoingActivity.Builder...")
        ongoingActivity = OngoingActivity.Builder(
            applicationContext, 101, notificationCompatBuilder
        )
            .setStaticIcon(R.drawable.ic_notification_heart)
            .setTouchIntent(touchPi)
            .setStatus(Status.Builder().addTemplate("Heart Rate Broadcasting").build())
            .build()
        Log.d(TAG, "OngoingActivity.Builder created successfully")

        // Apply the OngoingActivity
        Log.d(TAG, "Attempting to apply OngoingActivity...")
        ongoingActivity?.apply(applicationContext)
        Log.d(TAG, "OngoingActivity apply completed. Null? ${ongoingActivity == null}")
        
        // Verify notification manager state
        Log.d(TAG, "Notification manager active notifications: ${nm.activeNotifications.size}")
        Log.d(TAG, "Are notifications enabled: ${nm.areNotificationsEnabled()}")
        
        Log.d(TAG, "OngoingActivity setup completed.")
        
        // Return the notification built from the same builder used by OngoingActivity
        // This ensures synchronization between OngoingActivity and startForeground
        return notificationCompatBuilder.build()
    }

    private fun updateOngoingActivityStatus(bpm: Int) {
        Log.d(TAG, "Updating OngoingActivity status to: HR: $bpm BPM")
        ongoingActivity?.update(
            applicationContext,
            Status.Builder()
                .addTemplate("HR: $bpm BPM")
                .build()
        )
        Log.d(TAG, "OngoingActivity status update completed")
    }

    // ---------- BLE GATT ----------
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startGatt() {
        Log.d(TAG, "startGatt")
        // Close old server if any
        gattServer?.close()
        gattServer = try {
            btManager.openGattServer(this, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open GATT server", e)
            null
        }
        
        if (gattServer == null) {
            Log.e(TAG, "GATT server is null, cannot continue")
            return
        }

        val hrChar = BluetoothGattCharacteristic(
            HRM_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = lastHrPayload
            addDescriptor(
                BluetoothGattDescriptor(
                    CCC_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        val bsl = BluetoothGattCharacteristic(
            BSL_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            // 0x0D = Wrist
            value = byteArrayOf(0x0D)
        }

        val service = BluetoothGattService(HRS_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(hrChar)
            addCharacteristic(bsl)
        }

        val success = gattServer?.addService(service) ?: false
        if (!success) {
            Log.e(TAG, "Failed to add GATT service")
        } else {
            Log.d(TAG, "GATT service added successfully")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "GATT state change: ${device.address} status=$status new=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
                if (subscribedDevices.isEmpty()) {
                    stopHeartRateStream()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCC_UUID) {
                if (offset != 0) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null
                    )
                    return
                }
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) {
                    subscribedDevices.add(device)
                    synchronized(this@HrBleService) {
                        if (!hsRegistered) startHeartRateStream()
                    }
                } else {
                    subscribedDevices.remove(device)
                    if (subscribedDevices.isEmpty()) stopHeartRateStream()
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != HRM_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val value = characteristic.value ?: lastHrPayload
            if (offset > value.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                val slice = value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        }
    }

    // ---------- Advertising ----------
    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started: $settingsInEffect")
            advAttempts = 0
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            if (advAttempts < 5) {
                val delayMs = 500L * (1 shl advAttempts) // 500, 1000, 2000, 4000, 8000
                advAttempts++
                serviceScope.launch {
                    delay(delayMs)
                    val hasPerms = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (hasPerms && adapter?.isEnabled == true) {
                        startAdvertisingWithRetry()
                    } else {
                        Log.w(TAG, "BLE not ready for retry (perms: $hasPerms, enabled: ${adapter?.isEnabled})")
                        if (!hasPerms) {
                            stopSelf()
                        }
                    }
                }
            } else {
                Log.e(TAG, "Max advertising retry attempts reached, giving up")
                Toast.makeText(this@HrBleService, "BLE advertising failed after $advAttempts attempts", Toast.LENGTH_LONG).show()
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startAdvertisingWithRetry() {
        Log.d(TAG, "startAdvertising (attempt=$advAttempts)")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HRS_UUID))
            .build()

        val scanResp = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .build()

        runCatching {
            advertiser?.startAdvertising(settings, data, scanResp, advCallback)
            adapter?.name = "Pixel Tick"
        }.onFailure { e ->
            Log.e(TAG, "startAdvertising error", e)
        }
    }

    // ---------- Push HR to clients ----------
    @SuppressLint("MissingPermission")
    private fun pushHr(bpm: Int) {
        if (subscribedDevices.isEmpty()) return
        val chr = gattServer?.getService(HRS_UUID)?.getCharacteristic(HRM_UUID) ?: return
        
        // Proper HRM format: flags=0x00 (UINT8 BPM), value=bpm (UINT8)
        val validBpm = bpm.coerceIn(0, 255)
        lastHrPayload = byteArrayOf(0x00, validBpm.toByte())
        chr.value = lastHrPayload
        
        Log.d(TAG, "Pushing HR: $validBpm BPM to ${subscribedDevices.size} devices")
        subscribedDevices.forEach { dev ->
            gattServer?.notifyCharacteristicChanged(dev, chr, false, lastHrPayload)
        }
        
        // Update OngoingActivity status with current HR
        updateOngoingActivityStatus(validBpm)
    }

    // ---------- Health Services start/stop ----------
    private fun startHeartRateStream() {
        if (hsRegistered) return
        val client = measureClient
        serviceScope.launch {
            runCatching {
                val caps = client.getCapabilitiesAsync().await()
                if (DataType.HEART_RATE_BPM in caps.supportedDataTypesMeasure) {
                    client.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
                    hsRegistered = true
                    Log.d(TAG, "HS MeasureCallback registered")
                } else {
                    Log.w(TAG, "HEART_RATE_BPM not supported on this device")
                }
            }.onFailure { e ->
                Log.e(TAG, "startHeartRateStream error", e)
            }
        }
    }

    private fun stopHeartRateStream() {
        if (!hsRegistered) return
        val client = measureClient
        serviceScope.launch {
            runCatching {
                client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback).await()
                Log.d(TAG, "HS MeasureCallback unregistered")
            }.onFailure { e ->
                Log.e(TAG, "stopHeartRateStream error", e)
            }
            hsRegistered = false
        }
    }

    // ---------- Bluetooth state handling ----------
    private val btReceiver = object : BroadcastReceiver() {
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED != intent.action) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "BT ON -> restart GATT & advertising")
                    startGatt()
                    startAdvertisingWithRetry()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "BT OFF -> stop advertising & close GATT")
                    runCatching { advertiser?.stopAdvertising(advCallback) }
                    gattServer?.close()
                    gattServer = null
                    stopHeartRateStream()
                }
            }
        }
    }

    // ---------- Hourly Reminder Notifications ----------
    private fun startHourlyReminders() {
        stopHourlyReminders() // Cancel any existing reminders
        
        reminderJob = serviceScope.launch {
            while (true) {
                delay(60 * 60 * 1000L) // Wait 1 hour
                sendHourlyReminderNotification()
            }
        }
        Log.d(TAG, "Hourly reminder notifications started")
    }

    private fun stopHourlyReminders() {
        reminderJob?.cancel()
        reminderJob = null
        Log.d(TAG, "Hourly reminder notifications stopped")
    }

    private fun sendHourlyReminderNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        
        // Create a separate notification channel for reminders
        val reminderChannel = NotificationChannel(
            "hr_reminder", 
            "HR Service Reminders", 
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Hourly reminders that heart rate service is running"
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500) // Short vibration pattern
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
        }
        nm.createNotificationChannel(reminderChannel)

        // Create touch intent to open MainActivity
        val touchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val touchPi = PendingIntent.getActivity(
            this, 1, touchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "hr_reminder")
            .setContentTitle("Heart Rate Service")
            .setContentText("Service is still running and broadcasting heart rate data")
            .setSmallIcon(R.drawable.ic_notification_heart)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(true)
            .setContentIntent(touchPi)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // This ensures sound, vibration, and lights
            .build()

        nm.notify(102, notification)
        Log.d(TAG, "Hourly reminder notification sent")
    }

    // ---------- Guards ----------
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun ensureBleReadyOrStop(): Boolean {
        val ready = (adapter?.isEnabled == true && advertiser != null)
        if (!ready) {
            Toast.makeText(this, "Bluetooth not ready", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        return ready
    }
}
