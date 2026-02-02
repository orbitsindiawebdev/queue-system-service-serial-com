package com.orbits.queuesystem.helper.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Singleton bridge between FTDI serial communication and the queue system.
 * Translates FTDI protocol commands to queue operations and vice versa.
 *
 * IMPORTANT: This bridge supports MULTIPLE hard keypads on the same RS-485 bus.
 * Each keypad has a unique 4-digit address (e.g., "0001", "0002", "0003").
 * All keypads share the same physical serial connection but are addressed individually.
 */
class FTDIBridge private constructor(private val context: Context) : FTDISerialManager.FTDISerialEventListener {

    companion object {
        private const val TAG = "FTDIBridge"

        // Polling interval for keypads that need periodic updates
        private const val KEYPAD_POLL_INTERVAL_MS = 5000L

        @Volatile
        private var instance: FTDIBridge? = null

        /**
         * Get singleton instance. Must be initialized first with init().
         */
        fun getInstance(): FTDIBridge {
            return instance ?: throw IllegalStateException(
                "FTDIBridge not initialized. Call init(context) first."
            )
        }

        /**
         * Initialize the singleton with application context.
         */
        fun init(context: Context): FTDIBridge {
            return instance ?: synchronized(this) {
                instance ?: FTDIBridge(context.applicationContext).also {
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
         * Release the singleton instance.
         */
        fun destroy() {
            instance?.release()
            instance = null
        }
    }

    // Map of FTDI addresses to counter IDs - supports multiple keypads
    private val addressToCounterMap = mutableMapOf<String, String>()
    private val counterToAddressMap = mutableMapOf<String, String>()

    // Map of counter IDs to service IDs - for broadcasting NPW updates
    private val counterToServiceMap = mutableMapOf<String, String>()

    // Track keypad connection times for debugging
    private val keypadConnectionTimes = mutableMapOf<String, Long>()

    // Event listeners for UI updates
    private val eventListeners = mutableListOf<FTDIEventListener>()

    // Queue operations handler
    private var queueOperations: FTDIQueueOperations? = null

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Data class representing a connected keypad.
     */
    data class ConnectedKeypad(
        val address: String,
        val counterId: String,
        val connectionTime: Long,
        val lastActivityTime: Long
    )

    /**
     * Listener interface for FTDI bridge events.
     */
    interface FTDIEventListener {
        fun onKeypadConnected(address: String, counterId: String)
        fun onKeypadDisconnected(address: String)
        fun onCommandReceived(address: String, command: String, data: String)
        fun onResponseSent(address: String, response: String)
        fun onConnectionStatusChanged(connected: Boolean)
        fun onLogMessage(message: String)
        fun onKeypadCountChanged(count: Int) {}
    }

    /**
     * Initialize the bridge and connect to FTDISerialManager.
     */
    private fun initialize() {
        if (FTDISerialManager.isInitialized()) {
            FTDISerialManager.getInstance().addEventListener(this)
            Log.d(TAG, "FTDIBridge initialized and connected to FTDISerialManager")
        } else {
            Log.w(TAG, "FTDISerialManager not initialized yet")
        }
    }

    /**
     * Set the queue operations handler.
     */
    fun setQueueOperations(operations: FTDIQueueOperations) {
        queueOperations = operations
        Log.d(TAG, "Queue operations handler set: ${operations.javaClass.simpleName}")
        notifyLog("Queue operations handler set: ${operations.javaClass.simpleName}")
    }

    /**
     * Connect to FTDISerialManager if not already connected.
     */
    fun connectToSerialManager() {
        if (FTDISerialManager.isInitialized()) {
            FTDISerialManager.getInstance().addEventListener(this)
        }
    }

    /**
     * Add event listener for UI updates.
     */
    fun addEventListener(listener: FTDIEventListener) {
        if (!eventListeners.contains(listener)) {
            eventListeners.add(listener)
        }
    }

    /**
     * Remove event listener.
     */
    fun removeEventListener(listener: FTDIEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Release resources.
     */
    private fun release() {
        if (FTDISerialManager.isInitialized()) {
            FTDISerialManager.getInstance().removeEventListener(this)
        }
        addressToCounterMap.clear()
        counterToAddressMap.clear()
        keypadConnectionTimes.clear()
        eventListeners.clear()
        queueOperations = null
        Log.d(TAG, "FTDIBridge released")
    }

    /**
     * Map a keypad address to a counter ID.
     * Called when a keypad sends CONNECT command.
     * Both address and counterId are normalized to 4-digit format with leading zeros.
     */
    fun mapAddressToCounter(address: String, counterId: String) {
        // Normalize both to 4-digit format to ensure consistency
        val normalizedAddress = address.padStart(4, '0').take(4)
        val normalizedCounterId = counterId.padStart(4, '0').take(4)

        val isNew = !addressToCounterMap.containsKey(normalizedAddress)
        addressToCounterMap[normalizedAddress] = normalizedCounterId
        counterToAddressMap[normalizedCounterId] = normalizedAddress
        keypadConnectionTimes[normalizedAddress] = System.currentTimeMillis()

        Log.d(TAG, "Mapped address $normalizedAddress to counter $normalizedCounterId (${if (isNew) "new" else "updated"})")
        Log.d(TAG, "Total connected keypads: ${addressToCounterMap.size}")

        // Notify listeners about keypad count change
        notifyKeypadCountChanged()
    }

    /**
     * Remove a keypad mapping.
     * Input is normalized to 4-digit format.
     */
    fun removeKeypadMapping(address: String) {
        val normalizedAddress = address.padStart(4, '0').take(4)
        val counterId = addressToCounterMap.remove(normalizedAddress)
        if (counterId != null) {
            counterToAddressMap.remove(counterId)
            counterToServiceMap.remove(counterId)
            keypadConnectionTimes.remove(normalizedAddress)
            Log.d(TAG, "Removed keypad mapping: $normalizedAddress -> $counterId")
            Log.d(TAG, "Remaining connected keypads: ${addressToCounterMap.size}")
            notifyKeypadCountChanged()
        }
    }

    /**
     * Update the service ID for a counter.
     * This is used to track which service each keypad/counter belongs to.
     */
    fun updateCounterService(counterId: String, serviceId: String) {
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        counterToServiceMap[normalizedCounterId] = serviceId
        Log.d(TAG, "Updated counter $normalizedCounterId -> service $serviceId")
        Log.d(TAG, "Counter to service map: $counterToServiceMap")
    }

    /**
     * Get service ID for a counter.
     */
    fun getServiceForCounter(counterId: String): String? {
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        return counterToServiceMap[normalizedCounterId]
    }

    /**
     * Get all counter IDs for a given service.
     */
    fun getCountersForService(serviceId: String): List<String> {
        return counterToServiceMap.filter { it.value == serviceId }.keys.toList()
    }

    /**
     * Get counter ID for an address.
     * Input is normalized to 4-digit format.
     */
    fun getCounterForAddress(address: String): String? {
        val normalizedAddress = address.padStart(4, '0').take(4)
        return addressToCounterMap[normalizedAddress]
    }

    /**
     * Get address for a counter ID.
     * Input is normalized to 4-digit format.
     */
    fun getAddressForCounter(counterId: String): String? {
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        return counterToAddressMap[normalizedCounterId]
    }

    /**
     * Get all connected keypads (address -> counterId).
     */
    fun getConnectedKeypads(): Map<String, String> {
        return addressToCounterMap.toMap()
    }

    /**
     * Get detailed info about all connected keypads.
     */
    fun getConnectedKeypadsInfo(): List<ConnectedKeypad> {
        val now = System.currentTimeMillis()
        return addressToCounterMap.map { (address, counterId) ->
            ConnectedKeypad(
                address = address,
                counterId = counterId,
                connectionTime = keypadConnectionTimes[address] ?: now,
                lastActivityTime = keypadConnectionTimes[address] ?: now
            )
        }
    }

    /**
     * Get count of connected keypads.
     */
    fun getConnectedKeypadCount(): Int {
        return addressToCounterMap.size
    }

    /**
     * Check if a specific keypad is connected.
     */
    fun isKeypadConnected(address: String): Boolean {
        val normalizedAddress = address.padStart(4, '0').take(4)
        return addressToCounterMap.containsKey(normalizedAddress)
    }

    /**
     * Check if a counter has a keypad connected.
     * Input is normalized to 4-digit format.
     */
    fun isCounterHasKeypad(counterId: String): Boolean {
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        return counterToAddressMap.containsKey(normalizedCounterId)
    }

    /**
     * Send display update to a specific keypad by counter ID.
     * Format: NPW(3) + Counter(4) + Token(3)
     */
    fun sendDisplayToKeypad(counterId: String, npw: String, token: String) {
        // Normalize counterId to 4-digit format
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        val address = counterToAddressMap[normalizedCounterId] ?: FTDIProtocol.toFtdiAddress(normalizedCounterId)
        val counter = FTDIProtocol.toFtdiAddress(normalizedCounterId)

        val frame = FTDIProtocol.buildDisplayFrame(address, npw, counter, token)

        if (FTDISerialManager.isInitialized() && FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().write(frame)
            notifyResponseSent(address, "DISPLAY npw=$npw counter=$counter token=$token")
            notifyLog("TX: DISPLAY to $address (Counter $normalizedCounterId) -> npw=$npw token=$token")
            Log.d(TAG, "Sent display to counter $normalizedCounterId (addr=$address): npw=$npw, counter=$counter, token=$token")
        } else {
            Log.w(TAG, "Cannot send display: FTDISerialManager not connected")
            notifyLog("TX FAILED: Not connected - DISPLAY to $address")
        }
    }

    /**
     * Send display update to a keypad by address.
     */
    fun sendDisplayToAddress(address: String, npw: String, counter: String, token: String) {
        val frame = FTDIProtocol.buildDisplayFrame(address, npw, counter, token)

        if (FTDISerialManager.isInitialized() && FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().write(frame)
            notifyResponseSent(address, "DISPLAY npw=$npw counter=$counter token=$token")
            notifyLog("TX: DISPLAY to $address -> npw=$npw counter=$counter token=$token")
            Log.d(TAG, "Sent display to address $address: npw=$npw, counter=$counter, token=$token")
        } else {
            Log.w(TAG, "Cannot send display: FTDISerialManager not connected")
        }
    }

    /**
     * Send all services info to a keypad.
     */
    fun sendAllServices(services: Map<Int, String>) {
        val frame = FTDIProtocol.buildAllServicesResponse(services)

        if (FTDISerialManager.isInitialized() && FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().write(frame)
            notifyLog("TX: ALL_SERVICES (${services.size} services)")
            Log.d(TAG, "Sent all services: ${services.size} services")
        }
    }

    /**
     * Send my info response to a keypad.
     */
    fun sendMyInfo(address: String, counter: String, serviceNo: String) {
        val frame = FTDIProtocol.buildMyInfoResponse(address, counter, serviceNo)

        if (FTDISerialManager.isInitialized() && FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().write(frame)
            notifyResponseSent(address, "MY_INFO counter=$counter svc=$serviceNo")
            notifyLog("TX: MY_INFO to $address -> counter=$counter svc=$serviceNo")
            Log.d(TAG, "Sent my info to address $address")
        }
    }

    /**
     * Send MyNPW frame to a keypad.
     */
    fun sendMyNPW(address: String, npw: String, counter: String) {
        val frame = FTDIProtocol.buildMyNPWFrame(address, npw, counter)

        if (FTDISerialManager.isInitialized() && FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().write(frame)
            notifyResponseSent(address, "MY_NPW npw=$npw counter=$counter")
            notifyLog("TX: MY_NPW to $address -> npw=$npw counter=$counter")
        }
    }

    /**
     * Send MyNPW frame to a keypad by counter ID.
     */
    fun sendMyNPWToKeypad(counterId: String, npw: String) {
        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        val address = counterToAddressMap[normalizedCounterId] ?: FTDIProtocol.toFtdiAddress(normalizedCounterId)
        val counter = FTDIProtocol.toFtdiAddress(normalizedCounterId)

        sendMyNPW(address, npw, counter)
    }

    /**
     * Send both MY_NPW and DISPLAY commands to hard keypad IMMEDIATELY.
     * This is used for NEXT, CALL, and REPEAT operations.
     * Uses writeSync for immediate transmission without queue delay.
     *
     * @param counterId The counter ID
     * @param npw Number of people waiting (3 digits)
     * @param token Current token being called (3 digits)
     */
    fun sendResponseToHardKeypad(counterId: String, npw: String, token: String) {
        Log.d(TAG, "sendResponseToHardKeypad called: counterId=$counterId, npw=$npw, token=$token")

        val normalizedCounterId = counterId.padStart(4, '0').take(4)
        val address = counterToAddressMap[normalizedCounterId] ?: FTDIProtocol.toFtdiAddress(normalizedCounterId)
        val counter = FTDIProtocol.toFtdiAddress(normalizedCounterId)
        val npwPad = npw.padStart(3, '0').take(3)
        val tokenPad = token.padStart(3, '0').take(3)

        Log.d(TAG, "sendResponseToHardKeypad: normalizedCounterId=$normalizedCounterId, address=$address, counterToAddressMap=$counterToAddressMap")

        val isSerialInitialized = FTDISerialManager.isInitialized()
        val isSerialConnected = if (isSerialInitialized) FTDISerialManager.getInstance().isConnected() else false

        Log.d(TAG, "sendResponseToHardKeypad: isSerialInitialized=$isSerialInitialized, isSerialConnected=$isSerialConnected")
        notifyLog("sendResponseToHardKeypad: counterId=$normalizedCounterId, initialized=$isSerialInitialized, connected=$isSerialConnected")

        if (isSerialInitialized && isSerialConnected) {
            // Send MY_NPW command first - use writeSync for immediate transmission
            val myNpwFrame = FTDIProtocol.buildMyNPWFrame(address, npwPad, counter)
            val myNpwResult = FTDISerialManager.getInstance().writeSync(myNpwFrame)
            Log.d(TAG, "MY_NPW writeSync result: $myNpwResult")
            notifyLog("TX: MY_NPW to $address -> npw=$npwPad counter=$counter (success=$myNpwResult)")

            // Small delay between frames to ensure proper reception
            Thread.sleep(30)

            // Send DISPLAY command - use writeSync for immediate transmission
            val displayFrame = FTDIProtocol.buildDisplayFrame(address, npwPad, counter, tokenPad)
            val displayResult = FTDISerialManager.getInstance().writeSync(displayFrame)
            Log.d(TAG, "DISPLAY writeSync result: $displayResult")
            notifyLog("TX: DISPLAY to $address -> npw=$npwPad counter=$counter token=$tokenPad (success=$displayResult)")

            notifyResponseSent(address, "MY_NPW + DISPLAY npw=$npwPad counter=$counter token=$tokenPad")
            Log.d(TAG, "Sent MY_NPW + DISPLAY to $address: npw=$npwPad, counter=$counter, token=$tokenPad")
        } else {
            Log.w(TAG, "Cannot send response: FTDISerialManager not connected (initialized=$isSerialInitialized, connected=$isSerialConnected)")
            notifyLog("TX FAILED: Not connected (initialized=$isSerialInitialized, connected=$isSerialConnected)")
        }
    }

    /**
     * Broadcast display to ALL connected keypads.
     * Useful for announcements that should appear on all keypads.
     */
    fun broadcastDisplay(npw: String, token: String) {
        if (!FTDISerialManager.isInitialized() || !FTDISerialManager.getInstance().isConnected()) {
            Log.w(TAG, "Cannot broadcast: FTDISerialManager not connected")
            return
        }

        val keypads = addressToCounterMap.toMap()
        Log.d(TAG, "Broadcasting display to ${keypads.size} keypad(s): npw=$npw, token=$token")

        keypads.forEach { (address, counterId) ->
            val counter = FTDIProtocol.toFtdiAddress(counterId)
            val frame = FTDIProtocol.buildDisplayFrame(address, npw, counter, token)
            FTDISerialManager.getInstance().write(frame)
            notifyLog("TX: BROADCAST DISPLAY to $address")
        }
    }

    /**
     * Send display update to multiple specific keypads.
     */
    fun sendDisplayToMultipleKeypads(counterIds: List<String>, npw: String, token: String) {
        if (!FTDISerialManager.isInitialized() || !FTDISerialManager.getInstance().isConnected()) {
            Log.w(TAG, "Cannot send: FTDISerialManager not connected")
            return
        }

        counterIds.forEach { counterId ->
            // Normalize counterId to 4-digit format
            val normalizedCounterId = counterId.padStart(4, '0').take(4)
            val address = counterToAddressMap[normalizedCounterId]
            if (address != null) {
                sendDisplayToKeypad(normalizedCounterId, npw, token)
            } else {
                Log.w(TAG, "No keypad found for counter: $normalizedCounterId")
            }
        }
    }

    /**
     * Broadcast updated NPW count to all hard keypads of the same service.
     * This is called when any counter performs a Call or Next operation.
     *
     * @param serviceId The service ID to broadcast to
     * @param npw The updated NPW count (Number of People Waiting)
     * @param excludeCounterId Optional counter ID to exclude from broadcast (the one that triggered the action)
     */
    fun broadcastNpwToService(serviceId: String, npw: String, excludeCounterId: String? = null) {
        if (!FTDISerialManager.isInitialized() || !FTDISerialManager.getInstance().isConnected()) {
            Log.w(TAG, "Cannot broadcast NPW: FTDISerialManager not connected")
            return
        }

        val normalizedExcludeId = excludeCounterId?.padStart(4, '0')?.take(4)
        val countersForService = getCountersForService(serviceId)

        Log.d(TAG, "Broadcasting NPW=$npw to service $serviceId, counters: $countersForService, excluding: $normalizedExcludeId")
        notifyLog("Broadcasting NPW=$npw to service $serviceId (${countersForService.size} keypads)")

        var broadcastCount = 0
        countersForService.forEach { counterId ->
            // Skip the counter that triggered the action
            if (counterId == normalizedExcludeId) {
                Log.d(TAG, "Skipping counter $counterId (triggered the action)")
                return@forEach
            }

            val address = counterToAddressMap[counterId]
            if (address != null) {
                // Send MY_NPW frame to update the NPW display
                val frame = FTDIProtocol.buildMyNPWFrame(address, npw.padStart(3, '0').take(3), counterId)
                FTDISerialManager.getInstance().writeSync(frame)
                notifyLog("TX: NPW BROADCAST to $address (counter $counterId) -> npw=$npw")
                Log.d(TAG, "Sent NPW broadcast to counter $counterId (addr=$address): npw=$npw")
                broadcastCount++

                // Small delay between frames
                Thread.sleep(20)
            } else {
                Log.w(TAG, "No address found for counter $counterId")
            }
        }

        Log.d(TAG, "NPW broadcast complete: sent to $broadcastCount keypad(s)")
        notifyLog("NPW broadcast complete: sent to $broadcastCount keypad(s)")
    }

    // ==================== FTDISerialEventListener Implementation ====================

    override fun onDeviceConnected(deviceName: String) {
        Log.d(TAG, "USB device connected: $deviceName")
        notifyConnectionStatus(true)
        notifyLog("USB device connected: $deviceName")
        notifyLog("Waiting for keypad(s) to send CONNECT command...")
    }

    override fun onDeviceDisconnected() {
        Log.d(TAG, "USB device disconnected")

        // Notify about all keypads being disconnected
        val disconnectedKeypads = addressToCounterMap.keys.toList()
        disconnectedKeypads.forEach { address ->
            queueOperations?.onHardKeypadDisconnected(address)
            notifyKeypadDisconnected(address)
        }

        // Clear all mappings
        addressToCounterMap.clear()
        counterToAddressMap.clear()
        keypadConnectionTimes.clear()

        notifyConnectionStatus(false)
        notifyKeypadCountChanged()
        notifyLog("USB device disconnected - all ${disconnectedKeypads.size} keypad(s) disconnected")
    }

    override fun onDataReceived(data: ByteArray) {
        notifyLog("RX RAW: ${FTDIProtocol.run { data.toHexString() }}")
    }

    override fun onFrameReceived(frame: ByteArray) {
        Log.d(TAG, "Frame received: ${FTDIProtocol.run { frame.toHexString() }}")
        notifyLog("RX FRAME: ${FTDIProtocol.run { frame.toHexString() }}")

        val parsedCommand = FTDIProtocol.parseIncomingFrame(frame)
        Log.d(TAG, "Frame received: $parsedCommand")
        when (parsedCommand) {
            is FTDIProtocol.ParsedCommand.Connect -> handleConnect(parsedCommand)
            is FTDIProtocol.ParsedCommand.Next -> handleNext(parsedCommand)
            is FTDIProtocol.ParsedCommand.Repeat -> handleRepeat(parsedCommand)
            is FTDIProtocol.ParsedCommand.DirectCall -> handleDirectCall(parsedCommand)
            is FTDIProtocol.ParsedCommand.Display -> handleDisplayResponse(parsedCommand)
            is FTDIProtocol.ParsedCommand.Unknown -> handleUnknown(parsedCommand)
            is FTDIProtocol.ParsedCommand.RawData -> {
                notifyLog("RAW DATA: hex=${parsedCommand.hex}")
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Serial error: $error")
        notifyLog("Error: $error")
    }

    override fun onConnectionStateChanged(state: FTDISerialManager.ConnectionState) {
        notifyConnectionStatus(state.isConnected)
        if (!state.isConnected) {
            // Clear keypads when USB disconnected
            addressToCounterMap.clear()
            counterToAddressMap.clear()
            keypadConnectionTimes.clear()
            notifyKeypadCountChanged()
        }
    }

    // ==================== Command Handlers ====================

    private fun handleConnect(command: FTDIProtocol.ParsedCommand.Connect) {
        val address = command.address
        val counterId = FTDIProtocol.toCounterId(address)
        val isReconnect = addressToCounterMap.containsKey(address)

        Log.d(TAG, "Connect from address: $address, deviceType: ${command.deviceType}, counterId: $counterId, isReconnect: $isReconnect")
        notifyCommandReceived(address, "CONNECT", "deviceType=${command.deviceType}, counterId=$counterId")
        notifyLog("RX: CONNECT from $address (deviceType=${command.deviceType}) -> Counter $counterId ${if (isReconnect) "(reconnect)" else "(new)"}")

        // Map this address to the counter
        mapAddressToCounter(address, counterId)

        // Get and store the service ID for this counter (for NPW broadcasting)
        val serviceId = queueOperations?.getServiceIdForCounter(counterId)
        if (!serviceId.isNullOrEmpty()) {
            updateCounterService(counterId, serviceId)
            Log.d(TAG, "Counter $counterId mapped to service $serviceId")
            notifyLog("Counter $counterId -> Service $serviceId")
        } else {
            Log.w(TAG, "No service ID found for counter $counterId")
        }

        // Notify queue system
        queueOperations?.onHardKeypadConnected(address, counterId)
        notifyKeypadConnected(address, counterId)

        // Send initial display (000 tokens waiting, counter, 000 current token)
        sendDisplayToAddress(address, "000", FTDIProtocol.toFtdiAddress(counterId), "000")

        Log.d(TAG, "Keypad $address registered. Total keypads: ${addressToCounterMap.size}")
    }

    private fun handleNext(command: FTDIProtocol.ParsedCommand.Next) {
        val address = command.address
        val counterId = addressToCounterMap[address] ?: FTDIProtocol.toCounterId(address)

        Log.d(TAG, "Next from address: $address, counterId: $counterId, serviceNo: ${command.serviceNo}")
        notifyCommandReceived(address, "NEXT", "counterId=$counterId, svc=${command.serviceNo}")
        notifyLog("RX: NEXT from $address -> Counter $counterId (svc=${command.serviceNo})")

        // Ensure mapping exists (auto-register if keypad sends command without CONNECT)
        if (!addressToCounterMap.containsKey(address)) {
            Log.w(TAG, "Keypad $address not registered, auto-registering...")
            mapAddressToCounter(address, counterId)

            // Also get and store service ID
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }

            queueOperations?.onHardKeypadConnected(address, counterId)
            notifyKeypadConnected(address, counterId)
        }

        // Ensure service mapping exists even if address was already registered
        if (!counterToServiceMap.containsKey(counterId.padStart(4, '0').take(4))) {
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }
        }

        // Update last activity time
        keypadConnectionTimes[address] = System.currentTimeMillis()

        // Notify queue system to call next token
        Log.d(TAG, "handleNext: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        notifyLog("handleNext: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        if (queueOperations != null) {
            Log.d(TAG, "handleNext: Calling onHardKeypadNext($counterId)")
            notifyLog("handleNext: Calling onHardKeypadNext($counterId)")
            queueOperations?.onHardKeypadNext(counterId)
            Log.d(TAG, "handleNext: onHardKeypadNext completed")
        } else {
            Log.e(TAG, "handleNext: queueOperations is NULL! Cannot notify MainActivity")
            notifyLog("ERROR: queueOperations is NULL! Cannot notify MainActivity")
        }
    }

    private fun handleRepeat(command: FTDIProtocol.ParsedCommand.Repeat) {
        val address = command.address
        val counterId = addressToCounterMap[address] ?: FTDIProtocol.toCounterId(address)
        val tokenNo = command.tokenNo

        Log.d(TAG, "Repeat from address: $address, counterId: $counterId, token: $tokenNo")
        notifyCommandReceived(address, "REPEAT", "counterId=$counterId, token=$tokenNo")
        notifyLog("RX: REPEAT from $address -> Counter $counterId, Token $tokenNo")

        // Ensure mapping exists
        if (!addressToCounterMap.containsKey(address)) {
            Log.w(TAG, "Keypad $address not registered, auto-registering...")
            mapAddressToCounter(address, counterId)

            // Also get and store service ID
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }

            queueOperations?.onHardKeypadConnected(address, counterId)
            notifyKeypadConnected(address, counterId)
        }

        // Ensure service mapping exists even if address was already registered
        if (!counterToServiceMap.containsKey(counterId.padStart(4, '0').take(4))) {
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }
        }

        // Update last activity time
        keypadConnectionTimes[address] = System.currentTimeMillis()

        // Notify queue system to repeat token
        Log.d(TAG, "handleRepeat: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        notifyLog("handleRepeat: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        if (queueOperations != null) {
            Log.d(TAG, "handleRepeat: Calling onHardKeypadRepeat($counterId, $tokenNo)")
            notifyLog("handleRepeat: Calling onHardKeypadRepeat($counterId, $tokenNo)")
            queueOperations?.onHardKeypadRepeat(counterId, tokenNo)
            Log.d(TAG, "handleRepeat: onHardKeypadRepeat completed")
        } else {
            Log.e(TAG, "handleRepeat: queueOperations is NULL! Cannot notify MainActivity")
            notifyLog("ERROR: queueOperations is NULL! Cannot notify MainActivity")
        }
    }

    private fun handleDirectCall(command: FTDIProtocol.ParsedCommand.DirectCall) {
        val address = command.address
        val counterId = addressToCounterMap[address] ?: FTDIProtocol.toCounterId(address)
        val tokenNo = command.tokenNo
        Log.d(TAG, "Counter to service map0: $counterToServiceMap  $keypadConnectionTimes")
        Log.d(TAG, "DirectCall from address: $address, counterId: $counterId, token: $tokenNo  map: $addressToCounterMap")
        notifyCommandReceived(address, "DIRECT_CALL", "counterId=$counterId, token=$tokenNo")
        notifyLog("RX: DIRECT_CALL from $address -> Counter $counterId, Token $tokenNo")

        // Ensure mapping exists
        if (!addressToCounterMap.containsKey(address)) {
            Log.w(TAG, "Keypad $address not registered, auto-registering...")
            mapAddressToCounter(address, counterId)

            // Also get and store service ID
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }

            queueOperations?.onHardKeypadConnected(address, counterId)
            notifyKeypadConnected(address, counterId)
        }

        // Ensure service mapping exists even if address was already registered
        if (!counterToServiceMap.containsKey(counterId.padStart(4, '0').take(4))) {
            val serviceId = queueOperations?.getServiceIdForCounter(counterId)
            if (!serviceId.isNullOrEmpty()) {
                updateCounterService(counterId, serviceId)
            }
        }

        // Update last activity time
        keypadConnectionTimes[address] = System.currentTimeMillis()
        Log.d(TAG, "Counter to service map0: $counterToServiceMap  $keypadConnectionTimes")
        // Notify queue system to call specific token
        Log.d(TAG, "handleDirectCall: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        notifyLog("handleDirectCall: queueOperations is ${if (queueOperations != null) "SET" else "NULL"}")
        if (queueOperations != null) {
            Log.d(TAG, "handleDirectCall: Calling onHardKeypadDirectCall($counterId, $tokenNo)")
            notifyLog("handleDirectCall: Calling onHardKeypadDirectCall($counterId, $tokenNo)")
            queueOperations?.onHardKeypadDirectCall(counterId, tokenNo)
            Log.d(TAG, "handleDirectCall: onHardKeypadDirectCall completed")
        } else {
            Log.e(TAG, "handleDirectCall: queueOperations is NULL! Cannot notify MainActivity")
            notifyLog("ERROR: queueOperations is NULL! Cannot notify MainActivity")
        }
    }

    private fun handleDisplayResponse(command: FTDIProtocol.ParsedCommand.Display) {
        // This is a display response, typically from another device or echo
        Log.d(TAG, "Display response: addr=${command.address}, npw=${command.npw}, counter=${command.counter}, token=${command.token}")
        notifyLog("RX: DISPLAY addr=${command.address} npw=${command.npw} counter=${command.counter} token=${command.token}")
    }

    private fun handleUnknown(command: FTDIProtocol.ParsedCommand.Unknown) {
        Log.w(TAG, "Unknown command 0x${command.command.toInt().and(0xFF).toString(16)} from ${command.address}")
        notifyCommandReceived(command.address, "UNKNOWN", "cmd=0x${command.command.toInt().and(0xFF).toString(16)}")
        notifyLog("RX: UNKNOWN cmd=0x${command.command.toInt().and(0xFF).toString(16)} from ${command.address}")
        notifyLog("    RAW: ${command.rawHex}")
    }

    // ==================== Notification Helpers ====================

    private fun notifyKeypadConnected(address: String, counterId: String) {
        mainHandler.post {
            eventListeners.forEach { it.onKeypadConnected(address, counterId) }
        }
    }

    private fun notifyKeypadDisconnected(address: String) {
        mainHandler.post {
            eventListeners.forEach { it.onKeypadDisconnected(address) }
        }
    }

    private fun notifyCommandReceived(address: String, command: String, data: String) {
        mainHandler.post {
            eventListeners.forEach { it.onCommandReceived(address, command, data) }
        }
    }

    private fun notifyResponseSent(address: String, response: String) {
        mainHandler.post {
            eventListeners.forEach { it.onResponseSent(address, response) }
        }
    }

    private fun notifyConnectionStatus(connected: Boolean) {
        mainHandler.post {
            eventListeners.forEach { it.onConnectionStatusChanged(connected) }
        }
    }

    private fun notifyLog(message: String) {
        mainHandler.post {
            eventListeners.forEach { it.onLogMessage(message) }
        }
    }

    private fun notifyKeypadCountChanged() {
        val count = addressToCounterMap.size
        mainHandler.post {
            eventListeners.forEach { it.onKeypadCountChanged(count) }
        }
    }
}

/**
 * Interface for queue operations triggered by hard keypad commands.
 */
interface FTDIQueueOperations {
    fun onHardKeypadConnected(ftdiAddress: String, counterId: String)
    fun onHardKeypadNext(counterId: String)
    fun onHardKeypadRepeat(counterId: String, tokenNo: String)
    fun onHardKeypadDirectCall(counterId: String, tokenNo: String)
    fun onHardKeypadDisconnected(ftdiAddress: String)

    /**
     * Get the service ID for a given counter ID.
     * Used for NPW broadcasting to keypads of the same service.
     */
    fun getServiceIdForCounter(counterId: String): String?
}
