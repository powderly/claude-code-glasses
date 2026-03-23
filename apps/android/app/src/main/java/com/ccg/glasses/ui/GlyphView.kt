package com.ccg.glasses.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.ccg.glasses.R

/**
 * Renders the glyph display primitive -- a single Unicode character with
 * color and optional pulse animation. Always visible in the bottom-right
 * corner of the FOV.
 *
 * Glyph states:
 *   idle     -> ▽ (U+25BD) cyan dim, static
 *   thinking -> ▼ (U+25BC) cyan, 2s slow pulse
 *   running  -> ▲ (U+25B2) green, 1s fast pulse
 *   awaiting -> △ (U+25B3) purple, steady glow
 *   done     -> ▽ (U+25BD) green, fade out over 2s
 *   error    -> ✕ (U+2715) red, static until cleared
 */
class GlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var currentGlyph: String = "\u25BD" // ▽
    private var baseColor: Int = context.getColor(R.color.glyph_cyan_dim)
    private var pulseAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var currentAlpha = 1.0f

    /**
     * Set the glyph state. Updates the character, color, and animation.
     *
     * @param state One of: idle, thinking, running, awaiting, done, error
     */
    fun setState(state: String) {
        pulseAnimator?.cancel()
        fadeAnimator?.cancel()
        currentAlpha = 1.0f

        when (state) {
            "idle" -> {
                currentGlyph = "\u25BD" // ▽
                baseColor = context.getColor(R.color.glyph_cyan_dim)
            }
            "thinking" -> {
                currentGlyph = "\u25BC" // ▼
                baseColor = context.getColor(R.color.glyph_cyan)
                startPulse(durationMs = 2000)
            }
            "running" -> {
                currentGlyph = "\u25B2" // ▲
                baseColor = context.getColor(R.color.glyph_green)
                startPulse(durationMs = 1000)
            }
            "awaiting" -> {
                currentGlyph = "\u25B3" // △
                baseColor = context.getColor(R.color.glyph_purple)
                // Steady glow, no animation
            }
            "done" -> {
                currentGlyph = "\u25BD" // ▽
                baseColor = context.getColor(R.color.glyph_green)
                startFadeOut(durationMs = 2000)
            }
            "error" -> {
                currentGlyph = "\u2715" // ✕
                baseColor = context.getColor(R.color.glyph_red)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = baseColor
        paint.alpha = (currentAlpha * 255).toInt()
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(currentGlyph, x, y, paint)
    }

    private fun startPulse(durationMs: Long) {
        pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startFadeOut(durationMs: Long) {
        fadeAnimator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            duration = durationMs.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        fadeAnimator?.cancel()
    }
}
