package com.ccg.glasses

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ccg.glasses.databinding.ActivityHudBinding
import com.ccg.glasses.transport.CardData
import com.ccg.glasses.transport.CardType
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity

/**
 * Display test — shows all three primitives with hardcoded data.
 * No WebSocket needed. Cycles through glyph states automatically.
 */
class TestActivity : BaseMirrorActivity<ActivityHudBinding>() {

    private val handler = Handler(Looper.getMainLooper())
    private val states = listOf("idle", "thinking", "running", "awaiting", "done", "error")
    private var stateIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show whisper
        mBindingPair.updateView {
            whisperView.showWhisper("reading src/auth/middleware.ts", 30000)
        }

        // Show glyph — cycle through states every 3s
        cycleGlyph()

        // Show card after 2s
        handler.postDelayed({
            mBindingPair.updateView {
                whisperView.hide()
                cardView.showCard(CardData(
                    id = "test_card",
                    cardType = CardType.DECISION,
                    message = "Delete legacy token handler?",
                    confirmLabel = "Approve",
                    dismissLabel = "Reject",
                    timeoutMs = 0
                ))
                glyphView.setState("awaiting")
            }
        }, 2000)
    }

    private fun cycleGlyph() {
        mBindingPair.updateView {
            glyphView.setState(states[stateIndex])
        }
        stateIndex = (stateIndex + 1) % states.size
        handler.postDelayed({ cycleGlyph() }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
