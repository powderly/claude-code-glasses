package com.ccg.glasses.state

import com.ccg.glasses.transport.ConnectionState
import com.ccg.glasses.transport.GlassStateData
import com.ccg.glasses.transport.TransportClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages the current [GlassStateData] and exposes it as a [StateFlow].
 *
 * Wraps a [TransportClient] and provides convenience methods for card actions.
 * Tracks idle duration for future power management (screen dimming, sleep).
 *
 * @param transport The transport client to collect state from
 */
class GlassStateManager(private val transport: TransportClient) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(GlassStateData())
    /** Current aggregated display state. Collect this to drive the HUD. */
    val state: StateFlow<GlassStateData> = _state.asStateFlow()

    /** Connection lifecycle state, forwarded from transport. */
    val connectionState: Flow<ConnectionState> = transport.connectionState

    /** Timestamp (SystemClock.elapsedRealtime style) of last non-idle state. */
    var lastActiveTimeMs: Long = System.currentTimeMillis()
        private set

    /** Duration in ms since last non-idle glyph state. */
    val idleDurationMs: Long
        get() = System.currentTimeMillis() - lastActiveTimeMs

    init {
        scope.launch {
            transport.stateFlow.collect { newState ->
                _state.value = newState
                if (newState.glyph != "idle") {
                    lastActiveTimeMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Connect to the ccg parser WebSocket server.
     *
     * @param url WebSocket URL, e.g. "ws://192.168.1.100:9200"
     * @param token Shared authentication token
     */
    suspend fun connect(url: String, token: String) {
        transport.connect(url, token)
    }

    /** Disconnect from the parser. */
    suspend fun disconnect() {
        transport.disconnect()
    }

    /**
     * Send an approve action for the currently displayed card.
     * @param cardId The card ID to approve
     */
    suspend fun approve(cardId: String) {
        transport.sendApprove(cardId)
    }

    /**
     * Send a dismiss/reject action for the currently displayed card.
     * @param cardId The card ID to dismiss
     */
    suspend fun dismiss(cardId: String) {
        transport.sendDismiss(cardId)
    }

    /** Cancel internal coroutines. Call when the activity is destroyed. */
    fun destroy() {
        scope.cancel()
    }
}
