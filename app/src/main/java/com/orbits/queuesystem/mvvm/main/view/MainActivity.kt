package com.orbits.queuesystem.mvvm.main.view

import NetworkMonitor
import android.content.Intent
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
import com.orbits.queuesystem.helper.database.LocalDB.getTransactionFromDbWithCalledStatus
import com.orbits.queuesystem.helper.database.LocalDB.isCounterAssigned
import com.orbits.queuesystem.helper.database.LocalDB.isResetDoneInDb
import com.orbits.queuesystem.helper.database.LocalDB.resetAllTransactionInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateCurrentDateTimeInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateLastDateTimeInDb
import com.orbits.queuesystem.helper.database.LocalDB.updateResetDateTime
import com.orbits.queuesystem.helper.database.TransactionDataDbModel
import com.orbits.queuesystem.mvvm.counters.view.CounterListActivity
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

class MainActivity : BaseActivity(), MessageListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var adapter = ServiceListAdapter()
    private var arrListService = ArrayList<ServiceListDataModel?>()
    private lateinit var headerLayout: NavHeaderLayoutBinding

    /*----------------------------------------- TCP server variables -----------------------------------------*/
    private var tcpServer: TCPServer? = null
    private lateinit var socket: Socket
    private var outStream: OutputStream? = null
    private val arrListClients = CopyOnWriteArrayList<String>()
    private var arrListDisplays = ArrayList<DisplayListDataModel?>()


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
                if (type == "editService") {
                    Dialogs.showAddServiceDialog(
                        this@MainActivity,
                        editServiceModel = data[position],
                        object : AlertDialogInterface {
                        override fun onUpdateService(model: ServiceListDataModel) {
                            val dbModel = parseInServiceDbModel(model, model.serviceId ?: "")
                            addServiceInDB(dbModel) // Add service functions or update
                            setData(parseInServiceModelArraylist(getAllServiceFromDB())) // Add services in ui
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
                        sendMessageToWebSocketClient(arrListClients.lastOrNull()?.toString() ?: "", createJsonData())
                    }
                    // For Ticket Dispenser
                    json.has(Constants.TICKET_TYPE) -> {
                        manageTicketData(json)
                    }
                    // For Window Display Connection
                    json.has(Constants.DISPLAY_CONNECTION) -> {
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

                            sendMessageToWebSocketClient(
                                tcpServer?.arrListMasterDisplays?.lastOrNull() ?: "",
                                createMasterDisplayJsonData(
                                    getRequiredTransactionWithServiceFromDB(serviceIds)
                                )
                            )
                        }else {
                            sendMessageToWebSocketClient(tcpServer?.arrListMasterDisplays?.lastOrNull() ?: "", createJsonData())
                        }

                    }
                    // For Username related for soft keypad
                    json.has(Constants.USERNAME) -> {
                        arrListClients.forEach {
                            sendMessageToWebSocketClient(it ?: "", createUserJsonData(json.get("userName").asString))
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
        println("THIS IS DISPLAY TYPE MODULE ::")
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


                sendMessageToWebSocketClient(
                    model?.get("displayId")?.asString ?: "",
                    createServiceJsonDataWithTransaction(
                        getTransactionFromDbWithCalledStatus(serviceId)
                    )
                )
            }

        }

        else if (json.has("Reconnection")) {
            sendMessageToWebSocketClientWith(
                json.get("displayId")?.asString ?: "",
                createReconnectionJsonDataWithTransaction(),
                onSuccess = {
                    val model = DisplayListDataModel(
                        id = json.get("displayId")?.asString ?: "",
                        counterId = json.get("counterId")?.asString ?: "",
                        serviceId = json.get("serviceId")?.asString ?: ""

                    )

                    arrListDisplays.add(model)
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
            println("here is transaction with with all status  ${getAllTransactionFromDB()}")
            println("here is transaction with status 1 in dislpay  ${getTransactionFromDbWithCalledStatus(json.get("serviceId")?.asString ?: "")}")

            val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "") // counter model to check service which is assigned to that counter
            val sentModel = getTransactionFromDbWithCalledStatus(counterModel?.serviceId)

            println("here is id ::::: ${json.get("serviceId")?.asString ?: ""}")
            var isDbUpdated = false

            sendMessageToWebSocketClientWith(
                json.get("displayId")?.asString ?: "",

                createServiceJsonDataWithTransaction(
                    sentModel ?: TransactionListDataModel(
                            id = "",
                            counterId = "",
                            serviceId = json.get("serviceId")?.asString ?: "",
                            entityID = "",
                            serviceAssign = json.get("counterType")?.asString ?: "",
                            token = "000",
                            ticketToken = "000",
                            keypadToken = "000",
                        )
                ),
                onSuccess = {

                    val model = DisplayListDataModel(
                        id = json.get("displayId")?.asString ?: "",
                        counterId = json.get("counterId")?.asString ?: "",
                        serviceId = json.get("serviceId")?.asString ?: ""

                    )

                    arrListDisplays.add(model)
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
                                sentModel = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)

                            )

                            // method to call voice tokens
                            val token = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)?.token
                            callTokens(token ?: "", counterModel)

                            if (!isDbUpdated){
                                Log.i("deepu", "manageKeypadData: 0 ${counterModel?.serviceId}")
                                updateDb(getTransactionFromDbWithIssuedStatus(counterModel?.serviceId))

                                isDbUpdated = true
                            }

                        },
                        onFailure = {}

                    )


                }else {
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
            if (json.has("tokenNo")){
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

                    val issueModel = getTransactionFromDbWithCalledStatus(counterModel?.serviceId ?: "")
                    Log.i("deepu", "manageKeypadData: $issueModel")
                    val changedModel = TransactionListDataModel(
                        id = issueModel?.id.asString(),
                        counterId = issueModel?.counterId,
                        serviceId = issueModel?.serviceId,
                        entityID = issueModel?.entityID,
                        serviceAssign = issueModel?.serviceAssign,
                        token = issueModel?.token,
                        ticketToken = issueModel?.ticketToken,
                        keypadToken = issueModel?.keypadToken,
                        issueTime = issueModel?.issueTime,
                        startKeypadTime = json.get("startKeypadTime")?.asString ?: "",
                        endKeypadTime = json.get("endKeypadTime")?.asString ?: "",
                        status = "2"

                    )
                    val changedDbModel = parseInTransactionDbModel(changedModel, changedModel.id ?: "")
                    addTransactionInDB(changedDbModel)

                    handler(500){
                        println("here is arrlist Display $arrListDisplays")

                        sendDisplayData(
                            json = json,
                            counterModel = counterModel,
                            sentModel = getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")

                        )

                        val token = getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: "")?.token
                        Log.i("deepu", "manageKeypadData:0 $token $counterModel")
                        callTokens(token ?: "", counterModel)

                        if (!isDbUpdated){
                            Log.i("deepu", "manageKeypadData: 1 ${json.get("tokenNo")?.asString} ${counterModel?.serviceId}")
                            updateDb(getTransactionByToken(json.get("tokenNo")?.asString ?: "",counterModel?.serviceId ?: ""))

                            isDbUpdated = true
                        }

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
                            sentModel = getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "")
                        )

                        if(!json.has("isFirst")){
                            val token = getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: "")?.token
                            callTokens(token ?: "", counterModel)
                        }

                        if (!isDbUpdated){
                            Log.i("deepu", "manageKeypadData: 2 ${json.get("repeatToken")?.asString} ${counterModel?.serviceId}")
                            updateDb(getTransactionByToken(json.get("repeatToken")?.asString ?: "",counterModel?.serviceId ?: ""))

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

            }

            else {
                // this is when the keypad is connected for first time
                println("here is to check for counter 2")
                println("here is counter data of counter 111 ${getCounterFromDB(json.get("counterId")?.asString ?: "")}")
                val counterModel = getCounterFromDB(json.get("counterId")?.asString ?: "")
                var isDbUpdated = false  // Flag to ensure the database is updated only once

                val jsonObjectTransactionListDataModel =
                    if (getLastTransactionFromDbWithStatusOne(counterModel?.serviceId) != null){
                        getLastTransactionFromDbWithStatusOne(counterModel?.serviceId)
                    }
                    else if (getTransactionFromDbWithIssuedStatus(counterModel?.serviceId) != null){
                            getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)
                    }else {
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
                                token = "000",
                                ticketToken = "000",
                                keypadToken = "000",
                            )
                    ),
                    onSuccess = {

                        println("here is arrlist Display $arrListDisplays")

                        sendDisplayData(
                            json = json,
                            counterModel = counterModel,
                            sentModel = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)
                        )

                       /* val token = getTransactionFromDbWithIssuedStatus(counterModel?.serviceId)?.token
                        callTokens(token ?: "", counterModel)*/

                        if (!isDbUpdated){
                            Log.i("deepu", "manageKeypadData: 3 ${jsonObjectTransactionListDataModel}")
                            updateDb(jsonObjectTransactionListDataModel)

                            isDbUpdated = true
                        }

                    },
                    onFailure = {  }
                )


            }
        }
    }

    private fun updateDb(sentModel: TransactionDataDbModel?){
        val changedDisplayModel = TransactionListDataModel(
            id = sentModel?.id.asString(),
            counterId = sentModel?.counterId,
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

    private fun manageTicketData(json: JsonObject){
        println("THIS IS TICKET TYPE MODULE :: ")
        if (json.has(Constants.SERVICE_TYPE)) {
            serviceId = json.get("serviceId")?.asString ?: ""
            serviceType = json.get("serviceType")?.asString ?: ""
            println("here is service id $serviceId")
            val service = getServiceById(serviceId.asInt())
            if (serviceId.isNotEmpty() && isCounterAssigned(serviceId)) {
                val model = TransactionListDataModel(
                    counterId = getCounterIdForService(serviceId),
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
                    arrListClients.clear()
                    arrListClients.addAll(clientList)
                    println("here is all list after client added $arrListClients")
                }
                println("Connected to server")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onClientDisconnected() {

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


    private fun sendMessageToAllConnectedClients(
        jsonObject: JsonObject,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        try {
            // Get all the connected client handlers from your WebSocket manager
            val connectedClients = TCPServer.WebSocketManager.getAllClients()

            println("here is all clients connected $connectedClients")

            // Loop through each client and send the message only if they are connected
            connectedClients.forEach { clientHandler ->
                if (clientHandler.isWebSocket) {
                    Thread {
                        try {
                            val jsonMessage = gson.toJson(jsonObject)
                            println("Sending message to client: ${clientHandler.clientId}")
                            clientHandler.sendMessageToClient(clientHandler.clientId, jsonMessage)
                            onSuccess() // Successfully sent the message
                        } catch (e: Exception) {
                            // Call failure callback for that particular client
                            onFailure(e)
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            // Call failure callback for errors outside the thread loop
            onFailure(e)
        }
    }



    // This Function is used to send data's to displays connected to keypad when token is callout or next button pressed on keypad
    private fun sendDisplayData(json: JsonObject,counterModel: CounterDataDbModel?,sentModel: TransactionDataDbModel?){
        if (arrListDisplays.isNotEmpty()) {
            println("here is display list  $arrListDisplays")

            val targetCounterId = json.get("counterId")?.asString ?: ""

            val matchFound = arrListDisplays.any { it?.counterId == targetCounterId }

            if (matchFound) {
                // Retrieve the matching item
                val matchingItem = arrListDisplays.filter { it?.counterId == targetCounterId }

                println("here is sentModel 111  $sentModel")

                matchingItem.forEach {
                    sendMessageToWebSocketClientWith(
                        it?.id ?: "",
                        createServiceJsonDataWithTransaction(sentModel),
                        onSuccess = {
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
                            println("Error: ${e.message}")
                            // Handle failure, such as logging or notifying the user
                        }
                    )
                }

            }
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
    }
}