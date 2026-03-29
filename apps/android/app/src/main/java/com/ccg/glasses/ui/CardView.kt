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
import com.ffalcon.mercury.android.sdk.core.make3DEffectForSide

/**
 * Renders the card display primitive -- a decision or update card with
 * actionable buttons. Decision cards have Approve + Reject buttons and
 * wait indefinitely. Update cards have a single "Got it" button and
 * auto-dismiss after the configured timeout.
 *
 * Buttons are exposed via [getApproveButton] and [getRejectButton] so
 * HudActivity can register them as FocusInfo targets with the SDK's
 * FocusHolder/FixPosFocusTracker system. Focus visual changes and 3D
 * stereo effects are driven externally via [triggerButtonFocus] which
 * calls [make3DEffectForSide] per the SDK pattern.
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
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val messageView: TextView
    private val buttonRow: LinearLayout
    private val approveBtn: TextView
    private val rejectBtn: TextView

    private val colorWhite by lazy { context.getColor(R.color.white) }
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

        approveBtn = createButton()
        rejectBtn = createButton()

        val spacer = View(context)
        val spacerWidth = (24 * resources.displayMetrics.density).toInt()

        buttonRow.addView(approveBtn)
        buttonRow.addView(spacer, LayoutParams(spacerWidth, 1))
        buttonRow.addView(rejectBtn)

        addView(buttonRow)
    }

    /**
     * Expose the approve button so HudActivity can pass it to FocusInfo.
     */
    fun getApproveButton(): TextView = approveBtn

    /**
     * Expose the reject button so HudActivity can pass it to FocusInfo.
     */
    fun getRejectButton(): TextView = rejectBtn

    /**
     * Called from HudActivity's focusChangeHandler to update button visual
     * state and apply SDK stereo 3D effect.
     *
     * Follows the exact same pattern as triggerFocus in the SDK sample:
     * update the background/styling, then call make3DEffectForSide.
     *
     * @param isApprove true for the approve button, false for reject
     * @param hasFocus whether this button currently has focus
     * @param isLeft whether this is the left eye binding
     */
    fun triggerButtonFocus(isApprove: Boolean, hasFocus: Boolean, isLeft: Boolean) {
        val btn = if (isApprove) approveBtn else rejectBtn
        if (hasFocus) {
            btn.setTextColor(colorGreenBracket)
        } else {
            btn.setTextColor(colorDimBracket)
        }
        // SDK 3D stereo effect — this is how the sample handles convergence
        make3DEffectForSide(btn, isLeft, hasFocus)
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

        messageView.text = card.message

        // Set up approve button
        val approveLabel = card.confirmLabel
        approveBtn.text = "[\u2713 $approveLabel]"
        approveBtn.setTextColor(colorGreenBracket) // default focused
        approveBtn.visibility = View.VISIBLE

        // Set up reject button
        if (card.cardType == CardType.DECISION && card.dismissLabel != null) {
            val rejectLabel = card.dismissLabel
            rejectBtn.text = "[\u2715 $rejectLabel]"
            rejectBtn.setTextColor(colorDimBracket) // default unfocused
            rejectBtn.visibility = View.VISIBLE
        } else {
            rejectBtn.visibility = View.GONE
        }

        visibility = View.VISIBLE

        // Auto-dismiss for update cards
        if (card.cardType == CardType.UPDATE && card.timeoutMs > 0) {
            timeoutRunnable = Runnable { confirmApprove() }
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
     * Confirm the approve action. Called from HudActivity via FocusInfo
     * eventHandler on temple Click, or from voice command.
     */
    fun confirmApprove() {
        val card = currentCard ?: return
        cancelTimeout()
        visibility = View.GONE
        onApprove?.invoke(card.id)
        currentCard = null
    }

    /**
     * Confirm the reject/dismiss action. Called from HudActivity via FocusInfo
     * eventHandler on temple Click, or from voice command.
     */
    fun confirmReject() {
        val card = currentCard ?: return
        cancelTimeout()
        visibility = View.GONE
        onDismiss?.invoke(card.id)
        currentCard = null
    }

    /** @return true if a card is currently visible */
    fun isShowing(): Boolean = visibility == View.VISIBLE && currentCard != null

    private fun createButton(): TextView {
        return TextView(context).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val hPad = (12 * resources.displayMetrics.density).toInt()
            val vPad = (6 * resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
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
