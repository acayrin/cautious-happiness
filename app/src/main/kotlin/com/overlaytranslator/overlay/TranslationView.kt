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
 *
 * Stability: OCR boxes jitter on animated screens, so a block whose text is
 * unchanged and whose box still overlaps keeps its previous box (hysteresis),
 * and an unchanged set of blocks skips re-rendering entirely.
 */
class TranslationView(context: Context) : View(context) {

    private class Item(
        val text: String,
        val box: Rect,
        val background: Int,
        val backgroundAlpha: Int,
        val fill: StaticLayout?,
        val stroke: StaticLayout?,
        val textTop: Float,
    )

    private data class Key(val text: String, val box: Rect, val background: Int, val backgroundAlpha: Int)

    private var items: List<Item> = emptyList()
    private var itemKeys: List<Key> = emptyList()

    /** When true, text lays out against the remaining screen width instead of
     *  the OCR box, so it wraps naturally instead of breaking mid-word. */
    var allowOverflow: Boolean = false
    private val bgPaint = Paint()
    private var statusText: String = ""

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6181818.toInt() }
    private val chipTextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
    }

    /** Status chip content, e.g. "translate 512ms · 4 blocks" or "skipped". */
    fun setStatus(text: String) {
        statusText = text
        invalidate()
    }

    /** Renders [blocks] at their absolute screen positions. */
    fun bind(blocks: List<RenderedBlock>, backgroundAlpha: Int) {
        val snapped = blocks.map { block ->
            val prev = items.firstOrNull {
                it.text == block.translatedText && overlaps(it.box, block.bbox)
            }
            block to (prev?.box ?: block.bbox)
        }

        val keys = snapped.map { (block, box) ->
            Key(block.translatedText, box, block.backgroundColor, backgroundAlpha)
        }
        if (keys == itemKeys) return

        itemKeys = keys
        items = snapped.map { (block, box) -> buildItem(block, box, backgroundAlpha) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            val box = item.box
            val bg = Color.argb(
                item.backgroundAlpha,
                Color.red(item.background),
                Color.green(item.background),
                Color.blue(item.background),
            )
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), bgPaint.apply { color = bg })
            val x = (box.left + PADDING).toFloat()
            item.stroke?.drawAt(canvas, x, item.textTop)
            item.fill?.drawAt(canvas, x, item.textTop)
        }

        if (statusText.isNotEmpty()) {
            val pad = 12f
            val fm = chipTextPaint.fontMetrics
            val chipW = chipTextPaint.measureText(statusText) + pad * 2
            val chipH = (fm.bottom - fm.top) + pad * 2
            val right = width - 16f
            val top = 16f
            canvas.drawRoundRect(right - chipW, top, right, top + chipH, 12f, 12f, chipPaint)
            canvas.drawText(statusText, right - chipW + pad, top + pad - fm.top, chipTextPaint)
        }
    }

    private fun StaticLayout.drawAt(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        draw(canvas)
        canvas.restore()
    }

    private fun buildItem(block: RenderedBlock, box: Rect, backgroundAlpha: Int): Item {
        val text = block.translatedText
        val textColor = ColorSampler.contrastingText(block.backgroundColor)
        val innerW = (box.width() - PADDING * 2).coerceAtLeast(1)
        val innerH = (box.height() - PADDING * 2).coerceAtLeast(1)

        // Overflow mode uses a fixed, readable size (not scale-to-box-height:
        // tall boxes would get giant text) and wraps against the screen width.
        val textSize = if (allowOverflow) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
            )
        } else {
            fittedTextSize(text, innerW, innerH)
        }
        val fillPaint = newTextPaint(textSize).apply { color = textColor }
        val strokePaint = newTextPaint(textSize).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, textSize / 6f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = if (textColor == Color.WHITE) Color.BLACK else Color.WHITE
        }

        val layoutW = if (allowOverflow) {
            maxOf(innerW, width - box.left - PADDING * 2).coerceAtLeast(1)
        } else {
            innerW
        }
        val fill = buildLayout(text, layoutW, fillPaint)
        val layoutHeight = fill?.height ?: 0
        val textTop = box.top + ((box.height() - layoutHeight) / 2f).coerceAtLeast(0f)

        return Item(
            text = text,
            box = box,
            background = block.backgroundColor,
            backgroundAlpha = backgroundAlpha,
            fill = fill,
            stroke = fill?.let { buildLayout(text, layoutW, strokePaint) },
            textTop = textTop,
        )
    }

    /**
     * Largest size that fits [innerW] x [innerH], shrinking step by step down to
     * [MIN_TEXT_SIZE_PX]. Never smaller: when the minimum still doesn't fit, the
     * text is drawn at [MIN_TEXT_SIZE_PX] and overflows the box (user policy).
     */
    private fun fittedTextSize(text: String, innerW: Int, innerH: Int): Float {
        val minSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SIZE_SP, resources.displayMetrics
        )
        var textSize = initialTextSize(innerH)
        while (textSize > minSize) {
            val layout = buildLayout(text, innerW, newTextPaint(textSize))
            if (layout != null && layout.height <= innerH) return textSize
            textSize -= 1f
        }
        return minSize
    }

    private fun newTextPaint(size: Float): TextPaint {
        return TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            textSize = size
        }
    }

    private fun buildLayout(text: String, maxW: Int, paint: TextPaint): StaticLayout? {
        if (text.isEmpty()) return null
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
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

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val interW = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val interH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (interW <= 0 || interH <= 0) return false
        val inter = interW.toLong() * interH
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return inter * 2 >= union // IoU ≥ 0.5
    }

    companion object {
        private const val PADDING = 4
        private const val MIN_TEXT_SIZE_SP = 12f
    }
}
