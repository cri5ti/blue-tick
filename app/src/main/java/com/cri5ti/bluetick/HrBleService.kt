package com.cri5ti.bluetick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.UUID

public class HrBleService : Service()
{
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notif: Notification
    private val mgr by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter get() = mgr.adapter
    private val advertiser get() = adapter.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    private var subscribedDevices = mutableSetOf<BluetoothDevice>()

    // standard UUID for the Heart Rate Service.
    private val HRS_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")

    // standard UUID for the Heart Rate Measurement characteristic.
    private val HRM_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")

    // standard UUID for the CCCD. This descriptor is essential for enabling notifications
    private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startForeground(1, buildNotification())
        startGatt()
        startAdvertising()
        startHeartRateStream() // Health Services
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val chanId = "hr_ble"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chanId, "HR Broadcast", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, chanId)
            .setContentTitle("Broadcasting Heart Rate")
            .setSmallIcon(R.drawable.ic_heart)
            .build()
    }

    private val BSL_UUID = UUID.fromString("00002A38-0000-1000-8000-00805F9B34FB")

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startGatt() {
        gattServer = mgr.openGattServer(this, gattCallback)

        val hrChar = BluetoothGattCharacteristic(
            HRM_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val ccc = BluetoothGattDescriptor(
            CCC_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        hrChar.addDescriptor(ccc)

        val bsl = BluetoothGattCharacteristic(
            BSL_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(0x0D) } // Wrist

        val service = BluetoothGattService(HRS_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(hrChar)
        service.addCharacteristic(bsl)

        gattServer?.addService(service)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCC_UUID) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) subscribedDevices.add(device) else subscribedDevices.remove(device)
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HRM_UUID) {
                // Provide last value on read
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value)
            }
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            android.util.Log.i("HR", "Advertising started: $settingsInEffect")
        }
        override fun onStartFailure(errorCode: Int) {
            android.util.Log.e("HR", "Advertising failed: $errorCode")
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startAdvertising() {
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

        advertiser?.startAdvertising(settings, data, scanResp, advCallback)

        adapter?.name = "Pixel Tick"
    }

    @SuppressLint("MissingPermission")
    private fun pushHr(bpm: Int) {
        val chr = gattServer?.getService(HRS_UUID)?.getCharacteristic(HRM_UUID) ?: return
        val payload = byteArrayOf(0x00, bpm.coerceIn(0, 255).toByte())
        chr.value = payload
        subscribedDevices.forEach { dev ->
            gattServer?.notifyCharacteristicChanged(dev, chr, /*confirm*/ false)
        }
    }

    private val measureClient: MeasureClient by lazy {
        HealthServices.getClient(this).measureClient
    }

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            // optional: react to DataTypeAvailability, sensor off-skin, etc.
        }

        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            val last = points.lastOrNull() ?: return
            val bpm = last.value.toInt()
            pushHr(bpm) // your BLE notify
        }
    }

    private fun startHeartRateStream() {
        val client = measureClient
        serviceScope.launch {
            val caps = client.getCapabilitiesAsync().await()
            if (DataType.HEART_RATE_BPM in caps.supportedDataTypesMeasure) {
                client.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        serviceScope.launch {
            val client = measureClient
            runCatching {
                client.unregisterMeasureCallbackAsync(
                    DataType.HEART_RATE_BPM, heartRateCallback
                ).await()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

}
