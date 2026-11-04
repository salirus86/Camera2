package com.example.umnyradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class DrawItem(val rect: RectF, val label: String, val color: Int)

class DetectionOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    @Volatile private var items: List<DrawItem> = emptyList()
    @Volatile private var sourceWidth = 1
    @Volatile private var sourceHeight = 1

    fun update(newItems: List<DrawItem>, width: Int, height: Int) {
        items = newItems
        sourceWidth = width.coerceAtLeast(1)
        sourceHeight = height.coerceAtLeast(1)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width.toFloat() / sourceWidth
        val sy = height.toFloat() / sourceHeight
        items.forEach { item ->
            val r = RectF(item.rect.left * sx, item.rect.top * sy,
                item.rect.right * sx, item.rect.bottom * sy)
            boxPaint.color = item.color
            canvas.drawRoundRect(r, 10f, 10f, boxPaint)
            textPaint.color = item.color
            val y = (r.top - 12f).coerceAtLeast(42f)
            canvas.drawText(item.label, r.left, y, textPaint)
        }
    }
}
