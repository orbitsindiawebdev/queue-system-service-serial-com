package com.orbits.queuesystem.mvvm.ftdi.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.orbits.queuesystem.R
import com.orbits.queuesystem.databinding.ItemUsbDeviceBinding

/**
 * Adapter for displaying USB devices with their connection status.
 */
class UsbDeviceAdapter(
    private val onConnectClick: (UsbDeviceItem) -> Unit,
    private val onDisconnectClick: (UsbDeviceItem) -> Unit
) : ListAdapter<UsbDeviceItem, UsbDeviceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUsbDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemUsbDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UsbDeviceItem) {
            val context = binding.root.context

            // Device name
            binding.txtDeviceName.text = item.deviceName.ifEmpty { "USB Serial Device" }

            // Device info (VID/PID)
            binding.txtDeviceInfo.text = "VID: ${String.format("%04X", item.vendorId)} | PID: ${String.format("%04X", item.productId)} | ${item.devicePath}"

            // Connection status indicator and button
            when (item.connectionState) {
                ConnectionState.DISCONNECTED -> {
                    binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_red)
                    binding.btnDeviceAction.text = "Connect"
                    binding.btnDeviceAction.setBackgroundColor(context.getColor(R.color.app_color))
                    binding.btnDeviceAction.isEnabled = true
                    binding.layoutKeypadInfo.visibility = View.GONE
                    binding.txtErrorMessage.visibility = View.GONE
                }
                ConnectionState.CONNECTING -> {
                    binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_orange)
                    binding.btnDeviceAction.text = "Connecting..."
                    binding.btnDeviceAction.setBackgroundColor(context.getColor(R.color.orange_color))
                    binding.btnDeviceAction.isEnabled = false
                    binding.layoutKeypadInfo.visibility = View.GONE
                    binding.txtErrorMessage.visibility = View.GONE
                }
                ConnectionState.CONNECTED -> {
                    binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_green)
                    binding.btnDeviceAction.text = "Disconnect"
                    binding.btnDeviceAction.setBackgroundColor(context.getColor(R.color.red_color))
                    binding.btnDeviceAction.isEnabled = true
                    binding.layoutKeypadInfo.visibility = View.VISIBLE
                    binding.txtErrorMessage.visibility = View.GONE

                    // Keypad status
                    if (item.keypadAddress != null) {
                        binding.txtKeypadStatus.text = "Connected"
                        binding.txtKeypadStatus.setTextColor(context.getColor(R.color.green_color))
                        binding.layoutKeypadDetails.visibility = View.VISIBLE
                        binding.txtKeypadAddress.text = item.keypadAddress
                        binding.txtCounterId.text = item.counterId ?: item.keypadAddress
                    } else {
                        binding.txtKeypadStatus.text = "Waiting for keypad CONNECT command..."
                        binding.txtKeypadStatus.setTextColor(context.getColor(R.color.orange_color))
                        binding.layoutKeypadDetails.visibility = View.GONE
                    }
                }
                ConnectionState.ERROR -> {
                    binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_red)
                    binding.btnDeviceAction.text = "Retry"
                    binding.btnDeviceAction.setBackgroundColor(context.getColor(R.color.app_color))
                    binding.btnDeviceAction.isEnabled = true
                    binding.layoutKeypadInfo.visibility = View.GONE
                    binding.txtErrorMessage.visibility = View.VISIBLE
                    binding.txtErrorMessage.text = item.errorMessage ?: "Connection failed"
                }
            }

            // Button click
            binding.btnDeviceAction.setOnClickListener {
                when (item.connectionState) {
                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> onConnectClick(item)
                    ConnectionState.CONNECTED -> onDisconnectClick(item)
                    ConnectionState.CONNECTING -> { /* Do nothing */ }
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<UsbDeviceItem>() {
        override fun areItemsTheSame(oldItem: UsbDeviceItem, newItem: UsbDeviceItem): Boolean {
            return oldItem.deviceId == newItem.deviceId
        }

        override fun areContentsTheSame(oldItem: UsbDeviceItem, newItem: UsbDeviceItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Data class representing a USB device in the list.
 */
data class UsbDeviceItem(
    val deviceId: Int,
    val deviceName: String,
    val devicePath: String,
    val vendorId: Int,
    val productId: Int,
    val driver: UsbSerialDriver?,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val keypadAddress: String? = null,
    val counterId: String? = null,
    val errorMessage: String? = null
)

/**
 * Connection state for a USB device.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
