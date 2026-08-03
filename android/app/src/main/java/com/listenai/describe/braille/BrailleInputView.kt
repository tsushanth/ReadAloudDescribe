package com.listenai.describe.braille

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Six-key braille input surface: a 2-column x 3-row grid of touch
 * regions representing dots 1-3 (left column) and 4-6 (right column).
 * Real braille typing presses several dots near-simultaneously with
 * different fingers and lifts them together — this view tracks every
 * pointer down during one multi-touch gesture, unions their dot
 * numbers, and fires [onCellEntered] once the LAST finger lifts (not
 * on each individual finger-up, which would fragment one intended
 * cell into several partial ones).
 */
class BrailleInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with the accumulated dot set (1-6) once every pointer in the gesture has lifted. */
    var onCellEntered: ((Set<Int>) -> Unit)? = null

    private val activeGestureDots = mutableSetOf<Int>()
    private var activePointerCount = 0

    private val dotPaintFilled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaintOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val backgroundPaint = Paint().apply { color = Color.DKGRAY }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val pointerIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                regionForCoordinates(event.getX(pointerIndex), event.getY(pointerIndex))?.let {
                    activeGestureDots.add(it)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Update dot membership as fingers drift between regions
                // mid-gesture — real touch typing on a small screen
                // rarely lands perfectly still.
                for (i in 0 until event.pointerCount) {
                    regionForCoordinates(event.getX(i), event.getY(i))?.let {
                        activeGestureDots.add(it)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                activePointerCount = (activePointerCount - 1).coerceAtLeast(0)
                if (activePointerCount == 0) {
                    val finalDots = activeGestureDots.toSet()
                    activeGestureDots.clear()
                    invalidate()
                    if (finalDots.isNotEmpty()) {
                        onCellEntered?.invoke(finalDots)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                activeGestureDots.clear()
                invalidate()
            }
        }
        return true
    }

    /** Dot numbering: 1,2,3 top-to-bottom in the left column; 4,5,6 top-to-bottom in the right column. */
    private fun regionForCoordinates(x: Float, y: Float): Int? {
        if (x < 0 || y < 0 || x > width || y > height) return null
        val col = if (x < width / 2f) 0 else 1
        val row = (y / (height / 3f)).toInt().coerceIn(0, 2)
        return when (col) {
            0 -> row + 1        // 1, 2, 3
            else -> row + 4     // 4, 5, 6
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val cellW = width / 2f
        val cellH = height / 3f
        val radius = minOf(cellW, cellH) * 0.25f
        for (dot in 1..6) {
            val col = if (dot <= 3) 0 else 1
            val row = if (dot <= 3) dot - 1 else dot - 4
            val cx = cellW * col + cellW / 2f
            val cy = cellH * row + cellH / 2f
            if (dot in activeGestureDots) {
                canvas.drawCircle(cx, cy, radius, dotPaintFilled)
            } else {
                canvas.drawCircle(cx, cy, radius, dotPaintOutline)
            }
        }
    }
}
