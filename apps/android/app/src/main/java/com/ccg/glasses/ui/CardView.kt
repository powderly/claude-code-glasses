package com.ccg.glasses.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ccg.glasses.R
import com.ccg.glasses.transport.CardData
import com.ccg.glasses.transport.CardType

/**
 * Renders the card display primitive -- a decision or update card with
 * actionable buttons. Decision cards have Approve + Reject buttons and
 * wait indefinitely. Update cards have a single "Got it" button and
 * auto-dismiss after the configured timeout.
 *
 * Button focus is navigated by temple swipe gestures:
 *   - Swipe forward  -> focusNext() (move to Reject)
 *   - Swipe backward -> focusPrev() (move to Approve)
 *   - Tap            -> confirmFocused()
 *
 * Visual spec:
 *   - Black background with 2px cyan border
 *   - Message: white 24px Medium
 *   - Focused button: white text, green brackets
 *   - Unfocused button: dim white text, dim cyan brackets
 */
class CardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Called when the user approves/confirms the card. */
    var onApprove: ((cardId: String) -> Unit)? = null

    /** Called when the user dismisses/rejects the card. */
    var onDismiss: ((cardId: String) -> Unit)? = null

    private var currentCard: CardData? = null
    private var focusIndex = 0 // 0 = confirm (left), 1 = dismiss (right)
    private val buttons = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val messageView: TextView
    private val buttonRow: LinearLayout

    private val colorWhite by lazy { context.getColor(R.color.white) }
    private val colorWhiteDim by lazy { context.getColor(R.color.white_dim) }
    private val colorGreenBracket by lazy { context.getColor(R.color.button_focus_bracket) }
    private val colorDimBracket by lazy { context.getColor(R.color.button_dim_bracket) }

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.card_border)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER
        visibility = View.GONE

        messageView = TextView(context).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorWhite)
            gravity = Gravity.CENTER
        }
        addView(messageView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val topMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, topMargin, 0, 0)
            }
        }
        addView(buttonRow)
    }

    /**
     * Show a card. Replaces any previously displayed card.
     *
     * For decision cards (cardType=DECISION), two buttons are shown:
     * confirm (Approve) and dismiss (Reject), with no auto-timeout.
     *
     * For update cards (cardType=UPDATE), one button is shown (Got it)
     * and the card auto-dismisses after [CardData.timeoutMs].
     *
     * @param card The card data to display
     */
    fun showCard(card: CardData) {
        cancelTimeout()
        currentCard = card
        focusIndex = 0
        buttons.clear()
        buttonRow.removeAllViews()

        messageView.text = card.message

        // Build buttons
        val confirmBtn = createButton(card.confirmLabel)
        buttons.add(confirmBtn)
        buttonRow.addView(confirmBtn)

        if (card.cardType == CardType.DECISION && card.dismissLabel != null) {
            val spacer = View(context)
            val spacerWidth = (24 * resources.displayMetrics.density).toInt()
            buttonRow.addView(spacer, LayoutParams(spacerWidth, 1))

            val dismissBtn = createButton(card.dismissLabel)
            buttons.add(dismissBtn)
            buttonRow.addView(dismissBtn)
        }

        updateButtonFocus()
        visibility = View.VISIBLE

        // Auto-dismiss for update cards
        if (card.cardType == CardType.UPDATE && card.timeoutMs > 0) {
            timeoutRunnable = Runnable { confirmFocused() }
            handler.postDelayed(timeoutRunnable!!, card.timeoutMs)
        }
    }

    /** Hide the card immediately. */
    fun hideCard() {
        cancelTimeout()
        visibility = View.GONE
        currentCard = null
    }

    /**
     * Move focus to the next button (toward Reject).
     * Called on temple swipe forward.
     */
    fun focusNext() {
        if (buttons.size > 1 && focusIndex < buttons.size - 1) {
            focusIndex++
            updateButtonFocus()
        }
    }

    /**
     * Move focus to the previous button (toward Approve).
     * Called on temple swipe backward.
     */
    fun focusPrev() {
        if (focusIndex > 0) {
            focusIndex--
            updateButtonFocus()
        }
    }

    /**
     * Confirm the currently focused button.
     * Called on temple tap or voice command.
     */
    fun confirmFocused() {
        val card = currentCard ?: return
        cancelTimeout()
        visibility = View.GONE

        if (focusIndex == 0) {
            onApprove?.invoke(card.id)
        } else {
            onDismiss?.invoke(card.id)
        }
        currentCard = null
    }

    /** @return true if a card is currently visible */
    fun isShowing(): Boolean = visibility == View.VISIBLE && currentCard != null

    private fun createButton(label: String): TextView {
        return TextView(context).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val hPad = (12 * resources.displayMetrics.density).toInt()
            val vPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
    }

    private fun updateButtonFocus() {
        for ((i, btn) in buttons.withIndex()) {
            val label = btn.text.toString().replace(Regex("^[\\[\\]✓✕ ]+|[\\[\\] ]+$"), "")
            if (i == focusIndex) {
                // Focused: white text, green brackets
                btn.setTextColor(colorWhite)
                btn.text = if (i == 0) "[\u2713 $label]" else "[\u2715 $label]"
                btn.setTextColor(colorWhite)
                // Apply bracket color via spans would be ideal, but for
                // the AR display we keep it simple with full-string color
                btn.setTextColor(colorGreenBracket)
            } else {
                // Unfocused: dim white text, dim cyan brackets
                btn.text = if (i == 0) "[\u2713 $label]" else "[\u2715 $label]"
                btn.setTextColor(colorDimBracket)
            }
        }
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimeout()
    }
}
