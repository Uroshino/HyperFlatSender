package com.hyperflatsender.network

import android.util.Log
import com.hyperflatsender.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "HyperionClient"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val WRITE_BUFFER_SIZE = 65_536

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class HyperionClient(private val scope: CoroutineScope) {

    private val flatbuffers = HyperionFlatbuffers()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: BufferedOutputStream? = null
    @Volatile private var inputStream: InputStream? = null
    private var readerJob: Job? = null

    suspend fun connect(settings: Settings): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(settings.serverIp, settings.serverPort), CONNECT_TIMEOUT_MS)
            // Low-latency LAN streaming tuning. TCP_NODELAY disables Nagle so each flushed frame goes
            // out immediately instead of waiting on the prior segment's (delayed) ACK — ambient light
            // must track the screen. keepAlive surfaces a silently-dropped Wi-Fi/NAT mapping as an
            // IOException instead of a frozen-but-Connected socket; a send buffer ≥ the ~163KB worst
            // case keeps a flushed frame from blocking the sender mid-write. Options are best-effort.
            try { sock.tcpNoDelay = true } catch (_: java.net.SocketException) {}
            try { sock.keepAlive = true } catch (_: java.net.SocketException) {}
            try { sock.sendBufferSize = 192 * 1024 } catch (_: java.net.SocketException) {}
            socket = sock
            outputStream = BufferedOutputStream(sock.getOutputStream(), WRITE_BUFFER_SIZE)
            inputStream = sock.getInputStream()

            val registerMsg = flatbuffers.buildRegisterMessage(settings.origin, settings.priority)
            outputStream!!.write(registerMsg)
            outputStream!!.flush()

            val reply = readMessage(inputStream!!)
            val parsed = if (reply != null) flatbuffers.parseReply(reply) else null
            if (parsed == null || parsed.registered == -1) {
                Log.w(TAG, "Register failed: error=${parsed?.error}")
                closeSocket()
                _connectionState.value = ConnectionState.Error("Register rejected: ${parsed?.error ?: "no reply"}")
                return@withContext false
            }

            Log.i(TAG, "Connected, registered at priority ${parsed.registered}")
            startReaderLoop()
            _connectionState.value = ConnectionState.Connected
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            closeSocket()
            _connectionState.value = ConnectionState.Error(e.message ?: "connect failed")
            false
        }
    }

    fun sendFrame(frameBytes: ByteArray) {
        val out = outputStream ?: return
        try {
            out.write(frameBytes)
            // One frame == one write == one flush. The conflated channel + IO-thread sender mean a
            // flush can't back-pressure capture, and flushing now removes up to a frame-interval of
            // buffered display-to-light latency (this was previously gated to every 100ms).
            out.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
            closeSocket()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        closeSocket()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun startReaderLoop() {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val input = inputStream ?: return@launch
            try {
                while (true) {
                    val data = readMessage(input) ?: break
                    val reply = flatbuffers.parseReply(data)
                    if (reply?.error != null) {
                        Log.w(TAG, "Server error: ${reply.error}")
                    }
                }
            } catch (_: Exception) {
                // socket closed — normal on disconnect
            }
            if (_connectionState.value == ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun readMessage(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header)) return null
        val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (len <= 0 || len > 1_048_576) return null
        val payload = ByteArray(len)
        return if (readFully(input, payload)) payload else null
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }
}
