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

    // 内置硬屏蔽（id 级）：启动常驻合并，清空规则也无法移除。
    // 【固化屏蔽】向日葵"手机投屏/共享屏幕给他人"功能卡(com.oray.sunlogin:id/fl_screen_projection_status)：
    //   - 它是主界面的"投屏给他人"功能卡片，本身不是广告，点击会弹出投屏授权/功能弹窗；
    //   - 因与横幅广告同框、且可点击，被匹配器误判为可关闭目标导致误点；
    //   - 用户明确要求"真正屏蔽、清空也不能移除"，故写死常驻。
    //   注意：该 id 常与横幅关闭按钮同处一棵树，屏蔽它不影响同包其他关闭规则的命中（匹配器按候选逐个跳过）。
    private val defaultBlockedIds = listOf(
        Triple("com.oray.sunlogin", "com.oray.sunlogin:id/fl_screen_projection_status", "")
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
        // 始终合并内置 id 级硬屏蔽（幂等，清空规则也无法移除）
        for ((pkg, vid, text) in defaultBlockedIds) {
            result.add(listOf(pkg, vid, text).joinToString(BLOCK_SEP))
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
