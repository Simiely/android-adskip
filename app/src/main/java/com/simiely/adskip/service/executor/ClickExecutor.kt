package com.simely.adskip.service.executor

import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.service.matcher.RuleMatcher
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.SecurePrefs

/**
 * 点击执行器：冷却控制 + 屏蔽过滤 + 统计记录 + 防泄漏。
 *
 * 职责：
 *   - 对匹配到的目标节点执行点击
 *   - 同一按钮 800ms 内不重复点击（冷却 Map 含 pkg 前缀避免跨 App 互锁）
 *   - 屏蔽规则优先：匹配到屏蔽规则的按钮永远不会被点击
 *   - 点击成功后记录统计
 *   - 定期清理冷却 Map 过期条目
 */
class ClickExecutor(
    private val ruleMatcher: RuleMatcher,
    private val secure: SecurePrefs
) {
    /** 点击成功后的 UI 反馈回调（由 Service 注入，避免 service 层直接依赖 float 层） */
    var onVisualFeedback: ((String) -> Unit)? = null
    /** 冷却 Map：key = "pkg|viewId|text"，value = 最后点击时间 */
    private val lastClick = mutableMapOf<String, Long>()

    /** 点击计数（用于定期清理冷却 Map） */
    private var clickCount = 0

    companion object {
        private const val COOLDOWN_MS = 800L

        /** 每 N 次冷却查询后清理一次过期条目 */
        private const val CLEANUP_INTERVAL = 100
    }

    /**
     * 在候选节点中找到第一个未被冷却、未被屏蔽的并执行点击。
     * @return 点击的节点文本（用于统计），null 表示未点击
     */
    fun tryClick(
        targets: List<AccessibilityNodeInfo>,
        pkg: String,
        stats: StatsStore?
    ): String? {
        val resolvedNodes = mutableSetOf<AccessibilityNodeInfo>()

        for (node in targets) {
            val clickable = AccessibilityUtil.resolveClickable(node) ?: continue
            resolvedNodes.add(clickable)

            // 屏蔽规则检查
            val btnText = node.text?.toString()?.trim()
            val ancestorText = clickable.text?.toString()?.trim()
            val btnVid = clickable.viewIdResourceName
            val blocked = ruleMatcher.isBlocked(pkg, btnText, btnVid) ||
                (ancestorText != null && ancestorText != btnText && ruleMatcher.isBlocked(pkg, ancestorText, btnVid))
            if (blocked) {
                // 被屏蔽：显示红色浮层
                onVisualFeedback?.invoke("⛔ 已屏蔽 $btnText")
                return "blocked"
            }

            // 冷却检查
            val key = "$pkg|${clickable.viewIdResourceName ?: clickable.text?.toString() ?: ""}"
            val now = System.currentTimeMillis()
            if (now - (lastClick[key] ?: 0L) < COOLDOWN_MS) continue

            // 执行点击
            lastClick[key] = now
            val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val vid = clickable.viewIdResourceName ?: ""
            val txt = node.text?.toString()?.ifEmpty { clickable.text?.toString() } ?: ""
            val cd = clickable.contentDescription?.toString() ?: ""
            val logText = txt.ifEmpty { cd }
            if (success) {
                stats?.recordClick(pkg, logText, vid)
                onVisualFeedback?.invoke("$logText | $pkg")
                // 自动捕获：仅当按钮有实际标识信息时才保存（避免纯位置匹配的无意义规则）
                val hasIdentity = vid.isNotEmpty() || txt.isNotEmpty() || cd.isNotEmpty()
                if (hasIdentity) {
                    ruleMatcher.autoCaptureRule(Rule(
                        text = clickable.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                        viewId = vid.takeIf { it.isNotEmpty() },
                        pkg = pkg,
                        activity = null,
                        action = "click",
                        name = logText.takeIf { it.isNotEmpty() },
                        contentDescription = cd.takeIf { it.isNotEmpty() },
                        className = clickable.className?.toString()?.takeIf { it.isNotEmpty() }
                    ))
                    // 自动将 App 加入当前过滤名单
                    secure.autoAddFilterPkg(pkg)
                }
            }

            // 回收 targets 列表中的节点
            targets.forEach { it.recycle() }
            // resolvedNodes 不回收 —— 通过 .parent 获取的是活树节点

            // 定期清理冷却 Map
            clickCount++
            if (clickCount % CLEANUP_INTERVAL == 0) {
                val cutoff = System.currentTimeMillis() - COOLDOWN_MS * 10
                lastClick.entries.removeAll { it.value < cutoff }
            }

            return if (success) logText else null
        }

        targets.forEach { it.recycle() }
        return null
    }
}
