package com.gads.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class HighlightKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    /** The key‐code that should be highlighted (null = nothing) */
    private var highlighted: Int? = null

    /**  A quick orange overlay; tweak in colours.xml or replace with drawable if you wish */
    private val paint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.remote_highlight)   // e.g. #FFFF9800
        alpha = 120                                                        // ≈ 47 % opacity
    }

    /** Public API for the IME */
    fun highlight(code: Int, duration: Long = 120L) {
        highlighted = code
        invalidate()

        // Clear it again after the given time‑slice
        removeCallbacks(clearRunnable)
        postDelayed(clearRunnable, duration)
    }

    private val clearRunnable = Runnable {
        highlighted = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw the stock keyboard first
        super.onDraw(canvas)

        val code = highlighted ?: return
        val kbd: Keyboard = keyboard ?: return

        // Find the first key that advertises that code
        val key = kbd.keys.firstOrNull { key -> code in key.codes } ?: return

        val radius = minOf(key.width, key.height) / 6f
        canvas.drawRoundRect(
            key.x.toFloat(),
            key.y.toFloat(),
            (key.x + key.width).toFloat(),
            (key.y + key.height).toFloat(),
            radius,
            radius,
            paint
        )
    }
}