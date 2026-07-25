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

    fun getDailyCount(date: String): Int = dailyMap()[date] ?: 0
    fun getTodayCount(): Int = getDailyCount(dateFmt.format(Date()))
    fun getTotalCount(): Long = prefs.getLong(KEY_TOTAL, 0L)

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

    fun recordClick() {
        val today = dateFmt.format(Date())
        val map = dailyMap().toMutableMap()
        map[today] = (map[today] ?: 0) + 1
        prefs.edit().putString(KEY_DAILY, JSONObject(map).toString()).apply()
        prefs.edit().putLong(KEY_TOTAL, getTotalCount() + 1).apply()
        if (prefs.getLong(KEY_START, 0L) == 0L) {
            prefs.edit().putLong(KEY_START, System.currentTimeMillis()).apply()
        }
    }

    fun getUsageDays(): Int {
        val start = prefs.getLong(KEY_START, 0L)
        if (start == 0L) return 0
        return ((System.currentTimeMillis() - start) / 86400000L).toInt() + 1
    }

    fun getRecentLogs(): List<ClickLog> {
        val json = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        val arr = JSONArray(json)
        val result = mutableListOf<ClickLog>()
        for (i in 0 until arr.length()) {
            result.add(ClickLog.fromJson(arr.getJSONObject(i)))
        }
        return result
    }

    fun addLog(app: String, text: String) {
        val logs = getRecentLogs().toMutableList()
        logs.add(0, ClickLog(app = app, text = text, time = System.currentTimeMillis()))
        if (logs.size > 100) logs.removeAt(logs.lastIndex)
        prefs.edit().putString(KEY_LOGS, JSONArray().apply {
            logs.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    data class ClickLog(
        val app: String,
        val text: String,
        val time: Long
    ) {
        fun toJson() = JSONObject().apply {
            put("app", app); put("text", text); put("time", time)
        }
        companion object {
            fun fromJson(o: JSONObject) = ClickLog(
                app = o.optString("app"), text = o.optString("text"), time = o.optLong("time")
            )
        }
        fun formattedTime(): String {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return fmt.format(Date(time))
        }
    }

    fun getShareText(): String = buildString {
        appendLine("📊 AdSkip 使用统计")
        appendLine("━━━━━━━━")
        appendLine("总点击：${getTotalCount()} 次")
        appendLine("使用天数：${getUsageDays()} 天")
        appendLine("今日：${getTodayCount()} 次")
    }

    private fun dailyMap(): Map<String, Int> {
        val json = prefs.getString(KEY_DAILY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            val r = mutableMapOf<String, Int>()
            o.keys().forEach { r[it] = o.getInt(it) }
            r
        } catch (_: Exception) { emptyMap() }
    }

    companion object {
        private const val KEY_DAILY = "dc"
        private const val KEY_TOTAL = "tc"
        private const val KEY_START = "fs"
        private const val KEY_LOGS = "rl"
    }
}
