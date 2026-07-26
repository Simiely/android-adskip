package com.simely.adskip.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.simely.adskip.model.Rule
import com.simely.adskip.model.RuleSet

/**
 * 本地规则存储（加密）。
 * - 关键词：内置保守默认词 + 用户新增词
 * - 规则：手动捕获的指纹规则（JSON 形式持久化）
 */
class RuleStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "adskip_rules",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** 内置保守默认关键词（误触最低） */
    val defaultKeywords: Set<String> get() = DEFAULT_KEYWORDS

    fun getKeywords(): Set<String> {
        val user = prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet()
        return DEFAULT_KEYWORDS + user
    }

    fun getUserKeywords(): MutableSet<String> {
        return LinkedHashSet(prefs.getStringSet(KEY_USER_KEYWORDS, emptySet()) ?: emptySet())
    }

    fun addKeyword(k: String) {
        val kw = getUserKeywords().apply { add(k.trim()) }
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, kw).apply()
    }

    fun removeKeyword(k: String) {
        val kw = getUserKeywords().apply { remove(k) }
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, kw).apply()
    }

    fun getRules(): List<Rule> {
        val json = prefs.getString(KEY_RULES_JSON, null) ?: return emptyList()
        return runCatching { RuleSet.parse(json).rules }.getOrDefault(emptyList())
    }

    fun addRule(rule: Rule) {
        val rules = getRules().toMutableList()
        if (rules.any { it.fingerprint() == rule.fingerprint() }) return
        rules.add(rule)
        saveRules(rules)
    }

    fun removeRule(fingerprint: String) {
        saveRules(getRules().filter { it.fingerprint() != fingerprint })
    }

    fun updateRuleText(fingerprint: String, newText: String) {
        saveRules(getRules().map { if (it.fingerprint() == fingerprint) it.copy(text = newText) else it })
    }

    fun clear() { saveRules(emptyList()) }

    /** 导出完整集合（含默认关键词），用于上传 */
    fun exportSet(): RuleSet = RuleSet(getKeywords(), getRules())

    /** 下载合并：关键词取并集（过滤默认词避免冗余），规则按指纹去重 */
    fun mergeRemote(set: RuleSet) {
        val userKw = getUserKeywords()
        // 只合并非默认的远程关键词，避免内置默认词冗余膨胀
        userKw.addAll(set.keywords.filter { it !in DEFAULT_KEYWORDS })
        prefs.edit().putStringSet(KEY_USER_KEYWORDS, userKw).apply()

        val existing = getRules().toMutableList()
        for (r in set.rules) {
            if (existing.none { it.fingerprint() == r.fingerprint() }) {
                existing.add(r)
            }
        }
        saveRules(existing)
    }

    private fun saveRules(rules: List<Rule>) {
        val json = RuleSet(emptySet(), rules).toJsonString()
        prefs.edit().putString(KEY_RULES_JSON, json).apply()
    }

    // ── 屏蔽规则（优先级最高，匹配到的按钮不点）──

    /** 屏蔽指纹分隔符（\u0000 不会出现在包名/文本中） */
    private val BLOCK_SEP = "\u0000"

    /** 检查某个按钮是否被屏蔽（精确字段匹配） */
    fun isBlocked(pkg: String, text: String?, viewId: String?): Boolean {
        val blocked = getBlockedSet()
        if (blocked.isEmpty()) return false
        if (pkg.isEmpty()) return false
        return blocked.any { bf ->
            val parts = bf.split(BLOCK_SEP)
            if (parts.size < 3) return@any false
            if (parts[0] != pkg) return@any false
            if (!viewId.isNullOrEmpty() && parts[1] == viewId) return@any true
            if (!text.isNullOrEmpty() && parts[2] == text) return@any true
            false
        }
    }

    fun addBlocked(pkg: String, text: String, viewId: String) {
        val fp = listOf(pkg, viewId.ifEmpty { "" }, text).joinToString(BLOCK_SEP)
        val set = getBlockedSet().toMutableSet()
        set.add(fp)
        prefs.edit().putStringSet(KEY_BLOCKED, set).apply()
    }

    fun removeBlocked(fingerprint: String) {
        val set = getBlockedSet().toMutableSet()
        set.remove(fingerprint)
        prefs.edit().putStringSet(KEY_BLOCKED, set).apply()
    }

    fun getBlockedSet(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_USER_KEYWORDS = "user_keywords"
        private const val KEY_RULES_JSON = "rules_json"
        private const val KEY_BLOCKED = "blocked"
        private val DEFAULT_KEYWORDS = setOf(
            "跳过",
            "跳过广告",
            "跳过视频广告",
            "关闭广告"
        )
    }
}
