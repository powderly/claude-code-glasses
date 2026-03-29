package com.ccg.glasses

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ccg.glasses.databinding.ActivityHudBinding
import com.ccg.glasses.state.GlassStateManager
import com.ccg.glasses.transport.CardType
import com.ccg.glasses.transport.CCGWebSocketClient
import com.ccg.glasses.transport.ConnectionState
import com.ccg.glasses.voice.VoiceCommandManager
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "HudActivity"

class HudActivity : BaseMirrorActivity<ActivityHudBinding>() {

    private lateinit var stateManager: GlassStateManager
    private lateinit var voiceManager: VoiceCommandManager
    private var cardVisible = false
    private var fixPosFocusTracker: FixPosFocusTracker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Base stereo alignment correction — the SDK's left layout sits
        // ~102px too far left. Proven to converge in hardware testing.
        mBindingPair.left.hudRoot.translationX = 102f

        val url = intent.getStringExtra("url") ?: run {
            Log.e(TAG, "No WebSocket URL provided")
            finish()
            return
        }
        val token = intent.getStringExtra("token") ?: ""

        val wsClient = CCGWebSocketClient()
        stateManager = GlassStateManager(wsClient)
        voiceManager = VoiceCommandManager(this) { cmd -> handleVoiceCommand(cmd) }

        lifecycleScope.launch {
            stateManager.connect(url, token)
        }

        // Wire card callbacks via setLeft (runs once, on the left binding)
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

        // Observe state — use updateView so both eyes update
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
                                initCardFocusTargets()
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
                    mBindingPair.updateView {
                        when (connState) {
                            is ConnectionState.Error -> {
                                whisperView.showWhisper(connState.message, 8000)
                            }
                            is ConnectionState.Connected -> {
                                whisperView.showWhisper("connected", 2000)
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        // Temple event handling — follows SDK sample pattern exactly
        initTempleEvents()
    }

    /**
     * Set up focus targets for the card buttons using the SDK's
     * FocusHolder / FocusInfo / FixPosFocusTracker pattern.
     *
     * Called when a card becomes visible. The focus targets are the
     * approve and reject buttons exposed by CardView.
     */
    private fun initCardFocusTargets() {
        val focusHolder = FocusHolder(false)
        val approveBtn = mBindingPair.left.cardView.getApproveButton()
        val rejectBtn = mBindingPair.left.cardView.getRejectButton()

        mBindingPair.setLeft {
            focusHolder.addFocusTarget(
                FocusInfo(
                    approveBtn,
                    eventHandler = { action ->
                        when (action) {
                            is TempleAction.Click -> {
                                mBindingPair.updateView { cardView.confirmApprove() }
                            }
                            else -> Unit
                        }
                    },
                    focusChangeHandler = { hasFocus ->
                        mBindingPair.updateView {
                            cardView.triggerButtonFocus(
                                isApprove = true,
                                hasFocus = hasFocus,
                                isLeft = mBindingPair.checkIsLeft(this)
                            )
                        }
                    }
                )
            )
            // Only add reject target if the button is visible (decision cards)
            if (rejectBtn.visibility == View.VISIBLE) {
                focusHolder.addFocusTarget(
                    FocusInfo(
                        rejectBtn,
                        eventHandler = { action ->
                            when (action) {
                                is TempleAction.Click -> {
                                    mBindingPair.updateView { cardView.confirmReject() }
                                }
                                else -> Unit
                            }
                        },
                        focusChangeHandler = { hasFocus ->
                            mBindingPair.updateView {
                                cardView.triggerButtonFocus(
                                    isApprove = false,
                                    hasFocus = hasFocus,
                                    isLeft = mBindingPair.checkIsLeft(this)
                                )
                            }
                        }
                    )
                )
            }
            focusHolder.currentFocus(approveBtn)
        }
        fixPosFocusTracker = FixPosFocusTracker(focusHolder).apply {
            focusObj.hasFocus = true
        }
    }

    /**
     * Temple event wiring — follows the exact SDK sample pattern:
     * templeActionViewModel.state.collect inside repeatOnLifecycle(RESUMED).
     * DoubleClick is not mapped (removed from spec). All other events
     * are delegated to the FixPosFocusTracker for card button navigation.
     */
    private fun initTempleEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    // DoubleClick intentionally not mapped (removed from spec)
                    fixPosFocusTracker?.handleFocusTargetEvent(action)
                }
            }
        }
    }

    private fun clearCard() {
        cardVisible = false
        fixPosFocusTracker = null
        voiceManager.stop()
        mBindingPair.updateView { cardView.hideCard() }
    }

    private fun handleVoiceCommand(command: String) {
        if (!cardVisible) return
        when (command) {
            "approve", "yes" -> {
                mBindingPair.updateView { cardView.confirmApprove() }
            }
            "reject", "no", "skip" -> {
                mBindingPair.updateView { cardView.confirmReject() }
            }
            "status" -> {
                mBindingPair.updateView {
                    whisperView.showWhisper("status: ${stateManager.state.value.glyph}", 3000)
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
