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
        Log.d("CounterListActivity", "Sending to all clients: ${MainActivity.arrListClients}")
        MainActivity.arrListClients.forEach { clientId ->
            Log.d("CounterListActivity", "Sending to client: $clientId")
            sendMessageToWebSocketClient(clientId, createJsonData())
        }
    }
    private fun sendMessageToWebSocketClient(clientId: String, jsonObject: JsonObject) {

        try {
            // this method to get client handler used in server
            val clientHandler = TCPServer.WebSocketManager.getClientHandler(clientId)
            if (clientHandler != null && clientHandler.isWebSocket) {
                Thread {
                    val jsonMessage = gson.toJson(jsonObject)
                    println("here is new 222 $clientId")
                    clientHandler.sendMessageToClient(clientId, jsonMessage)
                }.start()
                // Optionally handle success or error
            } else {
                // Handle case where clientHandler is not found or not a WebSocket client
            }
        } catch (e: Exception) {
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