package com.rayneo.airseries

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val usbManager by lazy {
        getSystemService(Context.USB_SERVICE) as UsbManager
    }
    private val permissionAction = "com.rayneo.airseries.USB_PERMISSION"
    private val parser = RayNeoPacketParser()

    private lateinit var poseImage: ImageView
    private lateinit var statusText: TextView
    private var readerJob: Job? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device == null) {
                updateStatus("USB permission receiver triggered without device.")
                return
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                updateStatus("USB permission granted. Starting sensor stream…")
                startReader(device)
            } else {
                updateStatus("USB permission denied for ${device.deviceName}.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        poseImage = findViewById(R.id.poseImage)
        statusText = findViewById(R.id.statusText)
        updateStatus(getString(R.string.status_idle))

        registerReceiver(permissionReceiver, IntentFilter(permissionAction))
        startIfDeviceAvailable()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        readerJob?.cancel()
        super.onDestroy()
    }

    private fun startIfDeviceAvailable() {
        val device = findCandidateDevice()
        if (device == null) {
            updateStatus("No USB device with bulk sensor endpoint found. Connect RayNeo glasses.")
            return
        }
        if (!usbManager.hasPermission(device)) {
            updateStatus("Requesting USB permission for ${device.deviceName}…")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = PendingIntent.getBroadcast(this, 0, Intent(permissionAction), flags)
            usbManager.requestPermission(device, intent)
            return
        }
        startReader(device)
    }

    private fun startReader(device: UsbDevice) {
        readerJob?.cancel()
        readerJob = lifecycleScope.launch(Dispatchers.IO) {
            val (usbInterface, endpoint) = findInterfaceAndEndpoint(device)
                ?: run {
                    updateStatus("No suitable USB interface/endpoint found on ${device.deviceName}.")
                    return@launch
                }
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                updateStatus("Failed to open USB device ${device.deviceName}.")
                return@launch
            }
            connection.use {
                if (!connection.claimInterface(usbInterface, true)) {
                    updateStatus("Unable to claim USB interface for sensor stream.")
                    return@launch
                }
                updateStatus("Reading sensor data from ${device.deviceName}…")
                val buffer = ByteArray(endpoint.maxPacketSize)
                while (isActive) {
                    val read = connection.bulkTransfer(endpoint, buffer, buffer.size, 50)
                    if (read > 0) {
                        val pose = parser.parse(buffer, read)
                        if (pose != null) {
                            updatePose(pose)
                        }
                    } else {
                        delay(20)
                    }
                }
            }
        }
    }

    private fun findCandidateDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            findInterfaceAndEndpoint(device) != null
        }
    }

    private fun findInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e)
                if (endpoint.direction == UsbConstants.USB_DIR_IN &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                ) {
                    return usbInterface to endpoint
                }
            }
        }
        return null
    }

    private suspend fun updatePose(pose: Pose3DoF) {
        withContext(Dispatchers.Main) {
            poseImage.rotation = pose.yawDegrees
            poseImage.rotationX = pose.pitchDegrees
            poseImage.rotationY = pose.rollDegrees
            statusText.text = "Yaw %.2f°, Pitch %.2f°, Roll %.2f°".format(
                pose.yawDegrees,
                pose.pitchDegrees,
                pose.rollDegrees
            )
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }
}

private fun UsbDeviceConnection.use(block: (UsbDeviceConnection) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
