package com.example.rayneo

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
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var headTracker: HeadTracker
    private lateinit var usbManager: UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val isRunning = AtomicBoolean(false)
    private var usbThread: Thread? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.rayneo.USB_PERMISSION"
        private const val TAG = "RayNeoSimple"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            setupDevice(this)
                        }
                    } else {
                        Log.d(TAG, "permission denied for device $device")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        headTracker = HeadTracker()
        glView = MyGLSurfaceView(this, headTracker)
        setContentView(glView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        findAndConnectDevice()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        stopUsbThread()
        closeUsb()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun findAndConnectDevice() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == RayNeoDriver.VID && device.productId == RayNeoDriver.PID) {
                if (usbManager.hasPermission(device)) {
                    setupDevice(device)
                } else {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0, Intent(ACTION_USB_PERMISSION),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
                break
            }
        }
    }

    private fun setupDevice(device: UsbDevice) {
        // Find interface
        // RayneoApi.cpp says: Prefer HID class (0x03).

        var foundInterface: UsbInterface? = null
        var foundEpIn: UsbEndpoint? = null
        var foundEpOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Look for HID or just try to find endpoints
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID || device.interfaceCount == 1) { // Or fallback
                 for (j in 0 until iface.endpointCount) {
                     val ep = iface.getEndpoint(j)
                     if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                         if (ep.direction == UsbConstants.USB_DIR_IN) {
                             foundEpIn = ep
                         } else {
                             foundEpOut = ep
                         }
                     }
                 }
                 if (foundEpIn != null) {
                     foundInterface = iface
                     break
                 }
            }
        }

        if (foundInterface == null) {
            Log.e(TAG, "Could not find suitable interface")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Could not open connection")
            return
        }

        if (connection.claimInterface(foundInterface, true)) {
            usbConnection = connection
            usbInterface = foundInterface
            endpointIn = foundEpIn
            endpointOut = foundEpOut

            Log.i(TAG, "Device connected. EP In: ${endpointIn?.address}, EP Out: ${endpointOut?.address}")

            startUsbThread()
        } else {
            connection.close()
            Log.e(TAG, "Could not claim interface")
        }
    }

    private fun startUsbThread() {
        if (isRunning.get()) return
        isRunning.set(true)

        usbThread = Thread {
            // Enable IMU
            val enableCmd = RayNeoDriver.getEnableImuCommand()
            if (endpointOut != null && usbConnection != null) {
                 usbConnection?.bulkTransfer(endpointOut, enableCmd, enableCmd.size, 1000)
                 // Note: Use bulkTransfer for Interrupt endpoints in Android usually works or use controlTransfer if endpointOut is null/doesn't exist.
                 // RayneoApi.cpp fallback: control transfer 0x21, 0x09, ...
            } else if (usbConnection != null) {
                 // Fallback control transfer
                 // libusb_control_transfer(ctx->handle, 0x21, 0x09, wValue, ctx->interfaceNumber, ...)
                 // wValue = (0x02 << 8) | 0x00
                 val wValue = (0x02 shl 8) or 0x00
                 usbConnection?.controlTransfer(
                     0x21, // requestType: Host to Device, Class, Interface
                     0x09, // request: SET_REPORT
                     wValue,
                     usbInterface?.id ?: 0,
                     enableCmd,
                     enableCmd.size,
                     1000
                 )
            }

            val buffer = ByteArray(64)
            var lastTime = System.nanoTime()

            while (isRunning.get() && usbConnection != null && endpointIn != null) {
                val len = usbConnection?.bulkTransfer(endpointIn, buffer, buffer.size, 100)
                if (len != null && len > 0) {
                    val sample = RayNeoDriver.parsePacket(buffer)
                    if (sample != null) {
                        val currentTime = System.nanoTime()
                        val dt = (currentTime - lastTime) * 1e-9f // ns to seconds
                        lastTime = currentTime

                        headTracker.update(sample.gyroRad, dt)
                    }
                }
            }
        }.apply { start() }
    }

    private fun stopUsbThread() {
        isRunning.set(false)
        try {
            usbThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        usbThread = null
    }

    private fun closeUsb() {
        usbConnection?.releaseInterface(usbInterface)
        usbConnection?.close()
        usbConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }
}
