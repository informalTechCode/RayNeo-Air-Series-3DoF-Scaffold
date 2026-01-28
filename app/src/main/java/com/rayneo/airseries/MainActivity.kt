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
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val permissionAction = "com.rayneo.airseries.USB_PERMISSION"
    private val parser = RayNeoPacketParser()

    private lateinit var poseImage: ImageView
    private lateinit var statusText: TextView
    private var readerJob: Job? = null

    private val permissionReceiver =
            object : BroadcastReceiver() {
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
        poseImage.cameraDistance = 12000 * resources.displayMetrics.density
        statusText = findViewById(R.id.statusText)
        updateStatus(getString(R.string.status_idle))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    permissionReceiver,
                    IntentFilter(permissionAction),
                    Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(permissionReceiver, IntentFilter(permissionAction))
        }
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
            val flags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
            val permissionIntent = Intent(permissionAction).apply { setPackage(packageName) }
            val intent = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
            usbManager.requestPermission(device, intent)
            return
        }
        startReader(device)
    }

    private fun startReader(device: UsbDevice) {
        readerJob?.cancel()
        readerJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    val (usbInterface, endpoint) =
                            findInterfaceAndEndpoint(device)
                                    ?: run {
                                        updateStatus(
                                                "No suitable USB interface/endpoint found on ${device.deviceName}."
                                        )
                                        return@launch
                                    }

                    val epType =
                            when (endpoint.type) {
                                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                                UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                                else -> "OTHER"
                            }
                    android.util.Log.d(
                            "AirSeriesTester",
                            "Using endpoint: $epType, max packet: ${endpoint.maxPacketSize}"
                    )

                    val connection = usbManager.openDevice(device)
                    if (connection == null) {
                        updateStatus("Failed to open USB device ${device.deviceName}.")
                        return@launch
                    }

                    try {
                        // Force detach kernel driver
                        if (!connection.claimInterface(usbInterface, true)) {
                            updateStatus("Unable to claim USB interface for sensor stream.")
                            return@launch
                        }

                        android.util.Log.d("AirSeriesTester", "Interface claimed successfully")

                        // Send HID SET_IDLE command (required for some HID devices)
                        // bmRequestType: 0x21 (host-to-device, class, interface)
                        // bRequest: 0x0A (SET_IDLE)
                        // wValue: 0 (report ID 0, duration 0 = indefinite)
                        // wIndex: interface number
                        val setIdleResult =
                                connection.controlTransfer(
                                        0x21, // REQUEST_TYPE_CLASS | RECIPIENT_INTERFACE |
                                        // ENDPOINT_DIR_OUT
                                        0x0A, // HID_SET_IDLE
                                        0, // wValue: report ID 0, idle rate 0
                                        usbInterface.id,
                                        null,
                                        0,
                                        1000
                                )
                        android.util.Log.d("AirSeriesTester", "SET_IDLE result: $setIdleResult")

                        // For HID devices (class 3), try GET_REPORT polling first
                        if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                            readWithGetReport(connection, usbInterface.id, endpoint.maxPacketSize)
                        } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            readWithUsbRequest(connection, endpoint)
                        } else {
                            readWithBulkTransfer(connection, endpoint)
                        }
                    } finally {
                        connection.releaseInterface(usbInterface)
                        connection.close()
                    }
                }
    }

    private suspend fun readWithUsbRequest(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        android.util.Log.d("AirSeriesTester", "Starting UsbRequest read loop...")
        updateStatus("Reading sensor data (UsbRequest)…")

        val request = android.hardware.usb.UsbRequest()
        if (!request.initialize(connection, endpoint)) {
            android.util.Log.e("AirSeriesTester", "Failed to initialize UsbRequest")
            updateStatus("Failed to initialize USB request")
            return
        }

        val buffer = java.nio.ByteBuffer.allocate(endpoint.maxPacketSize)
        var readCount = 0
        var errorCount = 0

        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                buffer.clear()
                if (!request.queue(buffer, buffer.capacity())) {
                    android.util.Log.e("AirSeriesTester", "Failed to queue request")
                    errorCount++
                    if (errorCount > 10) break
                    kotlinx.coroutines.delay(50)
                    continue
                }

                val completedRequest = connection.requestWait()
                if (completedRequest == request) {
                    val bytesRead = buffer.position()
                    if (bytesRead > 0) {
                        readCount++
                        buffer.flip()
                        val data = ByteArray(bytesRead)
                        buffer.get(data)

                        if (readCount <= 10 || readCount % 100 == 0) {
                            android.util.Log.d(
                                    "AirSeriesTester",
                                    "UsbRequest read #$readCount: $bytesRead bytes"
                            )
                        }

                        val pose = parser.parse(data, bytesRead)
                        if (pose != null) {
                            updatePose(pose)
                        }
                        errorCount = 0
                    }
                } else {
                    errorCount++
                    android.util.Log.w(
                            "AirSeriesTester",
                            "Unexpected request returned, error #$errorCount"
                    )
                    if (errorCount > 20) {
                        updateStatus("Too many USB errors")
                        break
                    }
                    kotlinx.coroutines.delay(20)
                }
            }
        } finally {
            request.close()
        }
    }

    private suspend fun readWithGetReport(
            connection: UsbDeviceConnection,
            interfaceId: Int,
            maxPacketSize: Int
    ) {
        android.util.Log.d("AirSeriesTester", "Starting GET_REPORT polling loop...")
        updateStatus("Reading sensor data (GET_REPORT)…")

        val buffer = ByteArray(maxPacketSize)
        var readCount = 0
        var errorCount = 0
        var consecutiveZeros = 0

        while (kotlin.coroutines.coroutineContext.isActive) {
            // HID GET_REPORT: bmRequestType=0xA1 (device-to-host, class, interface)
            // bRequest=0x01 (GET_REPORT)
            // wValue=0x0100 (report type INPUT=1, report ID=0)
            // wIndex=interface number
            val bytesRead =
                    connection.controlTransfer(
                            0xA1, // REQUEST_TYPE_CLASS | RECIPIENT_INTERFACE | ENDPOINT_DIR_IN
                            0x01, // HID_GET_REPORT
                            0x0100, // Report type: INPUT (1), Report ID: 0
                            interfaceId,
                            buffer,
                            buffer.size,
                            100 // timeout ms
                    )

            if (bytesRead > 0) {
                readCount++
                consecutiveZeros = 0

                if (readCount <= 10 || readCount % 100 == 0) {
                    android.util.Log.d(
                            "AirSeriesTester",
                            "GET_REPORT read #$readCount: $bytesRead bytes"
                    )
                }

                val pose = parser.parse(buffer, bytesRead)
                if (pose != null) {
                    updatePose(pose)
                }
                errorCount = 0

                // Small delay between polls
                kotlinx.coroutines.delay(5)
            } else if (bytesRead == 0) {
                consecutiveZeros++
                if (consecutiveZeros <= 5) {
                    android.util.Log.d(
                            "AirSeriesTester",
                            "GET_REPORT returned 0 bytes (#$consecutiveZeros)"
                    )
                }
                kotlinx.coroutines.delay(20)
            } else {
                errorCount++
                if (errorCount <= 5) {
                    android.util.Log.w(
                            "AirSeriesTester",
                            "GET_REPORT error #$errorCount: $bytesRead"
                    )
                }
                if (errorCount > 20) {
                    // Try different report IDs
                    if (errorCount == 21) {
                        android.util.Log.d("AirSeriesTester", "Trying different report IDs...")
                    }
                    kotlinx.coroutines.delay(100)
                } else {
                    kotlinx.coroutines.delay(50)
                }

                if (errorCount > 100) {
                    updateStatus("GET_REPORT not working. Device may need different protocol.")
                    return
                }
            }
        }
    }

    private suspend fun readWithBulkTransfer(
            connection: UsbDeviceConnection,
            endpoint: UsbEndpoint
    ) {
        android.util.Log.d("AirSeriesTester", "Starting bulk transfer read loop...")
        updateStatus("Reading sensor data (bulk)…")

        val buffer = ByteArray(endpoint.maxPacketSize)
        var readCount = 0
        var errorCount = 0

        while (kotlin.coroutines.coroutineContext.isActive) {
            val read = connection.bulkTransfer(endpoint, buffer, buffer.size, 100)

            if (read > 0) {
                readCount++
                if (readCount <= 10 || readCount % 100 == 0) {
                    android.util.Log.d("AirSeriesTester", "Bulk read #$readCount: $read bytes")
                }
                val pose = parser.parse(buffer, read)
                if (pose != null) {
                    updatePose(pose)
                }
                errorCount = 0
            } else if (read < 0) {
                errorCount++
                if (errorCount <= 5) {
                    android.util.Log.w("AirSeriesTester", "Bulk transfer error #$errorCount: $read")
                }
                if (errorCount > 50) {
                    updateStatus("Too many USB errors. Reconnect glasses.")
                    return
                }
                kotlinx.coroutines.delay(50)
            } else {
                kotlinx.coroutines.delay(10)
            }
        }
    }

    private fun findCandidateDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            updateStatus("No USB devices connected. Connect RayNeo glasses via USB-C.")
            return null
        }

        // Log all connected devices for debugging
        val deviceInfo = StringBuilder("Found ${devices.size} USB device(s):\n")
        for (device in devices) {
            deviceInfo.append("• ${device.deviceName}\n")
            deviceInfo.append("  VID: 0x${device.vendorId.toString(16).uppercase()}, ")
            deviceInfo.append("PID: 0x${device.productId.toString(16).uppercase()}\n")
            deviceInfo.append("  Interfaces: ${device.interfaceCount}\n")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                deviceInfo.append(
                        "    [${i}] Class: ${iface.interfaceClass}, Endpoints: ${iface.endpointCount}\n"
                )
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type =
                            when (ep.type) {
                                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                                UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                                else -> "UNKNOWN"
                            }
                    deviceInfo.append("      EP${e}: $dir $type (${ep.maxPacketSize} bytes)\n")
                }
            }
        }
        android.util.Log.d("AirSeriesTester", deviceInfo.toString())

        val candidate = devices.firstOrNull { device -> findInterfaceAndEndpoint(device) != null }

        if (candidate == null) {
            updateStatus(
                    "Found ${devices.size} USB device(s) but none with sensor endpoint.\n" +
                            "Check Logcat for details (tag: AirSeriesTester)"
            )
        }
        return candidate
    }

    private fun findInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e)
                // Accept both BULK and INTERRUPT IN endpoints for sensor data
                if (endpoint.direction == UsbConstants.USB_DIR_IN &&
                                (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT)
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
            statusText.text =
                    "Yaw %.2f°, Pitch %.2f°, Roll %.2f°".format(
                            pose.yawDegrees,
                            pose.pitchDegrees,
                            pose.rollDegrees
                    )
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }
}

private inline fun UsbDeviceConnection.use(block: (UsbDeviceConnection) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
