package com.simely.adskip.service.learning

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.simely.adskip.model.Rule
import com.simely.adskip.store.RuleStore
import com.simely.adskip.util.Logger

/**
 * 规则生命周期（学习引擎）：承接"点击后发生了什么"这一规则闭环。
 * 把散落在 Service 中的「已执行集合 + 结果验证 + 命中/失验入库」收敛到一处：
 *   - fired 集合：决定轮询盯守该 App 是否还有"未执行规则"
 *   - 点击后延迟复查：目标消失 → learnFromHit(累计转正)；仍在 → recordMiss(连错可降级停用)
 * RuleStore 只负责落库与状态机，本类负责"何时触发学习/验证"。
 */
class RuleLifecycle(private val ruleStore: RuleStore) {

    /** 已成功执行过的规则指纹（供轮询判定该 App 是否还有未执行规则；可迭代/查计数，不可直接改） */
    val fired: Set<String> get() = _fired

    private val _fired = HashSet<String>()

    private val verifyHandler = Handler(Looper.getMainLooper())

    /** 由 Service 注入：取当前活动窗口根节点（结果验证需要复查目标是否消失） */
    var rootProvider: (() -> AccessibilityNodeInfo?)? = null

    /** 标记该规则已成功执行（命中打卡） */
    fun markFired(fingerprint: String) { _fired.add(fingerprint) }

    /** 清空已执行集合（前台重入/调试重置，让未执行规则重新被盯守） */
    fun reset() { _fired.clear() }

    val firedCount: Int get() = _fired.size

    /** 该包是否还有"未执行"的已转正规则（决定轮询是否需要继续盯守） */
    fun hasPendingRuleFor(pkg: String): Boolean = ruleStore.activePendingFor(pkg, _fired)

    /**
     * 点击后延迟复查：只有"广告/弹窗真的消失"才当成可信命中，否则记 miss 并可自动降级。
     * 由 Service 在每次成功点击后调用（原 AdSkipAccessibilityService.scheduleVerification）。
     */
    fun scheduleVerification(candidate: Rule, activity: String) {
        val label = candidate.viewId ?: candidate.text ?: "(无标识)"
        verifyHandler.postDelayed({
            val root = rootProvider?.invoke() ?: return@postDelayed
            val gone = try {
                val vid = candidate.viewId
                if (vid != null) root.findAccessibilityNodeInfosByViewId(vid).isEmpty()
                else if (candidate.text != null) root.findAccessibilityNodeInfosByText(candidate.text!!).isEmpty()
                else true
            } finally {
                runCatching { root.recycle() }
            }
            Logger.d("[$activity] 结果验证 ${if (gone) "命中(目标消失)" else "miss(目标仍在)"} $label")
            if (gone) ruleStore.learnFromHit(candidate) else ruleStore.recordMiss(candidate)
        }, VERIFY_DELAY_MS)
    }

    /** 调试：转储本轮已执行规则 */
    fun dumpFired() {
        Logger.d("[调试] ---- 本轮已执行规则(${_fired.size} 条) ----")
        (ruleStore.allRules() ?: emptyList())
            .filter { it.fingerprint() in _fired }
            .forEach { Logger.d("[调试]   [${it.pkg}] ${it.shortDescription()}") }
        Logger.d("[调试] ---- 已执行规则结束 ----")
    }

    /** 服务销毁时清理挂起的验证回调 */
    fun shutdown() {
        verifyHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        /** 点击后等待多长时间再复查目标是否消失（结果验证） */
        private const val VERIFY_DELAY_MS = 700L
    }
}