package com.simely.adskip.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 点击统计：每日/月/年计数、最近日志、总点击、使用时间。
 * 全部存 SharedPreferences，零数据库依赖。
 */
class StatsStore(context: Context) {

    private val prefs = context.getSharedPreferences("adskip_stats", Context.MODE_PRIVATE)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val yearFmt = SimpleDateFormat("yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── 每日计数 ──
    fun getDailyCount(date: String): Int = dailyMap()[date] ?: 0
    fun getTodayCount(): Int = getDailyCount(dateFmt.format(Date()))
    fun getTotalCount(): Long = prefs.getLong(KEY_TOTAL, 0L)

    fun getDailyMap(): Map<String, Int> = dailyMap()
    fun getMonthlyMap(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        dailyMap().forEach { (date, count) ->
            val month = date.substring(0, 7)
            result[month] = (result[month] ?: 0) + count
        }
        return result
    }
    fun getYearlyMap(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        dailyMap().forEach { (date, count) ->
            val year = date.substring(0, 4)
            result[year] = (result[year] ?: 0) + count
        }
        return result
    }

    /** 最近 7 天（含今天）的每日次数，用于走势图 */
    fun getLast7Days(): List<Pair<String, Int>> {
        val cal = Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val d = dateFmt.format(cal.time)
            result.add(d to (dailyMap()[d] ?: 0))
        }
        return result
    }

    fun recordClick() {
        val today = dateFmt.format(Date())
        val map = dailyMap().toMutableMap()
        map[today] = (map[today] ?: 0) + 1
        prefs.edit().putString(KEY_DAILY, mapToJson(map)).apply()
        prefs.edit().putLong(KEY_TOTAL, getTotalCount() + 1).apply()
        if (prefs.getLong(KEY_START, 0L) == 0L) {
            prefs.edit().putLong(KEY_START, System.currentTimeMillis()).apply()
        }
    }

    /** 使用天数 = 从首次点击到今天的天数 */
    fun getUsageDays(): Int {
        val start = prefs.getLong(KEY_START, 0L)
        if (start == 0L) return 0
        return ((System.currentTimeMillis() - start) / 86400000L).toInt() + 1
    }

    fun getFirstUseTime(): Long = prefs.getLong(KEY_START, 0L)

    // ── 最近日志（最多 100 条） ──
    fun getRecentLogs(): List<ClickLog> {
        val json = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        val arr = JSONArray(json)
        val result = mutableListOf<ClickLog>()
        for (i in 0 until arr.length()) {
            result.add(ClickLog.fromJson(arr.getJSONObject(i)))
        }
        return result
    }

    fun addLog(pkg: String, text: String, viewId: String?) {
        val logs = getRecentLogs().toMutableList()
        logs.add(0, ClickLog(
            pkg = pkg,
            text = text,
            viewId = viewId,
            time = System.currentTimeMillis()
        ))
        // 只保留最近 100 条
        val trimmed = if (logs.size > 100) logs.subList(0, 100) else logs
        prefs.edit().putString(KEY_LOGS, JSONArray().apply {
            trimmed.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    // ── 分享文案 ──
    fun getShareText(): String {
        val total = getTotalCount()
        val days = getUsageDays()
        val months = getMonthlyMap().size
        val first = if (getFirstUseTime() > 0) dateFmt.format(Date(getFirstUseTime())) else "今天"
        return buildString {
            appendLine("📊 AdSkip 使用统计")
            appendLine("━━━━━━━━━━━━━━")
            appendLine("总点击次数：$total 次")
            appendLine("累计使用：$days 天（$months 个月）")
            appendLine("今日点击：${getTodayCount()} 次")
            appendLine("首次使用：$first")
            appendLine("━━━━━━━━━━━━━━")
            appendLine("AdSkip - 自动跳过广告工具")
        }
    }

    // ── 内部 ──
    private fun dailyMap(): Map<String, Int> {
        val json = prefs.getString(KEY_DAILY, null) ?: return emptyMap()
        return try { jsonToMap(json) } catch (_: Exception) { emptyMap() }
    }

    private fun mapToJson(map: Map<String, Int>): String = JSONObject(map).toString()
    private fun jsonToMap(json: String): Map<String, Int> {
        val o = JSONObject(json)
        val result = mutableMapOf<String, Int>()
        o.keys().forEach { result[it] = o.getInt(it) }
        return result
    }

    data class ClickLog(
        val pkg: String,
        val text: String,
        val viewId: String?,
        val time: Long
    ) {
        fun toJson() = JSONObject().apply {
            put("pkg", pkg)
            put("text", text)
            put("viewId", viewId ?: "")
            put("time", time)
        }
        companion object {
            fun fromJson(o: JSONObject) = ClickLog(
                pkg = o.optString("pkg"),
                text = o.optString("text"),
                viewId = o.optString("viewId").takeIf { it.isNotEmpty() },
                time = o.optLong("time")
            )
        }
        fun formattedTime(): String {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return fmt.format(Date(time))
        }
    }

    companion object {
        private const val KEY_DAILY = "daily_counts"
        private const val KEY_TOTAL = "total_clicks"
        private const val KEY_START = "first_use"
        private const val KEY_LOGS = "recent_logs"
    }
}
