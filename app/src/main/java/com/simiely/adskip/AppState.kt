package com.simely.adskip

/**
 * 跨组件共享的轻量状态（无障碍服务 / 悬浮窗 / 设置页 之间）。
 * 仅保存捕获模式开关与回调，不持有任何重对象。
 */
object AppState {
    /** 是否处于“手动捕获模式” */
    var isCapturing: Boolean = false

    /** 捕获成功（已记录一条规则）后回调，用于刷新 UI */
    var onCaptured: (() -> Unit)? = null

    /** 用户取消捕获后回调，用于移除提示遮罩 */
    var onCaptureCancelled: (() -> Unit)? = null

    fun enterCapture() {
        isCapturing = true
    }

    fun exitCapture() {
        isCapturing = false
    }
}
