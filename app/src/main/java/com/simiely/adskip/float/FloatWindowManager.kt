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
 * - 点击 → 进入捕获模式（胶囊 FLAG_NOT_TOUCHABLE，所有触摸穿透到 App）
 * - 捕获取消靠通知栏「取消捕获」按钮（Android 12+ 信任触摸限制无法用 FLAG_NOT_TOUCH_MODAL）
 * - 长按 → 隐藏胶囊
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
    private var warnedNoOverlay = false
    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    /** 捕获状态变化回调（用于 KeepAliveService 更新通知栏） */
    var onCaptureStateChanged: ((Boolean) -> Unit)? = null

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

    fun enterCapture() {
        AppState.enterCapture(
            onCaptured = {
                exitCaptureMode()
                Toast.makeText(context, R.string.toast_captured, Toast.LENGTH_SHORT).show()
            },
            onCancelled = { exitCaptureMode() }
        )

        // 不修改胶囊 flag：胶囊 48dp 在左上角，不挡按钮
        // 提示遮罩 FLAG_NOT_TOUCHABLE 穿透，触摸可达 App 按钮
        capsuleView?.let { v ->
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule_capture)
            }
        }

        if (hintView == null) {
            hintView = LayoutInflater.from(context).inflate(R.layout.capture_hint, null)
            wm.addView(hintView, hintParams)
        }

        onCaptureStateChanged?.invoke(true)
    }

    fun cancelCapture() {
        AppState.exitCapture()
        exitCaptureMode()
    }

    private fun exitCaptureMode() {
        capsuleView?.let { v ->
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule)
            }
        }
        hideHint()
        onCaptureStateChanged?.invoke(false)
    }

    private fun hideHint() {
        hintView?.let { wm.removeView(it) }
        hintView = null
    }
}
