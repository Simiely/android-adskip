package com.simely.adskip.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内调试日志：内存环形缓存，记录 Logger 的原始执行轨迹，
 * 供界面直接查看/复制，无需依赖 adb logcat。
 */
object DebugLog {
    private const val MAX = 400
    private val buffer = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(line: String) {
        // 过滤高频噪音，避免被 systemui 过滤/冷却提示刷屏淹没关键执行轨迹
        if (line.contains("被过滤(黑白名单)，跳过") || line.contains("冷却中(")) return
        val ts = fmt.format(Date())
        buffer.addLast("$ts  $line")
        while (buffer.size > MAX) buffer.removeFirst()
    }

    /** 最新在前 */
    @Synchronized
    fun lines(limit: Int = Int.MAX_VALUE): List<String> =
        buffer.toList().asReversed().take(limit)

    @Synchronized
    fun clear() = buffer.clear()

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n") { it }
}