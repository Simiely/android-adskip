package com.simely.adskip.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 简单 7 天点击走势柱状图。
 * data: 7 个 (标签, 值) 对
 */
class ChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt(); style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 24f; textAlign = Paint.Align.CENTER }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 22f; textAlign = Paint.Align.CENTER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEEEEEE.toInt(); strokeWidth = 1f }

    private var data: List<Pair<String, Int>> = emptyList()

    fun setData(d: List<Pair<String, Int>>) {
        data = d
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 50f
        val bottomPadding = 40f
        val topPadding = 24f

        val maxVal = (data.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        val barCount = data.size
        val barWidth = (w - padding * 2) / barCount * 0.6f
        val gap = (w - padding * 2) / barCount

        // 底线
        canvas.drawLine(padding, h - bottomPadding, w - padding, h - bottomPadding, linePaint)

        for ((i, pair) in data.withIndex()) {
            val (label, value) = pair
            val barH = if (maxVal > 0) ((h - bottomPadding - topPadding) * value / maxVal) else 0f
            val left = padding + i * gap + (gap - barWidth) / 2
            val top = h - bottomPadding - barH
            val right = left + barWidth

            // 柱状
            canvas.drawRoundRect(RectF(left, top, right, h - bottomPadding), 4f, 4f, barPaint)

            // 数值
            canvas.drawText(value.toString(), left + barWidth / 2, top - 8f, valuePaint)

            // 标签（MM-DD）
            val shortLabel = if (label.length >= 5) label.substring(5) else label
            canvas.drawText(shortLabel, left + barWidth / 2, h - 8f, textPaint)
        }
    }
}
