package com.overlaytranslator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.overlaytranslator.ocr.RenderedBlock
import com.overlaytranslator.util.ColorSampler

/**
 * Full-screen transparent view that paints every translation over its OCR
 * bounding box. All boxes are drawn in one window and one draw pass, so a
 * batch of translations appears simultaneously instead of window-by-window.
 */
class TranslationView(context: Context) : View(context) {

    private val bgPaint = Paint()
    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private class Item(
        val box: Rect,
        val background: Int,
        val backgroundAlpha: Int,
        val textColor: Int,
        val layout: StaticLayout?,
    )

    private var items: List<Item> = emptyList()

    /** Renders [blocks] at their absolute screen positions. */
    fun bind(blocks: List<RenderedBlock>, backgroundAlpha: Int) {
        items = blocks.map { block ->
            Item(
                box = block.bbox,
                background = block.backgroundColor,
                backgroundAlpha = backgroundAlpha,
                textColor = ColorSampler.contrastingText(block.backgroundColor),
                layout = buildLayout(
                    block.translatedText,
                    block.bbox.width(),
                    block.bbox.height(),
                    ColorSampler.contrastingText(block.backgroundColor),
                ),
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            val box = item.box
            bgPaint.color = Color.argb(
                item.backgroundAlpha,
                Color.red(item.background),
                Color.green(item.background),
                Color.blue(item.background),
            )
            canvas.drawRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                bgPaint,
            )
            val layout = item.layout ?: continue
            val textTop = box.top + ((box.height() - layout.height) / 2f).coerceAtLeast(0f)
            canvas.save()
            canvas.translate((box.left + PADDING).toFloat(), textTop)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Builds a [StaticLayout] that fits within [maxW] x [maxH], shrinking the
     * font step by step until it does. Returns null for empty text or when even
     * the smallest font cannot be laid out.
     */
    private fun buildLayout(text: String, maxW: Int, maxH: Int, textColor: Int): StaticLayout? {
        if (text.isEmpty()) return null
        val innerW = (maxW - PADDING * 2).coerceAtLeast(1)
        val innerH = (maxH - PADDING * 2).coerceAtLeast(1)

        var textSize = initialTextSize(innerH)
        while (textSize >= MIN_TEXT_SIZE_PX) {
            textPaint.textSize = textSize
            textPaint.color = textColor

            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()

            if (layout.height <= innerH) {
                return layout
            }
            textSize -= 1f
        }
        return null
    }

    /** Start large — roughly one line filling the box height — and shrink from there. */
    private fun initialTextSize(innerH: Int): Float {
        val density = resources.displayMetrics.density
        val fromHeight = innerH * 0.8f
        val fromDensity = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics
        )
        return maxOf(fromHeight, fromDensity * density).coerceAtMost(innerH.toFloat())
    }

    companion object {
        private const val PADDING = 4
        private const val MIN_TEXT_SIZE_PX = 8f
    }
}
