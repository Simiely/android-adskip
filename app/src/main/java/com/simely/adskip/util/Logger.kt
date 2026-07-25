package com.simely.adskip.util

import android.util.Log

/**
 * 统一日志工具：Debug 构建输出所有日志，Release 仅输出 warn/error。
 * TAG 自动取调用类名。
 */
object Logger {

    /** 是否启用详细日志（debug 构建为 true，release 为 false） */
    var isDebug: Boolean = false

    fun d(tag: String, msg: String) { if (isDebug) Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) { if (isDebug) Log.d(tag, msg, tr) }
    fun i(tag: String, msg: String) { Log.i(tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) { Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, msg, tr) }
}

/** 扩展函数，自动取类名作为 TAG */
inline fun Any.logd(msg: () -> String) { if (Logger.isDebug) Log.d(javaClass.simpleName, msg()) }
inline fun Any.logi(msg: () -> String) { Log.i(javaClass.simpleName, msg()) }
inline fun Any.logw(msg: () -> String) { Log.w(javaClass.simpleName, msg()) }
inline fun Any.loge(msg: () -> String) { Log.e(javaClass.simpleName, msg()) }
inline fun Any.loge(msg: () -> String, tr: Throwable) { Log.e(javaClass.simpleName, msg(), tr) }
