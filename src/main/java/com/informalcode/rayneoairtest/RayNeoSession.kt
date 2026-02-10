package com.informalcode.rayneoairtest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RayNeoSession(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onOrientation: (FloatArray) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private var receiverRegistered = false
    private var ioThread: Thread? = null
    private var activeDeviceId: Int = -1

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var assembler = RayNeoPacketAssembler()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        startStreaming(device)
                    } else {
                        postStatus("USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (!started.get()) {
                        return
                    }
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (isRayNeo(device) && !running.get()) {
                        requestPermissionOrStart(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (device.deviceId == activeDeviceId) {
                        postStatus("RayNeo disconnected")
                        running.set(false)
                    }
                }
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        registerReceiver()
        val device = findRayNeoDevice()
        if (device == null) {
            postStatus("RayNeo not found (VID 0x1bbb / PID 0xaf50)")
            return
        }
        requestPermissionOrStart(device)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        running.set(false)
        ioThread?.join(600)
        ioThread = null
        closeConnection()
        unregisterReceiver()
    }

    private fun requestPermissionOrStart(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            postStatus("Requesting USB permission...")
            usbManager.requestPermission(device, buildPermissionIntent())
            return
        }
        startStreaming(device)
    }

    private fun startStreaming(device: UsbDevice) {
        if (!started.get() || running.get()) {
            return
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            postStatus("Failed to open USB device")
            return
        }

        val selection = RayNeoProtocol.selectEndpoints(device)
        if (selection == null) {
            conn.close()
            postStatus("Could not find IN/OUT endpoints")
            return
        }

        if (!conn.claimInterface(selection.usbInterface, true)) {
            conn.close()
            postStatus("Failed to claim USB interface")
            return
        }

        connection = conn
        usbInterface = selection.usbInterface
        inEndpoint = selection.inEndpoint
        outEndpoint = selection.outEndpoint
        activeDeviceId = device.deviceId
        assembler = RayNeoPacketAssembler()
        running.set(true)

        ioThread = Thread {
            ioLoop()
        }.apply {
            name = "RayNeoUsbThread"
            start()
        }
    }

    private fun ioLoop() {
        try {
            postStatus("Initializing protocol...")
            val info = initializeProtocol() ?: run {
                postStatus("Initialization failed")
                return
            }

            val imuRotX = if (info.boardId == RayNeoProtocol.BOARD_AIR_4_PRO) -20f else 0f
            postStatus(
                String.format(
                    Locale.US,
                    "Streaming board=0x%02X, imuRotX=%+.1f deg",
                    info.boardId,
                    imuRotX
                )
            )

            val filter = OrientationFilter(imuRotX)
            val firstOrientationAtMs = SystemClock.elapsedRealtime() + SENSOR_WARMUP_DELAY_MS
            if (!initializeSensorPose(filter, firstOrientationAtMs)) {
                postStatus("No initial IMU sample")
                return
            }

            while (running.get()) {
                val pkt = readPacket(timeoutMs = 250) ?: continue
                if (pkt is RayNeoPacket.Sensor) {
                    val quat = filter.update(pkt.sample)
                    postOrientation(quat)
                }
            }
        } finally {
            bestEffortSend(RayNeoProtocol.CMD_CLOSE_IMU)
            closeConnection()
            running.set(false)
            ioThread = null
        }
    }

    private fun initializeSensorPose(filter: OrientationFilter, firstOrientationAtMs: Long): Boolean {
        postStatus("Waiting for IMU warmup...")
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Sensor) {
                val quat = filter.update(pkt.sample)
                if (SystemClock.elapsedRealtime() >= firstOrientationAtMs) {
                    postOrientation(quat)
                    return true
                }
            }
        }
        return false
    }

    private fun initializeProtocol(): RayNeoDeviceInfo? {
        if (!sendCommand(RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO)) {
            return null
        }

        val info = waitForDeviceInfo(2500) ?: return null

        if (info.sideBySideEnabled) {
            postStatus("Device reports side-by-side mode enabled")
        }

        if (!sendCommand(RayNeoProtocol.CMD_OPEN_IMU)) {
            return null
        }
        if (!waitForAck(RayNeoProtocol.CMD_OPEN_IMU, 1500)) {
            return null
        }

        return info
    }

    private fun waitForDeviceInfo(timeoutMs: Int): RayNeoDeviceInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Response && pkt.cmd == RayNeoProtocol.CMD_ACQUIRE_DEVICE_INFO) {
                val info = RayNeoProtocol.parseDeviceInfo(pkt.raw)
                if (info != null) {
                    return info
                }
            }
        }
        return null
    }

    private fun waitForAck(expectedCmd: Int, timeoutMs: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val pkt = readPacket(200) ?: continue
            if (pkt is RayNeoPacket.Response && pkt.cmd == expectedCmd) {
                return true
            }
        }
        return false
    }

    private fun readPacket(timeoutMs: Int): RayNeoPacket? {
        val conn = connection ?: return null
        val epIn = inEndpoint ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val readBuf = ByteArray(maxOf(epIn.maxPacketSize, 64))

        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            val packet = assembler.nextPacket()
            if (packet != null) {
                return RayNeoProtocol.classifyPacket(packet)
            }

            val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 50)
            if (n > 0) {
                assembler.append(readBuf, n)
            } else {
                SystemClock.sleep(5)
            }
        }
        return null
    }

    private fun sendCommand(cmd: Int, arg: Int = 0): Boolean {
        val conn = connection ?: return false
        val epOut = outEndpoint ?: return false
        val packet = RayNeoProtocol.buildCommandPacket(cmd, arg, epOut.maxPacketSize)
        val wrote = conn.bulkTransfer(epOut, packet, packet.size, 1000)
        return wrote == packet.size
    }

    private fun bestEffortSend(cmd: Int, arg: Int = 0) {
        if (connection == null || outEndpoint == null) {
            return
        }
        sendCommand(cmd, arg)
    }

    private fun closeConnection() {
        val conn = connection
        val iface = usbInterface

        if (conn != null && iface != null) {
            runCatching { conn.releaseInterface(iface) }
        }
        runCatching { conn?.close() }

        connection = null
        usbInterface = null
        inEndpoint = null
        outEndpoint = null
        activeDeviceId = -1
        assembler = RayNeoPacketAssembler()
    }

    private fun findRayNeoDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { isRayNeo(it) }
    }

    private fun isRayNeo(device: UsbDevice): Boolean {
        return device.vendorId == RayNeoProtocol.VENDOR_ID &&
            device.productId == RayNeoProtocol.PRODUCT_ID
    }

    private fun buildPermissionIntent(): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun registerReceiver() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun postOrientation(wxyz: FloatArray) {
        mainHandler.post { onOrientation(wxyz) }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.informalcode.rayneoairtest.USB_PERMISSION"
        const val SENSOR_WARMUP_DELAY_MS = 2000L
    }
}
