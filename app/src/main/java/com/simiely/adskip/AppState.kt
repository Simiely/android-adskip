package com.simely.adskip

/**
 * 跨组件共享的轻量状态（无障碍服务 / 悬浮窗 / 设置页 之间）。
 * - @Volatile 保证 isCapturing 在多线程间的可见性
 * - 回调通过 @Synchronized 保护，避免并发覆盖
 */
object AppState {
    /** 是否处于"手动捕获模式" */
    @Volatile
    var isCapturing: Boolean = false
        private set

    /** 捕获成功（已记录一条规则）后回调，用于刷新 UI */
    @Volatile
    var onCaptured: (() -> Unit)? = null
        private set

    /** 用户取消捕获后回调，用于移除提示遮罩 */
    @Volatile
    var onCaptureCancelled: (() -> Unit)? = null
        private set

    @Synchronized
    fun enterCapture(onCaptured: () -> Unit, onCancelled: () -> Unit) {
        isCapturing = true
        this.onCaptured = onCaptured
        this.onCaptureCancelled = onCancelled
    }

    @Synchronized
    fun exitCapture() {
        isCapturing = false
        onCaptured = null
        onCaptureCancelled = null
    }
}
