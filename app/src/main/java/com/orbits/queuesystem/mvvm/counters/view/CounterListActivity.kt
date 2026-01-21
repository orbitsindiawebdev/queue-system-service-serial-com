package com.orbits.queuesystem.mvvm.counters.view

import android.os.Bundle
import android.util.Log
import androidx.databinding.DataBindingUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.orbits.queuesystem.R
import com.orbits.queuesystem.databinding.ActivityCounterListBinding
import com.orbits.queuesystem.helper.BaseActivity
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.Dialogs
import com.orbits.queuesystem.helper.configs.CounterConfig.parseInCounterDbModel
import com.orbits.queuesystem.helper.configs.CounterConfig.parseInCounterModelArraylist
import com.orbits.queuesystem.helper.configs.JsonConfig.createJsonData
import com.orbits.queuesystem.helper.database.LocalDB.addCounterInDB
import com.orbits.queuesystem.helper.database.LocalDB.getAllCounterFromDB
import com.orbits.queuesystem.helper.interfaces.AlertDialogInterface
import com.orbits.queuesystem.helper.interfaces.CommonInterfaceClickEvent
import com.orbits.queuesystem.helper.server.TCPServer
import com.orbits.queuesystem.mvvm.counters.adapter.CounterListAdapter
import com.orbits.queuesystem.mvvm.counters.model.CounterListDataModel
import com.orbits.queuesystem.mvvm.main.view.MainActivity


class CounterListActivity : BaseActivity() {
    private lateinit var binding: ActivityCounterListBinding
    private var adapter = CounterListAdapter()
    private var arrListCounter = ArrayList<CounterListDataModel?>()
    val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_counter_list)
        initializeToolbar()
        initializeFields()
        onClickListeners()
    }

    private fun initializeToolbar() {
        setUpToolbar(
            binding.layoutToolbar,
            title = getString(R.string.counter_list),
            isBackArrow = true,
            toolbarClickListener = object : CommonInterfaceClickEvent {
                override fun onToolBarListener(type: String) {
                    if (type == Constants.TOOLBAR_ICON_ONE) {

                    }
                }
            }
        )
    }

    private fun initializeFields(){
        binding.rvCounterList.adapter = adapter
        setupAdapterClickListener()  // Set up click listener once
        setData(parseInCounterModelArraylist(getAllCounterFromDB()))
    }

    private fun setData(data: ArrayList<CounterListDataModel?>) {
        arrListCounter.clear()
        arrListCounter.addAll(data)
        Log.i("deepika", "setData: $data")
        adapter.setData(arrListCounter)
    }

    private fun setupAdapterClickListener() {
        adapter.onClickEvent = object : CommonInterfaceClickEvent {
            override fun onItemClick(type: String, position: Int) {
                Log.i("deepika", "setData0: $type $position")
                if (type == "editCounter" && position < arrListCounter.size) {
                    // Use arrListCounter instead of data parameter to avoid null issue
                    val counterToEdit = arrListCounter[position]
                    Log.i("deepika", "Editing counter: $counterToEdit")

                    Dialogs.showAddCounterDialog(
                        this@CounterListActivity,
                        editCounterModel = counterToEdit,
                        object : AlertDialogInterface {
                            override fun onUpdateCounter(model: CounterListDataModel) {
                                Log.i("deepika", "onUpdateCounter - serviceId: ${model.serviceId}")
                                val dbModel = parseInCounterDbModel(model, model.counterId ?: "")
                                addCounterInDB(dbModel)
                                setData(parseInCounterModelArraylist(getAllCounterFromDB()))

                                // Send updated data to all connected clients
                                sendToAllClients()

                                Log.i("deepika", "All counters after update: ${getAllCounterFromDB()}")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun sendToAllClients() {
        // Get all clients directly from WebSocketManager for accurate client list
        val allClientHandlers = TCPServer.WebSocketManager.getAllClients()

        Log.d("CounterListActivity", "Sending to all clients - total from WebSocketManager: ${allClientHandlers.size}")
        Log.d("CounterListActivity", "arrListClients: ${MainActivity.arrListClients}")

        if (allClientHandlers.isEmpty()) {
            Log.w("CounterListActivity", "No clients connected to broadcast!")
            return
        }

        val jsonData = createJsonData()
        allClientHandlers.forEach { clientHandler ->
            if (clientHandler.isWebSocket) {
                Log.d("CounterListActivity", "Sending to client: ${clientHandler.clientId}")
                sendMessageToClientHandler(clientHandler, jsonData)
            } else {
                Log.d("CounterListActivity", "Skipping non-WebSocket client: ${clientHandler.clientId}")
            }
        }
    }

    private fun sendMessageToClientHandler(clientHandler: TCPServer.ClientHandler, jsonObject: JsonObject) {
        try {
            Thread {
                val jsonMessage = gson.toJson(jsonObject)
                Log.d("CounterListActivity", "Broadcasting to client: ${clientHandler.clientId}")
                clientHandler.sendMessageToClient(clientHandler.clientId, jsonMessage)
            }.start()
        } catch (e: Exception) {
            Log.e("CounterListActivity", "Error sending to client ${clientHandler.clientId}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendMessageToWebSocketClient(clientId: String, jsonObject: JsonObject) {
        try {
            val clientHandler = TCPServer.WebSocketManager.getClientHandler(clientId)
            if (clientHandler != null && clientHandler.isWebSocket) {
                Thread {
                    val jsonMessage = gson.toJson(jsonObject)
                    Log.d("CounterListActivity", "Sending to specific client: $clientId")
                    clientHandler.sendMessageToClient(clientId, jsonMessage)
                }.start()
            } else {
                Log.w("CounterListActivity", "Client handler not found or not WebSocket for: $clientId")
            }
        } catch (e: Exception) {
            Log.e("CounterListActivity", "Error sending to client $clientId: ${e.message}")
            e.printStackTrace()
        }
    }
    private fun onClickListeners(){
        binding.btnAddCounter.setOnClickListener {
            Dialogs.showAddCounterDialog(
                this,
                editCounterModel = null,
                object : AlertDialogInterface {
                override fun onAddCounter(model: CounterListDataModel) {
                    val dbModel = parseInCounterDbModel(model, model.counterId ?: "")
                    addCounterInDB(dbModel)
                    setData(parseInCounterModelArraylist(getAllCounterFromDB()))

                    // Send updated data to all connected clients
                    sendToAllClients()
                }
            })
        }
    }
}