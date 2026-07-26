package com.simely.adskip.util

import android.util.Log

/**
 * 统一日志工具：可通过 isDebug 开关控制日志级别。
 */
object Logger {
    private const val TAG = "AdSkip"

    /** 调试模式开关（生产环境应关闭） */
    var isDebug: Boolean = false

    fun d(msg: String) {
        if (isDebug) Log.d(TAG, msg)
    }

    fun w(msg: String, e: Throwable? = null) {
        if (e != null) Log.w(TAG, msg, e) else Log.w(TAG, msg)
    }

    fun e(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
    }
}
