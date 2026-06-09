package com.hyperionflatsender.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "HyperionJsonClient"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000

/**
 * Minimal client for Hyperion's JSON-RPC API (default port 19444) — used ONLY to set the colour
 * "adjustment" values live during calibration. This is a SEPARATE concern from the FlatBuffers image
 * client: different port, and messages are NEWLINE-delimited JSON (not the 4-byte length-prefix the
 * FlatBuffers socket uses), so it needs its own socket.
 *
 * Adjustments are applied in-memory by Hyperion and are NOT persisted (they revert on Hyperion
 * restart) — exactly what you want for a non-destructive calibration helper.
 *
 * Auth: if a token is supplied to [connect] it logs in (Hyperion `authorize`/`login`) right after
 * the socket opens; the per-connection authorisation then covers every command for the socket's
 * lifetime. With no token it relies on Hyperion's local-network bypass ("Local API Authentication"
 * disabled + a same-subnet/loopback peer). A failed login fails the connect with the server's reason
 * in [lastError]. All calls are main-safe (run on Dispatchers.IO).
 */
class HyperionJsonClient {

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: BufferedReader? = null
    private var tan = 0

    /** Human-readable reason for the most recent connect/send failure, or null after a success.
     *  Surfaced in the Calibration UI so a failure says *why* (e.g. "Connection refused",
     *  "No Authorization") instead of a generic "not reachable". */
    @Volatile var lastError: String? = null
        private set

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(host: String, port: Int, token: String? = null): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.soTimeout = READ_TIMEOUT_MS
            socket = s
            writer = s.getOutputStream().bufferedWriter()
            reader = s.getInputStream().bufferedReader()
            lastError = null
            Log.i(TAG, "JSON-RPC connected to $host:$port")
            // Authenticate before anything else if a token is configured (needed off Hyperion's local
            // subnet). On failure lastError is set by login(); fail the whole connect.
            if (!token.isNullOrBlank() && !login(token)) {
                Log.w(TAG, "JSON-RPC login failed: $lastError")
                disconnect()
                return@withContext false
            }
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "JSON-RPC connect failed: $lastError")
            disconnect()
            false
        }
    }

    /** Sends Hyperion's `authorize`/`login` with [token] and returns whether it was accepted. Runs on
     *  the caller's thread (already Dispatchers.IO inside [connect]). */
    private fun login(token: String): Boolean {
        val w = writer ?: return false
        val r = reader ?: return false
        return try {
            val msg = JSONObject()
                .put("command", "authorize")
                .put("subcommand", "login")
                .put("token", token)
                .put("tan", ++tan)
            w.write(msg.toString()); w.write("\n"); w.flush()

            val line = r.readLine() ?: run {
                lastError = "connection closed during login"
                return false
            }
            val obj = JSONObject(line)
            val ok = obj.optBoolean("success", false)
            if (!ok) lastError = obj.optString("error", "").ifBlank { "token rejected" }
            ok
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Sends an `adjustment` command carrying only [fields] (so unspecified values are left untouched).
     * Float values are sent as JSON numbers; an IntArray of 3 becomes a per-channel [r,g,b] array.
     * Returns the parsed reply, or null on I/O error.
     */
    suspend fun setAdjustment(fields: Map<String, Any>): Reply? = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext null
        val r = reader ?: return@withContext null
        try {
            val adjustment = JSONObject()
            for ((key, value) in fields) adjustment.put(key, toJson(value))
            val msg = JSONObject()
                .put("command", "adjustment")
                .put("adjustment", adjustment)
                .put("tan", ++tan)
            w.write(msg.toString()); w.write("\n"); w.flush()

            val line = r.readLine() ?: run {
                lastError = "connection closed by server"
                return@withContext null
            }
            val obj = JSONObject(line)
            val reply = Reply(obj.optBoolean("success", false), obj.optString("error", "").ifBlank { null })
            lastError = if (reply.success) null else (reply.error ?: "rejected by Hyperion")
            reply
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "JSON-RPC adjustment failed: $lastError")
            null
        }
    }

    private fun toJson(value: Any): Any = when (value) {
        is Float -> value.toDouble()           // JSONObject has no Float overload; widen to double
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        else -> value
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
    }

    data class Reply(val success: Boolean, val error: String?)
}
