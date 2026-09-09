package com.simely.adskip.service.matcher

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.Logger

/**
 * 规则匹配引擎：5 层匹配策略 + 屏蔽检查。
 * 依赖三个独立 Store：KeywordStore / RuleStore / BlockedRuleStore。
 */
class RuleMatcher(
    private val keywordStore: KeywordStore,
    private val ruleStore: RuleStore,
    private val blockedRuleStore: BlockedRuleStore
) {
    fun findTargets(
        root: AccessibilityNodeInfo, pkg: String,
        keywordEnabled: Boolean,
        screenW: Int = 1080, screenH: Int = 2400,
        activity: String? = null
    ): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()

        // 1. 规则优先（候选与已转正都参与点击，让"捕获成功即可生效"）。按指定性降序。
        //    activity 作用域：仅当"规则声明了 Activity 且当前也已知 Activity"时强校验，避免弹窗窗口因 className 抖动而误压掉规则。
        //    disabled 规则不再参与点击（结果验证连错后自动停用）。
        val activeRules = ruleStore.allRules()
            .filter { r ->
                if (r.pkg != pkg || r.disabled) return@filter false
                if (r.activity.isNullOrEmpty() || activity.isNullOrEmpty()) return@filter true
                r.activity == activity
            }
            .sortedByDescending { it.specificity() }
        for (rule in activeRules) {
            if (targets.size >= MAX_TARGETS) break
            val hits = findByRule(root, rule).filter { AccessibilityUtil.isClickable(it) }
            if (hits.isNotEmpty())
                Logger.d("  → 规则命中[${rule.shortDescription()}] 节点=${hits.size}")
            targets.addAll(hits)
        }
        // 2. 关键词（规则未命中时的兜底）
        if (targets.isEmpty() && keywordEnabled) {
            for (kw in keywordStore.getAll()) {
                if (kw.isBlank()) continue
                runCatching { root.findAccessibilityNodeInfosByText(kw) }
                    .getOrDefault(emptyList())
                    .filter { AccessibilityUtil.isClickable(it) }
                    .let { targets.addAll(it) }
            }
        }
        // 3. contentDescription
        if (targets.isEmpty() && keywordEnabled) {
            for (kw in keywordStore.getAll()) {
                if (kw.isBlank()) continue
                targets.addAll(AccessibilityUtil.scanByContentDescription(root, kw))
            }
        }
        // 4. ✕/关闭
        if (targets.isEmpty() && keywordEnabled)
            targets.addAll(AccessibilityUtil.scanSkipOrCloseButtons(root, screenW, screenH))
        // 5. 深度跳字
        if (targets.isEmpty() && keywordEnabled)
            targets.addAll(AccessibilityUtil.deepScanForSkipText(root))

        // 统一过滤不可见/离屏目标：规则或关键词命中的节点若是隐藏/反向bounds，ACTION_CLICK 会被应用忽略，
        // 造成"点了但目标仍在"的假死。只点击"对用户可见且 bounds 有效"的节点。
        return targets.filter { AccessibilityUtil.isClickable(it) && isVisibleTarget(it) }
    }

    /** 节点是否真实可感知：可见且有正向的有效屏幕 bounds（排除收起/离屏/负高度） */
    private fun isVisibleTarget(node: AccessibilityNodeInfo): Boolean {
        if (runCatching { !node.isVisibleToUser() }.getOrDefault(true)) return false
        val b = Rect()
        try { node.getBoundsInScreen(b) } catch (_: Exception) { return false }
        return b.width() > 0 && b.height() > 0
    }

    private fun findByRule(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        // 组合约束：若规则同时声明了 viewId/text 与 className，则命中的节点必须同时满足，避免"同文案不同控件"误点。
        val classConstraint = rule.className?.takeIf { it.isNotBlank() }

        fun matchesConstraint(node: AccessibilityNodeInfo): Boolean {
            if (classConstraint == null) return true
            val clz = node.className?.toString()
            return clz == classConstraint ||
                // 兜底：可点击祖先上溯一层（按钮容器类名常与服务上命中文本的子节点不同）
                runCatching { node.parent?.className?.toString() }.getOrNull() == classConstraint
        }

        if (!rule.viewId.isNullOrEmpty()) {
            val byId = runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }
                .getOrDefault(emptyList())
            if (byId.isNotEmpty()) return byId.filter { matchesConstraint(it) }
        }
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (c in rule.textCandidates()) {
            runCatching { root.findAccessibilityNodeInfosByText(c) }
                .getOrDefault(emptyList())
                .filter { matchesConstraint(it) }
                .let { results.addAll(it) }
            if (results.isNotEmpty()) break
        }
        if (results.isNotEmpty()) return results
        if (classConstraint != null) return AccessibilityUtil.findNodesByClass(root, classConstraint)
        return emptyList()
    }

    fun isBlocked(pkg: String, text: String?, viewId: String?) =
        blockedRuleStore.isBlocked(pkg, text, viewId)

    /** 自动捕获入口：不直接入库，走置信门槛（累计命中后转正） */
    fun autoCaptureRule(rule: Rule) = ruleStore.learnFromHit(rule)

    companion object {
        /** 一轮匹配最多保留的候选目标数，避免高频事件下过量处理 */
        private const val MAX_TARGETS = 12
    }
}
