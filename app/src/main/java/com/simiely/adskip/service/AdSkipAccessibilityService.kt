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

        if (secure?.getKeywordEnabled() == true) {
            for (kw in rs.getKeywords()) {
                if (kw.isBlank()) continue
                runCatching { root.findAccessibilityNodeInfosByText(kw) }
                    .getOrDefault(emptyList())
                    .filter { isClickable(it) }
                    .let { targets.addAll(it) }
            }
        }

        if (targets.isEmpty()) {
            for (rule in rs.getRules()) {
                targets.addAll(findRuleNodes(root, rule).filter { isClickable(it) })
            }
        }

        for (node in targets) {
            val clickable = resolveClickable(node) ?: continue
            val key = "$pkg|${clickable.viewIdResourceName ?: clickable.text?.toString() ?: ""}"
            val now = System.currentTimeMillis()
            if (now - (lastClick[key] ?: 0L) < COOLDOWN_MS) continue
            lastClick[key] = now
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // 记录点击统计
            stats?.recordClick(pkg, clickable.text?.toString() ?: "")
            break
        }
    }

    private fun findRuleNodes(root: AccessibilityNodeInfo, rule: Rule): List<AccessibilityNodeInfo> {
        return when {
            !rule.viewId.isNullOrEmpty() ->
                runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }.getOrDefault(emptyList())
            !rule.text.isNullOrEmpty() ->
                runCatching { root.findAccessibilityNodeInfosByText(rule.text) }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }

    private fun captureNode(event: AccessibilityEvent) {
        val rs = ruleStore ?: return
        val src = event.source ?: return
        try {
            val rule = Rule(
                text = src.text?.toString(), viewId = src.viewIdResourceName,
                pkg = event.packageName?.toString() ?: "", activity = null,
                action = "click", name = src.text?.toString()
            )
            rs.addRule(rule)
        } finally { src.recycle() }
    }

    private fun isClickable(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) return true
        var p = n.parent
        while (p != null) { if (p.isClickable) return true; p = p.parent }
        return false
    }

    private fun resolveClickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isClickable) return n
        var p = n.parent
        while (p != null) { if (p.isClickable) return p; p = p.parent }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AdSkipService"
        private const val COOLDOWN_MS = 800L
    }
}
