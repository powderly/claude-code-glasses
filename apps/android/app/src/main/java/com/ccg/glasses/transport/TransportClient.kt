package com.ccg.glasses.transport

import kotlinx.coroutines.flow.Flow

// ── Data models ─────────────────────────────────────────────────────────────

/**
 * Card type discriminator. Decision cards pause the agent and require
 * approve/reject. Update cards are informational and auto-dismiss.
 */
enum class CardType {
    DECISION,
    UPDATE
}

/**
 * Mirrors the @ccg/core CardEvent type. Represents an actionable card
 * displayed on the HUD.
 *
 * @param id Unique identifier for this card instance
 * @param cardType Whether this is a decision (blocking) or update (informational) card
 * @param message The message to display, max 2 lines
 * @param confirmLabel Label for the confirm button (e.g. "Approve", "Got it")
 * @param dismissLabel Label for the dismiss button (e.g. "Reject"), null for update cards
 * @param timeoutMs Auto-dismiss timeout in ms. 0 means no timeout (decision cards)
 */
data class CardData(
    val id: String,
    val cardType: CardType,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String?,
    val timeoutMs: Long
)

/**
 * Aggregated display state matching the @ccg/core GlassState type.
 * This is the single object that drives all three display primitives.
 *
 * @param glyph Current glyph state name: idle, thinking, running, awaiting, done, error
 * @param whisper Current whisper text, or null if nothing to show
 * @param card Current card, or null if no card is active
 */
data class GlassStateData(
    val glyph: String = "idle",
    val whisper: String? = null,
    val card: CardData? = null
)

/**
 * Connection lifecycle states for the transport layer.
 */
sealed class ConnectionState {
    /** Not connected, no reconnect in progress. */
    data object Disconnected : ConnectionState()

    /** Attempting to establish connection. */
    data object Connecting : ConnectionState()

    /** Connected and receiving state updates. */
    data object Connected : ConnectionState()

    /** Connection failed or lost. [message] describes the error. */
    data class Error(val message: String) : ConnectionState()
}

// ── Transport interface ─────────────────────────────────────────────────────

/**
 * Abstract transport between the ccg parser (laptop) and the renderer (glasses).
 * The WebSocket implementation is the primary transport for v0.1.
 */
interface TransportClient {

    /** Emits [GlassStateData] whenever the parser pushes a new state. */
    val stateFlow: Flow<GlassStateData>

    /** Emits [ConnectionState] on every connection lifecycle change. */
    val connectionState: Flow<ConnectionState>

    /**
     * Open a connection to the ccg WebSocket server.
     *
     * @param url WebSocket URL, e.g. "ws://192.168.1.100:9200"
     * @param token Shared secret for authentication
     */
    suspend fun connect(url: String, token: String)

    /** Cleanly close the connection. No reconnect will be attempted. */
    suspend fun disconnect()

    /**
     * Send an approve action for the given card.
     * @param cardId The [CardData.id] of the card being approved
     */
    suspend fun sendApprove(cardId: String)

    /**
     * Send a dismiss/reject action for the given card.
     * @param cardId The [CardData.id] of the card being dismissed
     */
    suspend fun sendDismiss(cardId: String)
}
