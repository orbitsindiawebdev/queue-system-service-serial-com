package com.orbits.queuesystem.helper.server

import android.util.Log

/**
 * FTDI Protocol Handler for Hard Keypad Communication.
 *
 * Matches the exact protocol used in FTDITEST app.
 *
 * Frame Format (Incoming from keypad):
 *   Byte 0     = 0x40 '@'       start marker
 *   Bytes 1-4  = myAddr         4 ASCII digits e.g. "0003"
 *   Byte 5     = 0x00           separator
 *   Byte 6     = command byte   ':' (58) Connect, 'U' (85) Next, ' ' (32) Repeat, '!' (33) Call
 *   Byte 7     = statusByte     e.g. 0x80 (128)
 *   Byte 8     = dataLen        number of bytes in data field
 *   Bytes 9..  = data           [myAddr(4) + tokenNo(dataLen-4)]
 *   End        = 0x0D (CR)      possibly followed by 0x0A (LF)
 *
 * Frame Format (Outgoing Display to keypad):
 *   Byte 0     = 0x40 '@'       start marker
 *   Bytes 1-4  = targetAddr     4 ASCII digits
 *   Byte 5     = 0x00           separator
 *   Byte 6     = 0x2A '*'       Display command (42)
 *   Byte 7     = statusByte     usually 0x00
 *   Byte 8     = dataLen        length of data field
 *   Bytes 9..  = data           NPW(3) + Counter(4) + Token(3)
 *   End        = 0x0D (CR)
 */
object FTDIProtocol {

    private const val TAG = "FTDIProtocol"

    // Frame markers
    const val FRAME_START: Byte = 0x40  // '@'
    const val FRAME_END: Byte = 0x0D    // CR (Carriage Return)
    const val SEPARATOR: Byte = 0x00    // Separator after address

    // Command bytes (matching FTDITEST QueueCommand.kt)
    const val CMD_CONNECT: Byte = 58    // ':' Chr(58) - Connect Me
    const val CMD_NEXT: Byte = 85       // 'U' Chr(85) - Next token
    const val CMD_REPEAT: Byte = 32     // ' ' Chr(32) - Repeat token
    const val CMD_DIRECT_CALL: Byte = 33 // '!' Chr(33) - Direct call specific token
    const val CMD_DISPLAY: Byte = 42    // '*' Chr(42) - Display data on keypad
    const val CMD_COUNTER_NPW: Byte = 44 // ',' Chr(44) - Counter NPW
    const val CMD_MY_NPW: Byte = 57     // '9' Chr(57) - My NPW

    // Frame offsets
    private const val OFF_START = 0
    private const val OFF_ADDR = 1
    private const val OFF_SEP = 5
    private const val OFF_CMD = 6
    private const val OFF_STATUS = 7
    private const val OFF_DATALEN = 8
    private const val OFF_DATA = 9
    private const val ADDR_LEN = 4
    private const val MIN_FRAME = 8

    /**
     * Parsed command from incoming FTDI frame.
     */
    sealed class ParsedCommand {
        data class Connect(val address: String, val deviceType: Int, val statusByte: Int) : ParsedCommand()
        data class Next(val address: String, val statusByte: Int, val serviceNo: String, val priority: String) : ParsedCommand()
        data class Repeat(val address: String, val statusByte: Int, val tokenNo: String) : ParsedCommand()
        data class DirectCall(val address: String, val statusByte: Int, val tokenNo: String) : ParsedCommand()
        data class Display(val address: String, val npw: String, val counter: String, val token: String) : ParsedCommand()
        data class Unknown(val address: String, val command: Byte, val rawHex: String) : ParsedCommand()
        data class RawData(val hex: String, val ascii: String) : ParsedCommand()
    }

    /**
     * Convert address to counter ID.
     * Preserves the full address format including leading zeros.
     * Address "0008" -> Counter ID "0008"
     */
    fun toCounterId(address: String): String {
        return try {
            // Preserve the address as-is, just ensure it's 4 digits
            address.padStart(4, '0').take(4)
        } catch (e: Exception) {
            "0001"
        }
    }

    /**
     * Convert counter ID to FTDI address format.
     * Ensures 4-digit format with leading zeros.
     * Counter ID "8" -> Address "0008"
     * Counter ID "0008" -> Address "0008"
     */
    fun toFtdiAddress(counterId: String): String {
        return try {
            counterId.padStart(4, '0').take(4)
        } catch (e: Exception) {
            "0001"
        }
    }

    /**
     * Convert counter ID to display format.
     * Takes last 2 digits and puts them first, then appends "00".
     * Example: "0008" -> last 2 digits "08" -> "0800"
     * Example: "0012" -> last 2 digits "12" -> "1200"
     */
    fun toDisplayCounter(counterId: String): String {
        val padded = counterId.padStart(4, '0').take(4)
        val lastTwo = padded.takeLast(2)  // Get last 2 digits
        return lastTwo + "00"              // Append "00"
    }

    /**
     * Build a display frame to send to keypad.
     * Format: @[displayAddr][0x00][0x2A][status][dataLen][NPW(3)+Counter(4)+Token(3)][0x0D]
     *
     * Note: Both address and counter are transformed for display - last 2 digits first, then "00"
     * Example: address/counter "0008" becomes "0800" in the frame
     */
    fun buildDisplayFrame(address: String, npw: String, counter: String, token: String, statusByte: Int = 0): ByteArray {
        val displayAddr = toDisplayCounter(address)     // Transform address for display (0008 -> 0800)
        val npwPad = npw.padStart(3, '0').take(3)
        val displayCounter = toDisplayCounter(counter)  // Transform counter for display
        val tokenPad = token.padStart(3, '0').take(3)

        val data = npwPad + displayCounter + tokenPad  // Total 10 bytes
        val dataLen = data.length

        val buf = mutableListOf<Byte>()
        buf.add(FRAME_START)                        // '@' start marker
        buf.addAll(displayAddr.toAsciiBytes())      // 4-digit display address
        buf.add(SEPARATOR)                          // 0x00 separator
        buf.add(CMD_DISPLAY)                        // '*' Chr(42) Display
        buf.add(statusByte.toByte())                // status byte
        buf.add(dataLen.toByte())                   // data length
        buf.addAll(data.toAsciiBytes())             // NPW + DisplayCounter + Token
        buf.add(FRAME_END)                          // CR end marker

        val frame = buf.toByteArray()
        Log.d(TAG, "Built DISPLAY frame: ${frame.toHexString()}")
        Log.d(TAG, "  -> addr=$address->$displayAddr, npw=$npwPad, counter=$counter->$displayCounter, token=$tokenPad")
        return frame
    }

    /**
     * Build CounterNPW frame (response with multiple counter NPW entries).
     *
     * Note: Both address and counter are transformed for display - last 2 digits first, then "00"
     * Example: address/counter "0008" becomes "0800" in the frame
     */
    fun buildCounterNPWFrame(address: String, entries: List<Pair<String, String>>, statusByte: Int = 0): ByteArray {
        val displayAddr = toDisplayCounter(address)  // Transform address for display

        // Each entry: NPW(3) + DisplayCounter(4) + "000" = 10 chars
        val dataBuilder = StringBuilder()
        entries.forEach { (npw, counter) ->
            dataBuilder.append(npw.padStart(3, '0').take(3))
            dataBuilder.append(toDisplayCounter(counter))  // Transform counter for display
            dataBuilder.append("000")
        }
        val data = dataBuilder.toString()

        val buf = mutableListOf<Byte>()
        buf.add(FRAME_START)
        buf.addAll(displayAddr.toAsciiBytes())
        buf.add(SEPARATOR)
        buf.add(CMD_COUNTER_NPW)
        buf.add(statusByte.toByte())
        buf.add(data.length.toByte())
        buf.addAll(data.toAsciiBytes())
        buf.add(FRAME_END)

        return buf.toByteArray()
    }

    /**
     * Build MyNPW frame.
     * Format: @[displayAddr][0x00][0x39='9'][status][dataLen][NPW(3)+Counter(4)+"000"][0x0D]
     *
     * Note: Both address and counter are transformed for display - last 2 digits first, then "00"
     * Example: address/counter "0008" becomes "0800" in the frame
     */
    fun buildMyNPWFrame(address: String, npw: String, counter: String, statusByte: Int = 0): ByteArray {
        val displayAddr = toDisplayCounter(address)     // Transform address for display (0008 -> 0800)
        val npwPad = npw.padStart(3, '0').take(3)
        val displayCounter = toDisplayCounter(counter)  // Transform counter for display
        val data = npwPad + displayCounter + "000"

        val buf = mutableListOf<Byte>()
        buf.add(FRAME_START)
        buf.addAll(displayAddr.toAsciiBytes())
        buf.add(SEPARATOR)
        buf.add(CMD_COUNTER_NPW)                         // ',' Chr(44) - Counter NPW
        buf.add(statusByte.toByte())
        buf.add(data.length.toByte())
        buf.addAll(data.toAsciiBytes())
        buf.add(FRAME_END)

        val frame = buf.toByteArray()
        Log.d(TAG, "Built MY_NPW frame: ${frame.toHexString()}")
        Log.d(TAG, "  -> addr=$address->$displayAddr, npw=$npwPad, counter=$counter->$displayCounter, cmd=0x${"%02X".format(CMD_MY_NPW)}('9')")
        return frame
    }

    /**
     * Build AllServices text response.
     */
    fun buildAllServicesResponse(services: Map<Int, String>): ByteArray {
        val sb = StringBuilder("AllServices:")
        services.forEach { (id, name) ->
            sb.append("$id $name:")
        }
        sb.append("\r")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * Build MyInfo text response.
     */
    fun buildMyInfoResponse(address: String, counter: String, serviceNo: String): ByteArray {
        val response = "MyInfo:\"@$address;${counter.padStart(4, '0')}${serviceNo.padStart(2, '0')}\"\r"
        return response.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Parse an incoming FTDI frame.
     */
    fun parseIncomingFrame(raw: ByteArray): ParsedCommand {
        if (raw.isEmpty()) return ParsedCommand.RawData("", "")

        val hex = raw.toHexString()
        val ascii = raw.toSafeAscii()

        Log.d(TAG, "┌─ RX ${raw.size} bytes ─────────────────────────")
        Log.d(TAG, "│ HEX   : $hex")
        Log.d(TAG, "│ ASCII : $ascii")

        // Check for text-based responses
        val text = String(raw, Charsets.US_ASCII).trim()
        if (text.startsWith("AllServices:") || text.startsWith("MyInfo:") || text.startsWith("MWT:")) {
            Log.d(TAG, "└─ TEXT response (not a command)")
            return ParsedCommand.RawData(hex, ascii)
        }

        // Check minimum frame size and start marker
        if (raw.size < MIN_FRAME || raw[OFF_START] != FRAME_START) {
            Log.d(TAG, "└─ RAW (unrecognised frame)")
            return ParsedCommand.RawData(hex, ascii)
        }

        return try {
            parseBinaryFrame(hex, raw)
        } catch (e: Exception) {
            Log.e(TAG, "└─ PARSE ERROR: ${e.message}", e)
            ParsedCommand.RawData(hex, ascii)
        }
    }

    private fun parseBinaryFrame(hex: String, raw: ByteArray): ParsedCommand {
        val addr = String(raw, OFF_ADDR, ADDR_LEN, Charsets.US_ASCII)

        // Check for separator byte
        val cmdOffset = if (raw.size > OFF_SEP && raw[OFF_SEP] == SEPARATOR) {
            OFF_CMD  // Standard frame with separator
        } else {
            OFF_SEP  // Old format without separator
        }

        val cmdByte = raw[cmdOffset].toInt() and 0xFF
        val statusByte = if (raw.size > cmdOffset + 1) raw[cmdOffset + 1].toInt() and 0xFF else 0
        val dataLen = if (raw.size > cmdOffset + 2) raw[cmdOffset + 2].toInt() and 0xFF else 0
        val dataStart = cmdOffset + 3
        val dataEnd = minOf(dataStart + dataLen, raw.size)
        val dataBytes = if (raw.size > dataStart) raw.sliceArray(dataStart until dataEnd) else byteArrayOf()
        val dataStr = String(dataBytes, Charsets.US_ASCII)

        Log.d(TAG, "│ FRAME : addr=$addr  cmd=0x${"%02X".format(cmdByte)}('${
            if (cmdByte in 32..126) cmdByte.toChar() else '.'
        }')  status=0x${"%02X".format(statusByte)}  dataLen=$dataLen")
        Log.d(TAG, "│ DATA  : ${dataBytes.toHexString()}  \"$dataStr\"")

        return when (cmdByte) {
            CMD_CONNECT.toInt() and 0xFF -> {
                // Connect: deviceType is in the byte before cmd in some formats
                val deviceType = if (raw.size > OFF_SEP) raw[OFF_SEP].toInt() and 0xFF else 0
                Log.d(TAG, "└─ CONNECT  deviceType=$deviceType")
                ParsedCommand.Connect(addr, deviceType, statusByte)
            }

            CMD_NEXT.toInt() and 0xFF -> {
                // Next: data = addr(4) + 0x00*4 + serviceNo + priority + "000"
                // Token extraction: after embedded address (first 4 chars)
                val embeddedAddr = dataStr.take(ADDR_LEN)
                val restData = dataStr.drop(ADDR_LEN).replace("\u0000", "") // Remove null bytes
                // serviceNo and priority parsing
                val serviceNo = if (restData.length >= 1) restData.take(1) else "1"
                val priority = if (restData.length >= 4) restData.substring(1, 4) else "253"
                Log.d(TAG, "└─ NEXT  serviceNo=$serviceNo  priority=$priority")
                ParsedCommand.Next(addr, statusByte, serviceNo, priority)
            }

            CMD_REPEAT.toInt() and 0xFF -> {
                // Repeat: data = addr(4) + tokenNo + "000"
                val tokenNo = if (dataStr.length > ADDR_LEN) {
                    dataStr.substring(ADDR_LEN).removeSuffix("000").trim()
                } else ""
                Log.d(TAG, "└─ REPEAT  token=\"$tokenNo\"")
                ParsedCommand.Repeat(addr, statusByte, tokenNo)
            }

            CMD_DIRECT_CALL.toInt() and 0xFF -> {
                // DirectCall: data = addr(4) + tokenNo + "000"
                val tokenNo = if (dataStr.length > ADDR_LEN) {
                    dataStr.substring(ADDR_LEN).removeSuffix("000").trim()
                } else ""
                Log.d(TAG, "└─ DIRECT_CALL  token=\"$tokenNo\"")
                ParsedCommand.DirectCall(addr, statusByte, tokenNo)
            }

            CMD_DISPLAY.toInt() and 0xFF -> {
                // Display: data = NPW(3) + Counter(4) + Token(3)
                val npw = dataStr.take(3)
                val counter = dataStr.drop(3).take(4)
                val token = dataStr.drop(7).take(3)
                Log.d(TAG, "└─ DISPLAY  npw=$npw  counter=$counter  token=$token")
                ParsedCommand.Display(addr, npw, counter, token)
            }

            else -> {
                Log.d(TAG, "└─ UNKNOWN cmd byte 0x${"%02X".format(cmdByte)}")
                ParsedCommand.Unknown(addr, cmdByte.toByte(), hex)
            }
        }
    }

    /**
     * Validate if a byte array contains a complete frame.
     */
    fun isCompleteFrame(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val startIndex = data.indexOfByte(FRAME_START)
        if (startIndex == -1) return false
        val endIndex = data.indexOfByte(FRAME_END, startIndex)
        return endIndex > startIndex
    }

    /**
     * Extract a complete frame from a buffer.
     * Returns the frame and remaining bytes, or null if no complete frame.
     */
    fun extractFrame(buffer: ByteArray): Pair<ByteArray?, ByteArray> {
        val startIndex = buffer.indexOfByte(FRAME_START)
        if (startIndex == -1) {
            return Pair(null, ByteArray(0))
        }

        val endIndex = buffer.indexOfByte(FRAME_END, startIndex)
        if (endIndex == -1) {
            // Keep data from start marker onwards
            return Pair(null, buffer.sliceArray(startIndex until buffer.size))
        }

        val frame = buffer.sliceArray(startIndex..endIndex)
        val remaining = if (endIndex + 1 < buffer.size) {
            // Skip optional LF after CR
            val nextStart = if (buffer.size > endIndex + 1 && buffer[endIndex + 1] == 0x0A.toByte()) {
                endIndex + 2
            } else {
                endIndex + 1
            }
            if (nextStart < buffer.size) buffer.sliceArray(nextStart until buffer.size) else ByteArray(0)
        } else {
            ByteArray(0)
        }

        return Pair(frame, remaining)
    }

    // Extension functions
    private fun ByteArray.indexOfByte(element: Byte, startIndex: Int = 0): Int {
        for (i in startIndex until size) {
            if (this[i] == element) return i
        }
        return -1
    }

    fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toSafeAscii(): String = map { b ->
        val c = b.toInt() and 0xFF
        if (c in 32..126) c.toChar() else '.'
    }.joinToString("")

    private fun String.toAsciiBytes(): List<Byte> =
        this.toByteArray(Charsets.US_ASCII).toList()
}
