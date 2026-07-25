package com.simely.adskip.float

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
 * - 点击胶囊 → 进入"手动捕获模式"（隐藏胶囊，显示半透明提示遮罩）
 * - 点击遮罩取消捕获；捕获模式下真实点击由无障碍服务捕获
 * - 修复：捕获期间隐藏胶囊，避免胶囊拦截用户对目标按钮的触摸
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

    /** 捕获提示遮罩：穿透触摸（用户点击直达下方 App 按钮），取消靠超时 */
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
    private var captureTimeout: Handler? = null
    private val captureTimeoutMs = 15_000L
    private var warnedNoOverlay = false

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun showCapsule() {
        if (!canShow()) {
            if (!warnedNoOverlay) {
                warnedNoOverlay = true
                Toast.makeText(context, "请开启悬浮窗权限以显示悬浮胶囊", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (capsuleView != null) return
        warnedNoOverlay = false
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
        AppState.enterCapture(
            onCaptured = {
                exitCaptureMode()
                Toast.makeText(context, R.string.toast_captured, Toast.LENGTH_SHORT).show()
            },
            onCancelled = { exitCaptureMode() }
        )

        // 关键修复：隐藏胶囊，让触摸穿透到目标 App 的按钮
        capsuleView?.let { wm.removeView(it) }
        capsuleView = null

        if (hintView == null) {
            hintView = LayoutInflater.from(context).inflate(R.layout.capture_hint, null)
            wm.addView(hintView, hintParams)
        }

        // 超时自动取消
        captureTimeout = Handler(Looper.getMainLooper()).apply {
            postDelayed({ cancelCapture() }, captureTimeoutMs)
        }
    }

    private fun cancelCapture() {
        AppState.exitCapture()
        exitCaptureMode()
    }

    private fun exitCaptureMode() {
        captureTimeout?.removeCallbacksAndMessages(null)
        captureTimeout = null
        hideHint()
        showCapsule()
    }

    private fun hideHint() {
        hintView?.let { wm.removeView(it) }
        hintView = null
    }
}
