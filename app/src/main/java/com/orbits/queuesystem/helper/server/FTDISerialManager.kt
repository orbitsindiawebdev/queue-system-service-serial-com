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
 *
 * SUPPORTS MULTIPLE USB DEVICES via USB Hub:
 * Each keypad connected through a USB hub is treated as a separate USB serial device.
 * The manager maintains connections to all devices and routes data appropriately.
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

    // Multi-device support: Map of device ID to connection info
    data class DeviceConnection(
        val deviceId: Int,
        val deviceName: String,
        val port: UsbSerialPort,
        val ioManager: SerialInputOutputManager,
        var keypadAddress: String? = null  // Mapped after CONNECT command
    )

    private val connectedDevices = mutableMapOf<Int, DeviceConnection>()
    private val deviceLock = Any()

    // Map keypad address to device ID for routing outgoing data
    private val addressToDeviceId = mutableMapOf<String, Int>()

    // Legacy single-device compatibility (will use first connected device)
    private var usbSerialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    private val executor = Executors.newCachedThreadPool()  // Changed to cached for multiple devices
    private val mainHandler = Handler(Looper.getMainLooper())

    private var baudRate = DEFAULT_BAUD_RATE
    private var isConnected = false
    private var isInitialized = false

    // Auto-reconnect support
    private var autoReconnectEnabled = true
    private var lastConnectedDrivers = mutableListOf<UsbSerialDriver>()
    private val reconnectRunnable = Runnable { attemptAutoReconnect() }

    // Frame buffer for handling fragmented data - per device
    private val frameBuffers = mutableMapOf<Int, ByteArray>()
    private val bufferLock = Any()

    // Write queue for thread-safe transmission - per device
    data class WriteRequest(val deviceId: Int?, val data: ByteArray)  // null deviceId = broadcast to all
    private val writeQueue = LinkedBlockingQueue<WriteRequest>()
    private var writeThread: Thread? = null
    private var isWriteThreadRunning = false

    // Listeners
    private val eventListeners = CopyOnWriteArrayList<FTDISerialEventListener>()

    // Available devices
    private val availableDrivers = mutableListOf<UsbSerialDriver>()

    // Pending permission requests
    private val pendingPermissionDevices = mutableListOf<UsbDevice>()

    // Connection state for UI
    data class ConnectionState(
        val isConnected: Boolean,
        val deviceName: String?,
        val baudRate: Int,
        val autoReconnectEnabled: Boolean,
        val connectedDeviceCount: Int = 0,
        val connectedDevices: List<String> = emptyList()
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

        // Multi-device support callbacks
        fun onMultiDeviceConnected(deviceId: Int, deviceName: String) {}
        fun onMultiDeviceDisconnected(deviceId: Int, deviceName: String) {}
        fun onMultiDeviceDataReceived(deviceId: Int, data: ByteArray) {}
        fun onMultiDeviceFrameReceived(deviceId: Int, frame: ByteArray) {}
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
                            pendingPermissionDevices.remove(it)
                            connectToDevice(it)

                            // Continue with other pending devices
                            processNextPendingDevice()
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for device: ${device?.deviceName}")
                        pendingPermissionDevices.remove(device)
                        notifyError("USB permission denied for ${device?.deviceName}")

                        // Continue with other pending devices
                        processNextPendingDevice()
                    }
                }
            }
        }
    }

    /**
     * Process the next device waiting for permission.
     */
    private fun processNextPendingDevice() {
        if (pendingPermissionDevices.isNotEmpty()) {
            val nextDevice = pendingPermissionDevices.first()
            requestPermissionForDevice(nextDevice)
        }
    }

    /**
     * Request USB permission for a specific device.
     */
    private fun requestPermissionForDevice(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, device.deviceId, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (device != null) {
                    Log.d(TAG, "USB device detached: ${device.deviceName} (ID: ${device.deviceId})")
                    handleSingleDeviceDetached(device.deviceId)
                } else {
                    Log.d(TAG, "USB device detached (unknown device)")
                    handleDeviceDetached()
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                Log.d(TAG, "USB device attached: ${device?.deviceName} (VID=${device?.vendorId}, PID=${device?.productId})")

                // Try to connect to this specific new device directly if we have permission
                if (device != null && autoReconnectEnabled) {
                    mainHandler.postDelayed({
                        // Refresh device list first
                        refreshDeviceList()

                        // Try to connect to the newly attached device
                        if (usbManager.hasPermission(device)) {
                            Log.d(TAG, "Auto-connecting to newly attached device: ${device.deviceName}")
                            connectToDevice(device)
                        } else {
                            Log.d(TAG, "Requesting permission for newly attached device: ${device.deviceName}")
                            // Find the driver for this device and request permission
                            val driver = availableDrivers.find { it.device.deviceId == device.deviceId }
                            if (driver != null) {
                                connectToDriver(driver)
                            } else {
                                // Device might not be a serial device, try connectToAllAvailable
                                connectToAllAvailable()
                            }
                        }
                    }, 500)
                } else {
                    // Just refresh the device list
                    refreshDeviceList()
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
        disconnectAll()

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
        synchronized(deviceLock) {
            val deviceNames = connectedDevices.values.map { it.deviceName }
            return ConnectionState(
                isConnected = connectedDevices.isNotEmpty(),
                deviceName = deviceNames.firstOrNull(),
                baudRate = baudRate,
                autoReconnectEnabled = autoReconnectEnabled,
                connectedDeviceCount = connectedDevices.size,
                connectedDevices = deviceNames
            )
        }
    }

    /**
     * Get count of connected devices.
     */
    fun getConnectedDeviceCount(): Int {
        synchronized(deviceLock) {
            return connectedDevices.size
        }
    }

    /**
     * Get info about all connected devices.
     */
    fun getConnectedDevicesInfo(): List<Pair<Int, String>> {
        synchronized(deviceLock) {
            return connectedDevices.values.map { it.deviceId to it.deviceName }
        }
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

        // First, log all USB devices connected to the system
        val allUsbDevices = usbManager.deviceList
        Log.d(TAG, "Total USB devices on system: ${allUsbDevices.size}")
        allUsbDevices.forEach { (name, device) ->
            Log.d(TAG, "  USB Device: $name, VID=${device.vendorId}, PID=${device.productId}, Class=${device.deviceClass}")
        }

        // Find serial drivers
        availableDrivers.addAll(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
        Log.d(TAG, "Found ${availableDrivers.size} USB serial device(s)")
        availableDrivers.forEachIndexed { index, driver ->
            val device = driver.device
            Log.d(TAG, "  Serial Device $index: ${device.deviceName}, VID=${device.vendorId}, PID=${device.productId}, Ports=${driver.ports.size}")
        }

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
     * For legacy compatibility - prefer connectToAllAvailable() for multi-device support.
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
     * Connect to ALL available USB serial devices (via USB hub).
     * This is the preferred method for multi-keypad support.
     */
    fun connectToAllAvailable(): Int {
        refreshDeviceList()

        // Count already connected devices
        val alreadyConnectedCount = synchronized(deviceLock) {
            connectedDevices.size
        }

        if (availableDrivers.isEmpty()) {
            // If we already have connected devices, don't show error
            if (alreadyConnectedCount > 0) {
                Log.d(TAG, "No new USB serial devices found, but $alreadyConnectedCount already connected")
                return alreadyConnectedCount
            }
            Log.w(TAG, "No USB serial devices found")
            notifyError("No USB serial devices found")
            return 0
        }

        Log.d(TAG, "Found ${availableDrivers.size} USB serial device(s), $alreadyConnectedCount already connected, attempting to connect to new ones...")

        var connectedCount = alreadyConnectedCount
        val devicesNeedingPermission = mutableListOf<UsbDevice>()

        for (driver in availableDrivers) {
            val device = driver.device
            val deviceId = device.deviceId

            // Skip if already connected
            val alreadyConnected = synchronized(deviceLock) {
                connectedDevices.containsKey(deviceId)
            }

            if (alreadyConnected) {
                Log.d(TAG, "Device ${device.deviceName} (ID: $deviceId) already connected, skipping")
                continue  // Don't increment count again, already counted above
            }

            if (!usbManager.hasPermission(device)) {
                Log.d(TAG, "Need permission for device: ${device.deviceName}")
                devicesNeedingPermission.add(device)
            } else {
                if (connectToDevice(device)) {
                    connectedCount++
                }
            }
        }

        // Request permissions for devices that need them (one at a time)
        if (devicesNeedingPermission.isNotEmpty()) {
            pendingPermissionDevices.addAll(devicesNeedingPermission)
            requestPermissionForDevice(devicesNeedingPermission.first())
        }

        Log.d(TAG, "Connected to $connectedCount device(s), ${devicesNeedingPermission.size} pending permission")
        return connectedCount
    }

    /**
     * Connect to a specific USB serial driver.
     */
    fun connectToDriver(driver: UsbSerialDriver): Boolean {
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for: ${device.deviceName}")
            if (!pendingPermissionDevices.contains(device)) {
                pendingPermissionDevices.add(device)
            }
            requestPermissionForDevice(device)
            return false
        }

        return connectToDevice(device)
    }

    /**
     * Connect to a USB device (after permission granted).
     * Supports multiple devices - each device gets its own connection.
     */
    private fun connectToDevice(device: UsbDevice): Boolean {
        val deviceId = device.deviceId
        val deviceName = device.deviceName ?: "Unknown"

        // Check if already connected
        synchronized(deviceLock) {
            if (connectedDevices.containsKey(deviceId)) {
                Log.d(TAG, "Device $deviceName already connected")
                return true
            }
        }

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.find { it.device == device }

        if (driver == null) {
            Log.e(TAG, "No driver found for device: $deviceName")
            notifyError("No driver found for device: $deviceName")
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open device: $deviceName")
            notifyError("Failed to open USB device: $deviceName")
            return false
        }

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            // Create IO manager for this device
            val ioManager = createSerialIoManager(port, deviceId, deviceName)

            // Store the connection
            synchronized(deviceLock) {
                connectedDevices[deviceId] = DeviceConnection(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    port = port,
                    ioManager = ioManager
                )

                // Initialize frame buffer for this device
                frameBuffers[deviceId] = ByteArray(0)

                // For legacy compatibility, set the first device as the default
                if (usbSerialPort == null) {
                    usbSerialPort = port
                    serialIoManager = ioManager
                }

                // Track connected drivers for reconnect
                if (!lastConnectedDrivers.any { it.device.deviceId == deviceId }) {
                    lastConnectedDrivers.add(driver)
                }
            }

            // Start IO manager
            executor.submit(ioManager)

            // Start write thread if not already running
            startWriteThread()

            isConnected = true
            mainHandler.removeCallbacks(reconnectRunnable)

            Log.d(TAG, "Connected to device: $deviceName (ID: $deviceId)")
            Log.d(TAG, "Total connected devices: ${connectedDevices.size}")

            notifyConnected(deviceName)
            notifyMultiDeviceConnected(deviceId, deviceName)
            notifyConnectionStateChanged()
            return true

        } catch (e: IOException) {
            Log.e(TAG, "Error opening port for $deviceName: ${e.message}")
            notifyError("Error opening port for $deviceName: ${e.message}")
            return false
        }
    }

    /**
     * Create a SerialInputOutputManager for a specific device.
     */
    private fun createSerialIoManager(port: UsbSerialPort, deviceId: Int, deviceName: String): SerialInputOutputManager {
        return SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                Log.d(TAG, "Received ${data.size} bytes from device $deviceId: ${FTDIProtocol.run { data.toHexString() }}")
                notifyDataReceived(data)
                notifyMultiDeviceDataReceived(deviceId, data)
                processIncomingData(deviceId, data)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial IO error on device $deviceId ($deviceName): ${e.message}")

                // Check if this is a USB power/hub issue
                val isUsbHubError = e.message?.contains("get_status") == true ||
                        e.message?.contains("USB") == true

                mainHandler.post {
                    if (isUsbHubError) {
                        notifyError("USB hub error on $deviceName. Try using a powered USB hub.")
                    } else {
                        notifyError("Serial IO error on $deviceName: ${e.message}")
                    }
                    handleSingleDeviceDetached(deviceId)

                    // If this looks like a USB hub power issue, schedule a quick reconnect
                    if (isUsbHubError && autoReconnectEnabled) {
                        Log.d(TAG, "USB hub error detected, scheduling quick reconnect in 1500ms")
                        mainHandler.postDelayed({
                            connectToAllAvailable()
                        }, 1500)
                    }
                }
            }
        })
    }

    /**
     * Handle detachment of a single device.
     */
    private fun handleSingleDeviceDetached(deviceId: Int) {
        var deviceName: String? = null
        var keypadAddress: String? = null

        synchronized(deviceLock) {
            val connection = connectedDevices.remove(deviceId)
            if (connection != null) {
                deviceName = connection.deviceName
                keypadAddress = connection.keypadAddress

                // Stop IO manager
                connection.ioManager.listener = null
                connection.ioManager.stop()

                // Close port
                try {
                    connection.port.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Error closing port for device $deviceId: ${e.message}")
                }

                // Clean up frame buffer
                frameBuffers.remove(deviceId)

                // Remove address mapping
                if (keypadAddress != null) {
                    addressToDeviceId.remove(keypadAddress)
                }

                // Update legacy single-device reference
                if (usbSerialPort?.device?.deviceId == deviceId) {
                    usbSerialPort = connectedDevices.values.firstOrNull()?.port
                    serialIoManager = connectedDevices.values.firstOrNull()?.ioManager
                }

                Log.d(TAG, "Device $deviceId ($deviceName) disconnected. Remaining: ${connectedDevices.size}")
            }

            isConnected = connectedDevices.isNotEmpty()

            // Remove from last connected drivers
            lastConnectedDrivers.removeAll { it.device.deviceId == deviceId }
        }

        if (deviceName != null) {
            notifyMultiDeviceDisconnected(deviceId, deviceName!!)
        }

        // If all devices disconnected
        if (!isConnected) {
            notifyDisconnected()
            stopWriteThread()

            if (autoReconnectEnabled) {
                Log.d(TAG, "All devices disconnected. Scheduling auto-reconnect in ${AUTO_RECONNECT_DELAY_MS}ms")
                mainHandler.postDelayed(reconnectRunnable, AUTO_RECONNECT_DELAY_MS)
            }
        }

        notifyConnectionStateChanged()
    }

    /**
     * Handle complete device detachment (all devices) - for legacy compatibility.
     */
    private fun handleDeviceDetached() {
        val wasConnected = isConnected
        disconnectAll()

        if (wasConnected && autoReconnectEnabled) {
            Log.d(TAG, "Scheduling auto-reconnect in ${AUTO_RECONNECT_DELAY_MS}ms")
            mainHandler.postDelayed(reconnectRunnable, AUTO_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Attempt auto-reconnection to all previously connected devices.
     */
    private fun attemptAutoReconnect() {
        if (!autoReconnectEnabled) return

        // If already connected to some devices, try to find and connect to any new ones
        synchronized(deviceLock) {
            if (connectedDevices.size >= availableDrivers.size && connectedDevices.isNotEmpty()) {
                return
            }
        }

        Log.d(TAG, "Attempting auto-reconnect...")
        val connectedCount = connectToAllAvailable()

        if (connectedCount == 0 && availableDrivers.isEmpty()) {
            Log.d(TAG, "No devices found, scheduling retry...")
            mainHandler.postDelayed(reconnectRunnable, AUTO_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Disconnect from all devices.
     */
    fun disconnectAll() {
        mainHandler.removeCallbacks(reconnectRunnable)
        stopWriteThread()

        synchronized(deviceLock) {
            // Close all device connections
            connectedDevices.values.forEach { connection ->
                connection.ioManager.listener = null
                connection.ioManager.stop()
                try {
                    connection.port.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Error closing port for device ${connection.deviceId}: ${e.message}")
                }
            }

            connectedDevices.clear()
            addressToDeviceId.clear()
            frameBuffers.clear()

            usbSerialPort = null
            serialIoManager = null
        }

        if (isConnected) {
            isConnected = false
            notifyDisconnected()
            notifyConnectionStateChanged()
        }
        Log.d(TAG, "Disconnected from all devices")
    }

    /**
     * Disconnect from a specific device by ID.
     */
    fun disconnectDevice(deviceId: Int) {
        handleSingleDeviceDetached(deviceId)
    }

    /**
     * Legacy method - disconnect from the current/first device.
     * For multi-device support, use disconnectAll() or disconnectDevice(deviceId).
     */
    fun disconnect() {
        disconnectAll()
    }

    /**
     * Check if connected to any device.
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Check if connected to a specific device.
     */
    fun isDeviceConnected(deviceId: Int): Boolean {
        synchronized(deviceLock) {
            return connectedDevices.containsKey(deviceId)
        }
    }

    /**
     * Map a keypad address to a device ID.
     * This is called when we learn which keypad is on which USB device.
     */
    fun mapAddressToDevice(address: String, deviceId: Int) {
        synchronized(deviceLock) {
            val normalizedAddress = address.padStart(4, '0').take(4)
            addressToDeviceId[normalizedAddress] = deviceId

            // Also update the device connection with the keypad address
            connectedDevices[deviceId]?.keypadAddress = normalizedAddress

            Log.d(TAG, "Mapped keypad address $normalizedAddress to device $deviceId")
        }
    }

    /**
     * Get the device ID for a keypad address.
     */
    fun getDeviceIdForAddress(address: String): Int? {
        synchronized(deviceLock) {
            val normalizedAddress = address.padStart(4, '0').take(4)
            return addressToDeviceId[normalizedAddress]
        }
    }

    /**
     * Get the keypad address for a device ID.
     */
    fun getAddressForDeviceId(deviceId: Int): String? {
        synchronized(deviceLock) {
            return connectedDevices[deviceId]?.keypadAddress
        }
    }

    /**
     * Set the baud rate. If connected, will reconnect all devices with new baud rate.
     */
    fun setBaudRate(rate: Int) {
        val wasConnected = isConnected

        baudRate = rate
        Log.d(TAG, "Baud rate set to: $rate")

        // If connected, reconnect all devices with new baud rate
        if (wasConnected) {
            val drivers = lastConnectedDrivers.toList()
            disconnectAll()
            drivers.forEach { driver ->
                connectToDriver(driver)
            }
        }

        notifyConnectionStateChanged()
    }

    /**
     * Get current baud rate.
     */
    fun getBaudRate(): Int = baudRate

    /**
     * Write data to all connected devices (broadcast).
     */
    fun write(data: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "Cannot write: not connected")
            return
        }
        writeQueue.offer(WriteRequest(null, data))  // null = broadcast to all
    }

    /**
     * Write data to a specific device by ID.
     */
    fun writeToDevice(deviceId: Int, data: ByteArray) {
        if (!isConnected) {
            Log.w(TAG, "Cannot write: not connected")
            return
        }
        writeQueue.offer(WriteRequest(deviceId, data))
    }

    /**
     * Write data to the device associated with a keypad address.
     */
    fun writeToAddress(address: String, data: ByteArray) {
        val deviceId = getDeviceIdForAddress(address)
        if (deviceId != null) {
            writeToDevice(deviceId, data)
        } else {
            // If we don't know which device, broadcast to all
            Log.d(TAG, "No device mapping for address $address, broadcasting to all")
            write(data)
        }
    }

    /**
     * Write data synchronously to all connected devices (blocking).
     */
    fun writeSync(data: ByteArray): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot write: not connected")
            return false
        }

        var success = false
        synchronized(deviceLock) {
            for (connection in connectedDevices.values) {
                try {
                    connection.port.write(data, 1000)
                    Log.d(TAG, "Wrote ${data.size} bytes to device ${connection.deviceId}: ${FTDIProtocol.run { data.toHexString() }}")
                    success = true
                } catch (e: IOException) {
                    Log.e(TAG, "Write error on device ${connection.deviceId}: ${e.message}")
                }
            }
        }
        return success
    }

    /**
     * Write data synchronously to a specific device (blocking).
     */
    fun writeSyncToDevice(deviceId: Int, data: ByteArray): Boolean {
        synchronized(deviceLock) {
            val connection = connectedDevices[deviceId]
            if (connection == null) {
                Log.w(TAG, "Cannot write: device $deviceId not connected")
                return false
            }

            return try {
                connection.port.write(data, 1000)
                Log.d(TAG, "Wrote ${data.size} bytes to device $deviceId: ${FTDIProtocol.run { data.toHexString() }}")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Write error on device $deviceId: ${e.message}")
                false
            }
        }
    }

    /**
     * Write data synchronously to the device for a specific keypad address (blocking).
     */
    fun writeSyncToAddress(address: String, data: ByteArray): Boolean {
        val deviceId = getDeviceIdForAddress(address)
        return if (deviceId != null) {
            writeSyncToDevice(deviceId, data)
        } else {
            // If we don't know which device, broadcast to all
            Log.d(TAG, "No device mapping for address $address, writing to all")
            writeSync(data)
        }
    }

    /**
     * Start the write thread if not already running.
     */
    private fun startWriteThread() {
        if (isWriteThreadRunning) return

        isWriteThreadRunning = true
        writeThread = Thread {
            while (isWriteThreadRunning) {
                try {
                    val request = writeQueue.take()
                    if (!isConnected) continue

                    synchronized(deviceLock) {
                        if (request.deviceId != null) {
                            // Write to specific device
                            val connection = connectedDevices[request.deviceId]
                            if (connection != null) {
                                try {
                                    connection.port.write(request.data, 1000)
                                    Log.d(TAG, "Wrote ${request.data.size} bytes to device ${request.deviceId}: ${FTDIProtocol.run { request.data.toHexString() }}")
                                } catch (e: IOException) {
                                    Log.e(TAG, "Write error on device ${request.deviceId}: ${e.message}")
                                }
                            }
                        } else {
                            // Broadcast to all devices
                            for (connection in connectedDevices.values) {
                                try {
                                    connection.port.write(request.data, 1000)
                                    Log.d(TAG, "Wrote ${request.data.size} bytes to device ${connection.deviceId}: ${FTDIProtocol.run { request.data.toHexString() }}")
                                } catch (e: IOException) {
                                    Log.e(TAG, "Write error on device ${connection.deviceId}: ${e.message}")
                                }
                            }
                        }
                    }
                    Thread.sleep(INTER_FRAME_DELAY_MS)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
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
     * Process incoming data and extract complete frames (per-device).
     */
    private fun processIncomingData(deviceId: Int, data: ByteArray) {
        synchronized(bufferLock) {
            // Get or create buffer for this device
            var buffer = frameBuffers[deviceId] ?: ByteArray(0)
            buffer += data
            frameBuffers[deviceId] = buffer

            while (true) {
                val currentBuffer = frameBuffers[deviceId] ?: break
                val (frame, remaining) = FTDIProtocol.extractFrame(currentBuffer)
                frameBuffers[deviceId] = remaining

                if (frame != null) {
                    Log.d(TAG, "Complete frame from device $deviceId: ${FTDIProtocol.run { frame.toHexString() }}")

                    // Try to extract keypad address from frame and map it to this device
                    val address = extractAddressFromFrame(frame)
                    if (address != null) {
                        mapAddressToDevice(address, deviceId)
                    }

                    notifyFrameReceived(frame)
                    notifyMultiDeviceFrameReceived(deviceId, frame)
                } else {
                    break
                }
            }
        }
    }

    /**
     * Extract keypad address from a frame.
     * Frame format: @[addr(4)][...]
     */
    private fun extractAddressFromFrame(frame: ByteArray): String? {
        if (frame.size < 5 || frame[0] != 0x40.toByte()) {
            return null
        }
        // Extract 4-byte address after '@'
        return try {
            String(frame.sliceArray(1..4), Charsets.US_ASCII)
        } catch (e: Exception) {
            null
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

    private fun notifyMultiDeviceConnected(deviceId: Int, deviceName: String) {
        mainHandler.post {
            eventListeners.forEach { it.onMultiDeviceConnected(deviceId, deviceName) }
        }
    }

    private fun notifyMultiDeviceDisconnected(deviceId: Int, deviceName: String) {
        mainHandler.post {
            eventListeners.forEach { it.onMultiDeviceDisconnected(deviceId, deviceName) }
        }
    }

    private fun notifyMultiDeviceDataReceived(deviceId: Int, data: ByteArray) {
        mainHandler.post {
            eventListeners.forEach { it.onMultiDeviceDataReceived(deviceId, data) }
        }
    }

    private fun notifyMultiDeviceFrameReceived(deviceId: Int, frame: ByteArray) {
        mainHandler.post {
            eventListeners.forEach { it.onMultiDeviceFrameReceived(deviceId, frame) }
        }
    }
}
