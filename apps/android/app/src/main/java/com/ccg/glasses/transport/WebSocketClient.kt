package com.ccg.glasses.transport

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "CCGWebSocket"
private const val PING_INTERVAL_S = 30L
private const val RECONNECT_BASE_MS = 1000L
private const val RECONNECT_MAX_MS = 30000L

/**
 * OkHttp-based WebSocket transport client.
 *
 * Connects to the ccg parser's WebSocket server, authenticates with a shared
 * token, and emits [GlassStateData] for every state push. Handles automatic
 * reconnection with exponential backoff on disconnect.
 *
 * Wire protocol (JSON over WebSocket text frames):
 *
 * Client → Server:
 *   auth:    {"type":"auth","version":1,"token":"...","role":"renderer"}
 *   action:  {"type":"action","action":"approve"|"dismiss","cardId":"..."}
 *
 * Server → Client:
 *   state:   {"type":"state","glyph":"...","whisper":"..."|null,"card":{...}|null}
 */
class CCGWebSocketClient : TransportClient {

    private val _stateFlow = MutableSharedFlow<GlassStateData>(replay = 1)
    override val stateFlow: Flow<GlassStateData> = _stateFlow.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var shouldReconnect = false
    private var currentUrl: String? = null
    private var currentToken: String? = null
    private var reconnectAttempt = 0

    override suspend fun connect(url: String, token: String) {
        currentUrl = url
        currentToken = token
        shouldReconnect = true
        reconnectAttempt = 0
        openConnection(url, token)
    }

    override suspend fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun sendApprove(cardId: String) {
        sendAction("approve", cardId)
    }

    override suspend fun sendDismiss(cardId: String) {
        sendAction("dismiss", cardId)
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun openConnection(url: String, token: String) {
        _connectionState.value = ConnectionState.Connecting

        client = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $url")
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.Connected

                // Send auth handshake
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("version", 1)
                    put("token", token)
                    put("role", "renderer")
                }
                ws.send(auth.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "state" -> {
                            val state = parseState(json)
                            scope.launch { _stateFlow.emit(state) }
                        }
                        "error" -> {
                            Log.w(TAG, "Server error: ${json.optString("message")}")
                        }
                        else -> {
                            Log.d(TAG, "Unknown message type: ${json.optString("type")}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.Error("reconnecting...")
                scheduleReconnect()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Server closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $code $reason")
                if (shouldReconnect) {
                    _connectionState.value = ConnectionState.Error("reconnecting...")
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        val delayMs = (RECONNECT_BASE_MS * (1L shl reconnectAttempt.coerceAtMost(5)))
            .coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++

        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")

        scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                currentUrl?.let { url ->
                    currentToken?.let { token ->
                        openConnection(url, token)
                    }
                }
            }
        }
    }

    private fun sendAction(action: String, cardId: String) {
        val msg = JSONObject().apply {
            put("type", "action")
            put("action", action)
            put("cardId", cardId)
        }
        val sent = webSocket?.send(msg.toString()) ?: false
        if (!sent) {
            Log.w(TAG, "Failed to send action: $action for card $cardId")
        }
    }

    private fun parseState(json: JSONObject): GlassStateData {
        // State messages wrap data in a "data" field: {"type":"state","data":{...}}
        val data = json.optJSONObject("data") ?: json
        val glyph = data.optString("glyph", "idle")
        val whisper = if (data.isNull("whisper")) null else data.optString("whisper")

        val card = if (data.isNull("card")) {
            null
        } else {
            val cardJson = data.getJSONObject("card")
            CardData(
                id = cardJson.optString("id", ""),
                cardType = if (cardJson.optString("cardType") == "update") {
                    CardType.UPDATE
                } else {
                    CardType.DECISION
                },
                message = cardJson.optString("message", ""),
                confirmLabel = cardJson.optString("confirmLabel", "Approve"),
                dismissLabel = if (cardJson.isNull("dismissLabel")) null
                    else cardJson.optString("dismissLabel"),
                timeoutMs = cardJson.optLong("timeoutMs", 0)
            )
        }

        return GlassStateData(glyph = glyph, whisper = whisper, card = card)
    }
}
