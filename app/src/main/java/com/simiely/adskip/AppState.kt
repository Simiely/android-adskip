package com.simely.adskip

<<<<<<< HEAD
import android.graphics.Rect

object AppState {
    @Volatile
=======
/**
 * 跨组件共享的轻量状态（无障碍服务 / 悬浮窗 / 设置页 之间）。
 * 仅保存捕获模式开关与回调，不持有任何重对象。
 */
object AppState {
    /** 是否处于“手动捕获模式” */
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    var isCapturing: Boolean = false

<<<<<<< HEAD
    @Volatile
=======
    /** 捕获成功（已记录一条规则）后回调，用于刷新 UI */
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    var onCaptured: (() -> Unit)? = null

<<<<<<< HEAD
    @Volatile
=======
    /** 用户取消捕获后回调，用于移除提示遮罩 */
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    var onCaptureCancelled: (() -> Unit)? = null

<<<<<<< HEAD
    /** 捕获模式下检测到的可点击节点边界，用于绘制高亮图层 */
    var highlightedRects: List<Rect> = emptyList()

    @Synchronized
    fun enterCapture(onCaptured: () -> Unit, onCancelled: () -> Unit) {
        isCapturing = true
        this.onCaptured = onCaptured
        this.onCaptureCancelled = onCancelled
        highlightedRects = emptyList()
=======
    fun enterCapture() {
        isCapturing = true
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    }

    fun exitCapture() {
        isCapturing = false
<<<<<<< HEAD
        onCaptured = null
        onCaptureCancelled = null
        highlightedRects = emptyList()
=======
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    }
}
