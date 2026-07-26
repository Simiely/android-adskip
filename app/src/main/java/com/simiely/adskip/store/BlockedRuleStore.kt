package com.simely.adskip.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 屏蔽规则存储：使用独立明文 SharedPreferences（无需加密），
 * 避免与 RuleStore/KeywordStore 共用 EncryptedSharedPreferences 导致的读写问题。
 */
class BlockedRuleStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("adskip_blocked", Context.MODE_PRIVATE)

    private val BLOCK_SEP = "###"

    val defaultBlockedPkgs = setOf(
        "com.miui.home", "com.android.systemui", "com.android.settings",
        "com.android.launcher", "com.google.android.apps.nexuslauncher"
    )

    data class BlockedRule(val pkg: String, val viewId: String, val text: String)

    // ── 读写 ──

    private fun getAll(): Set<String> {
        val json = prefs.getString(KEY_DATA, null)
        val stored = if (json != null) {
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (_: Exception) { emptySet<String>() }
        } else emptySet<String>()
        // 始终合并系统默认屏蔽包（幂等）
        val result = stored.toMutableSet()
        for (pkg in defaultBlockedPkgs) {
            result.add(listOf(pkg, "", pkg).joinToString(BLOCK_SEP))
        }
        return result
    }

    private fun save(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(KEY_DATA, arr.toString()).commit()
    }

    // ── 查询 ──

    fun isBlocked(pkg: String, text: String?, viewId: String?): Boolean {
        val blocked = getAll()
        if (blocked.isEmpty() || pkg.isEmpty()) return false
        return blocked.any { bf ->
            val parts = bf.split(BLOCK_SEP)
            if (parts.size < 3) return@any false
            if (parts[0] != pkg) return@any false
            if (!viewId.isNullOrEmpty() && parts[1] == viewId) return@any true
            if (!text.isNullOrEmpty() && parts[2].isNotEmpty() && text.contains(parts[2])) return@any true
            false
        }
    }

    fun getBlockedRules(): List<BlockedRule> {
        return getAll().mapNotNull { fp ->
            val parts = fp.split(BLOCK_SEP)
            if (parts.size >= 3) BlockedRule(parts[0], parts[1], parts[2]) else null
        }
    }

    // ── 增删 ──

    fun add(pkg: String, text: String, viewId: String) {
        val fp = listOf(pkg, viewId.ifEmpty { "" }, text).joinToString(BLOCK_SEP)
        val set = getAll().toMutableSet()
        set.add(fp)
        save(set)
    }

    fun removeByFields(pkg: String, viewId: String, text: String) {
        val fp = listOf(pkg, viewId.ifEmpty { "" }, text).joinToString(BLOCK_SEP)
        val set = getAll().toMutableSet()
        set.remove(fp)
        save(set)
    }

    fun clear() {
        save(emptySet())
    }

    fun ensureSystemBlocked() {
        // 默认值已在 getAll() 中合并，此方法仅用于清理旧格式
        val current = getAll().toMutableSet()
        current.removeAll { it.contains("\u0000") }
        save(current.filter { fp ->
            val parts = fp.split(BLOCK_SEP)
            parts.size >= 3 && parts[0] !in defaultBlockedPkgs
        }.toSet())
    }

    companion object {
        private const val KEY_DATA = "data"
    }
}
