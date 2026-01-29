package com.orbits.queuesystem.helper.server

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Singleton manager for USB Serial communication with FTDI hard keypads.
 * Maintains persistent connection across activity lifecycle.
 */
class FTDISerialManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FTDISerialManager"
        private const val ACTION_USB_PERMISSION = "com.orbits.queuesystem.USB_PERMISSION"
        private const val DEFAULT_BAUD_RATE = 9600
        private const val INTER_FRAME_DELAY_MS = 50L
        private const val AUTO_RECONNECT_DELAY_MS = 3000L

        @Volatile
        private var instance: FTDISerialManager? = null

        /**
         * Get singleton instance. Must be initialized first with init().
         */
        fun getInstance(): FTDISerialManager {
            return instance ?: throw IllegalStateException(
                "FTDISerialManager not initialized. Call init(context) first."
            )
        }

        /**
         * Initialize the singleton with application context.
         * Should be called once from Application.onCreate() or MainActivity.
         */
        fun init(context: Context): FTDISerialManager {
            return instance ?: synchronized(this) {
                instance ?: FTDISerialManager(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }

        /**
         * Check if instance is initialized.
         */
        fun isInitialized(): Boolean = instance != null

        /**
         * Release the singleton instance (only call on app termination).
         */
        fun destroy() {
            instance?.release()
            instance = null
        }
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var baudRate = DEFAULT_BAUD_RATE
    private var isConnected = false
    private var isInitialized = false

    // Auto-reconnect support
    private var autoReconnectEnabled = true
    private var lastConnectedDriver: UsbSerialDriver? = null
    private val reconnectRunnable = Runnable { attemptAutoReconnect() }

    // Frame buffer for handling fragmented data
    private var frameBuffer = ByteArray(0)
    private val bufferLock = Any()

    // Write queue for thread-safe transmission
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private var writeThread: Thread? = null
    private var isWriteThreadRunning = false

    // Listeners
    private val eventListeners = CopyOnWriteArrayList<FTDISerialEventListener>()

    // Available devices
    private val availableDrivers = mutableListOf<UsbSerialDriver>()

    // Connection state for UI
    data class ConnectionState(
        val isConnected: Boolean,
        val deviceName: String?,
        val baudRate: Int,
        val autoReconnectEnabled: Boolean
    )

    /**
     * Listener interface for FTDI serial events.
     */
    interface FTDISerialEventListener {
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onFrameReceived(frame: ByteArray)
        fun onError(error: String)
        fun onConnectionStateChanged(state: ConnectionState) {}
    }

    private var receiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB permission granted for device: ${it.deviceName}")
                            connectToDevice(it)
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for device: ${device?.deviceName}")
                        notifyError("USB permission denied")
                    }
                }
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                Log.d(TAG, "USB device detached")
                handleDeviceDetached()
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                Log.d(TAG, "USB device attached")
                refreshDeviceList()
                if (autoReconnectEnabled && !isConnected) {
                    mainHandler.postDelayed({ connectToFirstAvailable() }, 500)
                }
            }
        }
    }

    /**
     * Initialize the serial manager and register receivers.
     */
    private fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "FTDISerialManager already initialized")
            return
        }

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, permissionFilter)
            context.registerReceiver(usbDetachReceiver, detachFilter)
            context.registerReceiver(usbAttachReceiver, attachFilter)
        }
        receiverRegistered = true

        refreshDeviceList()
        isInitialized = true
        Log.d(TAG, "FTDISerialManager initialized (singleton)")
    }

    /**
     * Release resources and unregister receivers.
     */
    private fun release() {
        mainHandler.removeCallbacks(reconnectRunnable)
        disconnect()

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
                context.unregisterReceiver(usbDetachReceiver)
                context.unregisterReceiver(usbAttachReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receivers: ${e.message}")
            }
            receiverRegistered = false
        }

        executor.shutdown()
        isInitialized = false
        Log.d(TAG, "FTDISerialManager released")
    }

    /**
     * Add event listener.
     */
    fun addEventListener(listener: FTDISerialEventListener) {
        if (!eventListeners.contains(listener)) {
            eventListeners.add(listener)
            // Immediately notify the new listener of current state
            listener.onConnectionStateChanged(getConnectionState())
        }
    }

    /**
     * Remove event listener.
     */
    fun removeEventListener(listener: FTDISerialEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Get current connection state.
     */
    fun getConnectionState(): ConnectionState {
        return ConnectionState(
            isConnected = isConnected,
            deviceName = usbSerialPort?.device?.productName,
            baudRate = baudRate,
            autoReconnectEnabled = autoReconnectEnabled
        )
    }

    /**
     * Set auto-reconnect enabled/disabled.
     */
    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            mainHandler.removeCallbacks(reconnectRunnable)
        }
        notifyConnectionStateChanged()
    }

    /**
     * Check if auto-reconnect is enabled.
     */
    fun isAutoReconnectEnabled(): Boolean = autoReconnectEnabled

    /**
     * Refresh the list of available USB serial devices.
     */
    fun refreshDeviceList(): List<UsbSerialDriver> {
        availableDrivers.clear()
        availableDrivers.addAll(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
        Log.d(TAG, "Found ${availableDrivers.size} USB serial device(s)")
        return availableDrivers.toList()
    }

    /**
     * Get list of available devices.
     */
    fun getAvailableDevices(): List<UsbSerialDriver> {
        return availableDrivers.toList()
    }

    /**
     * Connect to the first available USB serial device.
     */
    fun connectToFirstAvailable(): Boolean {
        refreshDeviceList()
        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            notifyError("No USB serial devices found")
            return false
        }
        return connectToDriver(availableDrivers[0])
    }

    /**
     * Connect to a specific USB serial driver.
     */
    fun connectToDriver(driver: UsbSerialDriver): Boolean {
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for: ${device.deviceName}")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            return false
        }

        return connectToDevice(device)
    }

    /**
     * Connect to a USB device (after permission granted).
     */
    private fun connectToDevice(device: UsbDevice): Boolean {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.find { it.device == device }

        if (driver == null) {
            Log.e(TAG, "No driver found for device: ${device.deviceName}")
            notifyError("No driver found for device")
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open device: ${device.deviceName}")
            notifyError("Failed to open USB device")
            return false
        }

        try {
            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            startSerialIoManager()
            startWriteThread()

            isConnected = true
            lastConnectedDriver = driver
            mainHandler.removeCallbacks(reconnectRunnable)

            Log.d(TAG, "Connected to device: ${device.deviceName}")
            notifyConnected(device.deviceName)
            notifyConnectionStateChanged()
            return true

        } catch (e: IOException) {
            Log.e(TAG, "Error opening port: ${e.message}")
            notifyError("Error opening port: ${e.message}")
            disconnect()
            return false
        }
    }

    /**
     * Handle device detachment - disconnect and schedule reconnect if enabled.
     */
    private fun handleDeviceDetached() {
        val wasConnected = isConnected
        disconnect()

        if (wasConnected && autoReconnectEnabled) {
            Log.d(TAG, "Scheduling auto-reconnect in ${AUTO_RECONNECT_DELAY_MS}ms")
            mainHandler.postDelayed(reconnectRunnable, AUTO_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Attempt auto-reconnection.
     */
    private fun attemptAutoReconnect() {
        if (!autoReconnectEnabled || isConnected) return

        Log.d(TAG, "Attempting auto-reconnect...")
        refreshDeviceList()

        if (availableDrivers.isNotEmpty()) {
            // Try to reconnect to last known device first
            val targetDriver = lastConnectedDriver?.let { last ->
                availableDrivers.find { it.device.deviceId == last.device.deviceId }
            } ?: availableDrivers[0]

            if (!connectToDriver(targetDriver)) {
                // If connection request sent (waiting for permission), don't reschedule
                Log.d(TAG, "Connection pending (waiting for permission)")
            }
        } else {
            Log.d(TAG, "No devices found, scheduling retry...")
            mainHandler.postDelayed(reconnectRunnable, AUTO_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Disconnect from the current device.
     */
    fun disconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        stopWriteThread()
        serialIoManager?.listener = null
        serialIoManager?.stop()
        serialIoManager = null

        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing port: ${e.message}")
        }
        usbSerialPort = null

        synchronized(bufferLock) {
            frameBuffer = ByteArray(0)
        }

        if (isConnected) {
            isConnected = false
            notifyDisconnected()
            notifyConnectionStateChanged()
        }
        Log.d(TAG, "Disconnected")
    }

    /**
     * Check if connected to a device.
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Set the baud rate. If connected, will reconnect with new baud rate.
     */
    fun setBaudRate(rate: Int) {
        val wasConnected = isConnected
        val currentDriver = lastConnectedDriver

        baudRate = rate
        Log.d(TAG, "Baud rate set to: $rate")

        // If connected, reconnect with new baud rate
        if (wasConnected && currentDriver != null) {
            disconnect()
            connectToDriver(currentDriver)
        }

        notifyConnectionStateChanged()
    }

    /**
     * Get current baud rate.
     */
    fun getBaudRate(): Int = baudRate

    /**
     * Write data to the serial port.
     */
    fun write(data: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "Cannot write: not connected")
            return
        }
        writeQueue.offer(data)
    }

    /**
     * Write data synchronously (blocking).
     */
    fun writeSync(data: ByteArray): Boolean {
        if (!isConnected || usbSerialPort == null) {
            Log.w(TAG, "Cannot write: not connected")
            return false
        }

        return try {
            usbSerialPort?.write(data, 1000)
            Log.d(TAG, "Wrote ${data.size} bytes: ${FTDIProtocol.run { data.toHexString() }}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write error: ${e.message}")
            false
        }
    }

    private fun startSerialIoManager() {
        val port = usbSerialPort ?: return

        serialIoManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Log.d(TAG, "Received ${data.size} bytes: ${FTDIProtocol.run { data.toHexString() }}")
                notifyDataReceived(data)
                processIncomingData(data)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial IO error: ${e.message}")
                mainHandler.post {
                    notifyError("Serial IO error: ${e.message}")
                    handleDeviceDetached()
                }
            }
        })

        executor.submit(serialIoManager)
        Log.d(TAG, "Serial IO manager started")
    }

    private fun startWriteThread() {
        isWriteThreadRunning = true
        writeThread = Thread {
            while (isWriteThreadRunning) {
                try {
                    val data = writeQueue.take()
                    if (isConnected && usbSerialPort != null) {
                        usbSerialPort?.write(data, 1000)
                        Log.d(TAG, "Wrote ${data.size} bytes: ${FTDIProtocol.run { data.toHexString() }}")
                        Thread.sleep(INTER_FRAME_DELAY_MS)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Write thread error: ${e.message}")
                }
            }
        }
        writeThread?.start()
        Log.d(TAG, "Write thread started")
    }

    private fun stopWriteThread() {
        isWriteThreadRunning = false
        writeThread?.interrupt()
        writeThread = null
        writeQueue.clear()
    }

    /**
     * Process incoming data and extract complete frames.
     */
    private fun processIncomingData(data: ByteArray) {
        synchronized(bufferLock) {
            frameBuffer += data

            while (true) {
                val (frame, remaining) = FTDIProtocol.extractFrame(frameBuffer)
                frameBuffer = remaining

                if (frame != null) {
                    Log.d(TAG, "Complete frame extracted: ${FTDIProtocol.run { frame.toHexString() }}")
                    notifyFrameReceived(frame)
                } else {
                    break
                }
            }
        }
    }

    private fun notifyConnected(deviceName: String) {
        mainHandler.post {
            eventListeners.forEach { it.onDeviceConnected(deviceName) }
        }
    }

    private fun notifyDisconnected() {
        mainHandler.post {
            eventListeners.forEach { it.onDeviceDisconnected() }
        }
    }

    private fun notifyDataReceived(data: ByteArray) {
        mainHandler.post {
            eventListeners.forEach { it.onDataReceived(data) }
        }
    }

    private fun notifyFrameReceived(frame: ByteArray) {
        mainHandler.post {
            eventListeners.forEach { it.onFrameReceived(frame) }
        }
    }

    private fun notifyError(error: String) {
        mainHandler.post {
            eventListeners.forEach { it.onError(error) }
        }
    }

    private fun notifyConnectionStateChanged() {
        val state = getConnectionState()
        mainHandler.post {
            eventListeners.forEach { it.onConnectionStateChanged(state) }
        }
    }
}
