package com.simely.adskip.util

/**
 * 运行时调试开关（由 adb 广播控制，无需重编译安装）。
 * 开启后会输出更多匹配决策日志，便于定位误触/漏点根因。
 */
object DebugFlags {
    /** 匹配级追踪：开启后在每次扫描时打印"每条规则命中/拒绝原因" */
    @Volatile
    var traceEnabled: Boolean = false
}