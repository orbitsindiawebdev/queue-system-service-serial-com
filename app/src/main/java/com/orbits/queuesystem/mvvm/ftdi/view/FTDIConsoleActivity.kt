package com.orbits.queuesystem.mvvm.ftdi.view

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.databinding.DataBindingUtil
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.orbits.queuesystem.R
import com.orbits.queuesystem.databinding.ActivityFtdiConsoleBinding
import com.orbits.queuesystem.helper.BaseActivity
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.interfaces.CommonInterfaceClickEvent
import com.orbits.queuesystem.helper.server.FTDIBridge
import com.orbits.queuesystem.helper.server.FTDIProtocol
import com.orbits.queuesystem.helper.server.FTDIQueueOperations
import com.orbits.queuesystem.helper.server.FTDISerialManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug console activity for testing FTDI hard keypad communication.
 * Uses singleton FTDISerialManager for persistent connection.
 */
class FTDIConsoleActivity : BaseActivity(), FTDIBridge.FTDIEventListener, FTDIQueueOperations,
    FTDISerialManager.FTDISerialEventListener {

    private lateinit var binding: ActivityFtdiConsoleBinding

    private var availableDevices = listOf<UsbSerialDriver>()
    private var selectedDeviceIndex = 0

    private val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    private var selectedBaudRate = 9600

    private val logBuilder = StringBuilder()
    private val maxLogLines = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_ftdi_console)

        initializeToolbar()
        setupSpinners()
        setupClickListeners()
        connectToFTDI()
        refreshDevices()
        updateUIFromConnectionState()
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

    /**
     * Connect to the singleton FTDI managers.
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

        // Add ourselves as listener to FTDIBridge
        FTDIBridge.getInstance().addEventListener(this)
        FTDIBridge.getInstance().setQueueOperations(this)

        // Get current baud rate from manager
        selectedBaudRate = FTDISerialManager.getInstance().getBaudRate()
        val baudIndex = baudRates.indexOf(selectedBaudRate)
        if (baudIndex >= 0) {
            binding.spinnerBaudRate.setSelection(baudIndex)
        }

        appendLog("FTDI Console connected to persistent manager")
    }

    /**
     * Update UI based on current connection state.
     */
    private fun updateUIFromConnectionState() {
        val state = FTDISerialManager.getInstance().getConnectionState()
        updateConnectionStatus(state.isConnected, state.deviceName)
        binding.switchAutoReconnect.isChecked = state.autoReconnectEnabled
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
                    appendLog("Baud rate set to: $selectedBaudRate")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Device spinner - will be populated when devices are refreshed
        updateDeviceSpinner()
    }

    private fun updateDeviceSpinner() {
        val deviceNames = if (availableDevices.isEmpty()) {
            listOf("No devices found")
        } else {
            availableDevices.mapIndexed { index, driver ->
                "${driver.device.productName ?: "USB Device"} (${driver.device.deviceName})"
            }
        }

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevices.adapter = deviceAdapter
        binding.spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeviceIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        binding.btnRefreshDevices.setOnClickListener {
            refreshDevices()
        }

        binding.btnConnect.setOnClickListener {
            if (FTDISerialManager.getInstance().isConnected()) {
                disconnect()
            } else {
                connect()
            }
        }

        binding.btnClearLog.setOnClickListener {
            clearLog()
        }

        binding.btnSendDisplay.setOnClickListener {
            sendTestDisplayFrame()
        }

        binding.switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            FTDISerialManager.getInstance().setAutoReconnect(isChecked)
            appendLog("Auto-reconnect ${if (isChecked) "enabled" else "disabled"}")
        }
    }

    private fun refreshDevices() {
        availableDevices = FTDISerialManager.getInstance().refreshDeviceList()
        updateDeviceSpinner()
        appendLog("Found ${availableDevices.size} device(s)")
    }

    private fun connect() {
        if (availableDevices.isEmpty()) {
            appendLog("No devices available to connect")
            return
        }

        if (selectedDeviceIndex >= availableDevices.size) {
            appendLog("Invalid device selection")
            return
        }

        FTDISerialManager.getInstance().setBaudRate(selectedBaudRate)
        val success = FTDISerialManager.getInstance().connectToDriver(availableDevices[selectedDeviceIndex])
        if (!success) {
            appendLog("Connection request sent (waiting for permission)")
        }
    }

    private fun disconnect() {
        FTDISerialManager.getInstance().disconnect()
        updateConnectionStatus(false, null)
        appendLog("Disconnected")
    }

    private fun sendTestDisplayFrame() {
        val address = binding.edtAddress.text.toString().padStart(4, '0').take(4)
        val npw = binding.edtNpw.text.toString().padStart(3, '0').take(3)
        val token = binding.edtToken.text.toString().padStart(3, '0').take(3)
        // Counter is same as address for display purposes
        val counter = address

        if (!FTDISerialManager.getInstance().isConnected()) {
            appendLog("Cannot send: not connected")
            return
        }

        // Build display frame: @[addr][0x00][*][status][len][NPW(3)+Counter(4)+Token(3)][CR]
        val frame = FTDIProtocol.buildDisplayFrame(address, npw, counter, token)
        FTDISerialManager.getInstance().write(frame)
        appendLog("TX: DISPLAY addr=$address npw=$npw counter=$counter token=$token")
        appendLog("TX HEX: ${FTDIProtocol.run { frame.toHexString() }}")
    }

    private fun updateConnectionStatus(connected: Boolean, deviceName: String?) {
        runOnUiThread {
            if (connected) {
                binding.viewConnectionIndicator.setBackgroundResource(R.drawable.circle_green)
                binding.txtConnectionStatus.text = if (deviceName != null) {
                    "Connected: $deviceName"
                } else {
                    "Connected"
                }
                binding.btnConnect.text = "Disconnect"
                binding.btnConnect.setBackgroundColor(getColor(R.color.red_color))
            } else {
                binding.viewConnectionIndicator.setBackgroundResource(R.drawable.circle_red)
                binding.txtConnectionStatus.text = "Disconnected"
                binding.btnConnect.text = "Connect"
                binding.btnConnect.setBackgroundColor(getColor(R.color.app_color))
            }

            // Update connected keypads info
            updateConnectedKeypadsInfo()
        }
    }

    private fun updateConnectedKeypadsInfo() {
        runOnUiThread {
            if (FTDIBridge.isInitialized()) {
                val keypads = FTDIBridge.getInstance().getConnectedKeypads()
                if (keypads.isNotEmpty()) {
                    val info = keypads.entries.joinToString(", ") { "${it.key}->C${it.value}" }
                    binding.txtConnectedKeypads.text = "Keypads (${keypads.size}): $info"
                    binding.txtConnectedKeypads.visibility = View.VISIBLE
                } else {
                    binding.txtConnectedKeypads.text = "Keypads: None connected"
                    binding.txtConnectedKeypads.visibility = View.VISIBLE
                }
            } else {
                binding.txtConnectedKeypads.visibility = View.GONE
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message\n"

        runOnUiThread {
            logBuilder.append(logLine)

            // Limit log size
            val lines = logBuilder.toString().split("\n")
            if (lines.size > maxLogLines) {
                logBuilder.clear()
                logBuilder.append(lines.takeLast(maxLogLines).joinToString("\n"))
            }

            binding.txtConsoleLog.text = logBuilder.toString()

            // Auto-scroll to bottom
            binding.scrollConsole.post {
                binding.scrollConsole.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun clearLog() {
        logBuilder.clear()
        binding.txtConsoleLog.text = ""
        appendLog("Log cleared")
    }

    // ==================== FTDISerialEventListener Implementation ====================

    override fun onDeviceConnected(deviceName: String) {
        updateConnectionStatus(true, deviceName)
        appendLog("USB device connected: $deviceName")
    }

    override fun onDeviceDisconnected() {
        updateConnectionStatus(false, null)
        appendLog("USB device disconnected")
    }

    override fun onDataReceived(data: ByteArray) {
        // Raw data - already logged by FTDIBridge
    }

    override fun onFrameReceived(frame: ByteArray) {
        // Frame received - already logged by FTDIBridge
    }

    override fun onError(error: String) {
        appendLog("ERROR: $error")
    }

    override fun onConnectionStateChanged(state: FTDISerialManager.ConnectionState) {
        updateConnectionStatus(state.isConnected, state.deviceName)
        binding.switchAutoReconnect.isChecked = state.autoReconnectEnabled
    }

    // ==================== FTDIEventListener Implementation ====================

    override fun onKeypadConnected(address: String, counterId: String) {
        val totalKeypads = if (FTDIBridge.isInitialized()) FTDIBridge.getInstance().getConnectedKeypadCount() else 0
        appendLog("Keypad connected: addr=$address -> counter=$counterId (Total: $totalKeypads)")
        updateConnectedKeypadsInfo()
    }

    override fun onKeypadDisconnected(address: String) {
        val totalKeypads = if (FTDIBridge.isInitialized()) FTDIBridge.getInstance().getConnectedKeypadCount() else 0
        appendLog("Keypad disconnected: addr=$address (Remaining: $totalKeypads)")
        updateConnectedKeypadsInfo()
    }

    override fun onKeypadCountChanged(count: Int) {
        updateConnectedKeypadsInfo()
    }

    override fun onCommandReceived(address: String, command: String, data: String) {
        appendLog("RX CMD: $command from $address ($data)")
    }

    override fun onResponseSent(address: String, response: String) {
        appendLog("TX RSP: $response to $address")
    }

    override fun onConnectionStatusChanged(connected: Boolean) {
        updateConnectionStatus(connected, null)
        appendLog("Connection status: ${if (connected) "CONNECTED" else "DISCONNECTED"}")
    }

    override fun onLogMessage(message: String) {
        appendLog(message)
    }

    // ==================== FTDIQueueOperations Implementation ====================

    override fun onHardKeypadConnected(ftdiAddress: String, counterId: String) {
        appendLog("Queue: Keypad $ftdiAddress connected as Counter $counterId")
    }

    override fun onHardKeypadNext(counterId: String) {
        appendLog("Queue: NEXT request from Counter $counterId")
        // In console mode, just send a test display response
        if (FTDIBridge.isInitialized()) {
            FTDIBridge.getInstance().sendDisplayToKeypad(counterId, "000", "000")
        }
    }

    override fun onHardKeypadRepeat(counterId: String, tokenNo: String) {
        appendLog("Queue: REPEAT request from Counter $counterId, token=$tokenNo")
    }

    override fun onHardKeypadDirectCall(counterId: String, tokenNo: String) {
        appendLog("Queue: DIRECT_CALL request from Counter $counterId, token=$tokenNo")
    }

    override fun onHardKeypadDisconnected(ftdiAddress: String) {
        appendLog("Queue: Keypad $ftdiAddress disconnected")
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
        // Connection persists because we're using singletons
    }
}
