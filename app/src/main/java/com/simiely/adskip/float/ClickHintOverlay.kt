package com.simely.adskip.float

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 点击反馈浮动提示：半透明 "点击跳过" 文字，3秒淡入淡出。
 */
object ClickHintOverlay {

    private const val DURATION_FADE_IN = 250L
    private const val DURATION_HOLD = 2500L
    private const val DURATION_FADE_OUT = 250L

    fun show(context: Context, detail: String = "") {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val msg = if (detail.isNotEmpty()) "点击跳过\n$detail" else "点击跳过"
        val textView = TextView(context).apply {
            text = msg
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(28, 10, 28, 10)
            background = GradientDrawable().apply {
                setColor(0x99FF9292.toInt())
                cornerRadius = 24f
            }
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            y = 180
        }

        wm.addView(textView, params)

        val fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_FADE_IN
            addUpdateListener { textView.alpha = animatedValue as Float }
        }
        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = DURATION_FADE_OUT
            addUpdateListener { textView.alpha = animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try { wm.removeView(textView) } catch (_: Exception) {}
                }
            })
        }

        fadeIn.start()
        textView.postDelayed({
            fadeOut.start()
        }, DURATION_FADE_IN + DURATION_HOLD)
    }
}
