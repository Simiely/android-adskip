package com.simely.adskip.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * 运行时动作历史环形缓冲：记录扫描/点击/验证等关键动作，供 adb 接口回查最近发生了什么。
 * 帮助回看"点到奇怪的地方"、规则是否触发、为什么没点。
 */
object ActionHistory {
    private const val CAP = 80

    private data class Entry(val at: Long, val tag: String, val msg: String)

    private val buf = ArrayDeque<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(tag: String, msg: String) {
        buf.addLast(Entry(System.currentTimeMillis(), tag, msg))
        while (buf.size > CAP) buf.removeFirst()
    }

    /** 返回最近动作的格式化行（时间戳 + 类型 + 内容），最新的在最后 */
    @Synchronized
    fun dump(): List<String> =
        buf.map { "${fmt.format(Date(it.at))} [${it.tag}] ${it.msg}" }
}