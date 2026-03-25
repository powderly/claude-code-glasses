package com.ccg.glasses

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ccg.glasses.databinding.ActivityHudBinding
import com.ccg.glasses.state.GlassStateManager
import com.ccg.glasses.transport.CardType
import com.ccg.glasses.transport.CCGWebSocketClient
import com.ccg.glasses.transport.ConnectionState
import com.ccg.glasses.voice.VoiceCommandManager
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "HudActivity"

/**
 * Main HUD activity for the RayNeo X2 AR display.
 *
 * Extends [BaseMirrorActivity] for binocular rendering. Connects to the
 * ccg parser via WebSocket and drives three display primitives:
 * glyph, whisper, and card.
 */
class HudActivity : BaseMirrorActivity<ActivityHudBinding>() {

    private lateinit var stateManager: GlassStateManager
    private lateinit var voiceManager: VoiceCommandManager
    private var cardVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: run {
            Log.e(TAG, "No WebSocket URL provided")
            finish()
            return
        }
        val token = intent.getStringExtra("token") ?: ""

        val wsClient = CCGWebSocketClient()
        stateManager = GlassStateManager(wsClient)
        voiceManager = VoiceCommandManager(this) { cmd -> handleVoiceCommand(cmd) }

        // Connect
        lifecycleScope.launch {
            stateManager.connect(url, token)
        }

        // Wire card callbacks
        mBindingPair.setLeft {
            cardView.onApprove = { cardId ->
                lifecycleScope.launch { stateManager.approve(cardId) }
                clearCard()
            }
            cardView.onDismiss = { cardId ->
                lifecycleScope.launch { stateManager.dismiss(cardId) }
                clearCard()
            }
        }

        // Observe state updates
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                stateManager.state.collectLatest { state ->
                    mBindingPair.updateView {
                        glyphView.setState(state.glyph)

                        val card = state.card
                        if (card != null) {
                            whisperView.hide()
                            cardView.showCard(card)
                            if (!cardVisible) {
                                cardVisible = true
                                if (card.cardType == CardType.DECISION) {
                                    voiceManager.start()
                                }
                            }
                        } else {
                            if (cardVisible) clearCard()
                            if (state.whisper != null) {
                                whisperView.showWhisper(state.whisper)
                            } else {
                                whisperView.hide()
                            }
                        }
                    }
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
                        else -> {}
                    }
                }
            }
        }

        // Temple touchpad: route to CardView directly
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    if (!cardVisible) return@collect
                    when (action) {
                        is TempleAction.Click -> {
                            mBindingPair.updateView { cardView.confirmFocused() }
                        }
                        is TempleAction.SlideForward -> {
                            mBindingPair.updateView { cardView.focusNext() }
                        }
                        is TempleAction.SlideBackward -> {
                            mBindingPair.updateView { cardView.focusPrev() }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun clearCard() {
        cardVisible = false
        voiceManager.stop()
        mBindingPair.updateView { cardView.hideCard() }
    }

    private fun handleVoiceCommand(command: String) {
        if (!cardVisible) return
        when (command) {
            "approve", "yes" -> {
                mBindingPair.updateView {
                    cardView.focusPrev() // ensure on approve
                    cardView.confirmFocused()
                }
            }
            "reject", "no", "skip" -> {
                mBindingPair.updateView {
                    cardView.focusNext() // ensure on reject
                    cardView.confirmFocused()
                }
            }
            "status" -> {
                val glyph = stateManager.state.value.glyph
                mBindingPair.updateView {
                    whisperView.showWhisper("status: $glyph", 3000)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stop()
        stateManager.destroy()
    }
}
