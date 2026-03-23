package com.ccg.glasses.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.ccg.glasses.R

/**
 * Renders the whisper display primitive -- a single line of white text at
 * the bottom of the FOV. Auto-fades after a configurable TTL.
 *
 * Spec constraints:
 *   - Max 48 characters (hard truncation with ellipsis)
 *   - 20px Noto Sans Medium (sans-serif-medium)
 *   - White on transparent, no background
 *   - Auto-fades after TTL (default 4s, 8s for tool events)
 *   - Never interrupts an active card
 */
class WhisperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        /** Hard character limit per spec. */
        const val MAX_CHARS = 48
        /** Default fade delay for non-tool whispers. */
        const val DEFAULT_TTL_MS = 4000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var fadeAnimator: ObjectAnimator? = null
    private var fadeRunnable: Runnable? = null

    init {
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.getColor(R.color.white))
        setBackgroundColor(0x00000000) // transparent
        visibility = View.GONE
    }

    /**
     * Show a whisper message. Truncates to 48 chars and auto-fades after [ttlMs].
     *
     * @param message The text to display
     * @param ttlMs Time in milliseconds before the whisper fades out (default 4000)
     */
    fun showWhisper(message: String, ttlMs: Long = DEFAULT_TTL_MS) {
        // Cancel any pending fade
        cancelFade()

        // Truncate to spec limit
        val truncated = if (message.length > MAX_CHARS) {
            message.take(MAX_CHARS - 1) + "\u2026" // ellipsis
        } else {
            message
        }

        text = truncated
        alpha = 1.0f
        visibility = View.VISIBLE

        // Schedule fade-out
        fadeRunnable = Runnable { fadeOut() }
        handler.postDelayed(fadeRunnable!!, ttlMs)
    }

    /** Immediately hide the whisper without animation. */
    fun hide() {
        cancelFade()
        visibility = View.GONE
        alpha = 1.0f
    }

    private fun fadeOut() {
        fadeAnimator = ObjectAnimator.ofFloat(this, "alpha", 1.0f, 0.0f).apply {
            duration = 500
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = View.GONE
                    alpha = 1.0f
                }
            })
            start()
        }
    }

    private fun cancelFade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelFade()
    }
}
