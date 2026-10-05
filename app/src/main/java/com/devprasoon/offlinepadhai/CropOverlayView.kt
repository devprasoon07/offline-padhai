package com.devprasoon.offlinepadhai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Photo ke upar ungli se rectangle kheenchne wala overlay.
 * Chuna hua hissa saaf dikhta hai, baaki hissa halka kaala.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** View coordinates me chuna hua rectangle (null = abhi kuch nahi chuna). */
    var selection: RectF? = null
        private set

    private var startX = 0f
    private var startY = 0f
    private var drawing = false

    private val shadePaint = Paint().apply { color = Color.parseColor("#B3000000") }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                selection = RectF(startX, startY, startX, startY)
                drawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    selection = RectF(
                        minOf(startX, event.x),
                        minOf(startY, event.y),
                        maxOf(startX, event.x),
                        maxOf(startY, event.y)
                    )
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sel = selection ?: return
        if (sel.width() < 4f || sel.height() < 4f) return
        // Selection ke chaaron taraf halka kaala shade.
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, shadePaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, shadePaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, shadePaint)
        // Safed border.
        canvas.drawRect(sel, borderPaint)
    }
}
