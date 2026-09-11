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
    /**
     * 相对横幅偏移的命中结果：锚定到的横幅容器屏幕 bounds + 计算出的手势点击屏幕坐标。
     * @param bannerBounds 命中横幅容器的屏幕矩形（用于日志/兜底）
     * @param tapX/tapY 待点击的屏幕坐标（横幅右上角内缩 offRight/offTop）
     * @param rule 命中规则（供日志/入库）
     */
    class RelativeTap(val bannerBounds: Rect, val tapX: Int, val tapY: Int, val rule: Rule?, val node: AccessibilityNodeInfo?)

    /**
     * 相对横幅偏移匹配：定位所有声明了 offRight/offTop 的活动中规则对应的"全宽横幅容器"，
     * 计算其右上角内缩点作为手势点击坐标。因为 ✕ 绘制在 React Native 纯图片横幅内、不作为无障碍节点暴露，
     * 所以这里锚定**横幅容器本身**（有稳定的结构特征），再按相对偏移产出点击点，绝不用节点中心（防跳转误触）。
     * 与 findTargets 独立：这类规则不会以节点形式参与 tryClick。
     */
    fun findRelativeTaps(
        root: AccessibilityNodeInfo, pkg: String,
        screenW: Int, screenH: Int, activity: String?
    ): List<RelativeTap> {
        val rules = ruleStore.allRules().filter {
            it.pkg == pkg && !it.disabled && it.offRight != null && it.offTop != null &&
                (it.activity.isNullOrEmpty() || it.activity == activity || activity.isNullOrEmpty())
        }
        if (rules.isEmpty()) return emptyList()
        val fullW = if (screenW > 0) screenW else 1080
        val fullH = if (screenH > 0) screenH else 2400
        val out = mutableListOf<RelativeTap>()
        val seen = HashSet<String>()
        // BFS 全树：锚定"全宽 + 横幅高度"的容器（默认 android.view.View；规则可收紧 className）
        val queue = ArrayList<AccessibilityNodeInfo>().apply { add(root) }
        var qi = 0
        while (qi < queue.size) {
            val node = queue[qi++]
            val b = Rect()
            runCatching { node.getBoundsInScreen(b) }
            val isWide = b.width() >= (fullW * 0.9).toInt()
            val isBannerH = b.height() >= BANNER_MIN_H && b.height() <= (fullH * 0.45).toInt()
            val cls = runCatching { node.className?.toString() }.getOrNull()
            val matches = if (rules.size == 1 && rules[0].className.isNullOrBlank()) {
                // 单规则且未声明 className：用"全宽 + 横幅高"结构特征
                isWide && isBannerH
            } else {
                // 有 className 约束：按每规则匹配其 className，再叠加全宽/横幅高
                rules.any { r ->
                    val rc = r.className
                    (rc.isNullOrBlank() || cls == rc) && isWide && isBannerH
                }
            }
            if (matches) {
                for (r in rules) {
                    val rc = r.className
                    if (!rc.isNullOrBlank() && cls != rc) continue
                    val tapX = b.right - (r.offRight ?: 0)
                    val tapY = b.top + (r.offTop ?: 0)
                    val key = "${b.left},${b.top},${b.right},${b.bottom}|$tapX,$tapY|${r.fingerprint()}"
                    if (seen.add(key)) {
                        Logger.d("  [relativeTap] 横幅[${r.name}] bounds=$b → tap($tapX,$tapY) 距右=${r.offRight} 距上=${r.offTop}")
                        out.add(RelativeTap(Rect(b), tapX, tapY, r, node))
                    }
                }
            }
            val cnt = runCatching { node.childCount }.getOrDefault(0)
            if (cnt in 1..64) {
                for (i in 0 until cnt) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
            }
            runCatching { node.recycle() }
        }
        return out
    }

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
                // 相对横幅偏移规则不在此返回节点（banner 容器被当普通节点点击会跳到购买页），
                // 由 findRelativeTaps 独立锚定并产出偏移坐标，这里是纯覆盖隔离，避免两路同时命中重复操作。
                if (r.offRight != null && r.offTop != null) return@filter false
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

    /** 供调试探针(probe)单条规则干跑所用：返回匹配到的原始节点（不做可点击过滤）。 */
    fun findByRule(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        // 结构锚定优先：按"父容器描述前缀 + 子节点序号 + 类名"定位，抗坐标漂移。
        // 波点隐藏 viewId，关闭按钮常为"带 desc 的横幅容器下第 N 个可点 ImageView"，比绝对坐标稳。
        if (!rule.parentDesc.isNullOrBlank() || rule.childIndex != null)
            return findParentAnchored(root, rule)
        // 组合约束：若规则同时声明了 viewId/text 与 className，则命中的节点必须同时满足，避免"同文案不同控件"误点。
        val classConstraint = rule.className?.takeIf { it.isNotBlank() }
        // 语义定位符：viewId/text/desc（name 只是标签不是定位符，不能因规则命名了就从坐标/类名分支退出）。
        // 坐标桥接与类名兜底仅服务于"无任何语义定位符"的纯位置/纯类名规则。
        val hasLocator = !rule.viewId.isNullOrBlank() || !rule.text.isNullOrBlank() ||
            !rule.contentDescription.isNullOrBlank()

        fun matchesConstraint(node: AccessibilityNodeInfo): Boolean {
            if (classConstraint == null) return true
            val clz = node.className?.toString()
            return clz == classConstraint ||
                // 兜底：可点击祖先上溯一层（按钮容器类名常与服务上命中文本的子节点不同）
                runCatching { node.parent?.className?.toString() }.getOrNull() == classConstraint
        }

        // 坐标桥接仅用于"无任何语义标识"的纯位置按钮。一旦规则带 text/desc/viewId，
        // 语义标识才是新布局下的精确锚点；过期的坐标矩形会因"见缝就收"扫到无关可点击控件（曾误触右下歌单按钮弹出歌曲菜单）。
        if (!rule.bounds.isNullOrEmpty() && !hasLocator) {
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
        // contentDescription 精确匹配：系统 API findAccessibilityNodeInfosByText 只查 text、不查 contentDescription，
        // 若直接塞进 textCandidates 会让"仅凭 cd 定位"的规则永远命中 0（如通用角标 rule『波点广告角标关闭』= desc"广告"）。
        // 这里用递归扫描精确比对 cd，配合 className 交叉约束收敛到真正的目标（如广告右下角可点 ImageView）。
        if (!rule.contentDescription.isNullOrBlank()) {
            val byDesc = mutableListOf<AccessibilityNodeInfo>()
            collectByContentDescription(root, rule.contentDescription, classConstraint, byDesc, 0)
            if (byDesc.isNotEmpty())
                return byDesc.filter { matchesAncestor(it, rule.ancestorViewId) }
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
        // 类名兜底仅适用于"纯类名规则"（无任何语义定位符）。若规则带 text/desc/viewId，
        // 语义匹配未命中时应返回空而非把整类控件全抓进来（那是误配来源）。
        if (classConstraint != null && !hasLocator)
            return AccessibilityUtil.findNodesByClass(root, classConstraint)
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

    /**
     * 深度优先收集 contentDescription 精确等值于 desc 的节点（配合可选 className 交叉约束）。
     * 系统 findAccessibilityNodeInfosByText 不搜索 contentDescription，故手写遍历。
     */
    private fun collectByContentDescription(
        node: AccessibilityNodeInfo,
        desc: String,
        classConstraint: String?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 16 || out.size >= 20) return
        try {
            val cd = node.contentDescription?.toString()
            if (!cd.isNullOrBlank() && (cd == desc || cd.contains(desc)) &&
                (classConstraint == null || node.className?.toString() == classConstraint)
            ) {
                if (!out.any { it == node }) out.add(node)
            }
            val cnt = runCatching { node.childCount }.getOrDefault(0)
            if (cnt in 1..64) {
                for (i in 0 until cnt) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    val before = out.size
                    collectByContentDescription(child, desc, classConstraint, out, depth + 1)
                    if (out.size == before) runCatching { child.recycle() }
                }
            }
        } catch (_: Exception) {}
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
            // 用"节点中心是否落在目标矩形内"而非"矩形是否相交"作为命中判定：
            // 相交判定会把只擦到一边的无关可点击控件抓进来（曾因坐标矩形边缘扫到右下歌单按钮而误触弹出歌曲菜单）。
            val inBounds = r.centerX() in target.left..target.right && r.centerY() in target.top..target.bottom
            if (node.isClickable && inBounds && r.width() > 0 && r.height() > 0) {
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

    /**
     * 结构锚定：BFS 整棵树找满足 parentDesc 前缀(/parentClass) 的容器节点，
     * 再在其子树内搜索第 childIndex 个"可点 + 目标类名"的节点（任意深度），做"可点 + 类名 + 祖先约束"校验。
     * 相比绝对坐标，这种结构关系不随横幅位置/尺寸漂移而失效；相比"仅直接子节点"，
     * 它也能穿透中间隔层（如插屏广告的 X 常隔一层全屏遮罩 View）真正锁定到控件。
     */
    private fun findParentAnchored(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        val idx = rule.childIndex ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        // parentAnyDesc 语义下，父容器必须接近全屏(用于识别"歌播放页根/大容器")，过滤掉私人推荐/歌手名等小容器
        val fullScreen = Rect()
        runCatching { root.getBoundsInScreen(fullScreen) }
        val queue = ArrayList<AccessibilityNodeInfo>().apply { add(root) }
        var qi = 0
        while (qi < queue.size) {
            val node = queue[qi++]
            // 接近全屏判定(仅对 parentAnyDesc 生效)：宽 ≥ 屏宽且高 ≥ 屏高 90%，避免小容器子树误配
            val nearFull = if (rule.parentAnyDesc == true) {
                val rr = Rect()
                runCatching { node.getBoundsInScreen(rr) }
                fullScreen.width() > 0 && rr.width() >= fullScreen.width() && rr.height() >= fullScreen.height() * 9 / 10
            } else true
            if (!nearFull) {
                val cnt = runCatching { node.childCount }.getOrDefault(0)
                if (cnt in 1..64) {
                    for (i in 0 until cnt)
                        runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
                runCatching { node.recycle() }
                continue
            }
            if (!runCatching { parentMatches(node, rule) }.getOrDefault(false)) {
                val cnt = runCatching { node.childCount }.getOrDefault(0)
                if (cnt in 1..64) {
                    for (i in 0 until cnt)
                        runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
                runCatching { node.recycle() }
                continue
            }
            // 匹配父容器：在其子树内深搜第 idx 个目标（该函数自管回收，命中加入 out）
            collectNthTarget(node, rule, idx, 0, out)
            runCatching { node.recycle() }
        }
        return out
    }

    /** 容器节点是否满足结构锚定的父条件（desc 按 parentMatch 匹配 + parentAnyDesc + 可选 className） */
    private fun parentMatches(node: AccessibilityNodeInfo, rule: Rule): Boolean = runCatching {
        val pd = rule.parentDesc
        if (!pd.isNullOrBlank()) {
            val desc = node.contentDescription?.toString()
            val matched = when (rule.parentMatch) {
                1 -> desc?.contains(pd) == true
                2 -> desc == pd
                else -> desc?.startsWith(pd) == true
            }
            if (!matched) return false
        } else if (rule.parentAnyDesc == true) {
            // 父容器不限定具体 desc 内容，只要有非空 contentDescription 即视为命中。
            // 用于"宿主根容器 desc=动态歌名/标题"这类内容随场景变化但始终有值的父容器。
            val desc = node.contentDescription?.toString().orEmpty()
            if (desc.isBlank()) return false
        }
        val pc = rule.parentClass
        if (!pc.isNullOrBlank() && node.className?.toString() != pc) return false
        true
    }.getOrDefault(false)

    /** 在容器子树内深度优先收集第 targetIdx 个满足"可点 + className + 祖先约束"的节点，返回游标推进后的下一个序号。 */
    private fun collectNthTarget(
        node: AccessibilityNodeInfo, rule: Rule,
        targetIdx: Int, start: Int, out: MutableList<AccessibilityNodeInfo>
    ): Int {
        var cur = start
        try {
            if (runCatching {
                    node.isClickable() &&
                        (rule.className.isNullOrBlank() || node.className?.toString() == rule.className) &&
                        matchesAncestor(node, rule.ancestorViewId)
                }.getOrDefault(false)) {
                if (cur == targetIdx) {
                    if (!out.any { it == node }) out.add(node)
                    return cur + 1
                }
                cur++
            }
            val cnt = runCatching { node.childCount }.getOrDefault(0)
            if (cnt in 1..64) {
                for (i in 0 until cnt) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    val before = out.size
                    cur = collectNthTarget(child, rule, targetIdx, cur, out)
                    if (out.size == before) runCatching { child.recycle() }
                }
            }
        } catch (_: Exception) {}
        return cur
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
        /** 相对横幅偏移锚定的横幅容器最小高度（px）：过矮的节点不是运营横幅 */
        private const val BANNER_MIN_H = 120
    }
}
