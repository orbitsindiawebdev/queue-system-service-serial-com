package com.orbits.queuesystem.helper.server

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.orbits.queuesystem.helper.Constants
import com.orbits.queuesystem.helper.Extensions
import com.orbits.queuesystem.helper.interfaces.MessageListener
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.experimental.xor
import kotlin.random.Random

class TCPServer(private val port: Int, private val messageListener: MessageListener, private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, ClientHandler>()
    private val connectedClientsList = MutableLiveData<List<String>>()
    var arrListClients = ArrayList<String>()
    var arrListMasterDisplays = ArrayList<String>()
    var masterDisplay = 1
    @Volatile private var isRunning = false
    private var serverThread: Thread? = null
    private var cleanupHandler: Handler? = null
    private val CLEANUP_INTERVAL = 60_000L * 15 // Run cleanup every 15 min

    init {
        connectedClientsList.value = emptyList()
    }

    fun start() {
        if (isRunning) return

        isRunning = true

        // Start periodic cleanup of stale clients
        startPeriodicCleanup()

        serverThread = Thread {
            try {
                // Bind to ALL interfaces (IMPORTANT)
                serverSocket = ServerSocket(port)
//                serverSocket!!.reuseAddress = true
//                serverSocket!!.bind(InetSocketAddress("0.0.0.0", port))

                Log.i("TCPServer", "Server started on port $port")

                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    val clientIp = clientSocket.inetAddress.hostAddress

                    // Check for existing connection from same IP and clean it up
                    cleanupExistingConnectionFromIp(clientIp)

                    val clientHandler = ClientHandler(clientSocket)

                    val clientId = clientHandler.clientId
                    if (clients.containsKey(clientId)) {
                        Log.w("TCPServer", "Client already connected: $clientId")
//                        clientSocket.close()
                        continue
                    }

//                    clients[clientId] = clientHandler
                    Thread(clientHandler).start()

                    Log.i("TCPServer", "Client connected: $clientIp")
                }

            } catch (e: Exception) {
//                if (isRunning) {
                    Log.e("TCPServer", "Server error", e)
//                }
            } finally {
//                stop()
            }
        }

        serverThread!!.start()

    }

    /**
     * Cleans up any existing connection from the same IP address.
     * This ensures only one connection per device.
     */
    private fun cleanupExistingConnectionFromIp(newClientIp: String?) {
        if (newClientIp.isNullOrEmpty()) return

        val clientsToRemove = mutableListOf<String>()

        clients.forEach { (clientId, handler) ->
            try {
                val existingIp = handler.clientSocket?.inetAddress?.hostAddress
                if (existingIp == newClientIp) {
                    Log.d("TCPServer", "Found existing connection from IP $newClientIp (clientId: $clientId), marking for removal")
                    clientsToRemove.add(clientId)
                }
            } catch (e: Exception) {
                // Socket might be in bad state, mark for removal
                clientsToRemove.add(clientId)
            }
        }

        clientsToRemove.forEach { clientId ->
            try {
                val handler = clients.remove(clientId)
                handler?.close()
                WebSocketManager.removeClientHandler(clientId)
                removeFromConnectedClients(clientId)
                Log.d("TCPServer", "Removed old connection: $clientId from IP: $newClientIp")
            } catch (e: Exception) {
                Log.e("TCPServer", "Error removing old connection: ${e.message}")
            }
        }
    }

    /**
     * Starts periodic cleanup of stale/disconnected clients.
     */
    private fun startPeriodicCleanup() {
        cleanupHandler = Handler(Looper.getMainLooper())
        val cleanupRunnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    performCleanup()
                    cleanupHandler?.postDelayed(this, CLEANUP_INTERVAL)
                }
            }
        }
        cleanupHandler?.postDelayed(cleanupRunnable, CLEANUP_INTERVAL)
        Log.d("TCPServer", "Started periodic cleanup every ${CLEANUP_INTERVAL / 1000} seconds")
    }

    /**
     * Performs cleanup of stale clients from both clients map and WebSocketManager.
     */
    private fun performCleanup() {
        Log.d("TCPServer", "Running periodic cleanup... Current clients: ${clients.size}, WebSocketManager clients: ${WebSocketManager.getClientCount()}")

        // Clean up stale entries from clients map
        val staleClientIds = mutableListOf<String>()
        clients.forEach { (clientId, handler) ->
            try {
                if (handler.clientSocket?.isClosed == true || handler.clientSocket?.isConnected == false) {
                    staleClientIds.add(clientId)
                }
            } catch (e: Exception) {
                staleClientIds.add(clientId)
            }
        }

        staleClientIds.forEach { clientId ->
            clients.remove(clientId)
            removeFromConnectedClients(clientId)
        }

        // Also clean up WebSocketManager
        WebSocketManager.cleanupStaleClients()

        // Sync arrListClients with actual connected clients
        synchronized(clients) {
            arrListClients.clear()
            arrListClients.addAll(clients.keys)
            connectedClientsList.postValue(arrListClients.toList())
        }

        if (staleClientIds.isNotEmpty()) {
            Log.d("TCPServer", "Cleanup removed ${staleClientIds.size} stale clients. Remaining: ${clients.size}")
        }
    }
    fun stop() {
        isRunning = false

        // Stop periodic cleanup
        cleanupHandler?.removeCallbacksAndMessages(null)
        cleanupHandler = null

        // Close all client connections
        try {
            clients.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    // Ignore individual close errors
                }
            }
            clients.clear()
            arrListClients.clear()
            arrListMasterDisplays.clear()
            WebSocketManager.clearAll()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("TCPServer", "Error stopping server", e)
        }

        serverSocket = null
        serverThread = null
        Log.i("TCPServer", "Server stopped")
    }



    /*------------------------------------------------All Functions-----------------------------------------------------------------*/



    private fun addToConnectedClients(clientId: String) {
        synchronized(clients) {
            val currentList = connectedClientsList.value.orEmpty().toMutableList()
            // Prevent duplicate entries
            if (!currentList.contains(clientId)) {
                currentList.add(clientId)
                Log.d("TCPServer", "Added client to connected list: $clientId, total: ${currentList.size}")
            } else {
                Log.d("TCPServer", "Client $clientId already in connected list, skipping add")
            }
            connectedClientsList.postValue(currentList)
            arrListClients.clear()
            arrListClients.addAll(currentList)
        }
    }

    fun handler(delay: Long, block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            block()
        }, delay)
    }

    private fun removeFromConnectedClients(clientId: String) {
        synchronized(clients) {
            clients.remove(clientId)
            // Also remove from WebSocketManager to prevent stale entries
            WebSocketManager.removeClientHandler(clientId)
            val currentList = connectedClientsList.value.orEmpty().toMutableList()
            currentList.remove(clientId)
            connectedClientsList.postValue(currentList)
            arrListClients.remove(clientId)
            Log.d("TCPServer", "Removed client: $clientId, remaining: ${arrListClients.size}")
        }
    }

    var counter = 1




    /*------------------------------------------------All Functions-----------------------------------------------------------------*/


    //----------------------------------------------------------------------//----------------------------------------------------------------------------//


    inner class ClientHandler(val clientSocket: Socket?) : Runnable {
        private var inStream: BufferedReader? = null
        var outStream: OutputStream? = null
        var isWebSocket = false
        var clientId = UUID.randomUUID().toString() // random client id connected at start for connection
        private var counterId : String? = null
        private var ticketId : String? = null
        private var isAddedToManager = false // Track if we've been added to WebSocketManager

        init {
            try {
                // Don't add to WebSocketManager here with random UUID
                // We'll add it later when we have a meaningful client ID (counterId, displayId, etc.)
                // This prevents orphaned entries when connection fails before getting real ID
                inStream = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                outStream = clientSocket?.getOutputStream()
                Log.d("ClientHandler", "Initialized client handler with temp ID: $clientId")
            } catch (e: Exception) {
                Log.e("ClientHandler", "Error initializing client handler: ${e.message}")
                e.printStackTrace()
            }
        }

        /**
         * Registers this handler with WebSocketManager using the current clientId.
         * Should be called after we have a meaningful client ID.
         * Also handles updating from temporary UUID to real client ID.
         *
         * @param newClientId The new client ID to use (counterId, displayId, etc.)
         * @return true if registration was successful, false if this is a duplicate connection
         */
        private fun registerWithManager(newClientId: String? = null): Boolean {
            val idToUse = newClientId ?: clientId

            // Check if this is a meaningful ID (not a temp UUID) and if client already exists
            if (newClientId != null && newClientId != clientId) {
                // Check for existing active connection with same ID
                val existingHandler = WebSocketManager.getClientHandler(newClientId)
                if (existingHandler != null && existingHandler != this) {
                    // Check if existing connection is still active
                    try {
                        if (existingHandler.clientSocket?.isClosed == false &&
                            existingHandler.clientSocket?.isConnected == true) {
                            Log.w("ClientHandler", "Client $newClientId already has active connection, closing this duplicate")
                            // Close this new connection as duplicate
                            return false
                        } else {
                            // Existing connection is stale, remove it
                            Log.d("ClientHandler", "Replacing stale connection for $newClientId")
                            WebSocketManager.removeClientHandler(newClientId)
                        }
                    } catch (e: Exception) {
                        // Error checking, assume stale and remove
                        WebSocketManager.removeClientHandler(newClientId)
                    }
                }

                // Update from old temp ID to new ID
                if (isAddedToManager) {
                    WebSocketManager.updateClientId(clientId, newClientId)
                } else {
                    WebSocketManager.addClientHandler(newClientId, this)
                }
                clientId = newClientId
            } else if (!isAddedToManager) {
                // First time registration with current ID
                WebSocketManager.addClientHandler(idToUse, this)
            }

            isAddedToManager = true
            Log.d("ClientHandler", "Registered client with manager: $clientId, total: ${WebSocketManager.getClientCount()}")
            return true
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun run() {
            try {
                // Perform WebSocket handshake if applicable
                if (performHandshake()) {
                    isWebSocket = true
                    println("WebSocket handshake successful for client $clientId")

                    while (true) {
                        val message = readWebSocketFrame(clientSocket?.getInputStream() ?: return)
                        if (message.isNullOrEmpty()) break

                        println("Received WebSocket message from client $clientId: $message")

                        try {
                            println("Received WebSocket jsonObject from client $clientId: $message")
                            val jsonObject = Gson().fromJson(message, JsonObject::class.java)
                            println("here is counter id 111 $counterId")
                            when {
                                jsonObject.has(Constants.KEYPAD_COUNTER_TYPE) -> {
                                    if (jsonObject.has("displayId")){
                                        val displayId = jsonObject.get("displayId").asString
                                        println("here is display id $displayId")
                                        if (!displayId.isNullOrEmpty()) {
                                            registerWithManager(displayId)
                                            clients[clientId] = this
                                            addToConnectedClients(clientId)
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }
                                    }else {
                                        if (jsonObject.has("transaction")){
                                            println("here is msg with status")
                                            // Register with current clientId for transaction messages
                                            registerWithManager()
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }else {
                                            println("here is msg without status")
                                            counterId = jsonObject.get("counterId").asString // Fetch from database
                                            println("here is counter id $counterId")
                                            if (!counterId.isNullOrEmpty()) {
                                                // Register with counterId
                                                registerWithManager(counterId)
                                                clients[clientId] = this
                                                addToConnectedClients(clientId)
                                                messageListener.onClientConnected(clientSocket,arrListClients)
                                                Extensions.handler(400) {
                                                    messageListener.onMessageJsonReceived(jsonObject)
                                                }
                                            }
                                        }
                                    }
                                }

                                 // For Ticket Dispenser
                                jsonObject.has(Constants.TICKET_TYPE) -> {
                                    if (ticketId == null){
                                        ticketId = jsonObject.get("ticketId").asString
                                        println("here is ticketId id $ticketId")
                                        if (!ticketId.isNullOrEmpty()) {
                                            // Register with ticketId
                                            registerWithManager(ticketId)
                                            clients[clientId] = this
                                            addToConnectedClients(clientId)
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }
                                    }else {
                                        messageListener.onClientConnected(clientSocket,arrListClients)
                                        Extensions.handler(400) {
                                            messageListener.onMessageJsonReceived(jsonObject)
                                        }
                                    }
                                }
                                // For All connections
                                jsonObject.has(Constants.CONNECTION) -> {
                                    Log.d("ClientHandler", "Connection message received from client: $clientId, isAddedToManager: $isAddedToManager")
                                    // Only register if not already registered (prevents duplicate entries from heartbeat messages)
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                // For Display

                                jsonObject.has(Constants.DISPLAY_CONNECTION) -> {
                                    Log.d("ClientHandler", "Display connection received from client: $clientId, isAddedToManager: $isAddedToManager")
                                    // Only register if not already registered
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                // For Master Display
                                jsonObject.has(Constants.MASTER_DISPLAY_CONNECTION) -> {
                                    println("here is master display connection received")
                                    val masterId = "M${generateCustomMasterId()}"
                                    if (masterId.isNotEmpty()) {
                                        registerWithManager(masterId)
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                        arrListMasterDisplays.add(clientId)
                                        arrListMasterDisplays.add(clientId)
                                        messageListener.onClientConnected(clientSocket,arrListClients)
                                        Extensions.handler(400) {
                                            messageListener.onMessageJsonReceived(jsonObject)
                                        }
                                    }
                                }
                                // For Login with keypad for username
                                jsonObject.has(Constants.USERNAME) -> {
                                    // Only register once for login messages
                                    if (!isAddedToManager) {
                                        var userClientId = UUID.randomUUID().toString()
                                        registerWithManager(userClientId)
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                // for various client connection
                                else -> {
                                    // Only register if not already registered
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                            }


                        } catch (e: JsonSyntaxException) {
                            // if any issues in json or message from clients connection disconnects
                            println("Invalid JSON format received from client $clientId: $message")
                            inStream?.close()
                            outStream?.close()
                            clientSocket.close()
                        }

                         outStream?.flush() // to move to next client message or data
                    }
                } else {
                    // Handle TCP communication
                    var message: String?
                    while (inStream?.readLine().also { message = it } != null) {
                        println("Received from TCP client $clientId: $message")
                        try {
                            println("Received TCP jsonObject from client $clientId: $message")
                            val jsonObject = Gson().fromJson(message, JsonObject::class.java)
                            println("here is counter id 111 $counterId")
                            when {
                                jsonObject.has(Constants.KEYPAD_COUNTER_TYPE) -> {
                                    if (jsonObject.has("displayId")){
                                        val displayId = jsonObject.get("displayId").asString
                                        println("here is display id $displayId")
                                        if (!displayId.isNullOrEmpty()) {
                                            registerWithManager(displayId)
                                            clients[clientId] = this
                                            addToConnectedClients(clientId)
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }
                                    }else {
                                        if (jsonObject.has("transaction")){
                                            println("here is msg with status")
                                            registerWithManager()
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }else {
                                            println("here is msg without status")
                                            counterId = jsonObject.get("counterId").asString // Fetch from database
                                            println("here is counter id $counterId")
                                            if (!counterId.isNullOrEmpty()) {
                                                // Register with counterId
                                                registerWithManager(counterId)
                                                clients[clientId] = this
                                                addToConnectedClients(clientId)
                                                messageListener.onClientConnected(clientSocket,arrListClients)
                                                Extensions.handler(400) {
                                                    messageListener.onMessageJsonReceived(jsonObject)
                                                }
                                            }
                                        }
                                    }
                                }
                                jsonObject.has(Constants.TICKET_TYPE) -> {
                                    if (ticketId == null){
                                        ticketId = jsonObject.get("ticketId").asString
                                        println("here is ticketId id $ticketId")
                                        if (!ticketId.isNullOrEmpty()) {
                                            // Register with ticketId
                                            registerWithManager(ticketId)
                                            clients[clientId] = this
                                            addToConnectedClients(clientId)
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }
                                    }else {
                                        messageListener.onClientConnected(clientSocket,arrListClients)
                                        Extensions.handler(400) {
                                            messageListener.onMessageJsonReceived(jsonObject)
                                        }
                                    }
                                }

                                jsonObject.has(Constants.CONNECTION) -> {
                                    Log.d("ClientHandler", "TCP Connection message from: $clientId, isAddedToManager: $isAddedToManager")
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                jsonObject.has(Constants.DISPLAY_CONNECTION) -> {
                                    Log.d("ClientHandler", "TCP Display connection from: $clientId, isAddedToManager: $isAddedToManager")
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                jsonObject.has(Constants.MASTER_DISPLAY_CONNECTION) -> {
                                    Log.d("ClientHandler", "TCP Master display connection from: $clientId, isAddedToManager: $isAddedToManager")
                                    if (!isAddedToManager) {
                                        val masterId = "M${generateCustomMasterId()}"
                                        if (masterId.isNotEmpty()) {
                                            registerWithManager(masterId)
                                            clients[clientId] = this
                                            addToConnectedClients(clientId)
                                        }
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                jsonObject.has(Constants.USERNAME) -> {
                                    Log.d("ClientHandler", "TCP Username message from: $clientId, isAddedToManager: $isAddedToManager")
                                    if (!isAddedToManager) {
                                        var userClientId = UUID.randomUUID().toString()
                                        registerWithManager(userClientId)
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                else -> {
                                    Log.d("ClientHandler", "TCP Other message from: $clientId, isAddedToManager: $isAddedToManager")
                                    if (!isAddedToManager) {
                                        registerWithManager()
                                        clients[clientId] = this
                                    }
                                    messageListener.onClientConnected(clientSocket, arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                            }


                        } catch (e: JsonSyntaxException) {
                            println("Invalid JSON format received from client $clientId: $message")
                            inStream?.close()
                            outStream?.close()
                            clientSocket?.close()
                        }
                        outStream?.flush()

                        // Handle TCP message here as needed
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    Log.d("ClientHandler", "Cleaning up client: $clientId")
                    inStream?.close()
                    outStream?.close()
                    clientSocket?.close()
                    clients.remove(clientId)
                    // Remove from WebSocketManager - this is critical to prevent stale entries
                    WebSocketManager.removeClientHandler(clientId ?: "")
                    removeFromConnectedClients(clientId ?: "")
                    Log.d("ClientHandler", "Client disconnected and cleaned up: $clientId")
                    messageListener.onClientDisconnected(clientId)
                } catch (e: Exception) {
                    Log.e("ClientHandler", "Error during client cleanup: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        fun close() {
            try {
                clientSocket?.close()
            } catch (_: Exception) {}
        }
        // generating master display id randomly

        fun generateCustomMasterId(): String {
            return masterDisplay++.toString()
        }

        // Hand shake for websocket connection with client
        @RequiresApi(Build.VERSION_CODES.O)
        private fun performHandshake(): Boolean {
            try {
                val request = readHttpRequest()
                val webSocketKey = extractWebSocketKey(request) // only with websocket key it is possible to get client handshake
                if (webSocketKey.isNotEmpty()) {
                    val acceptKey = generateWebSocketAcceptKey(webSocketKey)
                    val response = buildHandshakeResponse(acceptKey)
                    outStream?.write(response.toByteArray())
                    outStream?.flush()
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        private fun readHttpRequest(): String {
            val requestBuilder = StringBuilder()
            var line: String?
            while (inStream?.readLine().also { line = it } != null && line != "") {
                requestBuilder.append(line).append("\r\n")
            }
            requestBuilder.append("\r\n")
            return requestBuilder.toString()
        }

        private fun extractWebSocketKey(request: String): String {
            val keyStart = request.indexOf("Sec-WebSocket-Key: ") + 19
            val keyEnd = request.indexOf("\r\n", keyStart)
            return request.substring(keyStart, keyEnd).trim()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun generateWebSocketAcceptKey(webSocketKey: String): String {
            val magicKey = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val combined = webSocketKey + magicKey
            val bytes = MessageDigest.getInstance("SHA-1").digest(combined.toByteArray())
            return Base64.getEncoder().encodeToString(bytes)
        }

        private fun buildHandshakeResponse(acceptKey: String): String {
            return """
                HTTP/1.1 101 Switching Protocols
                Upgrade: websocket
                Connection: Upgrade
                Sec-WebSocket-Accept: $acceptKey
                """.trimIndent() + "\r\n\r\n"
        }

        fun sendMessageToClient(clientId: String?, message: String) {
            val response = encodeWebSocketFrame(message)
            val recipient = clients[clientId]
            if (isWebSocket) {
                println("here is recipient 111 $recipient")
                if (clientId != null) {
                    try {
                        recipient?.outStream?.write(response)
                        recipient?.outStream?.flush()
                        println("Sent message to client $clientId: $message")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    println("Recipient Websocket client $clientId not found or not connected.")
                    // Optionally handle this scenario (e.g., notify sender)
                }
            }else {
                if (recipient != null) {
                    try {
                        // Assuming `outStream` is the OutputStream for the TCP client
                        val messageContent = message.substringAfter(":").trim()
                        recipient.outStream?.write(response)
                        recipient.outStream?.flush()
                        println("Sent message to TCP client $clientId:$message")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    println("Recipient client $clientId not found or not connected.")
                    // Optionally handle this scenario (e.g., notify sender)
                }
            }

        }

        // WebSocket frame encoding function
        private fun encodeWebSocketFrame(message: String): ByteArray {
            val rawData = message.toByteArray(Charsets.UTF_8)
            val frame = ByteArrayOutputStream()

            // FIN, RSV1, RSV2, RSV3 flags (1 byte)
            frame.write(0x81) // FIN + Opcode (0x1 for text frame)

            // Payload length
            if (rawData.size <= 125) {
                frame.write(rawData.size)
            } else if (rawData.size <= 65535) {
                frame.write(126)
                frame.write(rawData.size shr 8)
                frame.write(rawData.size and 0xFF)
            } else {
                frame.write(127)
                for (i in 7 downTo 0) {
                    frame.write((rawData.size shr (i * 8)) and 0xFF)
                }
            }

            // Payload data
            frame.write(rawData)

            return frame.toByteArray()
        }


        // WebSocket frame decoding function
        private fun readWebSocketFrame(input: InputStream): String? {
            val firstByte = input.read()
            val secondByte = input.read()
            if (firstByte == -1 || secondByte == -1) return null

            val isMasked = (secondByte and 0x80) != 0
            var payloadLength = secondByte and 0x7F

            if (payloadLength == 126) {
                payloadLength = (input.read() shl 8) or input.read()
            } else if (payloadLength == 127) {
                payloadLength = 0
                for (i in 0..7) {
                    payloadLength = (payloadLength shl 8) or ((input.read().toLong() and 0xFF).toInt())
                }
            }

            val maskingKey = if (isMasked) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(payloadLength)

            for (i in payload.indices) {
                val byte = input.read().toByte()
                payload[i] = if (isMasked) (byte xor maskingKey!![i % 4]) else byte
            }

            return String(payload, Charsets.UTF_8)
        }
    }

    // websocket manager for managing client id and updating ids
    // Uses ConcurrentHashMap for thread-safe access during concurrent client connections/disconnections
    object WebSocketManager {
        private val clientHandlers: ConcurrentHashMap<String, ClientHandler> = ConcurrentHashMap()
        // Track IP to clientId mapping to prevent duplicate connections from same device
        private val ipToClientId: ConcurrentHashMap<String, String> = ConcurrentHashMap()

        /**
         * Adds a client handler. If a client with the same ID or IP already exists,
         * the old one is closed and replaced (handles app restart/reconnection scenario).
         */
        fun addClientHandler(clientId: String, clientHandler: ClientHandler) {
            if (clientId.isEmpty()) {
                Log.w("WebSocketManager", "Attempted to add client with empty ID, ignoring")
                return
            }

            // Get IP address of new client
            val clientIp = try {
                clientHandler.clientSocket?.inetAddress?.hostAddress ?: ""
            } catch (e: Exception) {
                ""
            }

            // Check if there's already a client from this IP address
            if (clientIp.isNotEmpty()) {
                val existingClientId = ipToClientId[clientIp]
                if (existingClientId != null && existingClientId != clientId) {
                    val existingHandler = clientHandlers[existingClientId]
                    if (existingHandler != null) {
                        Log.d("WebSocketManager", "Found existing client $existingClientId from same IP $clientIp, removing old connection")
                        try {
                            existingHandler.close()
                        } catch (e: Exception) {
                            Log.e("WebSocketManager", "Error closing existing client from same IP: ${e.message}")
                        }
                        clientHandlers.remove(existingClientId)
                    }
                }
                // Update IP mapping
                ipToClientId[clientIp] = clientId
            }

            // Check if client with same ID already exists (app restart scenario)
            val existingHandler = clientHandlers[clientId]
            if (existingHandler != null && existingHandler != clientHandler) {
                Log.d("WebSocketManager", "Client $clientId already exists, closing old connection")
                try {
                    existingHandler.close()
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Error closing existing client: ${e.message}")
                }
            }

            clientHandlers[clientId] = clientHandler
            Log.d("WebSocketManager", "Added client: $clientId (IP: $clientIp), total clients: ${clientHandlers.size}")
        }

        fun getClientHandler(clientId: String): ClientHandler? {
            return clientHandlers[clientId]
        }

        /**
         * Updates client ID from old to new. If newClientId already exists,
         * closes the existing handler first (handles reconnection with same counter ID).
         */
        fun updateClientId(oldClientId: String, newClientId: String) {
            if (oldClientId == newClientId) {
                Log.d("WebSocketManager", "Old and new client IDs are same, no update needed")
                return
            }

            val handler = clientHandlers.remove(oldClientId)
            if (handler != null) {
                // Check if newClientId already has a handler (reconnection scenario)
                val existingHandler = clientHandlers[newClientId]
                if (existingHandler != null && existingHandler != handler) {
                    Log.d("WebSocketManager", "Client $newClientId already exists, closing old connection")
                    try {
                        existingHandler.close()
                    } catch (e: Exception) {
                        Log.e("WebSocketManager", "Error closing existing client: ${e.message}")
                    }
                }

                clientHandlers[newClientId] = handler
                Log.d("WebSocketManager", "Updated client ID from $oldClientId to $newClientId, total: ${clientHandlers.size}")
            } else {
                Log.w("WebSocketManager", "No handler found for oldClientId: $oldClientId")
            }
        }

        fun removeClientHandler(clientId: String) {
            val removed = clientHandlers.remove(clientId)
            if (removed != null) {
                // Also remove from IP mapping
                val clientIp = try {
                    removed.clientSocket?.inetAddress?.hostAddress ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (clientIp.isNotEmpty()) {
                    ipToClientId.remove(clientIp)
                }
                Log.d("WebSocketManager", "Removed client: $clientId, remaining clients: ${clientHandlers.size}")
            }
        }

        /**
         * Returns only active/connected clients by filtering out closed sockets.
         */
        fun getAllClients(): List<ClientHandler> {
            // Filter out clients with closed sockets
            val activeClients = clientHandlers.values.filter { handler ->
                try {
                    handler.clientSocket?.isClosed == false && handler.clientSocket?.isConnected == true
                } catch (e: Exception) {
                    false
                }
            }

            // Clean up stale entries
            val staleClientIds = clientHandlers.filter { (_, handler) ->
                try {
                    handler.clientSocket?.isClosed == true || handler.clientSocket?.isConnected == false
                } catch (e: Exception) {
                    true
                }
            }.keys

            staleClientIds.forEach { clientId ->
                clientHandlers.remove(clientId)
                Log.d("WebSocketManager", "Cleaned up stale client: $clientId")
            }

            Log.d("WebSocketManager", "getAllClients: ${activeClients.size} active, removed ${staleClientIds.size} stale")
            return activeClients
        }

        fun getClientCount(): Int {
            return clientHandlers.size
        }

        fun getAllClientIds(): List<String> {
            return clientHandlers.keys().toList()
        }

        /**
         * Cleans up all disconnected/stale client handlers.
         * Can be called periodically or before broadcasts.
         */
        fun cleanupStaleClients() {
            val staleClientIds = mutableListOf<String>()
            val staleIps = mutableListOf<String>()

            clientHandlers.forEach { (clientId, handler) ->
                try {
                    if (handler.clientSocket?.isClosed == true || handler.clientSocket?.isConnected == false) {
                        staleClientIds.add(clientId)
                        // Also track IP for cleanup
                        val ip = handler.clientSocket?.inetAddress?.hostAddress
                        if (ip != null) {
                            staleIps.add(ip)
                        }
                    }
                } catch (e: Exception) {
                    staleClientIds.add(clientId)
                }
            }

            staleClientIds.forEach { clientId ->
                clientHandlers.remove(clientId)
            }

            // Clean up IP mappings for stale clients
            staleIps.forEach { ip ->
                ipToClientId.remove(ip)
            }

            if (staleClientIds.isNotEmpty()) {
                Log.d("WebSocketManager", "Cleaned up ${staleClientIds.size} stale clients: $staleClientIds")
            }
        }

        /**
         * Gets the current count of IP to client mappings (for debugging).
         */
        fun getIpMappingCount(): Int {
            return ipToClientId.size
        }

        /**
         * Clears all clients and IP mappings. Use when restarting server.
         */
        fun clearAll() {
            clientHandlers.values.forEach { handler ->
                try {
                    handler.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            clientHandlers.clear()
            ipToClientId.clear()
            Log.d("WebSocketManager", "Cleared all clients and IP mappings")
        }
    }
}