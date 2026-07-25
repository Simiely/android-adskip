package com.simely.adskip.float

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import android.view.WindowManager
import android.widget.Toast
import com.simely.adskip.AppState
import com.simely.adskip.R

/**
 * 悬浮胶囊管理：
 * - 常驻 TYPE_APPLICATION_OVERLAY 小圆点
 * - 点击胶囊 → 进入/退出“手动捕获模式”
 * - 捕获模式下显示全屏半透明提示遮罩（穿透点击，真实点击由无障碍捕获）
 */
class FloatWindowManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var capsuleView: View? = null
    private var hintView: View? = null

    private val capsuleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 200
    }

    private val hintParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun showCapsule() {
        if (!canShow()) return
        if (capsuleView != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.floating_capsule, null)
        view.setOnTouchListener(::onCapsuleTouch)
        capsuleView = view
        wm.addView(view, capsuleParams)
    }

    fun hideCapsule() {
        capsuleView?.let { wm.removeView(it) }
        capsuleView = null
        hideHint()
    }

    private fun onCapsuleTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > 8 || abs(dy) > 8) moved = true
                capsuleParams.x = (capsuleParams.x + dx.toInt()).coerceAtLeast(0)
                capsuleParams.y = (capsuleParams.y + dy.toInt()).coerceAtLeast(0)
                downX = event.rawX
                downY = event.rawY
                wm.updateViewLayout(v, capsuleParams)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onCapsuleTap()
            }
        }
        return true
    }

    private fun onCapsuleTap() {
        if (AppState.isCapturing) {
            cancelCapture()
        } else {
            enterCapture()
        }
    }

    private fun enterCapture() {
        AppState.enterCapture()
        AppState.onCaptured = {
            hideHint()
            Toast.makeText(context, R.string.toast_captured, Toast.LENGTH_SHORT).show()
        }
        AppState.onCaptureCancelled = { hideHint() }
        if (hintView == null) {
            hintView = LayoutInflater.from(context).inflate(R.layout.capture_hint, null)
            wm.addView(hintView, hintParams)
        }
    }

    private fun cancelCapture() {
        AppState.exitCapture()
        hideHint()
    }

    private fun hideHint() {
        hintView?.let { wm.removeView(it) }
        hintView = null
    }
}
