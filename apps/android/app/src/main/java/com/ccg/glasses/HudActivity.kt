package com.ccg.glasses

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.ccg.glasses.databinding.ActivityHudBinding
import com.ccg.glasses.state.GlassStateManager
import com.ccg.glasses.transport.CardType
import com.ccg.glasses.transport.CCGWebSocketClient
import com.ccg.glasses.transport.ConnectionState
import com.ccg.glasses.voice.VoiceCommandManager
import com.ffalcon.mercury.android.sdk.base.BaseMirrorActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "HudActivity"

/**
 * Main HUD activity for the RayNeo X2 AR display.
 *
 * Extends [BaseMirrorActivity] to render identically on both waveguide
 * displays (binocular). Connects to the ccg parser via WebSocket and
 * drives the three display primitives: glyph, whisper, and card.
 *
 * Intent extras:
 *   - "url"   : WebSocket URL (e.g. "ws://192.168.1.100:9200")
 *   - "token" : Authentication token
 *
 * Temple input mapping:
 *   - Click         -> confirm focused card button
 *   - SlideForward  -> focus next button (toward Reject)
 *   - SlideBackward -> focus previous button (toward Approve)
 */
class HudActivity : BaseMirrorActivity<ActivityHudBinding>() {

    private lateinit var stateManager: GlassStateManager
    private lateinit var voiceManager: VoiceCommandManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: run {
            Log.e(TAG, "No WebSocket URL provided")
            finish()
            return
        }
        val token = intent.getStringExtra("token") ?: ""

        // Initialize transport and state manager
        val wsClient = CCGWebSocketClient()
        stateManager = GlassStateManager(wsClient)

        // Initialize voice command manager
        voiceManager = VoiceCommandManager(this) { command ->
            handleVoiceCommand(command)
        }

        // Connect to parser
        lifecycleScope.launch {
            stateManager.connect(url, token)
        }

        // Collect state updates and render on both displays
        lifecycleScope.launch {
            stateManager.state.collectLatest { state ->
                mBindingPair.updateView {
                    // Update glyph
                    glyphView.setState(state.glyph)

                    // Update card
                    val card = state.card
                    if (card != null) {
                        whisperView.hide()
                        cardView.showCard(card)
                        cardView.onApprove = { cardId ->
                            lifecycleScope.launch { stateManager.approve(cardId) }
                        }
                        cardView.onDismiss = { cardId ->
                            lifecycleScope.launch { stateManager.dismiss(cardId) }
                        }
                        // Start voice recognition only for decision cards
                        if (card.cardType == CardType.DECISION) {
                            voiceManager.start()
                        }
                    } else {
                        cardView.hideCard()
                        voiceManager.stop()
                        // Update whisper only when no card is active
                        if (state.whisper != null) {
                            whisperView.showWhisper(state.whisper)
                        } else {
                            whisperView.hide()
                        }
                    }
                }
            }
        }

        // Collect connection state for error whispers
        lifecycleScope.launch {
            stateManager.connectionState.collectLatest { connState ->
                when (connState) {
                    is ConnectionState.Error -> {
                        mBindingPair.updateView {
                            whisperView.showWhisper(connState.message, 8000)
                        }
                    }
                    is ConnectionState.Connected -> {
                        mBindingPair.updateView {
                            whisperView.showWhisper("connected", 2000)
                        }
                    }
                    else -> { /* no-op */ }
                }
            }
        }
    }

    // ── Temple touchpad input ───────────────────────────────────────────────

    /**
     * Temple tap -- confirm the focused card button.
     * No double-tap handler: zero latency on single tap.
     */
    override fun onTouchClick() {
        mBindingPair.updateView {
            if (cardView.isShowing()) {
                cardView.confirmFocused()
            }
        }
    }

    /** Temple swipe forward -- move focus to next button (Reject). */
    override fun onSlideForward() {
        mBindingPair.updateView {
            if (cardView.isShowing()) {
                cardView.focusNext()
            }
        }
    }

    /** Temple swipe backward -- move focus to previous button (Approve). */
    override fun onSlideBackward() {
        mBindingPair.updateView {
            if (cardView.isShowing()) {
                cardView.focusPrev()
            }
        }
    }

    // ── Voice commands ──────────────────────────────────────────────────────

    private fun handleVoiceCommand(command: String) {
        mBindingPair.updateView {
            if (!cardView.isShowing()) return@updateView

            when (command) {
                "approve", "yes" -> cardView.confirmFocused().also {
                    // Ensure focus is on approve first
                    cardView.focusPrev()
                    cardView.confirmFocused()
                }
                "reject", "no", "skip" -> {
                    cardView.focusNext()
                    cardView.confirmFocused()
                }
                "status" -> {
                    // Status voice command: show current glyph state as whisper
                    val state = stateManager.state.value
                    whisperView.showWhisper("state: ${state.glyph}", 4000)
                }
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stop()
        stateManager.destroy()
        lifecycleScope.launch {
            stateManager.disconnect()
        }
    }
}
