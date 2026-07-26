package com.simely.adskip.float

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import com.simely.adskip.AppState
import com.simely.adskip.R

/**
 * 悬浮胶囊管理：
 * - 常驻 TYPE_APPLICATION_OVERLAY 小圆点
 * - 点击 → 进入捕获模式（高亮提示遮罩 FLAG_NOT_TOUCHABLE 穿透）
 * - 长按 → 打开主界面
 * - 拖拽 → 移动位置
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

    private val hintParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    // ── 触摸状态 ──
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false

    /** 胶囊可见性变化回调 */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** 捕获状态变化回调（用于 KeepAliveService 更新通知栏） */
    var onCaptureStateChanged: ((Boolean) -> Unit)? = null

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)
    fun isVisible(): Boolean = capsuleView != null

    fun showCapsule() {
        if (!canShow()) return
        if (capsuleView != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.floating_capsule, null)
        view.setOnTouchListener(::onCapsuleTouch)
        wm.addView(view, capsuleParams)
        capsuleView = view
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

    // ── 触摸事件 ──

    private fun onCapsuleTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
                longPressFired = false
                longPressRunnable = Runnable {
                    longPressFired = true
                    openMainActivity()
                }
                longPressHandler.postDelayed(longPressRunnable!!, 500)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > 8 || abs(dy) > 8) {
                    moved = true
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }
                if (moved) {
                    val vw = capsuleView?.width ?: 100
                    val vh = capsuleView?.height ?: 100
                    val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        wm.currentWindowMetrics.bounds
                    } else {
                        val r = android.graphics.Rect()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRectSize(r)
                        r
                    }
                    val maxX = bounds.width() - vw
                    val maxY = bounds.height() - vh
                    capsuleParams.x = (capsuleParams.x + dx.toInt()).coerceIn(0, maxX)
                    capsuleParams.y = (capsuleParams.y + dy.toInt()).coerceIn(0, maxY)
                    downX = event.rawX
                    downY = event.rawY
                    wm.updateViewLayout(v, capsuleParams)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                if (!moved && !longPressFired) onCapsuleTap()
            }
        }
        return true
    }

    private fun onCapsuleTap() {
        if (AppState.isCapturing) cancelCapture() else enterCapture()
    }

    private fun openMainActivity() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        }
    }

    // ── 捕获模式 ──

    fun enterCapture() {
        AppState.enterCapture(
            onCaptured = {
                exitCaptureMode()
                Toast.makeText(context, R.string.toast_captured, Toast.LENGTH_SHORT).show()
            },
            onCancelled = { exitCaptureMode() }
        )

        capsuleView?.let { v ->
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule_capture)
            }
        }

        if (hintView == null) {
            hintView = HighlightOverlay(context)
            wm.addView(hintView, hintParams)
        }
        startHighlightRefresh()
        onCaptureStateChanged?.invoke(true)
    }

    fun cancelCapture() {
        AppState.exitCapture()
        exitCaptureMode()
    }

    private var highlightHandler: android.os.Handler? = null

    private fun startHighlightRefresh() {
        highlightHandler = android.os.Handler(android.os.Looper.getMainLooper())
        highlightHandler?.post(object : Runnable {
            override fun run() {
                hintView?.invalidate()
                if (AppState.isCapturing) highlightHandler?.postDelayed(this, 150)
            }
        })
    }

    private fun exitCaptureMode() {
        highlightHandler?.removeCallbacksAndMessages(null)
        highlightHandler = null
        capsuleView?.let { v ->
            v.findViewById<View>(R.id.capsule)?.let {
                it.setBackgroundResource(R.drawable.bg_capsule)
            }
        }
        hideHint()
        vibrate()
        onCaptureStateChanged?.invoke(false)
    }

    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun hideHint() {
        hintView?.let { wm.removeView(it) }
        hintView = null
    }
}
