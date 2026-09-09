package com.simely.adskip.service.executor

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.service.matcher.RuleMatcher
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.AdFeature
import com.simely.adskip.util.Logger
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
    private val secure: SecurePrefs,
    private val screenW: Int,
    private val screenH: Int
) {
    /** 点击成功后的 UI 反馈回调（由 Service 注入，避免 service 层直接依赖 float 层） */
    var onVisualFeedback: ((String) -> Unit)? = null
    /** 冷却 Map：key = "pkg|viewId|text"，value = 最后点击时间 */
    private val lastClick = mutableMapOf<String, Long>()

    /** 点击计数（用于定期清理冷却 Map） */
    private var clickCount = 0

    /** 会话级每按钮点击次数：同一按钮连续点满上限后，本次会话不再点，避免"广告点不掉/常驻按钮"被无限连点 */
    private val sessionClickCount = mutableMapOf<String, Int>()

    /** 新界面上下文（切屏/换 Activity）时清空会话计数，让新广告对应的按钮可以再次被跳过 */
    fun resetSession() = sessionClickCount.clear()

    companion object {
        private const val COOLDOWN_MS = 800L

        /** 每 N 次冷却查询后清理一次过期条目 */
        private const val CLEANUP_INTERVAL = 100

        /**
         * 视为"容器型节点"的屏幕覆盖比例上限。
         * resolveClickable 会把不可点按钮解析到最近可点击祖先，可能一路跑到全屏容器。
         * 真正的关闭 ✕ 尺寸都很小，覆盖率超过该比例的节点判定为容器而非按钮，跳过，避免点空。
         */
        private const val CONTAINER_COVER_RATIO = 0.65f

        /** 同一按钮本次会话最多点击次数：点满即止，直到切屏/换界面才重置 */
        private const val MAX_SESSION_CLICKS = 3
    }

    /**
     * 在候选节点中找到第一个未被冷却、未被屏蔽的并执行点击。
     * @return 点击的节点文本（用于统计），null 表示未点击
     */
    fun tryClick(
        targets: List<AccessibilityNodeInfo>,
        pkg: String,
        activity: String?,
        stats: StatsStore?
    ): ClickAudit? {
        val resolvedNodes = mutableSetOf<AccessibilityNodeInfo>()

        for (node in targets) {
            val clickable = AccessibilityUtil.resolveClickable(node) ?: continue
            resolvedNodes.add(clickable)

            // 容器型节点跳过：覆盖屏幕过大的"可点击祖先"是布局容器而非关闭按钮，
            // 点它会点空/点中广告卡而非 ✕。真正的关闭 ✕ 尺寸远小于该类阈值。
            if (isContainerLike(clickable)) {
                Logger.d("[$pkg] 跳过容器节点 vid=${clickable.viewIdResourceName ?: "无"}")
                continue
            }

            // 屏蔽规则检查
            val btnText = node.text?.toString()?.trim()
            val ancestorText = clickable.text?.toString()?.trim()
            val btnVid = clickable.viewIdResourceName
            val blocked = ruleMatcher.isBlocked(pkg, btnText, btnVid) ||
                (ancestorText != null && ancestorText != btnText && ruleMatcher.isBlocked(pkg, ancestorText, btnVid))
            if (blocked) {
                onVisualFeedback?.invoke("⛔ 已屏蔽 $btnText")
                Logger.d("[$pkg] 候选被屏蔽 text=$btnText vid=$btnVid")
                continue // 只跳过该候选，不要中止整轮；否则队列里靠后的真正关闭✕再也点不到
            }

            // 冷却检查
            val key = "$pkg|${clickable.viewIdResourceName ?: clickable.text?.toString() ?: ""}"
            val now = System.currentTimeMillis()
            val since = now - (lastClick[key] ?: 0L)
            if (since < COOLDOWN_MS) {
                Logger.d("[$pkg] 冷却中(${COOLDOWN_MS - since}ms) key=$key")
                continue
            }

            // 会话点击上限：同一按钮连续点满 MAX_SESSION_CLICKS 次后停止，
            // 防止"广告点不掉/常驻按钮"被无限重复点击。切屏/换界面时由 Service 调 resetSession() 重置。
            val sessionTimes = sessionClickCount[key] ?: 0
            if (sessionTimes >= MAX_SESSION_CLICKS) {
                Logger.d("[$pkg] 同按钮已点满 $MAX_SESSION_CLICKS 次，本次会话不再点击 key=$key")
                continue
            }

            // 执行点击
            lastClick[key] = now
            val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Logger.d("[$pkg] 点击 ${if (success) "成功" else "失败"} text=$btnText vid=$clickable.viewIdResourceName")
            if (success) sessionClickCount[key] = sessionTimes + 1

            val vid = clickable.viewIdResourceName ?: ""
            val txt = node.text?.toString()?.ifEmpty { clickable.text?.toString() } ?: ""
            val cd = clickable.contentDescription?.toString() ?: ""
            val logText = txt.ifEmpty { cd }
            var candidate: Rule? = null
            if (success) {
                stats?.recordClick(pkg, logText, vid)
                onVisualFeedback?.invoke("$logText | $pkg")
                // 仅当具备广告跳过特征时才生成候选，交由 Service 做"点击后目标消失"的结果验证再入库
                candidate = buildCandidate(node, clickable, pkg, activity)
                // 自动将 App 标记为“放行”（黑名单模式从黑名单移除，白名单模式加入白名单）
                secure.ensurePkgAllowed(pkg)
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

            return if (success) ClickAudit(logText, candidate) else null
        }

        targets.forEach { it.recycle() }
        return null
    }

    /** 判断节点是否"容器型"：屏幕覆盖率超过阈值（如全屏可点击祖先），非真正按钮 */
    private fun isContainerLike(clickable: AccessibilityNodeInfo): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val r = Rect()
        clickable.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val cover = (r.width().toLong() * r.height().toLong()) /
            (screenW.toLong() * screenH.toLong()).toDouble()
        return cover >= CONTAINER_COVER_RATIO
    }

    /** 构造"广告特征候选"：无标识、或不具备广告跳过特征（远程桌面入口/许可协议"同意"等）的节点不产出候选 */
    private fun buildCandidate(
        node: AccessibilityNodeInfo,
        clickable: AccessibilityNodeInfo,
        pkg: String,
        activity: String?
    ): Rule? {
        val vid = clickable.viewIdResourceName ?: ""
        val txt = node.text?.toString()?.trim().orEmpty()
            .ifEmpty { clickable.text?.toString()?.trim().orEmpty() }
        val cd = clickable.contentDescription?.toString() ?: ""
        if (vid.isEmpty() && txt.isEmpty() && cd.isEmpty()) return null
        if (!AdFeature.looksLikeAd(txt, vid, cd)) return null
        return Rule(
            text = txt.takeIf { it.isNotEmpty() },
            viewId = vid.takeIf { it.isNotEmpty() },
            pkg = pkg,
            activity = activity?.takeIf { it.isNotBlank() },
            action = "click",
            name = txt.ifEmpty { cd }.takeIf { it.isNotEmpty() },
            contentDescription = cd.takeIf { it.isNotEmpty() },
            className = clickable.className?.toString()?.takeIf { it.isNotEmpty() }
        )
    }

    /** 一次点击动作的审计结果：logText 供统计/反馈；candidate 为待结果验证的候选规则（null=非广告特征不捕获） */
    data class ClickAudit(val logText: String?, val candidate: Rule?)
}
