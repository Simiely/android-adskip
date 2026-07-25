package com.simely.adskip.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.AppState
import com.simely.adskip.model.Rule
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.SecurePrefs

/**
 * 核心无障碍服务：事件驱动，零轮询。
 * - 普通模式：界面变化时同步匹配关键词/捕获规则，无延迟点击。
 * - 捕获模式：监听 TYPE_VIEW_CLICKED，记录被点节点的指纹。
 * 注意：直接在回调线程同步处理（无障碍服务的标准做法），
 * 处理完立即 recycle(root/source)，避免节点泄漏。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    private var ruleStore: RuleStore? = null
    private var secure: SecurePrefs? = null
    private val lastClick = mutableMapOf<String, Long>()
    private val COOLDOWN_MS = 800L

    override fun onCreate() {
        super.onCreate()
        try {
            ruleStore = RuleStore(this)
            secure = SecurePrefs(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init store/prefs", e)
            // 降级运行：不崩溃，但不执行匹配（等待用户重启服务）
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = secure ?: return
        if (!s.getMasterEnabled()) return

        val root = rootInActiveWindow ?: return
        try {
            if (AppState.isCapturing && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                captureNode(event)
                AppState.exitCapture()
                AppState.onCaptured?.invoke()
                return
            }
            if (AppState.isCapturing) return
            val pkg = root.packageName?.toString() ?: ""
            tryClick(root, pkg)
        } finally {
            root.recycle()
        }
    }

    private fun tryClick(root: AccessibilityNodeInfo, pkg: String) {
        val rs = ruleStore ?: return
        val targets = mutableListOf<AccessibilityNodeInfo>()

        // 1) 关键词文本匹配（仅在开关开启时执行，关闭则跳过以省电）
        if (secure?.getKeywordEnabled() == true) {
            for (kw in rs.getKeywords()) {
                if (kw.isBlank()) continue
                runCatching { root.findAccessibilityNodeInfosByText(kw) }
                    .getOrDefault(emptyList())
                    .filter { isClickable(it) }
                    .let { targets.addAll(it) }
            }
        }

        // 2) 手动捕获规则指纹匹配（长尾兜底）
        if (targets.isEmpty()) {
            for (rule in rs.getRules()) {
                targets.addAll(findRuleNodes(root, rule).filter { isClickable(it) })
            }
        }

        for (node in targets) {
            // 关键修复：点击真正可点击的祖先节点，而非文字节点本身
            val clickable = resolveClickable(node) ?: continue
            val key = "$pkg|${clickable.viewIdResourceName ?: clickable.text?.toString() ?: ""}"
            val now = System.currentTimeMillis()
            if (now - (lastClick[key] ?: 0L) < COOLDOWN_MS) continue
            lastClick[key] = now
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            break
        }
    }

    private fun findRuleNodes(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        return when {
            !rule.viewId.isNullOrEmpty() ->
                runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }
                    .getOrDefault(emptyList())
            !rule.text.isNullOrEmpty() ->
                runCatching { root.findAccessibilityNodeInfosByText(rule.text) }
                    .getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    private fun captureNode(event: AccessibilityEvent) {
        val rs = ruleStore ?: return
        val src = event.source ?: return
        try {
            val rule = Rule(
                text = src.text?.toString(),
                viewId = src.viewIdResourceName,
                pkg = event.packageName?.toString() ?: "",
                activity = null,
                action = "click",
                name = src.text?.toString()
            )
            rs.addRule(rule)
        } finally {
            src.recycle()
        }
    }

    /** 节点本身可点击，或其任一祖先可点击 */
    private fun isClickable(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) return true
        var p = n.parent
        while (p != null) {
            if (p.isClickable) return true
            p = p.parent
        }
        return false
    }

    /** 返回节点本身（若可点击）或最近的可点击祖先；全无可返回 null */
    private fun resolveClickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isClickable) return n
        var p = n.parent
        while (p != null) {
            if (p.isClickable) return p
            p = p.parent
        }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AdSkipService"
    }
}
