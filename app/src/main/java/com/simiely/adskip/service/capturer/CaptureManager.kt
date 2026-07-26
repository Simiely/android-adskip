package com.simely.adskip.service.capturer

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.AppState
import com.simely.adskip.model.Rule
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.SecurePrefs

/**
 * 捕获模式管理器：处理手动捕获按钮规则的完整流程。
 *
 * 将原来散落在 Service 中的 captureNode / saveCapturedRule / resolveToNearestClickable
 * 等逻辑集中管理。
 */
class CaptureManager(
    private val ruleStore: RuleStore,
    private val secure: SecurePrefs
) {
    /** 上次高亮扫描时间戳（200ms 防抖） */
    private var lastHighlightScan = 0L

    /**
     * 处理捕获相关事件。
     * @return true 表示已处理（事件被消费），false 表示非捕��事件，应继续常规匹配
     */
    fun handleCaptureEvent(event: AccessibilityEvent, root: AccessibilityNodeInfo): Boolean {
        val eventType = event.eventType

        // 点击/聚焦/选中事件 → 执行捕获
        if (eventType in CAPTURE_EVENT_TYPES) {
            val captured = captureNode(event)
            if (captured) {
                AppState.onCaptured?.invoke()
                AppState.exitCapture()
            }
            return true
        }

        // 窗口变化事件 → 刷新高亮（200ms 防抖）
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val now = System.currentTimeMillis()
            if (now - lastHighlightScan > 200) {
                lastHighlightScan = now
                val allRects = AccessibilityUtil.collectClickableBounds(root).toMutableList()
                // 追加 ✕/关闭 按钮的边界
                AccessibilityUtil.scanSkipOrCloseButtons(root, screenW, screenH).forEach { node ->
                    val r = android.graphics.Rect()
                    node.getBoundsInScreen(r)
                    allRects.add(r)
                }
                AppState.highlightedRects = allRects
            }
        }

        return false
    }

    /**
     * 捕获点击节点：获取 event.source 并提取规则信息。
     * 优先从 source 上溯到可点击祖先，source 为 null 时走焦点/兜底扫描。
     */
    private fun captureNode(event: AccessibilityEvent): Boolean {
        val pkgName = event.packageName?.toString() ?: ""
        val src = event.source

        if (src != null) {
            val clickTarget = AccessibilityUtil.resolveToNearestClickable(src)
            saveCapturedRule(clickTarget, pkgName, event)
            if (clickTarget !== src) clickTarget.recycle()
            return true
        }

        // source 为 null：窗口扫描兜底
        val root = this.rootForCapture() ?: return false
        try {
            var handled = false
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { fn ->
                if (fn.isClickable) { saveCapturedRule(fn, pkgName, event); handled = true }
                fn.recycle()
            }
            if (!handled) {
                root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { fn ->
                    if (fn.isClickable) { saveCapturedRule(fn, pkgName, event); handled = true }
                    fn.recycle()
                }
            }
            if (!handled) {
                AccessibilityUtil.findFirstClickableWithAnyContent(root)?.let {
                    saveCapturedRule(it, pkgName, event); handled = true
                }
            }
            return handled
        } finally {
            root.recycle()
        }
    }

    /**
     * 保存捕获到的规则，并自动将 App 加入过滤名单。
     */
    private fun saveCapturedRule(
        node: AccessibilityNodeInfo,
        pkgName: String,
        event: AccessibilityEvent
    ) {
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
        ruleStore.addRule(rule)

        secure.autoAddFilterPkg(pkgName)
    }

    // rootInActiveWindow 由 Service 提供
    private var rootProvider: (() -> AccessibilityNodeInfo?)? = null

    /** 屏幕尺寸（用于 ✕ 按钮高亮检测） */
    var screenW = 1080
    var screenH = 2400

    fun setRootProvider(provider: () -> AccessibilityNodeInfo?) {
        rootProvider = provider
    }

    private fun rootForCapture(): AccessibilityNodeInfo? = rootProvider?.invoke()

    companion object {
        /** 触发捕获的事件类型 */
        private val CAPTURE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED
        )
    }
}
