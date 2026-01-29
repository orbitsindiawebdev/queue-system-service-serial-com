package com.orbits.queuesystem.mvvm.main.view

import NetworkMonitor
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import androidx.drawerlayout.widget.DrawerLayout
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.orbits.queuesystem.R
import com.orbits.queuesystem.databinding.ActivityMainBinding
import com.orbits.queuesystem.databinding.NavHeaderLayoutBinding
import com.orbits.queuesystem.helper.interfaces.AlertDialogInterface
import com.orbits.queuesystem.helper.BaseActivity
import com.orbits.queuesystem.helper.interfaces.CommonInterfaceClickEvent
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.Dialogs
import com.orbits.queuesystem.helper.Extensions.asInt
import com.orbits.queuesystem.helper.Extensions.asString
import com.orbits.queuesystem.helper.Extensions.getCurrentDateTime
import com.orbits.queuesystem.helper.Extensions.getNextDate
import com.orbits.queuesystem.helper.Extensions.handler
import com.orbits.queuesystem.helper.Extensions.hideKeyboard
import com.orbits.queuesystem.helper.PrefUtils.getUserDataResponse
import com.orbits.queuesystem.helper.configs.JsonConfig.createDisplayJsonData
import com.orbits.queuesystem.helper.configs.JsonConfig.createJsonData
import com.orbits.queuesystem.helper.configs.JsonConfig.createMasterDisplayJsonData
import com.orbits.queuesystem.helper.configs.JsonConfig.createMasterDisplayJsonDataWithMsg
import com.orbits.queuesystem.helper.configs.JsonConfig.createNoTokensData
import com.orbits.queuesystem.helper.configs.JsonConfig.createReconnectionJsonDataWithTransaction
import com.orbits.queuesystem.helper.configs.JsonConfig.createServiceJsonDataWithModel
import com.orbits.queuesystem.helper.configs.JsonConfig.createServiceJsonDataWithTransaction
import com.orbits.queuesystem.helper.configs.JsonConfig.createServiceJsonDataWithTransactionForRepeat
import com.orbits.queuesystem.helper.configs.JsonConfig.createTransactionsJsonData
import com.orbits.queuesystem.helper.configs.JsonConfig.createUserJsonData
import com.orbits.queuesystem.helper.interfaces.MessageListener
import com.orbits.queuesystem.helper.server.ServerService
import com.orbits.queuesystem.helper.configs.ServiceConfig.parseInServiceDbModel
import com.orbits.queuesystem.helper.configs.ServiceConfig.parseInServiceModelArraylist
import com.orbits.queuesystem.helper.server.TCPServer
import com.orbits.queuesystem.helper.configs.TransactionConfig.parseInTransactionDbModel
import com.orbits.queuesystem.helper.database.CounterDataDbModel
import com.orbits.queuesystem.helper.database.LocalDB.addServiceInDB
import com.orbits.queuesystem.helper.database.LocalDB.addServiceTokenToDB
import com.orbits.queuesystem.helper.database.LocalDB.addTransactionInDB
import com.orbits.queuesystem.helper.database.LocalDB.addTransactionInDBKeypad
import com.orbits.queuesystem.helper.database.LocalDB.getAllResetData
import com.orbits.queuesystem.helper.database.LocalDB.getAllServiceFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getAllTransactionCount
import com.orbits.queuesystem.helper.database.LocalDB.getAllTransactionFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getCounterFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getTransactionFromDbWithIssuedStatus
import com.orbits.queuesystem.helper.database.LocalDB.getCounterIdForService
import com.orbits.queuesystem.helper.database.LocalDB.getCurrentServiceToken
import com.orbits.queuesystem.helper.database.LocalDB.getLastTransactionFromDbWithStatusOne
import com.orbits.queuesystem.helper.database.LocalDB.getRequiredTransactionFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getRequiredTransactionWithServiceFromDB
import com.orbits.queuesystem.helper.database.LocalDB.getResetData
import com.orbits.queuesystem.helper.database.LocalDB.getServiceById
import com.orbits.queuesystem.helper.database.LocalDB.getTransactionByToken
import com.orbits.queuesystem.helper.database.LocalDB.getTransactionFromDbCounterWise
import com.orbits.queuesystem.helper.database.LocalDB.getTransactionFromDbWithCalledStatus
import com.orbits.queuesystem.helper.database.LocalDB.isCounterAssigned
import com.orbits.queuesystem.helper.database.LocalDB.isResetDoneInDb
import com.orbits.queuesystem.helper.database.LocalDB.resetAllTransactionInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateCurrentDateTimeInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateLastDateTimeInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateResetDateTime
import com.orbits.queuesystem.helper.database.TransactionDataDbModel
import com.orbits.queuesystem.helper.server.FTDIBridge
import com.orbits.queuesystem.helper.server.FTDIQueueOperations
import com.orbits.queuesystem.helper.server.FTDISerialManager
import com.orbits.queuesystem.mvvm.counters.view.CounterListActivity
import com.orbits.queuesystem.mvvm.ftdi.view.FTDIConsoleActivity
import com.orbits.queuesystem.mvvm.main.adapter.ServiceListAdapter
import com.orbits.queuesystem.mvvm.main.model.DisplayListDataModel
import com.orbits.queuesystem.mvvm.main.model.ServiceListDataModel
import com.orbits.queuesystem.mvvm.main.model.TransactionListDataModel
import com.orbits.queuesystem.mvvm.reset.view.ResetActivity
import com.orbits.queuesystem.mvvm.users.view.UserListActivity
import com.orbits.queuesystem.mvvm.voice.view.VoiceConfigurationActivity
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : BaseActivity(), MessageListener, TextToSpeech.OnInitListener, FTDIQueueOperations {

    companion object {
        // Shared client list accessible from other activities
        val arrListClients = CopyOnWriteArrayList<String>()
    }

    private lateinit var binding: ActivityMainBinding
    private var adapter = ServiceListAdapter()
    private var arrListService = ArrayList<ServiceListDataModel?>()
    private lateinit var headerLayout: NavHeaderLayoutBinding

    /*----------------------------------------- TCP server variables -----------------------------------------*/
    private var tcpServer: TCPServer? = null
    private lateinit var socket: Socket
    private var outStream: OutputStream? = null
    // Use CopyOnWriteArrayList for thread-safe display list operations
    private var arrListDisplays = CopyOnWriteArrayList<DisplayListDataModel?>()


    /*----------------------------------------- TCP server variables -----------------------------------------*/


    private lateinit var networkMonitor: NetworkMonitor // this is used to check and monitor network conditions
    val gson = Gson()
    var serviceId = ""
    var serviceType = ""
    var counter = 1
    private lateinit var textToSpeech: TextToSpeech // for voice configuration in app to call token
    private var maleVoice: Voice? = null

    private val tokenQueue: Queue<Pair<String, CounterDataDbModel?>> = LinkedList()
    private var isSpeaking = false

    /*----------------------------------------- FTDI Hard Keypad variables -----------------------------------------*/
    // FTDI uses singleton pattern - connection persists across activities
    /*----------------------------------------- FTDI Hard Keypad variables -----------------------------------------*/


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        textToSpeech = TextToSpeech(this, this) // for voice configuration in app to call token

        // this is used to check and monitor network conditions
        networkMonitor = NetworkMonitor(this) {
            startServerService()
            runOnUiThread {
                initializeSocket()
            }
        }
        networkMonitor.registerNetworkCallback()

        updateCurrentDateTimeInDb(getCurrentDateTime())
        println("here is reset data in main ${getAllResetData()}")


        initResetData()
        initLeftNavMenuDrawer()
        initializeToolbar()
        initializeFields()
        onClickListeners()
        initializeFTDI()
        handleUsbIntent(intent)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /**
     * Handle USB device attachment intent.
     */
    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d("MainActivity", "USB device attached via intent")
            if (FTDISerialManager.isInitialized()) {
                FTDISerialManager.getInstance().refreshDeviceList()
                FTDISerialManager.getInstance().connectToFirstAvailable()
            }
        }
    }

    /**
     * Initialize FTDI serial manager and bridge for hard keypad support.
     * Uses singleton pattern for persistent connection across activities.
     */
    private fun initializeFTDI() {
        // Initialize singletons if not already done
        FTDISerialManager.init(this)
        FTDIBridge.init(this)

        // Set this activity as the queue operations handler
        FTDIBridge.getInstance().setQueueOperations(this)

        // Try to auto-connect if devices are available
        val devices = FTDISerialManager.getInstance().refreshDeviceList()
        if (devices.isNotEmpty() && !FTDISerialManager.getInstance().isConnected()) {
            FTDISerialManager.getInstance().connectToFirstAvailable()
        }

        Log.d("MainActivity", "FTDI hard keypad support initialized (singleton)")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var langResult = textToSpeech.setLanguage(Locale.US)


            when (getUserDataResponse()?.voice_selected) {
                Constants.ENGLISH -> {
                    langResult = textToSpeech.setLanguage(Locale.US)
                }
                Constants.ARABIC -> {
                    langResult = textToSpeech.setLanguage(Locale("ar"))
                }
                Constants.ENGLISH_ARABIC -> {
                    langResult = textToSpeech.setLanguage(Locale.ENGLISH)
                }
                Constants.ARABIC_ENGLISH -> {
                    langResult = textToSpeech.setLanguage(Locale.ENGLISH)
                }
            }

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
               println("here is text speech 111")
            }
        } else {
            println("here is text speech 222")
            // Handle initialization failure
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initResetData(){
        if(getAllResetData()?.isNotEmpty() == true){
            println("here is data reset everyday ${getAllResetData()}")
            if (isResetDoneInDb()){
                println("here is data resetted")
                resetAllTransactionInDb()
                Toast.makeText(this@MainActivity,
                    getString(R.string.queue_reset_successfully), Toast.LENGTH_SHORT).show()
                updateLastDateTimeInDb(getCurrentDateTime())
                updateResetDateTime(getNextDate(getResetData()?.resetDateTime ?: ""))
            }
        }
    }

    private fun initLeftNavMenuDrawer() {
        headerLayout = binding.headerLayout

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.drawerLayout.hideKeyboard()
            }
            override fun onDrawerOpened(drawerView: View) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        onLeftNavMenuDrawerClickListener()
    }

    private fun onLeftNavMenuDrawerClickListener() {
        headerLayout.conHome.setOnClickListener {
            binding.drawerLayout.closeDrawers()
        }

        headerLayout.conUsers.setOnClickListener {
            val intent = Intent(this@MainActivity, UserListActivity::class.java)
            startActivity(intent)
        }

        headerLayout.conReset.setOnClickListener {
            val intent = Intent(this@MainActivity, ResetActivity::class.java)
            startActivity(intent)
        }

        headerLayout.conVoiceConfig.setOnClickListener {
            val intent = Intent(this@MainActivity, VoiceConfigurationActivity::class.java)
            startActivity(intent)
        }

        headerLayout.conFTDIConsole.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            val intent = Intent(this@MainActivity, FTDIConsoleActivity::class.java)
            startActivity(intent)
        }
    }

    private fun initializeToolbar() {
        setUpToolbar(
            binding.layoutToolbar,
            title = getString(R.string.app_name),
            isBackArrow = false,
            iconMenu = R.drawable.ic_menu,
            toolbarClickListener = object : CommonInterfaceClickEvent {
                override fun onToolBarListener(type: String) {
                    when (type) {
                        Constants.TOOLBAR_ICON_ONE -> {
                            val intent = Intent(this@MainActivity, CounterListActivity::class.java)
                            val passList = ArrayList(arrListClients);
                            Log.i("deepika", "onToolBarListener: $arrListClients $passList")
                            intent.putStringArrayListExtra("KEY_LIST_DATA", passList)
                            startActivity(intent)
                        }
                        Constants.TOOLBAR_ICON_MENU -> {
                            binding.drawerLayout.open()
                        }
                    }
                }
            }
        )
    }


    // Here the whole server is started via port number and device ip address
    private fun initializeSocket() {
        if (tcpServer != null) {
            Log.w("TCP", "Server already running")
            return
        }

        tcpServer = TCPServer(8085, this, this@MainActivity)
        Thread {
            tcpServer?.start()
        }.start()


    }


    // Here the service is started to monitor for background tasks
    private fun startServerService(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                0
            )
        }
        val intent = Intent(this@MainActivity, ServerService::class.java)
        intent.action = ServerService.Actions.START.toString()
        startService(intent)
        println("Service started")

    }

    private fun initializeFields() {
        binding.rvServiceList.adapter = adapter
        setData(parseInServiceModelArraylist(getAllServiceFromDB())) // service as set if not empty



    }


    // Display custom id for window displays
    fun generateCustomId(): String {
        return counter++.toString()
    }

    // this is the process to update service in data table and show it in ui
    private fun setData(data: ArrayList<ServiceListDataModel?>) {
        arrListService.clear()
        arrListService.addAll(data)
        adapter.onClickEvent = object : CommonInterfaceClickEvent {
            override fun onItemClick(type: String, position: Int) {
                Log.i("deepu", "setData11: $type $position")
                if (type == "editService") {
                    Dialogs.showAddServiceDialog(
                        this@MainActivity,
                        editServiceModel = data[position],
                        object : AlertDialogInterface {
                        override fun onUpdateService(model: ServiceListDataModel) {
                            val dbModel = parseInServiceDbModel(model, model.serviceId ?: "")
                            addServiceInDB(dbModel) // Add service functions or update
                            setData(parseInServiceModelArraylist(getAllServiceFromDB())) // Add services in ui
                            // Broadcast to all connected clients using WebSocketManager
                            broadcastToAllClients(createJsonData())
                            println("here is all services ${getAllServiceFromDB()}")
                        }
                    })
                }
            }
        }
        adapter.setData(arrListService)
    }

    private fun onClickListeners() {
        binding.btnAddService.setOnClickListener {
            Dialogs.showAddServiceDialog(this,
                editServiceModel = null,
                object : AlertDialogInterface {
                override fun onAddService(model: ServiceListDataModel) {
                    // this is the process to add service in data table and show it in ui
                    val dbModel = parseInServiceDbModel(model, model.serviceId ?: "")
                    addServiceInDB(dbModel)
                    setData(parseInServiceModelArraylist(getAllServiceFromDB()))
                    // Broadcast to all connected clients using WebSocketManager
                    broadcastToAllClients(createJsonData())
                }
            })
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMessageReceived(message: String) {

    }

    // All messages from clients received in tcp server and passed through interface and handled here
    override fun onMessageJsonReceived(json: JsonObject) {
        synchronized(arrListClients) {
            if (!json.isJsonNull) {

                println("Received json in activity: $json ${json.has(Constants.CONNECTION)}")

                when {
                    // For Connection of every client
                    json.has(Constants.CONNECTION) -> {
                        val set = HashSet<String>(arrListClients)
                        println("deepu0 : $set ")
//                        sendMessageToWebSocketClient(arrListClients.lastOrNull()?.toString() ?: "", createJsonData())
                        set.forEach {
                            sendMessageToWebSocketClient(it ?: "", createJsonData())
                        }
                    }
                    // For Ticket Dispenser
                    json.has(Constants.TICKET_TYPE) -> {
                        manageTicketData(json)
                    }
                    // For Window Display Connection
                    json.has(Constants.DISPLAY_CONNECTION) -> {
                        println("deepu1 : $arrListClients ")
                        sendMessageToWebSocketClient(arrListClients.lastOrNull()?.toString() ?: "", createDisplayJsonData(
                            "D${generateCustomId()}"
                        ))
                    }
                    // For Master Display Connection
                    json.has(Constants.MASTER_DISPLAY_CONNECTION) || json.has(Constants.MASTER_RECONNECTION) -> {
                        if (json.has("services")){
                            val serviceIds: List<String?>? = json.get("services")?.asString?.split(",")?.map { it.trim() }
                            println("here is data with ${getRequiredTransactionWithServiceFromDB(serviceIds)}")
                            println("here is serviceIds ${serviceIds}")
                            println("deepu2 : $arrListClients ")
                            sendMessageToWebSocketClient(
                                tcpServer?.arrListMasterDisplays?.lastOrNull() ?: "",
                                createMasterDisplayJsonData(
                                    getRequiredTransactionWithServiceFromDB(serviceIds)
                                )
                            )
                        }else {
                            println("deepu3 : $arrListClients ")
                            sendMessageToWebSocketClient(tcpServer?.arrListMasterDisplays?.lastOrNull() ?: "", createJsonData())
                        }

                    }
                    // For Username related for soft keypad - only send response to the requesting client
                    json.has(Constants.USERNAME) -> {
                        // Only send login response to the client that made the request (last connected client)
                        val requestingClientId = arrListClients.lastOrNull()
                        val loginRequestId = json.get("loginRequestId")?.asString
                        if (!requestingClientId.isNullOrEmpty()) {
                            println("deepu4 : $arrListClients ")
                            sendMessageToWebSocketClient(
                                requestingClientId,
                                createUserJsonData(json.get("userName").asString, loginRequestId)
                            )
                            println("Login response sent only to requesting client: $requestingClientId with requestId: $loginRequestId")
                        }
                    }
                    // For Window Display Connection
                    json.has(Constants.DISPLAY_ID) -> {
                        manageCounterDisplayData(json)
                    }
                    // For Keypad Connection and data management
                    else -> {
                        manageKeypadData(json)
                    }
                }

            } else {
                socket.close()
            }
        }
    }

    private fun manageCounterDisplayData(json: JsonObject){
        println("THIS IS DISPLAY TYPE MODULE :: $json")
        if (json.has(Constants.TRANSACTION)) {
            val model =  json.getAsJsonObject("transaction")
            serviceId = model?.get("serviceId")?.asString ?: ""
            serviceType = model?.get("serviceType")?.asString ?: ""
            println("here is service id $serviceId")
            if (serviceId.isNotEmpty()) {
                val updateModel = TransactionListDataModel(
                    id = model?.get("id")?.asString ?: "",
                    counterId = model?.get("counterId")?.asString ?: "",
                    serviceId = serviceId,
                    entityID = model?.get("entityID")?.asString ?: "",
                    serviceAssign = serviceType,
                    token = model?.get("token")?.asString ?: "",
                    ticketToken = model?.get("ticketToken")?.asString ?: "",
                    keypadToken = model?.get("keypadToken")?.asString ?: "",
                    issueTime = model?.get("issueTime")?.asString ?: "",
                    startKeypadTime = null,
                    endKeypadTime = null,
                    status = model?.get("status")?.asString ?: ""

                )

//                {"keypadCounterType":"Counter 1","counterId":"1","message":"ConnectionWithDisplay","serviceId":"1","displayId":"D2"}
                sendMessageToWebSocketClient(
                    model?.get("displayId")?.asString ?: "",
                    createServiceJsonDataWithTransaction(
                        getTransactionFromDbWithCalledStatus(serviceId)
                    )
                )
            }

        }

        else if (json.has("Reconnection")) {
            val displayId = json.get("displayId")?.asString ?: ""
            val counterId = json.get("counterId")?.asString ?: ""
            val serviceIdVal = json.get("serviceId")?.asString ?: ""

            sendMessageToWebSocketClientWith(
                displayId,
                createReconnectionJsonDataWithTransaction(),
                onSuccess = {
                    // Remove any existing entry with same displayId to prevent duplicates
                    arrListDisplays.removeAll { it?.id == displayId }

                    val model = DisplayListDataModel(
                        id = displayId,
                        counterId = counterId,
                        serviceId = serviceIdVal
                    )

                    arrListDisplays.add(model)
                    println("Display registered (Reconnection): id=$displayId, counterId=$counterId, total displays=${arrListDisplays.size}")
                },
                onFailure = { e ->
                    println("Error: ${e.message}")
                    // Handle failure, such as logging or notifying the user
                }
            )

            println("here is changed transactions issue model 2222 ${getTransactionFromDbWithCalledStatus(json.get("serviceId")?.asString ?: "")}")

        }

        else {
            println("here is transaction with service id ${json.get("serviceId")?.asString ?: ""}")
//            println("here is transaction with with all status  ${getAllTransactionFromDB()}")
            println("here is transaction with status 1 in display  ${getTransactionFromDbWithCalledStatus(json.get("serviceId")?.asString ?: "")}")

            val displayId = json.get("displayId")?.asString ?: ""
            val counterId = json.get("counterId")?.asString ?: ""
            val serviceIdVal = json.get("serviceId")?.asString ?: ""

            val counterModel = getCounterFromDB(counterId) // counter model to check service which is assigned to that counter
            val sentModel = getLastTransactionFromDbWithStatusOne(counterModel?.counterId)

            println("here is id ::::: $serviceIdVal")
            var isDbUpdated = false

            sendMessageToWebSocketClientWith(
                displayId,

                createServiceJsonDataWithTransaction(
                    sentModel ?: TransactionListDataModel(
                            id = "0",
                            counterId = counterModel?.counterId,
                            serviceId = serviceIdVal,
                            entityID = "",
                            serviceAssign = json.get("counterType")?.asString ?: "",
                            token = "00",
                            ticketToken = "00",
                            keypadToken = "00",
                        )
                ),
                onSuccess = {
                    // Remove any existing entry with same displayId to prevent duplicates
                    arrListDisplays.removeAll { it?.id == displayId }

                    val model = DisplayListDataModel(
                        id = displayId,
                        counterId = counterId,
                        serviceId = serviceIdVal
                    )

                    arrListDisplays.add(model)
                    println("Display registered: id=$displayId, counterId=$counterId, total displays=${arrListDisplays.size}")
                },
                onFailure = { e ->
                    println("Error: ${e.message}")
                    // Handle failure, such as logging or notifying the user
                }
            )

        }
    }

    private fun manageKeypadData(json: JsonObject){
        println("THIS IS KEYPAD TYPE MODULE ::   $json")
        if (json.has(Constants.TRANSACTION)) {
            val model =  json.getAsJsonObject("transaction")
            serviceId = model?.get("serviceId")?.asString ?: ""
            serviceType = model?.get("serviceType")?.asString ?: ""
            println("here is service id $serviceId")
            if (serviceId.isNotEmpty()) {
                val updateModel = TransactionListDataModel(
                    id = model?.get("id")?.asString ?: "",
                    counterId = json.get("counterId")?.asString ?: "",
                    serviceId = serviceId,
                    entityID = model?.get("entityID")?.asString ?: "",
                    serviceAssign = serviceType,
                    token = model?.get("token")?.asString ?: "",
                    ticketToken = model?.get("ticketToken")?.asString ?: "",
                    keypadToken = model?.get("keypadToken")?.asString ?: "",
                    issueTime = model?.get("issueTime")?.asString ?: "",
                    startKeypadTime = model?.get("startKeypadTime")?.asString ?: "",
                    endKeypadTime = model?.get("endKeypadTime")?.asString ?: "",
                    status = "2" // status 2 is for the keypad has completed that token

                )
                println("here is transaction data ${json.get("counterId")?.asString}-- $updateModel")

                val dbModel = parseInTransactionDbModel(updateModel, updateModel.id ?: "")
                addTransactionInDBKeypad(dbModel)  // here the transaction is update with new status which is 2 completed transaction

                println("here is transactions 0000 ${getAllTransactionFromDB()}")
                println("here is counter data of counter ${getCounterFromDB(json.get("counterId")?.asString ?: "")}")
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "") // counter model to check service which is assigned to that counter

                var isDbUpdated = false

                if ((getTransactionFromDbWithIssuedStatus(counterModel?.serviceId) != null)){
                    updateCounter(counterModel?.serviceId, counterModel?.counterId, getTransactionFromDbWithIssuedStatus(counterModel?.serviceId))
                    println("here is status of transaction ${model.get("status")?.asString}")
                    sendMessageToWebSocketClientWith(
                        json.get("counterId")?.asString ?: "",
                        createServiceJsonDataWithTransaction(
                            getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)
                        ), // here new transaction is sent to keypad and window display
                        onSuccess = {
                            println("here is arrlist Display $arrListDisplays")

                            // method for managing display data and status is updated as 1 where it means the transaction is in progress
                            sendDisplayData(
                                json = json,
                                counterModel = counterModel,
                                sentModel = getTransactionFromDbCounterWise(counterModel?.counterId)

                            )

                            // method to call voice tokens
                            val token = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)?.token
                            callTokens(token ?: "", counterModel)

                            if (!isDbUpdated){
                                Log.i("deepu", "manageKeypadData: 0 ${counterModel?.serviceId}")
                                updateDb(counterModel?.serviceId, counterModel?.counterId, getTransactionFromDbWithIssuedStatus(counterModel?.serviceId))

                                isDbUpdated = true
                            }

                            sendMessageToWebSocketClientWith(
                                json.get("counterId")?.asString ?: "",
                                createReconnectionJsonDataWithTransaction(),
                                onSuccess = {
                                    sendMessageToWebSocketClient(
                                        json.get("counterId")?.asString ?: "",
                                        createTransactionsJsonData(
                                            getAllTransactionCount(counterModel?.serviceId ?: "")
                                        )
                                    )
                                },
                                onFailure = { e ->
                                    // Handle failure, such as logging or notifying the user
                                }
                            )
                        },
                        onFailure = {}

                    )


                }else {
                    val transModel=TransactionDataDbModel(
                        id = 0,
                        counterId = json.get("counterId")?.asString ?:"",
                        serviceId = json.get("serviceId")?.asString ?: serviceId,
                        entityID = "",
                        counterType = json.get("counterId")?.asString ?:"",
                        serviceAssign = json.get("serviceType")?.asString,
                        token = "00",
                        ticketToken = null,
                        keypadToken = "00",
                        issueTime = getCurrentTimeFormatted(),
                        startKeypadTime = getCurrentTimeFormatted(),
                        endKeypadTime = getCurrentTimeFormatted(),
                        status = "0"
                    )
                    sendDisplayData(
                        json = json,
                        counterModel = counterModel,
                        sentModel = transModel

                    )
                    // no tokens with status 0 available in table
                    sendMessageToWebSocketClient(
                        json.get("counterId")?.asString ?: "",
                        createNoTokensData(),
                    )
                }

            }

        }
        else {
            // this is process for token call out feature
            if(json.has("keypadLogOut")){
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")

                val issueModel = getLastTransactionFromDbWithStatusOne(counterModel?.counterId ?: "")
                Log.i("deepu", "manageKeypadData: $issueModel")
                val model =  json.getAsJsonObject("currentToken")
                val changedModel = TransactionListDataModel(
                    id = issueModel?.id.asString(),
                    counterId = json.get("counterId")?.asString ?: "",
                    serviceId = issueModel?.serviceId,
                    entityID = issueModel?.entityID,
                    serviceAssign = issueModel?.serviceAssign,
                    token = issueModel?.token,
                    ticketToken = issueModel?.ticketToken,
                    keypadToken = issueModel?.keypadToken,
                    issueTime = issueModel?.issueTime,
                    startKeypadTime = model.get("startKeypadTime")?.asString ?: "",
                    endKeypadTime = model.get("endKeypadTime")?.asString ?: "",
                    status = "2"

                )
                if(issueModel!=null) {
                    Log.i("deepu", "manageKeypadData:- $issueModel ${changedModel}")
                    val changedDbModel =
                        parseInTransactionDbModel(changedModel, changedModel.id ?: "")
                    addTransactionInDB(changedDbModel)
                }

                handler(500){
                    println("here is arrlist Display $arrListDisplays")
                    val transModel=TransactionDataDbModel(
                        id = 0,
                        counterId = json.get("counterId")?.asString ?:"",
                        serviceId = json.get("serviceId")?.asString ?: serviceId,
                        entityID = "",
                        counterType = json.get("counterId")?.asString ?:"",
                        serviceAssign = json.get("serviceType")?.asString,
                        token = "00",
                        ticketToken = null,
                        keypadToken = "00",
                        issueTime = getCurrentTimeFormatted(),
                        startKeypadTime = getCurrentTimeFormatted(),
                        endKeypadTime = getCurrentTimeFormatted(),
                        status = "0"
                    )
                    sendDisplayData(
                        json = json,
                        counterModel = counterModel,
                        sentModel = transModel

                    )

                }

            }

            else if (json.has("tokenNo")){
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")
                var isDbUpdated = false

                if ((getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "") != null)){
                    println("here is transaction with token :::" +
                            " ${getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")}")
                    sendMessageToWebSocketClient(
                        json.get("counterId")?.asString ?: "",
                        createServiceJsonDataWithTransaction(
                            getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")
                        )
                    )

                    val issueModel = getLastTransactionFromDbWithStatusOne(counterModel?.counterId ?: "")
                    Log.i("deepu", "manageKeypadData: $issueModel")
                    val model =  json.getAsJsonObject("currentToken")
                    val changedModel = TransactionListDataModel(
                        id = issueModel?.id.asString(),
                        counterId = json.get("counterId")?.asString ?: counterModel?.counterId,
                        serviceId = issueModel?.serviceId,
                        entityID = issueModel?.entityID,
                        serviceAssign = issueModel?.serviceAssign,
                        token = issueModel?.token,
                        ticketToken = issueModel?.ticketToken,
                        keypadToken = issueModel?.keypadToken,
                        issueTime = issueModel?.issueTime,
                        startKeypadTime = model.get("startKeypadTime")?.asString ?: "",
                        endKeypadTime = model.get("endKeypadTime")?.asString ?: "",
                        status = "2"

                    )
                    if(issueModel!=null) {
                        Log.i("deepu", "manageKeypadData:- $issueModel ${changedModel}")
                        val changedDbModel =
                            parseInTransactionDbModel(changedModel, changedModel.id ?: "")
                        addTransactionInDB(changedDbModel)
                    }

                    handler(500){
                        println("here is arrlist Display $arrListDisplays")



                        val token = getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")?.token
                        Log.i("deepu", "manageKeypadData:0 $token $counterModel")
                        callTokens(token ?: "", counterModel)

                        if (!isDbUpdated){
                            Log.i("deepu", "manageKeypadData: 1 ${json.get("tokenNo")?.asString} ${counterModel?.serviceId}")
                            updateDb(counterModel?.serviceId, counterModel?.counterId, getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: ""))

                            isDbUpdated = true
                        }
                        sendDisplayData(
                            json = json,
                            counterModel = counterModel,
                            sentModel = getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")

                        )
                    }
                }

            }

            // this is process for repeat token feature
            else if (json.has("repeatToken")){
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")
                var isDbUpdated = false

                if ((getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "") != null)){
                    println("here is transaction with token :::" +
                            " ${getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "")}")
                    sendMessageToWebSocketClient(
                        json.get("counterId")?.asString ?: "",
                        createServiceJsonDataWithTransaction(
                            getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "")
                        )
                    )


                    handler(500){
                        println("here is arrlist Display $arrListDisplays")

                        sendDisplayData(
                            json = json,
                            counterModel = counterModel,
                            sentModel = getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: ""),
                            true
                        )

                        if(!json.has("isFirst")){
                            val token = getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "")?.token
                            callTokens(token ?: "", counterModel)
                        }

                        if (!isDbUpdated){
                            Log.i("deepu", "manageKeypadData: 2 ${json.get("repeatToken")?.asString} ${counterModel?.serviceId}")
                            updateDb(counterModel?.serviceId, counterModel?.counterId, getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: ""))

                            isDbUpdated = true
                        }

                    }
                }

            }

            // Reconnection of clients if connection is disconnected
            else if (json.has("Reconnection")) {
                println("here is service id in reconnection ${json.get("serviceId")?.asString ?: ""}")

                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")

                sendMessageToWebSocketClientWith(
                    json.get("counterId")?.asString ?: "",
                    createReconnectionJsonDataWithTransaction(),
                    onSuccess = {
                        sendMessageToWebSocketClient(
                            json.get("counterId")?.asString ?: "",
                            createTransactionsJsonData(
                                getAllTransactionCount(counterModel?.serviceId ?: "")
                            )
                        )
                    },
                    onFailure = { e ->
                        // Handle failure, such as logging or notifying the user
                    }
                )

                broadcastToAllClients(createJsonData())

            }

            else {
                // this is when the keypad is connected for first time
                println("here is to check for counter 2")
                println("here is counter data of counter 111 ${getCounterFromDB(json.get("counterId")?.asString ?: "")}")
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")
                var isDbUpdated = false  // Flag to ensure the database is updated only once

                var jsonObjectTransactionListDataModel =
                    if (getLastTransactionFromDbWithStatusOne(counterModel?.counterId) != null){
                        getLastTransactionFromDbWithStatusOne(counterModel?.counterId)
                    }
                    else {
                            null
                        }



                sendMessageToWebSocketClientWith(
                    json.get("counterId")?.asString ?: "",
                    createServiceJsonDataWithTransaction(
                        jsonObjectTransactionListDataModel
                            ?: TransactionListDataModel(
                                id = "",
                                counterId = "",
                                serviceId = counterModel?.serviceId,
                                entityID = "",
                                serviceAssign = counterModel?.counterType,
                                token = "00",
                                ticketToken = "00",
                                keypadToken = "00",
                            )
                    ),
                    onSuccess = {

                        println("here is arrlist Display $arrListDisplays")
                        if(jsonObjectTransactionListDataModel==null){
                            jsonObjectTransactionListDataModel = TransactionDataDbModel(
                                id = 0,
                                counterId = json.get("counterId")?.asString ?: counterModel?.counterId ?: "",
                                serviceId = json.get("serviceId")?.asString ?: serviceId,
                                entityID = "",
                                counterType = json.get("counterId")?.asString ?: counterModel?.counterId ?: "",
                                serviceAssign = json.get("serviceType")?.asString,
                                token = "00",
                                ticketToken = null,
                                keypadToken = "00",
                                issueTime = getCurrentTimeFormatted(),
                                startKeypadTime = getCurrentTimeFormatted(),
                                endKeypadTime = getCurrentTimeFormatted(),
                                status = "0"
                            )
                        }
                        else{
                            if (!isDbUpdated){
                                Log.i("deepu", "manageKeypadData: 3 ${jsonObjectTransactionListDataModel}")
                                updateDb(counterModel?.serviceId, counterModel?.counterId, jsonObjectTransactionListDataModel)

                                isDbUpdated = true
                            }
                        }
                        sendDisplayData(
                            json = json,
                            counterModel = counterModel,
                            sentModel = jsonObjectTransactionListDataModel
                        )

                       /* val token = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)?.token
                        callTokens(token ?: "", counterModel)*/



                    },
                    onFailure = {  }
                )


            }
        }
    }



    private fun updateDb(serviceId: String?, counterId: String?, sentModel: TransactionDataDbModel?){
        val changedDisplayModel = TransactionListDataModel(
            id = sentModel?.id.asString(),
            counterId = counterId,
            serviceId = sentModel?.serviceId,
            entityID = sentModel?.entityID,
            serviceAssign = sentModel?.serviceAssign,
            token = sentModel?.token,
            ticketToken = sentModel?.ticketToken,
            keypadToken = sentModel?.keypadToken,
            issueTime = sentModel?.issueTime,
            startKeypadTime = sentModel?.startKeypadTime,
            endKeypadTime = sentModel?.endKeypadTime,
            status = "1"
        )

        if(sentModel!=null){
            val changedDisplayDbModel = parseInTransactionDbModel(changedDisplayModel, changedDisplayModel.id ?: "")
            println("Here is the changed transactions model: $changedDisplayDbModel")

            // Update the database
            addTransactionInDB(changedDisplayDbModel)
        }

    }

    /**
     * Update database for hard keypad request - sets startKeypadTime to current time.
     * This is called when a hard keypad requests a token (NEXT or DIRECT_CALL).
     */
    private fun updateDbForHardKeypad(serviceId: String?, counterId: String?, sentModel: TransactionDataDbModel?) {
        val currentTime = getCurrentTimeFormatted()
        val changedDisplayModel = TransactionListDataModel(
            id = sentModel?.id.asString(),
            counterId = counterId,
            serviceId = sentModel?.serviceId,
            entityID = sentModel?.entityID,
            serviceAssign = sentModel?.serviceAssign,
            token = sentModel?.token,
            ticketToken = sentModel?.ticketToken,
            keypadToken = sentModel?.keypadToken,
            issueTime = sentModel?.issueTime,
            startKeypadTime = currentTime,  // Set startKeypadTime when hard keypad requests token
            endKeypadTime = sentModel?.endKeypadTime,
            status = "1"
        )

        if (sentModel != null) {
            val changedDisplayDbModel = parseInTransactionDbModel(changedDisplayModel, changedDisplayModel.id ?: "")
            Log.d("MainActivity", "Hard keypad token update - token: ${sentModel.token}, startKeypadTime: $currentTime")

            // Update the database
            addTransactionInDB(changedDisplayDbModel)
        }
    }

    private fun updateCounter(serviceId: String?, counterId: String?,sentModel: TransactionDataDbModel?){
        val changedDisplayModel = TransactionListDataModel(
            id = sentModel?.id.asString(),
            counterId = counterId,
            serviceId = sentModel?.serviceId,
            entityID = sentModel?.entityID,
            serviceAssign = sentModel?.serviceAssign,
            token = sentModel?.token,
            ticketToken = sentModel?.ticketToken,
            keypadToken = sentModel?.keypadToken,
            issueTime = sentModel?.issueTime,
            status = "0"
        )

        if(sentModel!=null){
            val changedDisplayDbModel = parseInTransactionDbModel(changedDisplayModel, changedDisplayModel.id ?: "")
            println("Here is the changed transactions model: $changedDisplayDbModel")

            // Update the database
            addTransactionInDB(changedDisplayDbModel)
        }

    }

    private fun manageTicketData(json: JsonObject){
        println("THIS IS TICKET TYPE MODULE :: $json ")
        if (json.has(Constants.SERVICE_TYPE)) {
            serviceId = json.get("serviceId")?.asString ?: ""
            serviceType = json.get("serviceType")?.asString ?: ""
            println("here is service id $serviceId")
            val service = getServiceById(serviceId.asInt())
            if (serviceId.isNotEmpty() && isCounterAssigned(serviceId)) {
                val model = TransactionListDataModel(
                    serviceId = serviceId,
                    entityID = serviceId,
                    serviceAssign = serviceType,
                    token = getCurrentServiceToken(serviceId).asString(),
                    ticketToken = getCurrentServiceToken(serviceId).asString(),
                    keypadToken = getCurrentServiceToken(serviceId).asString(),
                    issueTime = getCurrentTimeFormatted(),
                    startKeypadTime = null,
                    endKeypadTime = null,
                    status = "0"

                )
                val dbModel = parseInTransactionDbModel(model, model.id ?: "")
                addTransactionInDB(dbModel)
                sendMessageToWebSocketClient(
                    json.get("ticketId")?.asString ?: "",
                    createServiceJsonDataWithModel(serviceId, dbModel)
                )
                if (getCurrentServiceToken(serviceId) == service?.tokenEnd.asInt()){
                    addServiceTokenToDB(
                        serviceId,
                        service?.tokenStart.asInt()
                    )

                }else {
                    addServiceTokenToDB(
                        serviceId,
                        getCurrentServiceToken(serviceId).plus(1)
                    )
                }
                serviceId = ""
            }
        } else {
            sendMessageToWebSocketClient(
                json.get("ticketId")?.asString ?: "",
                createJsonData()
            )
        }
    }


    override fun onClientConnected(clientSocket: Socket?, clientList: List<String?>) {
        Thread {
            try {
                outStream = clientSocket?.getOutputStream()
                if (clientSocket != null) {
                    socket = clientSocket
                    // Sync arrListClients with the server's client list
                    // Use synchronized block to prevent concurrent modification
                    synchronized(arrListClients) {
                        arrListClients.clear()
                        clientList.filterNotNull().forEach { clientId ->
                            if (!arrListClients.contains(clientId)) {
                                arrListClients.add(clientId)
                            }
                        }
                    }
                    Log.d("MainActivity", "Client connected - total clients: ${arrListClients.size}, clients: $arrListClients")
                }
                println("Connected to server")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onClientDisconnected(clientId: String?) {
        // Remove disconnected display from the list to prevent stale entries
        if (!clientId.isNullOrEmpty()) {
            val removedCount = arrListDisplays.count { it?.id == clientId }
            arrListDisplays.removeAll { it?.id == clientId }
            if (removedCount > 0) {
                println("onClientDisconnected: Removed display id=$clientId, remaining displays=${arrListDisplays.size}")
            }
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

    private fun sendMessageToWebSocketClientWith(
        clientId: String,
        jsonObject: JsonObject,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        try {
            // this method to get client handler used in server
            val clientHandler = TCPServer.WebSocketManager.getClientHandler(clientId)
            if (clientHandler != null && clientHandler.isWebSocket) {
                Thread {
                    try {
                        val jsonMessage = gson.toJson(jsonObject)
                        println("Sending message to client: $clientId $jsonObject")
                        clientHandler.sendMessageToClient(clientId, jsonMessage)
                        onSuccess() // when client is connected the success block is sent and based on that next functions are called
                    } catch (e: Exception) {
                        // Call the failure callback in case of an exception
                        onFailure(e)
                    }
                }.start()
            } else {
                // Handle case where clientHandler is not found or not a WebSocket client
                onFailure(Exception("Client handler is null or not a WebSocket client"))
            }
        } catch (e: Exception) {
            // Call the failure callback if there's an error outside the thread
            onFailure(e)
        }
    }


    /**
     * Broadcasts JSON data to ALL connected WebSocket clients.
     * Uses WebSocketManager to get accurate client list.
     */
    private fun broadcastToAllClients(jsonObject: JsonObject) {
        val allClients = TCPServer.WebSocketManager.getAllClients()
        Log.d("MainActivity", "broadcastToAllClients - total clients: ${allClients.size}")

        if (allClients.isEmpty()) {
            Log.w("MainActivity", "No clients connected to broadcast!")
            return
        }

        val jsonMessage = gson.toJson(jsonObject)
        var successCount = 0
        var failCount = 0

        allClients.forEach { clientHandler ->
            if (clientHandler.isWebSocket) {
                Thread {
                    try {
                        Log.d("MainActivity", "Broadcasting to client: ${clientHandler.clientId}")
                        clientHandler.sendMessageToClient(clientHandler.clientId, jsonMessage)
                        successCount++
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to send to client ${clientHandler.clientId}: ${e.message}")
                        failCount++
                    }
                }.start()
            }
        }

        Log.d("MainActivity", "Broadcast initiated to ${allClients.size} clients")
    }

    private fun sendMessageToAllConnectedClients(
        jsonObject: JsonObject,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        try {
            // Get all the connected client handlers from your WebSocket manager
            val connectedClients = TCPServer.WebSocketManager.getAllClients()

            Log.d("MainActivity", "sendMessageToAllConnectedClients - total clients: ${connectedClients.size}")

            if (connectedClients.isEmpty()) {
                Log.w("MainActivity", "No clients connected!")
                return
            }

            // Loop through each client and send the message only if they are connected
            connectedClients.forEach { clientHandler ->
                if (clientHandler.isWebSocket) {
                    Thread {
                        try {
                            val jsonMessage = gson.toJson(jsonObject)
                            Log.d("MainActivity", "Sending message to client: ${clientHandler.clientId}")
                            clientHandler.sendMessageToClient(clientHandler.clientId, jsonMessage)
                            onSuccess() // Successfully sent the message
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to send to ${clientHandler.clientId}: ${e.message}")
                            onFailure(e)
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "sendMessageToAllConnectedClients error: ${e.message}")
            onFailure(e)
        }
    }



    // This Function is used to send data's to displays connected to keypad when token is callout or next button pressed on keypad
    private fun sendDisplayData(json: JsonObject,counterModel: CounterDataDbModel?, sentModel: TransactionDataDbModel?, isRepeat: Boolean = false ){
        if (arrListDisplays.isNotEmpty()) {
            val targetCounterId = json.get("counterId")?.asString ?: ""

            println("sendDisplayData: Looking for displays with counterId=$targetCounterId")
            println("sendDisplayData: Total registered displays=${arrListDisplays.size}")
            arrListDisplays.forEach { display ->
                println("sendDisplayData: Registered display - id=${display?.id}, counterId=${display?.counterId}")
            }

            // Filter displays matching the target counter
            // Compare by removing leading zeros from both sides to match flexibly
            val matchingDisplays = arrListDisplays.filter { display ->
                val displayCounterId = display?.counterId ?: ""
                // Match if exact match, or if normalized versions match (compare without leading zeros)
                displayCounterId == targetCounterId ||
                displayCounterId.trimStart('0').ifEmpty { "0" } == targetCounterId.trimStart('0').ifEmpty { "0" }
            }

            if (matchingDisplays.isNotEmpty()) {
                println("sendDisplayData: Found ${matchingDisplays.size} display(s) for counterId=$targetCounterId")
                println("sendDisplayData: Token to send = ${sentModel?.token}")

                matchingDisplays.forEach { display ->
                    val displayId = display?.id ?: ""
                    println("sendDisplayData: Sending token to display id=$displayId")


                    val transactionListDataModel =
                        if (isRepeat){
                            createServiceJsonDataWithTransactionForRepeat(sentModel)
                        }
                        else {
                            createServiceJsonDataWithTransaction(sentModel)
                        }

                    sendMessageToWebSocketClientWith(
                        displayId,
                        transactionListDataModel,
                        onSuccess = {
                            println("sendDisplayData: Successfully sent token to display id=$displayId")
                            if (tcpServer?.arrListMasterDisplays?.isNotEmpty() == true){
                                sendMessageToWebSocketClient(
                                    tcpServer?.arrListMasterDisplays?.lastOrNull() ?: "",
                                    createMasterDisplayJsonDataWithMsg(
                                        getRequiredTransactionFromDB(),
                                    )
                                )
                            }
                        },
                        onFailure = { e ->
                            println("sendDisplayData: Failed to send to display id=$displayId, error=${e.message}")
                            // Remove disconnected display from the list
                            arrListDisplays.removeAll { it?.id == displayId }
                            println("sendDisplayData: Removed disconnected display, remaining=${arrListDisplays.size}")
                        }
                    )
                }
            } else {
                println("sendDisplayData: No displays found for counterId=$targetCounterId")
            }
        } else {
            println("sendDisplayData: No displays registered yet")
        }
    }




   /* // this function is for voice for every token called
    private fun speakText(text: String , id : String ?= "") {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
*/

    fun getCurrentTimeFormatted(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault())

        val currentTime = Date()

        return dateFormat.format(currentTime)
    }

    fun getStartTimeForKeypad(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault())

        val currentTime = Date()

        return dateFormat.format(currentTime)
    }

    // this function is for voice for every token called
    private fun callTokens(token: String, counterModel: CounterDataDbModel?) {
        // Add the token and counterModel to the queue
        tokenQueue.add(Pair(token, counterModel))

        println("here is token calling 000 $token")

        // Start processing the queue if not already speaking
        if (!isSpeaking) {
            println("here is token calling")
            processNextToken()
        }
    }


    private fun processNextToken() {
        // Safely handle the nullable result from poll()
        val nextToken = tokenQueue.poll()

        if (nextToken != null) {
            println("here is token called")
            isSpeaking = true
            val (token, counterModel) = nextToken

            val customMsgEn = getUserDataResponse()?.msg_en
                ?.replace("<token>", token)
                ?.replace("<counter>", counterModel?.id.asString())

            val customMsgAr = getUserDataResponse()?.msg_ar
                ?.replace("<token>", token)
                ?.replace("<counter>", counterModel?.id.asString())

            when (getUserDataResponse()?.voice_selected) {
                Constants.ENGLISH -> {
                    textToSpeech.language = Locale.US
                    setEnMaleVoice()
                    speakText(customMsgEn ?: "", onComplete = { processNextToken() })
                }
                Constants.ARABIC -> {
                    textToSpeech.language = Locale.US
                    setArMaleVoice()
                    speakText(customMsgAr ?: "", onComplete = { processNextToken() })
                }
                Constants.ENGLISH_ARABIC -> {
                    textToSpeech.language = Locale.US
                    setEnMaleVoice()
                    speakText(customMsgEn ?: "", Constants.ENGLISH, onComplete = {
                        setArMaleVoice()
                        speakText(customMsgAr ?: "", onComplete = { processNextToken() })
                    })
                }
                Constants.ARABIC_ENGLISH -> {
                    setArMaleVoice()
                    speakText(customMsgAr ?: "", Constants.ARABIC, onComplete = {
                        textToSpeech.language = Locale.US
                        setEnMaleVoice()
                        speakText(customMsgEn ?: "", onComplete = { processNextToken() })
                    })
                }
            }
        } else {
            isSpeaking = false
        }
    }



    private fun speakText(text: String, utteranceId: String? = null, onComplete: () -> Unit) {
        textToSpeech.setOnUtteranceCompletedListener {
            onComplete() // Trigger the callback after speaking
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId ?: "")
    }

    private fun setEnMaleVoice() {
        val voices = textToSpeech.voices
        for (voice in voices) {
            if (getUserDataResponse()?.voice_gender == Constants.MALE) {
                println("Selected voice: ${voice.name}")
                println("Selected voice: 111")
                if (voice.name.contains("en-in-x-ene-network", ignoreCase = true)) {
                    maleVoice = voice
                    textToSpeech.voice = maleVoice
                    println("Selected male voice: ${voice.name}")
                    println("Selected voice: 222")
                }
            }
        }
    }


    private fun setArMaleVoice() {
        val voices = textToSpeech.voices
        for (voice in voices) {
            if (getUserDataResponse()?.voice_gender == Constants.MALE) {
                println("Selected voice: ${voice.name}")
                if (voice.name.contains("ar-xa-x-ard-network", ignoreCase = true)) {
                    maleVoice = voice
                    textToSpeech.voice = maleVoice
                    println("Selected male voice: ${voice.name}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tcpServer?.stop()
        // Note: We don't release FTDI singletons here - they persist for the app lifetime
        // They will be released when the app process is destroyed
    }

    // ======================== FTDIQueueOperations Implementation ========================

    /**
     * Called when a hard keypad connects and is mapped to a counter.
     */
    override fun onHardKeypadConnected(ftdiAddress: String, counterId: String) {
        runOnUiThread {
            Log.d("MainActivity", "Hard keypad connected: address=$ftdiAddress, counter=$counterId")
            Toast.makeText(this, "Hard Keypad Connected (Counter $counterId)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Called when hard keypad sends NEXT command - call the next token for that counter.
     */
    override fun onHardKeypadNext(counterId: String) {
        Log.d("MainActivity", "Hard keypad NEXT for counter: $counterId")

        val counterModel = getCounterFromDB(counterId)
        if (counterModel == null) {
            Log.w("MainActivity", "No counter found for id: $counterId")
            return
        }

        // Use the counter's actual counterId from DB
        val actualCounterId = counterModel.counterId ?: counterId

        val currentTransaction = getLastTransactionFromDbWithStatusOne(actualCounterId)
        if (currentTransaction != null) {
            // Mark current transaction as completed (status = 2)
            val completedModel = TransactionListDataModel(
                id = currentTransaction.id?.toString(),
                counterId = actualCounterId,
                serviceId = currentTransaction.serviceId,
                entityID = currentTransaction.entityID,
                serviceAssign = currentTransaction.serviceAssign,
                token = currentTransaction.token,
                ticketToken = currentTransaction.ticketToken,
                keypadToken = currentTransaction.keypadToken,
                issueTime = currentTransaction.issueTime,
                startKeypadTime = currentTransaction.startKeypadTime,
                endKeypadTime = getCurrentTimeFormatted(),
                status = "2"
            )
            val dbModel = parseInTransactionDbModel(completedModel, completedModel.id ?: "")
            addTransactionInDB(dbModel)
        }

        // Get next waiting token for this counter's service
        val nextTransaction = getTransactionFromDbWithIssuedStatus(counterModel.serviceId)
        if (nextTransaction != null) {
            // Update status to in-progress (status = 1) with startKeypadTime
            updateDbForHardKeypad(counterModel.serviceId, actualCounterId, nextTransaction)

            val npw = getAllTransactionCount(counterModel.serviceId ?: "")?.size?.toString() ?: "0"
            val token = nextTransaction.token ?: "000"

            // Send MY_NPW and DISPLAY commands to hard keypad IMMEDIATELY on background thread
            Log.d("MainActivity", "Hard keypad NEXT: About to send response. FTDIBridge.isInitialized()=${FTDIBridge.isInitialized()}")
            Thread {
                Log.d("MainActivity", "Hard keypad NEXT: Background thread started. FTDIBridge.isInitialized()=${FTDIBridge.isInitialized()}")
                if (FTDIBridge.isInitialized()) {
                    Log.d("MainActivity", "Hard keypad NEXT: Calling sendResponseToHardKeypad($actualCounterId, $npw, $token)")
                    FTDIBridge.getInstance().sendResponseToHardKeypad(actualCounterId, npw, token)
                    Log.d("MainActivity", "Hard keypad NEXT: sendResponseToHardKeypad completed")
                } else {
                    Log.w("MainActivity", "Hard keypad NEXT: FTDIBridge not initialized!")
                }
            }.start()

            // Send to soft keypad (WebSocket client) if connected
            sendMessageToWebSocketClient(
                actualCounterId,
                createServiceJsonDataWithTransaction(nextTransaction)
            )

            // Update any connected window displays
            val json = JsonObject().apply {
                addProperty("counterId", actualCounterId)
            }
            sendDisplayData(json, counterModel, nextTransaction)

            // Call the token via TTS
            callTokens(token, counterModel)

            Log.d("MainActivity", "Hard keypad NEXT: called token $token for counter $actualCounterId")
        } else {
            // No more tokens waiting - send empty response to hard keypad IMMEDIATELY
            Log.d("MainActivity", "Hard keypad NEXT: No tokens - About to send empty response. FTDIBridge.isInitialized()=${FTDIBridge.isInitialized()}")
            Thread {
                if (FTDIBridge.isInitialized()) {
                    Log.d("MainActivity", "Hard keypad NEXT: Calling sendResponseToHardKeypad($actualCounterId, 000, 000)")
                    FTDIBridge.getInstance().sendResponseToHardKeypad(actualCounterId, "000", "000")
                }
            }.start()
            Log.d("MainActivity", "Hard keypad NEXT: no tokens waiting for counter $counterId")
        }
    }

    /**
     * Called when hard keypad sends REPEAT command - repeat the last called token.
     */
    override fun onHardKeypadRepeat(counterId: String, tokenNo: String) {
        Log.d("MainActivity", "Hard keypad REPEAT for counter: $counterId, token: $tokenNo")

        val counterModel = getCounterFromDB(counterId)
        if (counterModel == null) {
            Log.w("MainActivity", "No counter found for id: $counterId")
            return
        }

        // Use the counter's actual counterId from DB to ensure proper matching
        val actualCounterId = counterModel.counterId ?: counterId

        val transaction = if (tokenNo.isNotEmpty() && tokenNo != "000") {
            getTransactionByToken(tokenNo, counterModel.serviceId ?: "")
        } else {
            getLastTransactionFromDbWithStatusOne(actualCounterId)
        }

        if (transaction != null) {
            val npw = getAllTransactionCount(counterModel.serviceId ?: "")?.size?.toString() ?: "0"
            val token = transaction.token ?: "000"

            // Send MY_NPW and DISPLAY commands to hard keypad IMMEDIATELY on background thread
            Log.d("MainActivity", "Hard keypad REPEAT: About to send response. FTDIBridge.isInitialized()=${FTDIBridge.isInitialized()}")
            Thread {
                Log.d("MainActivity", "Hard keypad REPEAT: Background thread started")
                if (FTDIBridge.isInitialized()) {
                    Log.d("MainActivity", "Hard keypad REPEAT: Calling sendResponseToHardKeypad($actualCounterId, $npw, $token)")
                    FTDIBridge.getInstance().sendResponseToHardKeypad(actualCounterId, npw, token)
                    Log.d("MainActivity", "Hard keypad REPEAT: sendResponseToHardKeypad completed")
                } else {
                    Log.w("MainActivity", "Hard keypad REPEAT: FTDIBridge not initialized!")
                }
            }.start()

            // Send to soft keypad (WebSocket client) if connected
            sendMessageToWebSocketClient(
                actualCounterId,
                createServiceJsonDataWithTransactionForRepeat(transaction)
            )

            // Update connected window displays
            val json = JsonObject().apply {
                addProperty("counterId", actualCounterId)
            }
            sendDisplayData(json, counterModel, transaction, true)

            // Re-announce the token via TTS
            callTokens(token, counterModel)

            Log.d("MainActivity", "Hard keypad REPEAT: repeated token $token for counter $actualCounterId")
        } else {
            Log.w("MainActivity", "Hard keypad REPEAT: no transaction found for token $tokenNo")
        }
    }

    /**
     * Called when hard keypad sends DIRECT_CALL command - call a specific token directly.
     */
    override fun onHardKeypadDirectCall(counterId: String, tokenNo: String) {
        Log.d("MainActivity", "=== onHardKeypadDirectCall ENTERED === counterId: $counterId, token: $tokenNo")

        val counterModel = getCounterFromDB(counterId)
        Log.d("MainActivity", "onHardKeypadDirectCall: counterModel = $counterModel")
        if (counterModel == null) {
            Log.w("MainActivity", "No counter found for id: $counterId")
            return
        }

        // Use the counter's actual counterId from DB
        val actualCounterId = counterModel.counterId ?: counterId
        Log.d("MainActivity", "onHardKeypadDirectCall: actualCounterId = $actualCounterId, serviceId = ${counterModel.serviceId}")

        val transaction = getTransactionByToken(tokenNo, counterModel.serviceId ?: "")
        Log.d("MainActivity", "onHardKeypadDirectCall: transaction = $transaction")
        if (transaction != null) {
            // Mark any current in-progress transaction as completed
            val currentTransaction = getLastTransactionFromDbWithStatusOne(actualCounterId)
            if (currentTransaction != null && currentTransaction.token != tokenNo) {
                val completedModel = TransactionListDataModel(
                    id = currentTransaction.id?.toString(),
                    counterId = actualCounterId,
                    serviceId = currentTransaction.serviceId,
                    entityID = currentTransaction.entityID,
                    serviceAssign = currentTransaction.serviceAssign,
                    token = currentTransaction.token,
                    ticketToken = currentTransaction.ticketToken,
                    keypadToken = currentTransaction.keypadToken,
                    issueTime = currentTransaction.issueTime,
                    startKeypadTime = currentTransaction.startKeypadTime,
                    endKeypadTime = getCurrentTimeFormatted(),
                    status = "2"
                )
                val dbModel = parseInTransactionDbModel(completedModel, completedModel.id ?: "")
                addTransactionInDB(dbModel)
            }

            // Update the directly called token to in-progress with startKeypadTime
            updateDbForHardKeypad(counterModel.serviceId, actualCounterId, transaction)

            val npw = getAllTransactionCount(counterModel.serviceId ?: "")?.size?.toString() ?: "0"

            // Send MY_NPW and DISPLAY commands to hard keypad IMMEDIATELY on background thread
            Log.d("MainActivity", "Hard keypad DIRECT_CALL: About to send response. FTDIBridge.isInitialized()=${FTDIBridge.isInitialized()}")
            Thread {
                Log.d("MainActivity", "Hard keypad DIRECT_CALL: Background thread started")
                if (FTDIBridge.isInitialized()) {
                    Log.d("MainActivity", "Hard keypad DIRECT_CALL: Calling sendResponseToHardKeypad($actualCounterId, $npw, $tokenNo)")
                    FTDIBridge.getInstance().sendResponseToHardKeypad(actualCounterId, npw, tokenNo)
                    Log.d("MainActivity", "Hard keypad DIRECT_CALL: sendResponseToHardKeypad completed")
                } else {
                    Log.w("MainActivity", "Hard keypad DIRECT_CALL: FTDIBridge not initialized!")
                }
            }.start()

            // Send to soft keypad (WebSocket client) if connected
            sendMessageToWebSocketClient(
                actualCounterId,
                createServiceJsonDataWithTransaction(transaction)
            )

            // Update connected window displays
            val json = JsonObject().apply {
                addProperty("counterId", actualCounterId)
            }
            sendDisplayData(json, counterModel, transaction)

            // Call the token via TTS
            callTokens(tokenNo, counterModel)

            Log.d("MainActivity", "Hard keypad DIRECT_CALL: called token $tokenNo for counter $actualCounterId")
        } else {
            Log.w("MainActivity", "Hard keypad DIRECT_CALL: token $tokenNo not found")
        }
    }

    /**
     * Called when a hard keypad disconnects from the bus.
     */
    override fun onHardKeypadDisconnected(ftdiAddress: String) {
        runOnUiThread {
            Log.d("MainActivity", "Hard keypad disconnected: address=$ftdiAddress")
            Toast.makeText(this, "Hard Keypad Disconnected", Toast.LENGTH_SHORT).show()
        }
    }
}