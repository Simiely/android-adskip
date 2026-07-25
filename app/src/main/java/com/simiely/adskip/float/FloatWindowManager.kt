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
 * - 点击 → 进入捕获模式（胶囊变为 FLAG_NOT_TOUCH_MODAL，外部触摸穿透到 App）
 * - 捕获模式下点胶囊 → 取消捕获
 * - 长按 → 隐藏胶囊
 */
class FloatWindowManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var capsuleView: View? = null
    private var hintView: View? = null

    /** 普通模式：不可获焦（触摸由 onTouchListener 处理） */
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

    /** 捕获提示遮罩：穿透触摸（用户点击直达下方 App 按钮） */
    private val hintParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressed = false
    private var warnedNoOverlay = false
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun isVisible(): Boolean = capsuleView != null

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
        onVisibilityChanged?.invoke(true)
    }

    fun hideCapsuleAndNotify() {
        hideCapsule()
        onVisibilityChanged?.invoke(false)
        Toast.makeText(context, "悬浮窗已隐藏，点击通知栏可重新显示", Toast.LENGTH_SHORT).show()
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
                downTime = System.currentTimeMillis()
                moved = false
                longPressed = false
                v.postDelayed({
                    if (!moved && capsuleView != null && !AppState.isCapturing) {
                        longPressed = true
                        hideCapsuleAndNotify()
                    }
                }, 500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > 8 || abs(dy) > 8) moved = true
                if (moved && !longPressed) {
                    capsuleParams.x = (capsuleParams.x + dx.toInt()).coerceAtLeast(0)
                    capsuleParams.y = (capsuleParams.y + dy.toInt()).coerceAtLeast(0)
                    downX = event.rawX
                    downY = event.rawY
                    wm.updateViewLayout(v, capsuleParams)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(null)
                if (!moved && !longPressed) onCapsuleTap()
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

        // 关键：切换为 FLAG_NOT_TOUCH_MODAL，胶囊内触摸由胶囊处理，胶囊外穿透到 App
        capsuleView?.let { v ->
            capsuleParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            wm.updateViewLayout(v, capsuleParams)
            // 视觉提示：捕获模式 — 胶囊变红
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule_capture)
            }
        }

        if (hintView == null) {
            hintView = LayoutInflater.from(context).inflate(R.layout.capture_hint, null)
            wm.addView(hintView, hintParams)
        }
    }

    private fun cancelCapture() {
        AppState.exitCapture()
        exitCaptureMode()
    }

    private fun exitCaptureMode() {
        // 恢复胶囊正常标志和外观
        capsuleView?.let { v ->
            capsuleParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(v, capsuleParams)
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule)
            }
        }
        hideHint()
    }

    private fun hideHint() {
        hintView?.let { wm.removeView(it) }
        hintView = null
    }
}
