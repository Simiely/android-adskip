package com.simely.adskip.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.simely.adskip.model.Rule
import com.simely.adskip.model.RuleSet

/**
 * 规则存储（加密）：仅管理手动捕获的按钮规则。
 * 关键词 → KeywordStore，屏蔽 → BlockedRuleStore。
 */
class RuleStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context, "adskip_rules",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // 已转正规则的进程内缓存：轮询器会高频调用 activeRules()，避免每次读取都解密 SharedPreferences。
    // 任意写操作经 saveRules 立即失效；再加一个 TTL 兜底自愈。
    private var activeCache: List<Rule>? = null
    private var activeCacheAt = 0L

    fun getRules(): List<Rule> {
        val json = prefs.getString(KEY_RULES_JSON, null) ?: return emptyList()
        return runCatching { RuleSet.parse(json).rules }.getOrDefault(emptyList())
    }

    /** 已转正的精确规则（带进程内缓存，用于 UI 展示/计数/轮询判定） */
    fun activeRules(): List<Rule> {
        val now = System.currentTimeMillis()
        if (activeCache == null || now - activeCacheAt > ACTIVE_CACHE_TTL_MS) {
            activeCache = getRules().filter { it.approved }
            activeCacheAt = now
        }
        return activeCache.orEmpty()
    }

    /** 该包名下是否有任何已转正规则（决定前台轮询是否需要盯守/扫描） */
    fun hasActiveRuleFor(pkg: String): Boolean = activeRules().any { it.pkg == pkg }

    /** 该包名下是否存在"尚未执行"的已转正规则（fingerprint 不在已执行集合内） */
    fun activePendingFor(pkg: String, fired: Set<String>): Boolean =
        activeRules().any { it.pkg == pkg && it.fingerprint() !in fired }

    /** 全部规则（含候选/停用），供匹配层做完整过滤 */
    fun allRules(): List<Rule> = getRules()

    /**
     * 【固化规则】内置默认规则：随服务启动播种，去重幂等，属"手动规则"永不自动停用。
     * 固化对象：向日葵(com.oray.sunlogin) 的各类广告关闭按钮。
     * 这些规则由实际抓取节点树确认、经真机验证有效，因此写死进程序而非依赖自动捕获，
     * 保证"开箱即点"，也便于用户清理/重装规则库后仍能恢复。
     */
    fun ensureBuiltInRules() {
        val defaults = listOf(
            // 顶部弹窗卡片广告的右上角"折叠/关闭"X。viewId 稳定可点，点击后整张广告卡收起。
            Rule(
                text = null, viewId = "com.oray.sunlogin:id/close", pkg = "com.oray.sunlogin",
                activity = null, action = "click", name = "向日葵卡片关闭X"
            ),
            // 底部横幅广告自带的"×"关闭按钮。自身可点，点击后横幅消失。
            Rule(
                text = null, viewId = "com.oray.sunlogin:id/fl_close_advertise", pkg = "com.oray.sunlogin",
                activity = null, action = "click", name = "向日葵横幅广告关闭X"
            ),
            // 横幅广告右上角"不喜欢"图标（点了会弹"不喜欢广告"菜单）。本身不是关闭，是触发后续确认步骤。
            Rule(
                text = null, viewId = "com.oray.sunlogin:id/iv_dislike", pkg = "com.oray.sunlogin",
                activity = null, action = "click", name = "向日葵广告不喜欢X"
            ),
            // 点完"不喜欢"后的弹窗菜单，选第一项"不感兴趣"，从而真正关掉这条广告。
            // 该菜单项无 viewId，只能按文字匹配。
            Rule(
                text = "不感兴趣", viewId = null, pkg = "com.oray.sunlogin",
                activity = null, action = "click", name = "不喜欢确认·不感兴趣"
            )
        )
        defaults.forEach { addRule(it) }
    }

    fun addRule(rule: Rule) {
        val rules = getRules().toMutableList()
        if (rules.any { it.fingerprint() == rule.fingerprint() }) return
        rules.add(rule)
        saveRules(rules)
    }

    /**
     * 自动学习：不直接转正，先把记录降级为候选并累计 hits。
     * 仅当候选已被可信路径重复命中达阈值后才转正，避免把一次误点固化进规则。
     * 每次成功命中也重置 miss 计数（结果验证通过）。
     * @return 更新后的规则；null 表示未产生任何持久化变化或超出候选上限
     */
    fun learnFromHit(rule: Rule): Rule? {
        val rules = getRules()
        val idx = rules.indexOfFirst { it.fingerprint() == rule.fingerprint() }
        if (idx < 0) {
            // 每 App 候选上限：防止一个 App 内疯狂堆积不相干候选
            val candidateCount = rules.count { it.pkg == rule.pkg && !it.approved }
            if (candidateCount >= MAX_CANDIDATE_PER_APP) return null
            // 首次可信命中：建档为候选（hits=1），标记为自动学习，结果验证可降级
            val candidate = rule.copy(
                hits = 1, approved = false,
                createdAt = System.currentTimeMillis(), misses = 0, autoLearned = true
            )
            saveRules(rules + candidate)
            return candidate
        }
        val existing = rules[idx]
        if (existing.approved) return null // 已是转正规则，无需再加
        if (existing.disabled) return null   // 已停用的规则不再自动复活
        val bumped = existing.copy(
            hits = existing.hits + 1,
            misses = 0,
            approved = existing.hits + 1 >= Rule.PROMOTE_THRESHOLD
        )
        saveRules(rules.toMutableList().apply { this[idx] = bumped })
        return bumped
    }

    /**
     * 结果验证失败（点击后目标仍未消失）：仅对自动学习规则生效。
     * 连续未退场达到 MISS_LIMIT 后自动【停用】该规则，不再参与自动点击。
     */
    fun recordMiss(rule: Rule): Rule? {
        val rules = getRules()
        val idx = rules.indexOfFirst { it.fingerprint() == rule.fingerprint() }
        if (idx < 0) return null
        val existing = rules[idx]
        if (!existing.autoLearned) return null // 手动/导入规则永不自动停用
        val misses = existing.misses + 1
        val updated = if (misses >= Rule.MISS_LIMIT) {
            existing.copy(misses = misses, disabled = true)
        } else {
            existing.copy(misses = misses)
        }
        saveRules(rules.toMutableList().apply { this[idx] = updated })
        return updated
    }

    /** 清空某个 App 的全部规则 */
    fun clearByPkg(pkg: String) {
        saveRules(getRules().filter { it.pkg != pkg })
    }

    /**
     * 清理"危险范式"的已转正规则：无任何 text/viewId/描述，仅剩 className（或空）。
     * 这类规则匹配时会泛滥命中整类控件，是"乱点/误同意协议"的污染源。
     */
    fun sanitizeDangerousRules() {
        val rules = getRules()
        val kept = rules.filter { r -> !(r.approved && r.isDangerousPattern()) }
        if (kept.size != rules.size) saveRules(kept)
    }

    /**
     * 老化清理：移除长期未能转正的"卡死候选"（未达到阈值且建档过久）。
     * 已转正/手动规则绝不删除。
     */
    fun pruneExpiredCandidates(timeoutMs: Long = 30L * 24 * 3600 * 1000) {
        val now = System.currentTimeMillis()
        val rules = getRules()
        val kept = rules.filter {
            it.approved || it.createdAt == 0L || now - it.createdAt < timeoutMs
        }
        if (kept.size != rules.size) saveRules(kept)
    }

    fun removeRule(fingerprint: String) {
        saveRules(getRules().filter { it.fingerprint() != fingerprint })
    }

    fun clear() { saveRules(emptyList()) }

    fun mergeRemote(set: RuleSet, keywordStore: KeywordStore) {
        val userKw = keywordStore.getUser()
        userKw.addAll(set.keywords.filter { it !in keywordStore.defaultKeywords })
        keywordStore.saveUser(userKw)

        val existing = getRules().toMutableList()
        for (r in set.rules) {
            if (existing.none { it.fingerprint() == r.fingerprint() }) {
                existing.add(r)
            }
        }
        saveRules(existing)
    }

    private fun saveRules(rules: List<Rule>) {
        activeCache = null // 写后立即失效缓存，避免轮询读到旧规则
        val json = RuleSet(emptySet(), rules).toJsonString()
        prefs.edit().putString(KEY_RULES_JSON, json).apply()
    }

    companion object {
        private const val KEY_RULES_JSON = "rules_json"
        /** 每个 App 未转正候选的最大数量，超出不再新建候选，防止规则池被不相干节点撑爆 */
        const val MAX_CANDIDATE_PER_APP = 10
        /** 已转正规则缓存 TTL：写操作会立即失效，此 TTL 仅作兜底自愈 */
        private const val ACTIVE_CACHE_TTL_MS = 2000L
    }
}
