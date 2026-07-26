package com.simely.adskip

import android.graphics.Rect

object AppState {
    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var onCaptured: (() -> Unit)? = null
        private set

    @Volatile
    var onCaptureCancelled: (() -> Unit)? = null
        private set

    /** 捕获模式下检测到的可点击节点边界，用于绘制高亮图层 */
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
