package com.simely.adskip.service.matcher

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.DebugFlags
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
                // 危险范式（仅有 className、无任何定位信息）永不参与点击：会泛滥命中整类控件（如所有 ImageView），
                // 且不区分已转正/候选。真正的坐标/文字/viewId 规则不受影响。
                if (r.isDangerousPattern()) return@filter false
                if (r.activity.isNullOrEmpty() || activity.isNullOrEmpty()) return@filter true
                r.activity == activity
            }
            .sortedByDescending { it.specificity() }
        for (rule in activeRules) {
            if (targets.size >= MAX_TARGETS) break
            val hits = findByRule(root, rule).filter { AccessibilityUtil.isClickable(it) }
            if (DebugFlags.traceEnabled)
                Logger.d("  [trace] 规则[${rule.pkg}|${rule.name}]: ${rule.shortDescription()} → 命中可点节点=${hits.size}")
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

        if (!rule.bounds.isNullOrEmpty()) {
            // 坐标固化匹配（无 viewId/text/className 依赖的纯位置按钮，如波点开屏广告X）：
            // 收集"屏幕坐标与规则矩形相交"的可点击节点，再选其中面积最小者（真正的按钮是最小那一个，
            // 避免规则矩形同时盖住左侧相邻大图时误点）。若规则带 className 则在其上再过滤。
            val target = Rect(
                rule.bounds[0], rule.bounds[1], rule.bounds[2], rule.bounds[3]
            )
            val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
            collectNodesInBounds(root, target, candidates, 10)
            candidates.sortBy { it.second } // 面积升序，最小的按钮排最前
            // 尺寸闸门：真正的关闭 ✕ 都是小控件。候选若是宽或高超过阈值（横幅/大卡片/全屏容器，
            // 如"导入歌单"横幅 Rect(0,672 - 1080,891) 误配弹窗X坐标），一票否决，绝不让大块可点击区域冒充 ✕。
            val hits = candidates
                .filter { (node, area) ->
                    val b = Rect()
                    runCatching { node.getBoundsInScreen(b) }.getOrNull() ?: return@filter false
                    val sizeOk = b.width() <= MAX_CLOSE_DIMEN && b.height() <= MAX_CLOSE_DIMEN
                    if (DebugFlags.traceEnabled && !sizeOk)
                        Logger.d("    [trace] 坐标规则${rule.bounds} 候选尺寸超闸门跳过 bounds=$b area=$area class=${node.className?.toString()?.substringAfterLast('.')}")
                    sizeOk
                }
                .map { it.first }
            val filtered = if (classConstraint == null) hits else hits.filter { matchesConstraint(it) }
            return filtered.filter { matchesAncestor(it, rule.ancestorViewId) }
        }

        if (!rule.viewId.isNullOrEmpty()) {
            val byId = runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }
                .getOrDefault(emptyList())
            if (byId.isNotEmpty()) return byId.filter { matchesConstraint(it) && matchesAncestor(it, rule.ancestorViewId) }
        }
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (c in rule.textCandidates()) {
            runCatching { root.findAccessibilityNodeInfosByText(c) }
                .getOrDefault(emptyList())
                .filter { matchesConstraint(it) && matchesAncestor(it, rule.ancestorViewId) }
                .let { results.addAll(it) }
            if (results.isNotEmpty()) break
        }
        if (results.isNotEmpty()) return results
        if (classConstraint != null) return AccessibilityUtil.findNodesByClass(root, classConstraint)
            .filter { matchesAncestor(it, rule.ancestorViewId) }
        return emptyList()
    }

    /** 祖先约束：命中节点的某一层祖先(至多5层)持有指定 viewId 才采纳；null=不启用。 */
    private fun matchesAncestor(node: AccessibilityNodeInfo, ancestorViewId: String?): Boolean {
        if (ancestorViewId.isNullOrEmpty()) return true
        var cur = runCatching { node.parent }.getOrNull() ?: return false
        var depth = 0
        while (depth < 5) {
            try {
                if (cur.viewIdResourceName == ancestorViewId) {
                    runCatching { cur.recycle() }
                    return true
                }
            } catch (_: Exception) {}
            val next = runCatching { cur.parent }.getOrNull()
            if (next == null || next == cur) {
                runCatching { cur.recycle() }
                return false
            }
            cur.recycle()
            cur = next
            depth++
        }
        runCatching { cur.recycle() }
        return false
    }

    /** 收集屏幕坐标与目标矩形相交的可点击节点，并记录其面积（bounds 坐标匹配专用）。
     *  叶节点命中后仍向下遍历，以找到最内层的最小可点击按钮。 */
    private fun collectNodesInBounds(
        node: AccessibilityNodeInfo,
        target: Rect,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>,
        depth: Int
    ) {
        if (depth < 0 || out.size >= 20) return
        try {
            val r = Rect()
            node.getBoundsInScreen(r)
            val intersects = r.intersect(target) || target.intersect(r)
            if (node.isClickable && intersects && r.width() > 0 && r.height() > 0) {
                out.add(node to (r.width() * r.height()))
            }
            val childCount = node.childCount
            if (childCount > 64) return
            for (i in 0 until childCount) {
                node.getChild(i)?.let {
                    collectNodesInBounds(it, target, out, depth - 1)
                    if (out.none { p -> p.first == it }) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    fun isBlocked(pkg: String, text: String?, viewId: String?) =
        blockedRuleStore.isBlocked(pkg, text, viewId)

    /** 自动捕获入口：不直接入库，走置信门槛（累计命中后转正） */
    fun autoCaptureRule(rule: Rule) = ruleStore.learnFromHit(rule)

    companion object {
        /** 一轮匹配最多保留的候选目标数，避免高频事件下过量处理 */
        private const val MAX_TARGETS = 12
        /** 真正的关闭/跳过按钮都是小控件，超过此尺寸（px）的候选一律排除（避免横幅/大卡片误配坐标）。
         *  当前你的手机屏宽 1080px，440px 约占屏宽 2/5，关闭按钮很少超过这个尺寸。*/
        private const val MAX_CLOSE_DIMEN = 440
    }
}
