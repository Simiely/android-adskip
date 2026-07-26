package com.simely.adskip.float

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.simely.adskip.AppState

class HighlightOverlay(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FF4444.toInt(); style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FF4444.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val screenLoc = IntArray(2)
    private val drawRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(screenLoc)
        val ox = -screenLoc[0]; val oy = -screenLoc[1]
        for (rect in AppState.highlightedRects) {
            drawRect.set(rect); drawRect.offset(ox, oy)
            canvas.drawRect(drawRect, fillPaint)
            canvas.drawRect(drawRect, strokePaint)
        }
    }
}
