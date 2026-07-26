package com.simely.adskip.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.AppState
import com.simely.adskip.model.Rule
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.SecurePrefs

class AdSkipAccessibilityService : AccessibilityService() {

    private var ruleStore: RuleStore? = null
    private var secure: SecurePrefs? = null
    private var stats: StatsStore? = null
    private val lastClick = mutableMapOf<String, Long>()
    private var clickCount = 0
    private var lastHighlightScan = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            ruleStore = RuleStore(this)
            secure = SecurePrefs(this)
            stats = StatsStore(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = secure ?: return
        if (!s.getMasterEnabled()) return

        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == packageName) return  // 绝对不处理自己的界面

            if (AppState.isCapturing) {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED,
                    AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                        captureNode(event)
                        AppState.onCaptured?.invoke()
                        AppState.exitCapture()
                    }
                    else -> {
                        // 只在窗口变化时刷新高亮，200ms 防抖避免高频事件风暴
                        val et = event.eventType
                        if (et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                            || et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                            val now = System.currentTimeMillis()
                            if (now - lastHighlightScan > 200) {
                                lastHighlightScan = now
                                AppState.highlightedRects = collectClickableBounds(root)
                            }
                        }
                    }
                }
                return
            }

            // 过滤模式检查
            if (!isPkgAllowed(pkg)) return

            tryClick(root, pkg)
        } finally {
            root.recycle()
        }
    }

    /** 检查当前包名是否在过滤名单内 */
    private fun isPkgAllowed(pkg: String): Boolean {
        val s = secure ?: return false
        if (!s.isFilterEnabled()) return true  // 过滤总开关关闭 → 允许所有
        val filterList = s.getFilterList()
        if (filterList.isEmpty()) return true
        val isBlacklist = s.getFilterMode()
        return if (isBlacklist) pkg !in filterList else pkg in filterList
    }

    private fun tryClick(root: AccessibilityNodeInfo, pkg: String) {
        val rs = ruleStore ?: return
        val targets = mutableListOf<AccessibilityNodeInfo>()
        val kwOn = secure?.getKeywordEnabled() == true

        if (kwOn) {
            for (kw in rs.getKeywords()) {
                if (kw.isBlank()) continue
                runCatching { root.findAccessibilityNodeInfosByText(kw) }
                    .getOrDefault(emptyList())
                    .filter { isClickable(it) }
                    .let { targets.addAll(it) }
            }
        }

        // 手动规则始终生效（仅匹配当前包名）
        for (rule in rs.getRules()) {
            if (rule.pkg != pkg) continue
            targets.addAll(findRuleNodes(root, rule).filter { isClickable(it) })
        }

        val resolvedNodes = mutableSetOf<AccessibilityNodeInfo>()
        for (node in targets) {
            val clickable = resolveClickable(node) ?: continue
            resolvedNodes.add(clickable)
            val key = "$pkg|${clickable.viewIdResourceName ?: clickable.text?.toString() ?: ""}"
            val now = System.currentTimeMillis()
            if (now - (lastClick[key] ?: 0L) < COOLDOWN_MS) continue

            // 屏蔽规则优先级最高：匹配到则不点击
            val btnText = clickable.text?.toString()
            val btnVid = clickable.viewIdResourceName
            if (ruleStore?.isBlocked(pkg, btnText, btnVid) == true) continue

            lastClick[key] = now
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                val vid = clickable.viewIdResourceName ?: ""
                val txt = node.text?.toString()?.ifEmpty { clickable.text?.toString() } ?: ""
                val cd = clickable.contentDescription?.toString() ?: ""
                val logText = txt.ifEmpty { cd }
                stats?.recordClick(pkg, logText, vid)
            }
            break
        }
        targets.forEach { it.recycle() }
        // 不回收 resolvedNodes——通过 .parent 获取的是活树节点，由框架管理，recycle 会导致 native crash

        // 定期清理 lastClick 过期条目
        clickCount++
        if (clickCount % 100 == 0) {
            val cutoff = System.currentTimeMillis() - COOLDOWN_MS * 10
            lastClick.entries.removeAll { it.value < cutoff }
        }
    }

    /** 深度扫描可点击节点（捕获模式下扫描 5 层，展示所有可操作按钮） */
    private fun collectClickableBounds(node: AccessibilityNodeInfo): List<android.graphics.Rect> {
        val result = mutableListOf<android.graphics.Rect>()
        val seen = mutableSetOf<String>()
        deepScan(node, result, seen, 0)
        return result
    }

    private fun deepScan(node: AccessibilityNodeInfo, out: MutableList<android.graphics.Rect>, seen: MutableSet<String>, depth: Int) {
        if (depth > 5 || out.size >= 200) return
        try {
            // 节点自身可点击 或 有可点击祖先 → 都视为可捕获目标
            if (node.isClickable || quickHasClickableAncestor(node)) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val key = "${r.left},${r.top},${r.right},${r.bottom}"
                if (key !in seen && r.width() in 30..2000 && r.height() in 16..500 && r.left >= 0 && r.top >= 0) {
                    seen.add(key)
                    out.add(android.graphics.Rect(r))
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let { deepScan(it, out, seen, depth + 1); it.recycle() }
            }
        } catch (_: Exception) {}
    }

    /** 快速判断是否有可点击祖先（仅查 3 层，捕获模式专用） */
    private fun quickHasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent; var i = 0
        while (p != null && i < 3) { if (p.isClickable) return true; p = p.parent; i++ }
        return false
    }

    /** 用规则匹配节点：viewId 精确匹配 → text → contentDescription → className */
    private fun findRuleNodes(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        // 1. viewId 精确匹配
        if (!rule.viewId.isNullOrEmpty()) {
            return runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }.getOrDefault(emptyList())
        }
        // 2. text 模糊匹配
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (textCandidate in rule.textCandidates()) {
            runCatching { root.findAccessibilityNodeInfosByText(textCandidate) }
                .getOrDefault(emptyList())
                .let { results.addAll(it) }
            if (results.isNotEmpty()) break
        }
        if (results.isNotEmpty()) return results
        // 3. className 匹配（扫描可点击节点，比较类名）
        if (!rule.className.isNullOrEmpty()) {
            return findNodesByClass(root, rule.className!!)
        }
        return emptyList()
    }

    /** 按 className 查找可点击节点 */
    private fun findNodesByClass(root: AccessibilityNodeInfo, clz: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassRecursive(root, clz, out, 0)
        return out
    }

    private fun findNodesByClassRecursive(node: AccessibilityNodeInfo, clz: String, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 6 || out.size >= 10) return
        try {
            if (node.className?.toString() == clz && node.isClickable) {
                out.add(node)
                return  // node 已添加到 out，不回收，由调用方统一回收
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let {
                    findNodesByClassRecursive(it, clz, out, depth + 1)
                    // 只有当 child 不在 out 中时才回收
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    /** 尽可能全面捕获点击节点的所有信息 */
    private fun captureNode(event: AccessibilityEvent) {
        val rs = ruleStore ?: return
        val pkgName = event.packageName?.toString() ?: ""
        val src = event.source

        if (src != null) {
            // 核心修复：event.source 可能返回按钮内的子节点（文字标签/图标）
            // 必须上溯到最近的可点击祖先，确保捕获的是"按钮"而不是"按钮里的字"
            val clickTarget = resolveToNearestClickable(src)
            saveCapturedRule(clickTarget, rs, pkgName, event)
            // resolveToNearestClickable 返回的节点可能 != src，需回收非 src 的
            if (clickTarget !== src) clickTarget.recycle()
            return
        }

        // source 为 null：窗口扫描兜底
        val root = rootInActiveWindow ?: return
        try {
            var handled = false
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { fn ->
                if (fn.isClickable) { saveCapturedRule(fn, rs, pkgName, event); handled = true }
                fn.recycle()
            }
            if (!handled) {
                root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { fn ->
                    if (fn.isClickable) { saveCapturedRule(fn, rs, pkgName, event); handled = true }
                    fn.recycle()
                }
            }
            if (!handled) {
                findFirstClickableWithAnyContent(root)?.let {
                    saveCapturedRule(it, rs, pkgName, event)
                }
            }
        } finally {
            root.recycle()
        }
    }

    /** 从子节点上溯到最近的可点击祖先（最多 8 层），确保捕获的是按钮本体 */
    private fun resolveToNearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var p = node.parent
        var depth = 0
        while (p != null && depth < 8) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return node  // 找不到就降级用原始节点
    }

    /** 保存捕获到的规则 */
    private fun saveCapturedRule(node: AccessibilityNodeInfo, rs: RuleStore, pkgName: String, event: AccessibilityEvent) {
        val nodeText = node.text?.toString()?.trim()
        val nodeContentDesc = node.contentDescription?.toString()?.trim()
        val nodeViewId = node.viewIdResourceName
        val nodeClassName = node.className?.toString()
        val activityName = event.className?.toString()
        val displayName = nodeText ?: nodeContentDesc ?: nodeViewId ?: nodeClassName

        val rule = Rule(
            text = nodeText,
            viewId = nodeViewId,
            pkg = pkgName,
            activity = activityName,
            action = "click",
            name = displayName,
            contentDescription = nodeContentDesc,
            className = nodeClassName
        )
        rs.addRule(rule)
        // 自动将捕获到的 App 加入过滤名单（如果尚未加入）
        if (secure?.isFilterEnabled() == true && pkgName.isNotEmpty()) {
            val list = secure?.getFilterList() ?: emptySet()
            if (pkgName !in list) secure?.addFilterPkg(pkgName)
        }
    }

    /** 遍历树找任意有内容（text/contentDesc/viewId）的可点击节点 */
    private fun findFirstClickableWithAnyContent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hasContent = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() || !node.viewIdResourceName.isNullOrEmpty()
        if (node.isClickable && hasContent) return node
        for (i in 0 until node.childCount.coerceAtMost(30)) {
            val child = node.getChild(i) ?: continue
            val found = findFirstClickableWithAnyContent(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isClickable(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) return true
        var p = n.parent; var d = 0
        while (p != null && d < 10) { if (p.isClickable) return true; p = p.parent; d++ }
        return false
    }

    private fun resolveClickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isClickable) return n
        var p = n.parent; var d = 0
        while (p != null && d < 10) { if (p.isClickable) return p; p = p.parent; d++ }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AdSkipService"
        private const val COOLDOWN_MS = 800L
    }
}
