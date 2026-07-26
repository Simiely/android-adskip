package com.simely.adskip

import android.graphics.Rect

/**
 * 跨组件共享的轻量状态（无障碍服务 / 悬浮窗 / 设置页 之间）。
 * 仅保存捕获模式开关、回调与高亮数据，不持有任何重对象。
 */
object AppState {

    @Volatile
    var isCapturing: Boolean = false

    /** 捕获成功（已记录一条规则）后回调，用于刷新 UI */
    @Volatile
    var onCaptured: (() -> Unit)? = null

    /** 用户取消捕获后回调，用于移除提示遮罩 */
    @Volatile
    var onCaptureCancelled: (() -> Unit)? = null

    /** 捕获模式下检测到的可点击节点边界，用于绘制高亮图层 */
    @Volatile
    var highlightedRects: List<Rect> = emptyList()

    @Synchronized
    fun enterCapture(onCaptured: () -> Unit, onCancelled: () -> Unit) {
        isCapturing = true
        this.onCaptured = onCaptured
        this.onCaptureCancelled = onCancelled
        highlightedRects = emptyList()
    }

    @Synchronized
    fun exitCapture() {
        isCapturing = false
        onCaptured = null
        onCaptureCancelled = null
        highlightedRects = emptyList()
    }
}
