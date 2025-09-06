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
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
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
        const val EXTRA_TIMEOUT_MINUTES = "extra_timeout_minutes"

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

        val notification = buildNotification()
        Log.d(TAG, "Starting foreground service with notification")
        startForeground(1, notification)
        
        // Additional debugging
        val nm = getSystemService(NotificationManager::class.java)
        Log.d(TAG, "Notification manager active notifications: ${nm.activeNotifications.size}")
        Log.d(TAG, "Are notifications enabled: ${nm.areNotificationsEnabled()}")
        
        // Check if notifications are enabled
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled for this app!")
            Toast.makeText(this, "Notifications disabled - service will run without status icon", Toast.LENGTH_LONG).show()
        }

        // Build/refresh GATT each (re)start
        startGatt()

        // Start (or retry) advertising
        startAdvertisingWithRetry()
        
        Log.d(TAG, "Service started with flags: $flags, startId: $startId")

        val timeoutMin = intent?.getIntExtra(EXTRA_TIMEOUT_MINUTES, -1) ?: -1
        if (timeoutMin > 0) {
            // cancel any previous timer (if you add one), then schedule a new one
            serviceScope.launch {
                Log.d(TAG, "Auto-stop scheduled in $timeoutMin minutes")
                delay(timeoutMin * 60_000L)
                Log.d(TAG, "Auto-stop firing")
                stopSelf()
            }
        }

        // Health Services: start later when first client subscribes (via CCCD)
        return START_STICKY
    }


    override fun onDestroy() {
        Log.d(TAG, "onDestroy")

        stopHeartRateStream()

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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ---------- Notification ----------
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, 
            "HR Broadcast", 
            NotificationManager.IMPORTANCE_DEFAULT // Try DEFAULT instead of LOW
        ).apply {
            description = "Heart rate broadcasting service"
            setShowBadge(true)
            enableLights(false)
            enableVibration(false)
            setSound(null, null) // No sound
        }
        nm.createNotificationChannel(channel)

        val stopIntent = Intent(this, HrBleService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Broadcasting Heart Rate")
            .setContentText("Visible to BLE apps (Heart Rate Service)")
            .setSmallIcon(R.drawable.ic_notification_heart) // Use the new notification-specific icon
            .setColor(ContextCompat.getColor(this, android.R.color.white)) // Set notification color
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Match channel importance
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false) // Don't show timestamp
            .build()
            
        Log.d(TAG, "Notification created with icon: ${R.drawable.ic_notification_heart}")
        return notification
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
            gattServer?.notifyCharacteristicChanged(dev, chr, /*confirm*/ false)
        }
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
