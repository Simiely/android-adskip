package com.simely.adskip.service.matcher

import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.AccessibilityUtil

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
        screenW: Int = 1080, screenH: Int = 2400
    ): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()

        // 1. 关键词
        if (keywordEnabled) {
            for (kw in keywordStore.getAll()) {
                if (kw.isBlank()) continue
                runCatching { root.findAccessibilityNodeInfosByText(kw) }
                    .getOrDefault(emptyList())
                    .filter { AccessibilityUtil.isClickable(it) }
                    .let { targets.addAll(it) }
            }
        }
        // 2. 手动规则
        for (rule in ruleStore.getRules()) {
            if (rule.pkg != pkg) continue
            targets.addAll(findByRule(root, rule).filter { AccessibilityUtil.isClickable(it) })
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

        return targets
    }

    private fun findByRule(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        if (!rule.viewId.isNullOrEmpty())
            return runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }.getOrDefault(emptyList())
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (c in rule.textCandidates()) {
            runCatching { root.findAccessibilityNodeInfosByText(c) }.getOrDefault(emptyList()).let { results.addAll(it) }
            if (results.isNotEmpty()) break
        }
        if (results.isNotEmpty()) return results
        if (!rule.className.isNullOrEmpty()) return AccessibilityUtil.findNodesByClass(root, rule.className!!)
        return emptyList()
    }

    fun isBlocked(pkg: String, text: String?, viewId: String?) =
        blockedRuleStore.isBlocked(pkg, text, viewId)

    fun autoCaptureRule(rule: Rule) = ruleStore.addRule(rule)
}
