package com.orbits.queuesystem.mvvm.ftdi.view

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.orbits.queuesystem.R
import com.orbits.queuesystem.databinding.ActivityFtdiConsoleBinding
import com.orbits.queuesystem.helper.BaseActivity
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.interfaces.CommonInterfaceClickEvent
import com.orbits.queuesystem.helper.server.FTDIBridge
import com.orbits.queuesystem.helper.server.FTDISerialManager
import com.orbits.queuesystem.mvvm.ftdi.adapter.ConnectionState
import com.orbits.queuesystem.mvvm.ftdi.adapter.UsbDeviceAdapter
import com.orbits.queuesystem.mvvm.ftdi.adapter.UsbDeviceItem

/**
 * Activity for managing FTDI hard keypad connections.
 * Displays each USB device separately with individual connect/disconnect controls.
 *
 * NOTE: This activity only handles UI for connection management.
 * Queue operations (Next, Call, Repeat) are handled by MainActivity.
 */
class FTDIConsoleActivity : BaseActivity(), FTDIBridge.FTDIEventListener,
    FTDISerialManager.FTDISerialEventListener {

    companion object {
        private const val TAG = "FTDIConsoleActivity"
    }

    private lateinit var binding: ActivityFtdiConsoleBinding
    private lateinit var deviceAdapter: UsbDeviceAdapter

    private var availableDevices = listOf<UsbSerialDriver>()
    private var deviceItems = mutableListOf<UsbDeviceItem>()

    private val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    private var selectedBaudRate = 9600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_ftdi_console)

        initializeToolbar()
        setupRecyclerView()
        setupSpinners()
        setupClickListeners()
        connectToFTDI()
        refreshDevices()
    }

    private fun initializeToolbar() {
        setUpToolbar(
            binding.layoutToolbar,
            title = getString(R.string.ftdi_console),
            isBackArrow = true,
            toolbarClickListener = object : CommonInterfaceClickEvent {
                override fun onToolBarListener(type: String) {
                    if (type == Constants.TOOLBAR_ICON_ONE) {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun setupRecyclerView() {
        deviceAdapter = UsbDeviceAdapter(
            onConnectClick = { item -> connectDevice(item) },
            onDisconnectClick = { item -> disconnectDevice(item) }
        )

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@FTDIConsoleActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupSpinners() {
        // Baud rate spinner
        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudRates.map { "$it" })
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBaudRate.adapter = baudAdapter
        binding.spinnerBaudRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newBaudRate = baudRates[position]
                if (newBaudRate != selectedBaudRate) {
                    selectedBaudRate = newBaudRate
                    FTDISerialManager.getInstance().setBaudRate(selectedBaudRate)
                    Log.d(TAG, "Baud rate set to: $selectedBaudRate")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        binding.btnScanDevices.setOnClickListener {
            refreshDevices()
        }

        binding.btnScanEmpty.setOnClickListener {
            refreshDevices()
        }

        binding.switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            FTDISerialManager.getInstance().setAutoReconnect(isChecked)
            Log.d(TAG, "Auto-reconnect ${if (isChecked) "enabled" else "disabled"}")
        }

        binding.btnDismissWarning.setOnClickListener {
            binding.cardWarning.visibility = View.GONE
        }
    }

    /**
     * Show USB hub warning message.
     */
    private fun showUsbHubWarning(message: String) {
        runOnUiThread {
            binding.txtWarningMessage.text = message
            binding.cardWarning.visibility = View.VISIBLE
        }
    }

    /**
     * Hide USB hub warning message.
     */
    private fun hideWarning() {
        runOnUiThread {
            binding.cardWarning.visibility = View.GONE
        }
    }

    /**
     * Connect to the singleton FTDI managers.
     * NOTE: We do NOT set queueOperations here - MainActivity handles all queue operations.
     * This activity only listens for UI events (connection status, keypad info).
     */
    private fun connectToFTDI() {
        // Ensure FTDISerialManager is initialized
        if (!FTDISerialManager.isInitialized()) {
            FTDISerialManager.init(this)
        }

        // Add ourselves as listener to get events
        FTDISerialManager.getInstance().addEventListener(this)

        // Ensure FTDIBridge is initialized
        if (!FTDIBridge.isInitialized()) {
            FTDIBridge.init(this)
        }

        // Add ourselves as listener to FTDIBridge for UI updates only
        // DO NOT set queueOperations - MainActivity handles queue operations
        FTDIBridge.getInstance().addEventListener(this)

        // Get current baud rate from manager
        selectedBaudRate = FTDISerialManager.getInstance().getBaudRate()
        val baudIndex = baudRates.indexOf(selectedBaudRate)
        if (baudIndex >= 0) {
            binding.spinnerBaudRate.setSelection(baudIndex)
        }

        // Get auto-reconnect state
        binding.switchAutoReconnect.isChecked = FTDISerialManager.getInstance().isAutoReconnectEnabled()
    }

    /**
     * Refresh the list of available USB devices.
     */
    private fun refreshDevices() {
        availableDevices = FTDISerialManager.getInstance().refreshDeviceList()
        Log.d(TAG, "Scan complete: Found ${availableDevices.size} USB serial device(s)")

        updateDeviceList()
    }

    /**
     * Update the device list with current connection states.
     */
    private fun updateDeviceList() {
        val connectedDeviceIds = FTDISerialManager.getInstance().getConnectedDevicesInfo().map { it.first }.toSet()
        val keypads = if (FTDIBridge.isInitialized()) FTDIBridge.getInstance().getConnectedKeypads() else emptyMap()

        // Create items for all available devices
        deviceItems.clear()
        for (driver in availableDevices) {
            val device = driver.device
            val deviceId = device.deviceId
            val isConnected = connectedDeviceIds.contains(deviceId)

            // Find keypad info for this device
            val keypadAddress = FTDISerialManager.getInstance().getAddressForDeviceId(deviceId)
            val counterId = keypadAddress?.let { keypads[it] }

            // Check if there's an existing item with error state
            val existingItem = deviceItems.find { it.deviceId == deviceId }

            deviceItems.add(UsbDeviceItem(
                deviceId = deviceId,
                deviceName = device.productName ?: "USB Serial Device",
                devicePath = device.deviceName ?: "",
                vendorId = device.vendorId,
                productId = device.productId,
                driver = driver,
                connectionState = if (isConnected) ConnectionState.CONNECTED else (existingItem?.connectionState ?: ConnectionState.DISCONNECTED),
                keypadAddress = keypadAddress,
                counterId = counterId,
                errorMessage = existingItem?.errorMessage
            ))
        }

        // Update UI
        runOnUiThread {
            deviceAdapter.submitList(deviceItems.toList())
            updateEmptyState()
            updateSummary()
        }
    }

    /**
     * Update the empty state visibility.
     */
    private fun updateEmptyState() {
        if (deviceItems.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.recyclerDevices.visibility = View.GONE
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.recyclerDevices.visibility = View.VISIBLE
        }
    }

    /**
     * Update the summary text.
     */
    private fun updateSummary() {
        val totalDevices = deviceItems.size
        val connectedDevices = deviceItems.count { it.connectionState == ConnectionState.CONNECTED }
        val keypadsCount = if (FTDIBridge.isInitialized()) FTDIBridge.getInstance().getConnectedKeypadCount() else 0

        binding.txtSummaryStatus.text = "$totalDevices USB device(s) found • $connectedDevices connected • $keypadsCount keypad(s)"
    }

    /**
     * Connect to a specific device.
     */
    private fun connectDevice(item: UsbDeviceItem) {
        Log.d(TAG, "Connecting to device: ${item.deviceName} (ID: ${item.deviceId})")

        // Update item state to connecting
        updateDeviceState(item.deviceId, ConnectionState.CONNECTING)

        // Set baud rate and connect
        FTDISerialManager.getInstance().setBaudRate(selectedBaudRate)

        item.driver?.let { driver ->
            val success = FTDISerialManager.getInstance().connectToDriver(driver)
            if (!success) {
                // Permission request sent, state will be updated via callback
                Log.d(TAG, "Connection pending - waiting for permission")
            }
        }
    }

    /**
     * Disconnect from a specific device.
     */
    private fun disconnectDevice(item: UsbDeviceItem) {
        Log.d(TAG, "Disconnecting from device: ${item.deviceName} (ID: ${item.deviceId})")
        FTDISerialManager.getInstance().disconnectDevice(item.deviceId)
    }

    /**
     * Update the state of a specific device in the list.
     */
    private fun updateDeviceState(deviceId: Int, state: ConnectionState, errorMessage: String? = null) {
        val index = deviceItems.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            deviceItems[index] = deviceItems[index].copy(
                connectionState = state,
                errorMessage = errorMessage
            )
            runOnUiThread {
                deviceAdapter.submitList(deviceItems.toList())
                updateSummary()
            }
        }
    }

    /**
     * Update keypad info for a device.
     */
    private fun updateKeypadInfo(deviceId: Int, keypadAddress: String?, counterId: String?) {
        val index = deviceItems.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            deviceItems[index] = deviceItems[index].copy(
                keypadAddress = keypadAddress,
                counterId = counterId
            )
            runOnUiThread {
                deviceAdapter.submitList(deviceItems.toList())
                updateSummary()
            }
        }
    }

    // ==================== FTDISerialEventListener Implementation ====================

    override fun onDeviceConnected(deviceName: String) {
        Log.d(TAG, "USB device connected: $deviceName")
        updateDeviceList()
    }

    override fun onDeviceDisconnected() {
        Log.d(TAG, "All USB devices disconnected")
        updateDeviceList()
    }

    override fun onDataReceived(data: ByteArray) {
        // Not needed for UI
    }

    override fun onFrameReceived(frame: ByteArray) {
        // Not needed for UI
    }

    override fun onError(error: String) {
        Log.e(TAG, "Error: $error")

        // Check if this is a USB hub power issue
        val isUsbHubError = error.contains("get_status") || error.contains("USB hub")

        if (isUsbHubError) {
            showUsbHubWarning("USB hub error. Use a POWERED USB hub for multiple keypads, or connect one device at a time.")
        }

        // Find device with connecting state and mark as error
        deviceItems.forEachIndexed { index, item ->
            if (item.connectionState == ConnectionState.CONNECTING) {
                deviceItems[index] = item.copy(
                    connectionState = ConnectionState.ERROR,
                    errorMessage = if (isUsbHubError) "USB hub power issue" else error
                )
            }
        }
        runOnUiThread {
            deviceAdapter.submitList(deviceItems.toList())
            updateSummary()
        }
    }

    override fun onConnectionStateChanged(state: FTDISerialManager.ConnectionState) {
        binding.switchAutoReconnect.isChecked = state.autoReconnectEnabled
        updateDeviceList()
    }

    override fun onMultiDeviceConnected(deviceId: Int, deviceName: String) {
        Log.d(TAG, "USB device connected: $deviceName (ID: $deviceId)")
        updateDeviceState(deviceId, ConnectionState.CONNECTED)
        updateDeviceList()
    }

    override fun onMultiDeviceDisconnected(deviceId: Int, deviceName: String) {
        Log.d(TAG, "USB device disconnected: $deviceName (ID: $deviceId)")
        updateDeviceState(deviceId, ConnectionState.DISCONNECTED)
        updateDeviceList()
    }

    // ==================== FTDIEventListener Implementation ====================

    override fun onKeypadConnected(address: String, counterId: String) {
        Log.d(TAG, "Keypad connected: addr=$address -> counter=$counterId")
        // Find the device for this keypad and update its info
        val deviceId = FTDISerialManager.getInstance().getDeviceIdForAddress(address)
        if (deviceId != null) {
            updateKeypadInfo(deviceId, address, counterId)
        }
        updateDeviceList()
    }

    override fun onKeypadDisconnected(address: String) {
        Log.d(TAG, "Keypad disconnected: addr=$address")
        val deviceId = FTDISerialManager.getInstance().getDeviceIdForAddress(address)
        if (deviceId != null) {
            updateKeypadInfo(deviceId, null, null)
        }
        updateDeviceList()
    }

    override fun onKeypadCountChanged(count: Int) {
        updateSummary()
    }

    override fun onUsbDeviceCountChanged(count: Int) {
        updateDeviceList()
    }

    override fun onCommandReceived(address: String, command: String, data: String) {
        // Not needed for UI
    }

    override fun onResponseSent(address: String, response: String) {
        // Not needed for UI
    }

    override fun onConnectionStatusChanged(connected: Boolean) {
        updateDeviceList()
    }

    override fun onLogMessage(message: String) {
        // Not needed for UI - can add toast for important messages if needed
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        super.onDestroy()
        // Remove listeners but don't release the singleton managers
        if (FTDIBridge.isInitialized()) {
            FTDIBridge.getInstance().removeEventListener(this)
        }
        if (FTDISerialManager.isInitialized()) {
            FTDISerialManager.getInstance().removeEventListener(this)
        }
    }
}
