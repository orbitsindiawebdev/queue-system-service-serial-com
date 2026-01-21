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

    init {
        connectedClientsList.value = emptyList()
    }

    fun start() {
        if (isRunning) return

        isRunning = true

        serverThread = Thread {
            try {
                // Bind to ALL interfaces (IMPORTANT)
                serverSocket = ServerSocket(port)
//                serverSocket!!.reuseAddress = true
//                serverSocket!!.bind(InetSocketAddress("0.0.0.0", port))

                Log.i("TCPServer", "Server started on port $port")

                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    val clientHandler = ClientHandler(clientSocket)

                    val clientId = clientHandler.clientId
                    if (clients.containsKey(clientId)) {
                        Log.w("TCPServer", "Client already connected: $clientId")
//                        clientSocket.close()
                        continue
                    }

//                    clients[clientId] = clientHandler
                    Thread(clientHandler).start()

                    Log.i("TCPServer", "Client connected: ${clientSocket.inetAddress.hostAddress}")
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
    fun stop() {
        isRunning = false

//        try {
//            clients.values.forEach { it.close() } //doubt
//            clients.clear()
            serverSocket?.close()
//        } catch (e: Exception) {
//            Log.e("TCPServer", "Error stopping server", e)
//        }

        serverSocket = null
        serverThread = null
        Log.i("TCPServer", "Server stopped")
    }



    /*------------------------------------------------All Functions-----------------------------------------------------------------*/



    private fun addToConnectedClients(clientId: String) {
        synchronized(clients) {
            val currentList = connectedClientsList.value.orEmpty().toMutableList()
            currentList.add(clientId)
            println("here is list 1111 $currentList")
            connectedClientsList.postValue(currentList)
            arrListClients.clear()
            arrListClients.addAll(currentList)
            println("here is list new 111 ${arrListClients}")
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
            val currentList = connectedClientsList.value.orEmpty().toMutableList()
            currentList.remove(clientId)
            connectedClientsList.postValue(currentList)
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

        init {
            try {
                WebSocketManager.addClientHandler(clientId ?: "", this)
                inStream = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))
                outStream = clientSocket?.getOutputStream()
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                                            WebSocketManager.updateClientId(clientId, displayId)
                                            clientId = displayId
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
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }else {
                                            println("here is msg without status")
                                            counterId = jsonObject.get("counterId").asString // Fetch from database
                                            println("here is counter id $counterId")
                                            if (!counterId.isNullOrEmpty()) {
                                                // Update client ID in WebSocketManager
                                                WebSocketManager.updateClientId(
                                                    clientId ?: "",
                                                    counterId ?: ""
                                                )
                                                clientId = counterId ?: ""
                                                clients[clientId ?: ""] = this
                                                addToConnectedClients(clientId ?: "")
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
                                            // Update client ID in WebSocketManager
                                            WebSocketManager.updateClientId(
                                                clientId,
                                                ticketId ?: ""
                                            )
                                            clientId = ticketId ?: ""
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
                                    println("here is connection received")
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                // For Display

                                jsonObject.has(Constants.DISPLAY_CONNECTION) -> {
                                    println("here is connection received")
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                // For Master Display
                                jsonObject.has(Constants.MASTER_DISPLAY_CONNECTION) -> {
                                    println("here is connection received")
                                    val masterId = "M${generateCustomMasterId()}"
                                    if (masterId.isNotEmpty()) {
                                        WebSocketManager.updateClientId(
                                            clientId,
                                            masterId
                                        )
                                        clientId = masterId
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
                                    var userClientId = UUID.randomUUID().toString()
                                    WebSocketManager.updateClientId(clientId, userClientId)
                                    clientId = userClientId
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                // for various client connection
                                else -> {
                                    clients[clientId] = this
                                    messageListener.onClientConnected(clientSocket,arrListClients)
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
                                            WebSocketManager.updateClientId(clientId, displayId)
                                            clientId = displayId
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
                                            messageListener.onClientConnected(clientSocket,arrListClients)
                                            Extensions.handler(400) {
                                                messageListener.onMessageJsonReceived(jsonObject)
                                            }
                                        }else {
                                            println("here is msg without status")
                                            counterId = jsonObject.get("counterId").asString // Fetch from database
                                            println("here is counter id $counterId")
                                            if (!counterId.isNullOrEmpty()) {
                                                // Update client ID in WebSocketManager
                                                WebSocketManager.updateClientId(
                                                    clientId ?: "",
                                                    counterId ?: ""
                                                )
                                                clientId = counterId ?: ""
                                                clients[clientId ?: ""] = this
                                                addToConnectedClients(clientId ?: "")
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
                                            // Update client ID in WebSocketManager
                                            WebSocketManager.updateClientId(
                                                clientId,
                                                ticketId ?: ""
                                            )
                                            clientId = ticketId ?: ""
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
                                    println("here is connection received")
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                jsonObject.has(Constants.DISPLAY_CONNECTION) -> {
                                    println("here is connection received")
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }

                                jsonObject.has(Constants.MASTER_DISPLAY_CONNECTION) -> {
                                    println("here is connection received")
                                    val masterId = "M${generateCustomMasterId()}"
                                    if (masterId.isNotEmpty()) {
                                        WebSocketManager.updateClientId(
                                            clientId,
                                            masterId
                                        )
                                        clientId = masterId
                                        clients[clientId] = this
                                        addToConnectedClients(clientId)
                                        messageListener.onClientConnected(clientSocket,arrListClients)
                                        Extensions.handler(400) {
                                            messageListener.onMessageJsonReceived(jsonObject)
                                        }
                                    }
                                }

                                jsonObject.has(Constants.USERNAME) -> {
                                    var userClientId = UUID.randomUUID().toString()
                                    WebSocketManager.updateClientId(clientId, userClientId)
                                    clientId = userClientId
                                    clients[clientId] = this
                                    addToConnectedClients(clientId)
                                    messageListener.onClientConnected(clientSocket,arrListClients)
                                    Extensions.handler(400) {
                                        messageListener.onMessageJsonReceived(jsonObject)
                                    }
                                }
                                else -> {
                                    clients[clientId] = this
                                    messageListener.onClientConnected(clientSocket,arrListClients)
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
                    inStream?.close()
                    outStream?.close()
                    clientSocket?.close()
                    clients.remove(clientId)
                    removeFromConnectedClients(clientId ?: "")
                    println("Client disconnected: $clientId")
                    messageListener.onClientDisconnected(clientId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
//                finally {
//                    close()
//                }
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

        fun addClientHandler(clientId: String, clientHandler: ClientHandler) {
            clientHandlers[clientId] = clientHandler
            Log.d("WebSocketManager", "Added client: $clientId, total clients: ${clientHandlers.size}")
        }

        fun getClientHandler(clientId: String): ClientHandler? {
            return clientHandlers[clientId]
        }

        fun updateClientId(oldClientId: String, newClientId: String) {
            val handler = clientHandlers.remove(oldClientId)
            if (handler != null) {
                clientHandlers[newClientId] = handler
                Log.d("WebSocketManager", "Updated client ID from $oldClientId to $newClientId")
            }
        }

        fun removeClientHandler(clientId: String) {
            clientHandlers.remove(clientId)
            Log.d("WebSocketManager", "Removed client: $clientId, remaining clients: ${clientHandlers.size}")
        }

        fun getAllClients(): List<ClientHandler> {
            // Return a snapshot copy of current clients for safe iteration
            val clients = clientHandlers.values.toList()
            Log.d("WebSocketManager", "getAllClients called, returning ${clients.size} clients")
            return clients
        }

        fun getClientCount(): Int {
            return clientHandlers.size
        }

        fun getAllClientIds(): List<String> {
            return clientHandlers.keys().toList()
        }
    }
}