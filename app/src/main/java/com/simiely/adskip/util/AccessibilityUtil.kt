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
     * 上溯查找最近可点击节点的唯一核心实现（含节点自身）。
     * 其余便捷函数均基于此，避免同一遍历逻辑被重复实现多份。
     * @param maxDepth 允许上溯的祖先层级上限
     * @return 最近的可点击节点；maxDepth 内没有则返回 null
     */
    private fun findNearestClickable(node: AccessibilityNodeInfo, maxDepth: Int): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /** 判断节点自身或其祖先是否可点击（大多数 App 的按钮是外层容器 clickable，内层 TextView 不 clickable） */
    fun isClickable(node: AccessibilityNodeInfo): Boolean =
        findNearestClickable(node, MAX_ANCESTOR_DEPTH) != null

    /** 将匹配到的文本节点解析到最近的可点击祖先；找不到返回 null */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNearestClickable(node, MAX_ANCESTOR_DEPTH)

    /** 上溯到最近的可点击祖先（最多 8 层），用于捕获模式下确保捕获的是按钮本体。找不到时回退为原节点 */
    fun resolveToNearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        findNearestClickable(node, 8) ?: node

    /**
     * 快速上溯至多 3 层找最近可点击节点（含自身）；没有则 null。
     * 供各树扫描器做"是否值得解析"的栅栏判断，替代重复的 hasClickableAncestorQuick。
     */
    private fun findClickableAncestorQuick(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNearestClickable(node, 3)

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
            if (findClickableAncestorQuick(node) != null) {
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

    // ── 节点查找 ──

    // ── 方案：contentDescription 扫描 ──

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
            if (cd.contains(keyword, ignoreCase = true) || txt.contains(keyword, ignoreCase = true)) {
                val clickable = findClickableAncestorQuick(node)
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
            if (matched) {
                val clickable = findClickableAncestorQuick(node)
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
            if (isLikelySkipOrClose(node, screenW, screenH)) {
                val clickable = findClickableAncestorQuick(node)
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
