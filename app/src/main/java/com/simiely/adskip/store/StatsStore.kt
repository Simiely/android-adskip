package com.simely.adskip.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("adskip_stats", Context.MODE_PRIVATE)
    private val dateFmt: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun getTodayCount(): Int = getDailyCount(dateFmt.format(Date()))
    fun getTotalCount(): Long = prefs.getLong(KEY_TOTAL, 0L)
    fun getUsageDays(): Int {
        val start = prefs.getLong(KEY_START, 0L)
        if (start == 0L) return 0
        return ((System.currentTimeMillis() - start) / 86400000L).toInt() + 1
    }

    fun getDailyCount(date: String): Int = dailyMap()[date] ?: 0

    fun getLast7Days(): List<Pair<String, Int>> {
        val cal = Calendar.getInstance()
        return (6 downTo 0).map { i ->
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            dateFmt.format(cal.time).let { it to (dailyMap()[it] ?: 0) }
        }
    }

    fun getMonthlyData(): Map<String, Int> {
        val r = mutableMapOf<String, Int>()
        dailyMap().forEach { (d, c) -> r[d.substring(0, 7)] = (r[d.substring(0, 7)] ?: 0) + c }
        return r
    }

    fun getYearlyData(): Map<String, Int> {
        val r = mutableMapOf<String, Int>()
        dailyMap().forEach { (d, c) -> r[d.substring(0, 4)] = (r[d.substring(0, 4)] ?: 0) + c }
        return r
    }

    fun recordClick(pkg: String, text: String, viewId: String = "") {
        val today = dateFmt.format(Date())
        val map = dailyMap().toMutableMap()
        map[today] = (map[today] ?: 0) + 1
        prefs.edit()
            .putString(KEY_DAILY, JSONObject(map as Map<String, Any>).toString())
            .putLong(KEY_TOTAL, getTotalCount() + 1)
            .putLong(KEY_START, if (prefs.getLong(KEY_START, 0L) == 0L) System.currentTimeMillis() else prefs.getLong(KEY_START, 0L))
            .apply()
        addLog(pkg, text, viewId)
    }

    fun getRecentLogs(limit: Int = 20): List<ClickLog> {
        val j = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        try {
            val arr = JSONArray(j)
            return (0 until minOf(arr.length(), limit)).map { ClickLog.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { return emptyList() }
    }

    private fun addLog(app: String, text: String, viewId: String = "") {
        val logs = getRecentLogs(100).toMutableList()
        logs.add(0, ClickLog(app, text, System.currentTimeMillis(), viewId))
        if (logs.size > 100) logs.removeAt(logs.lastIndex)
        prefs.edit().putString(KEY_LOGS, JSONArray().apply { logs.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun dailyMap(): Map<String, Int> {
        val j = prefs.getString(KEY_DAILY, null) ?: return emptyMap()
        try {
            val o = JSONObject(j)
            val r = mutableMapOf<String, Int>()
            o.keys().forEach { r[it] = o.getInt(it) }
            return r
        } catch (_: Exception) { return emptyMap() }
    }

    fun getShareText(): String = buildString {
        appendLine("AdSkip 使用统计")
        appendLine("总点击：${getTotalCount()} 次")
        appendLine("使用天数：${getUsageDays()} 天")
        appendLine("今日：${getTodayCount()} 次")
    }

    data class ClickLog(val app: String, val text: String, val time: Long, val viewId: String = "") {
        fun toJson() = JSONObject().apply {
            put("a", app)
            put("t", text)
            put("ts", time)
            if (viewId.isNotEmpty()) put("v", viewId)
        }
        companion object {
            fun fromJson(o: JSONObject) = ClickLog(o.optString("a"), o.optString("t"), o.optLong("ts"), o.optString("v"))
        }
        fun formattedTime(): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    fun clearLogs() = prefs.edit().remove(KEY_LOGS).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_DAILY = "daily"
        private const val KEY_TOTAL = "total"
        private const val KEY_START = "start"
        private const val KEY_LOGS = "logs"
    }
}
