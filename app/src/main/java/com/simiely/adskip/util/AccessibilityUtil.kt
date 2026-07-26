package com.simely.adskip.util

import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点操作通用工具集。
 * 从 Service 中提取，供 RuleMatcher / CaptureManager / ClickExecutor 共用。
 */
object AccessibilityUtil {

    /**
     * 检查无障碍服务是否已开启。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val svc = "${context.packageName}/com.simely.adskip.service.AdSkipAccessibilityService"
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(svc) == true
    }

    /** 最大上溯层数 */
    private const val MAX_ANCESTOR_DEPTH = 10

    /** 高亮扫描最大深度 */
    private const val HIGHLIGHT_MAX_DEPTH = 5

    /** 高亮扫描节点上限 */
    private const val HIGHLIGHT_MAX_NODES = 200

    /** 高亮矩形尺寸约束 */
    private const val HIGHLIGHT_MIN_W = 30
    private const val HIGHLIGHT_MAX_W = 2000
    private const val HIGHLIGHT_MIN_H = 16
    private const val HIGHLIGHT_MAX_H = 500

    /**
     * 判断节点自身或其祖先是否可点击。
     * 大多数 App 的按钮是外层容器 clickable，内层 TextView 不 clickable。
     */
    fun isClickable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var p = node.parent
        var depth = 0
        while (p != null && depth < MAX_ANCESTOR_DEPTH) {
            if (p.isClickable) return true
            p = p.parent
            depth++
        }
        return false
    }

    /**
     * 将匹配到的文本节点解析到最近的可点击祖先。
     * @return 可点击祖先节点，或 null 如果找不到
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var p = node.parent
        var depth = 0
        while (p != null && depth < MAX_ANCESTOR_DEPTH) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return null
    }

    /**
     * 从子节点上溯到最近的可点击祖先（最多 8 层），用于捕获模式下确保捕获的是按钮本体。
     */
    fun resolveToNearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var p = node.parent
        var depth = 0
        while (p != null && depth < 8) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return node
    }

    // ── 高亮扫描 ──

    /**
     * 深度扫描可点击节点，返回它们的屏幕边界矩形列表。
     * 用于捕获模式下高亮覆盖层绘制。
     */
    fun collectClickableBounds(node: AccessibilityNodeInfo): List<Rect> {
        val result = mutableListOf<Rect>()
        val seen = mutableSetOf<String>()
        deepScan(node, result, seen, 0)
        return result
    }

    private fun deepScan(
        node: AccessibilityNodeInfo,
        out: MutableList<Rect>,
        seen: MutableSet<String>,
        depth: Int
    ) {
        if (depth > HIGHLIGHT_MAX_DEPTH || out.size >= HIGHLIGHT_MAX_NODES) return
        try {
            if (node.isClickable || hasClickableAncestorQuick(node)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val key = "${r.left},${r.top},${r.right},${r.bottom}"
                if (key !in seen &&
                    r.width() in HIGHLIGHT_MIN_W..HIGHLIGHT_MAX_W &&
                    r.height() in HIGHLIGHT_MIN_H..HIGHLIGHT_MAX_H &&
                    r.left >= 0 && r.top >= 0
                ) {
                    seen.add(key)
                    out.add(Rect(r))
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let { deepScan(it, out, seen, depth + 1); it.recycle() }
            }
        } catch (_: Exception) {}
    }

    /** 快速判断是否有可点击祖先（查 3 层），捕获模式专用 */
    private fun hasClickableAncestorQuick(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        var i = 0
        while (p != null && i < 3) {
            if (p.isClickable) return true
            p = p.parent
            i++
        }
        return false
    }

    // ── 节点查找 ──

    // ── B 方案：位置启发式扫描 ──

    /** 疑似跳过/关闭按钮的尺寸约束 */
    private const val SKIP_MIN_W = 40
    private const val SKIP_MAX_W = 160
    private const val SKIP_MIN_H = 24
    private const val SKIP_MAX_H = 72

    /**
     * 位置启发式：扫描右上角区域的可点击小按钮（严格约束）。
     * 仅用于文本匹配 + contentDescription 都失败后的最后兜底。
     */
    fun scanByPositionHeuristic(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        scanPositionRecursive(root, candidates, screenW, screenH, 0)
        // 按右上优先级排序：越靠右上越靠前
        candidates.sortByDescending { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.right - r.top  // right 越大 = 越靠右，top 越小 = 越靠上，差值越大越优先
        }
        return candidates
    }

    private fun scanPositionRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
        depth: Int
    ) {
        if (depth > 8 || out.size >= 30) return
        try {
            if (node.isClickable || hasClickableAncestorQuick(node)) {
                val clickable = if (node.isClickable) node else resolveToNearestClickable(node)
                val r = Rect()
                clickable.getBoundsInScreen(r)
                // 严格约束：右上角 35% 宽度 × 18% 高度区域
                if (r.left > screenW * 0.65f &&
                    r.top < screenH * 0.18f &&
                    r.width() in SKIP_MIN_W..SKIP_MAX_W &&
                    r.height() in SKIP_MIN_H..SKIP_MAX_H &&
                    r.left >= 0 && r.top >= 0
                ) {
                    // 去重
                    val key = "${r.left},${r.top},${r.right},${r.bottom}"
                    if (out.none {
                            val or = Rect(); it.getBoundsInScreen(or)
                            "${or.left},${or.top},${or.right},${or.bottom}" == key
                        }
                    ) {
                        out.add(clickable)
                        return  // 已添加 clickable，不回收
                    }
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let {
                    scanPositionRecursive(it, out, screenW, screenH, depth + 1)
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    // ── C 方案：contentDescription 扫描 ──

    /**
     * 遍历树，搜索 contentDescription 包含关键词的可点击节点。
     * Android API 的 findAccessibilityNodeInfosByText 不搜 contentDescription，需手动遍历。
     */
    fun scanByContentDescription(
        root: AccessibilityNodeInfo,
        keyword: String
    ): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        scanContentDescRecursive(root, keyword, out, 0)
        return out
    }

    private fun scanContentDescRecursive(
        node: AccessibilityNodeInfo,
        keyword: String,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 8 || out.size >= 30) return
        try {
            val cd = node.contentDescription?.toString() ?: ""
            val txt = node.text?.toString() ?: ""
            if ((cd.contains(keyword, ignoreCase = true) || txt.contains(keyword, ignoreCase = true)) &&
                (node.isClickable || hasClickableAncestorQuick(node))
            ) {
                val clickable = if (node.isClickable) node else resolveToNearestClickable(node)
                if (clickable != null && clickable !in out) {
                    out.add(clickable)
                    return
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let {
                    scanContentDescRecursive(it, keyword, out, depth + 1)
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    // ── "X" 关闭按钮检测 ──

    // ── 深度跳字扫描（最终兜底）──

    /** 跳过类关键词（用于深度扫描） */
    private val SKIP_KEYWORDS = arrayOf("跳过", "skip", "关闭", "close", "廣告", "广告")

    /**
     * 深度扫描：遍历整棵树，查找任何包含"跳过"文字的节点（不限深度，不限位置）。
     * 作为所有其他策略都失败后的最终兜底。
     */
    fun deepScanForSkipText(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        deepSkipRecursive(root, out, 0)
        return out
    }

    private fun deepSkipRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 10 || out.size >= 10) return
        try {
            val txt = node.text?.toString() ?: ""
            val cd = node.contentDescription?.toString() ?: ""
            val combined = "$txt $cd"
            val matched = SKIP_KEYWORDS.any { combined.contains(it, ignoreCase = true) }
            if (matched && (node.isClickable || hasClickableAncestorQuick(node))) {
                val clickable = if (node.isClickable) node else resolveToNearestClickable(node)
                if (clickable != null && clickable !in out) {
                    out.add(clickable)
                    return
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(40)) {
                node.getChild(i)?.let {
                    deepSkipRecursive(it, out, depth + 1)
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    // ── "X" 关闭按钮检测 ──

    /** ✕ / 关闭 类按钮的关键词（排除单字符避免误匹配） */
    private val CLOSE_KEYWORDS = arrayOf("关闭", "close", "跳过", "×", "✕", "skip", "广告")

    /**
     * 判断节点是否疑似关闭/跳过按钮（用于手动捕获高亮和自动匹配）。
     * 检查 text、contentDescription 是否包含关闭类关键词。
     */
    fun isLikelySkipOrClose(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val txt = node.text?.toString() ?: ""
        val cd = node.contentDescription?.toString() ?: ""
        val text = "$txt $cd"
        for (kw in CLOSE_KEYWORDS) {
            if (text.contains(kw, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 扫描所有疑似关闭/跳过按钮（供捕获模式高亮和自动匹配兜底）。
     */
    fun scanSkipOrCloseButtons(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        scanCloseRecursive(root, out, screenW, screenH, 0)
        return out
    }

    private fun scanCloseRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
        depth: Int
    ) {
        if (depth > 6 || out.size >= 30) return
        try {
            if (isLikelySkipOrClose(node, screenW, screenH) &&
                (node.isClickable || hasClickableAncestorQuick(node))
            ) {
                val clickable = if (node.isClickable) node else resolveToNearestClickable(node)
                if (clickable != null && clickable !in out) {
                    out.add(clickable)
                    return
                }
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let {
                    scanCloseRecursive(it, out, screenW, screenH, depth + 1)
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 按 className 在树中递归查找可点击节点。
     */
    fun findNodesByClass(root: AccessibilityNodeInfo, clz: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassRecursive(root, clz, out, 0)
        return out
    }

    private fun findNodesByClassRecursive(
        node: AccessibilityNodeInfo,
        clz: String,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 6 || out.size >= 10) return
        try {
            if (node.className?.toString() == clz && node.isClickable) {
                out.add(node)
                return
            }
            for (i in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(i)?.let {
                    findNodesByClassRecursive(it, clz, out, depth + 1)
                    if (it !in out) it.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 遍历树找任意有内容（text / contentDesc / viewId）的可点击节点。
     * 用于捕获模式下 source 为 null 时的兜底扫描。
     */
    fun findFirstClickableWithAnyContent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hasContent = !node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty()
        if (node.isClickable && hasContent) return node
        for (i in 0 until node.childCount.coerceAtMost(30)) {
            val child = node.getChild(i) ?: continue
            val found = findFirstClickableWithAnyContent(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
