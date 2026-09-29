package net.lunprojects.heartsync

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class HeartRateService : Service() {

    companion object {
        val latestBpm = MutableStateFlow(0)
        val isRunning = MutableStateFlow(false)

        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private lateinit var sessionFile: File

    override fun onCreate() {
        super.onCreate()
        sessionFile = File(filesDir, "temp_session.jsonl")
        createNotificationChannel()
        startForeground(101, buildNotification("Scanning for 808S..."))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = bluetoothManager.adapter?.bluetoothLeScanner
        startBleScan()
    }

    private fun startBleScan() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            updateNotification("Connecting to ${result.device.name ?: "808S"}...")
            bluetoothGatt = result.device.connectGatt(this@HeartRateService, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateNotification("Disconnected. Waiting...")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val hrChar = gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_UUID)
            hrChar?.let {
                gatt.setCharacteristicNotification(it, true)
                val descriptor = it.getDescriptor(CLIENT_CONFIG_UUID)
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                isRunning.value = true
                updateNotification("Recording HR active")
            }
        }

        @Deprecated("Deprecated for SDK 33+")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            parseAndSave(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            parseAndSave(value)
        }
    }

    private fun parseAndSave(data: ByteArray) {
        if (data.isEmpty()) return
        val flags = data[0].toInt()
        val bpm = if ((flags and 0x01) != 0) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }

        latestBpm.value = bpm
        updateNotification("Current HR: $bpm BPM")

        // Crash-proof write: append line and flush immediately
        try {
            val record = """{"ts":${System.currentTimeMillis()},"bpm":$bpm}""" + "\n"
            FileOutputStream(sessionFile, true).use { fos ->
                fos.write(record.toByteArray())
                fos.fd.sync() // Forces kernel write to flash memory
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "hr_channel")
            .setContentTitle("Heart Rate Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(101, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("hr_channel", "HR Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.stopScan(scanCallback)
        bluetoothGatt?.close()
        isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}