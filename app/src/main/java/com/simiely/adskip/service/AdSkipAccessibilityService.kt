package com.simely.adskip.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.simely.adskip.AppState
import com.simely.adskip.float.ClickHintOverlay
import com.simely.adskip.model.Rule
import com.simely.adskip.service.capturer.CaptureManager
import com.simely.adskip.service.executor.ClickExecutor
import com.simely.adskip.service.guard.FilterGuard
import com.simely.adskip.service.matcher.RuleMatcher
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.Logger
import com.simely.adskip.util.SecurePrefs

/**
 * 无障碍服务入口（事件驱动的调度器）。
 * 只做编排，不写业务逻辑。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    private var secure: SecurePrefs? = null
    private var stats: StatsStore? = null
    private var filterGuard: FilterGuard? = null
    private var ruleMatcher: RuleMatcher? = null
    private var captureManager: CaptureManager? = null
    private var clickExecutor: ClickExecutor? = null

    private var screenW = 1080
    private var screenH = 2400

    private var ruleStore: RuleStore? = null

    /** 最近一次 WINDOW_STATE_CHANGED 记录到的 Activity 类名，用于规则的意义作用域 */
    private var currentActivity: String? = null
    private var lastActionKey = ""
    private var lastActionTime = 0L
    private val verifyHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunning = false
    private var lastPolledPkg = ""

    override fun onCreate() {
        super.onCreate()
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        try {
            val se = SecurePrefs(this)
            val rs = RuleStore(this)
            val kw = KeywordStore(this)
            val bl = BlockedRuleStore(this)
            val st = StatsStore(this)
            secure = se
            stats = st
            ruleStore = rs
            // 播种内置默认规则（两个向日葵 X 关闭按钮），确保稳定关闭逻辑开箱即用
            rs.ensureBuiltInRules()
            // 启动时清理长期未转正的候选规则（不触碰已启用/手动规则）
            rs.pruneExpiredCandidates()
            // 清理"危险范式"的已转正规则（仅有 className/无任何定位信息），它们是乱点/误同意协议的污染源
            rs.sanitizeDangerousRules()
            // 一次性迁移：旧版误把捕获App加入黑名单。将“已有活动规则却仍在黑名单”的包从黑名单摘除。
            if (!se.isFilterAutoMigratedV1004()) {
                if (se.getFilterMode()) {
                    val ruled = rs.activeRules().map { it.pkg }.toSet()
                    se.getBlacklist().forEach { if (it in ruled) se.removeFromBlacklist(it) }
                }
                se.markFilterAutoMigratedV1004()
            }

            filterGuard = FilterGuard(se, bl)
            ruleMatcher = RuleMatcher(kw, rs, bl)
            captureManager = CaptureManager(rs, se).also {
                it.setRootProvider { rootInActiveWindow }
                it.screenW = screenW; it.screenH = screenH
            }
            clickExecutor = ClickExecutor(ruleMatcher!!, se, screenW, screenH).also {
                it.onVisualFeedback = { detail -> ClickHintOverlay.show(this, detail) }
            }
            // 兜底：部分 App 回到前台时不产生任何无障碍事件（如静态广告页从最近任务唤回）。
            // 用轻量轮询主动读取当前前台窗口，App 一变就扫一次，不再被动等事件。
            if (!pollRunning) { pollRunning = true; startForegroundPoll() }
        } catch (e: Exception) {
            Logger.e("Failed to init", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = secure ?: return
        if (!s.getMasterEnabled()) return
        // 记录最近的 Activity（窗口切换事件 className 通常是 Activity 类名）
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentActivity = event.className?.toString()?.takeIf { it.isNotBlank() } ?: currentActivity
            // 切屏/换界面 = 新的广告上下文，重置每个按钮的"会话点击上限"，让新广告的✕可再次跳过
            clickExecutor?.resetSession()
        }

        val root = rootInActiveWindow
        if (root == null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            Logger.i("[root=null/活动窗口取不到] pkg=${event.packageName} cls=${event.className}")

        try {
            val pkg = event.packageName?.toString() ?: root?.packageName?.toString() ?: ""
            if (pkg == packageName) return
            if (AppState.isCapturing) {
                if (root != null && captureManager?.handleCaptureEvent(event, root) == true) return
            }
            // 页面刚打开时内容常异步渲染，首次扫描往往取不到目标。对每一次"开屏/切屏"都安排一次延迟补扫，
            // 避免"开屏时没点、直到用户动手产生新事件才点"。
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                scheduleDeferredScan(pkg, currentActivity ?: "")
            if (event.eventType in MATCH_EVENT_TYPES)
                scanAndClick(pkg, currentActivity ?: "")
        } finally {
            root?.recycle()
        }
    }

    private val deferredScanKeys = HashSet<String>()

    /** 页面打开后延迟补扫一次，捕获异步渲染出的广告/关闭按钮 */
    private fun scheduleDeferredScan(pkg: String, activity: String) {
        if (pkg.isEmpty()) return
        val key = "$pkg|$activity"
        if (!deferredScanKeys.add(key)) return
        Logger.d("[$pkg] 已安排延迟补扫 ${DEFERRED_SCAN_MS}ms")
        verifyHandler.postDelayed({
            deferredScanKeys.remove(key)
            try { scanAndClick(pkg, activity) } catch (_: Exception) {}
        }, DEFERRED_SCAN_MS)
    }

    private fun scanAndClick(pkg: String, activity: String) {
        if (pkg.isEmpty()) return
        if (secure?.getMasterEnabled() != true) return
        if (filterGuard?.isPkgAllowed(pkg) != true) { Logger.d("[$pkg] 被过滤(黑白名单)，跳过"); return }
        val matcher = ruleMatcher ?: return
        val sc = secure ?: return
        val now = System.currentTimeMillis()
        val actionKey = "$pkg|$activity"
        if (actionKey == lastActionKey && now - lastActionTime < ACTION_GAP_MS) return
        if (actionKey == lastScanKey && now - lastScanTime < MIN_SCAN_INTERVAL) return
        lastScanKey = actionKey
        lastScanTime = now
        val combined = mutableListOf<AccessibilityNodeInfo>()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            fun scan(r: AccessibilityNodeInfo?) {
                if (r == null || r.packageName?.toString() != pkg) return
                combined.addAll(matcher.findTargets(r, pkg, sc.getKeywordEnabled(), screenW, screenH, activity))
            }
            val actRoot = rootInActiveWindow
            if (actRoot != null) { roots.add(actRoot); scan(actRoot) }
            for (w in windows) { val r = w.root; if (r != null) { roots.add(r); scan(r) } }
            Logger.d("[$pkg] 扫描$activity 目标=${combined.size}")
            val audit = clickExecutor?.tryClick(combined, pkg, activity, stats)
            if (audit != null && audit.logText != "blocked") {
                lastActionKey = actionKey
                lastActionTime = now
                audit.candidate?.let { firedRuleThisSession.add(it.fingerprint()) } // 标记该规则"已执行"
                if (audit.candidate != null) scheduleVerification(audit.candidate, activity)
            }
        } finally {
            for (r in roots) { try { r.recycle() } catch (_: Exception) {} }
        }
    }

    /** 前台轮询：App 回到前台不产生事件时，仍能主动发现并扫描 */
    private fun startForegroundPoll() {
        if (pollRunning) {
            pollHandler.postDelayed({ pollTick() }, SCAN_POLL_MS)
        }
    }

    private fun pollTick() {
        if (!pollRunning) return
        try {
            if (secure?.getMasterEnabled() == true) {
                val pkg = foregroundPkg()
                if (pkg != null && pkg.isNotBlank() && pkg != packageName
                    && ruleStore?.hasActiveRuleFor(pkg) == true) {
                    // 只盯守"有规则"的 App；且只在【存在尚未执行的规则】时轮询。
                    // 规则全部已执行 → 不再周期扫描；超 30 秒仍未命中 → 也停止轮询。
                    // 两者都交给事件驱动（开屏/切屏/内容变化），避免无限空转耗电。
                    val pending = ruleStore?.activePendingFor(pkg, firedRuleThisSession) ?: false
                    if (!pending) {
                        lastPolledPkg = "" // 松开盯守，下次再现未执行规则时重新起 30s 计时
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (pkg != lastPolledPkg) {
                        lastPolledPkg = pkg
                        lastPollScanTime = 0L
                        pendingStartTime = now
                        Logger.d("[$pkg] 前台有未执行规则，开始盯守(至多 ${PENDING_DEADLINE_MS}ms)")
                    }
                    if (now - pendingStartTime > PENDING_DEADLINE_MS) return // 超过 30s 未命中，停止轮询
                    if (now - lastPollScanTime >= PENDING_SCAN_MS) {
                        lastPollScanTime = now
                        scanAndClick(pkg, currentActivity ?: "")
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (pollRunning) pollHandler.postDelayed({ pollTick() }, SCAN_POLL_MS)
    }

    /**
     * 找真正"持有焦点"的前台 App。
     * 澎湃OS上状态栏/通知栏等 SystemUI 窗口常霸占 isFocused，若按"第一个 focused 窗口"判断，
     * 前台会被误判成 com.android.systemui，导致真实 App 被漏扫。
     * 参考设备管控方案：只在 TYPE_APPLICATION 应用窗口里找，并排除系统瞬时包（输入法/SystemUI/权限弹窗等）。
     */
    private fun foregroundPkg(): String? {
        var focused: String? = null
        var active: String? = null
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val p = w.root?.packageName?.toString()?.takeIf {
                    it.isNotBlank() && it != packageName && it !in transientPkgs
                } ?: continue
                try {
                    if (w.isFocused) focused = p
                    if (w.isActive) active = p
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        // 应用窗口优先取 focused，其次取 active；两者都无则回退 rootInActiveWindow（同样排除系统瞬时包）
        val candidate = focused ?: active
        if (candidate != null) return candidate
        val r = rootInActiveWindow
        val p = r?.packageName?.toString()?.takeIf {
            it.isNotBlank() && it != packageName && it !in transientPkgs
        }
        try { r?.recycle() } catch (_: Exception) {}
        return p
    }

    /** 点击后延迟复查：只有"广告/弹窗真的消失"才当成可信命中，否则记 miss 并可自动降级 */
    private fun scheduleVerification(candidate: Rule, activity: String) {
        val label = candidate.viewId ?: candidate.text ?: "(无标识)"
        verifyHandler.postDelayed({
            val root = rootInActiveWindow
            val store = ruleStore
            if (root == null || store == null) return@postDelayed
            val gone = try {
                val vid = candidate.viewId
                if (vid != null) root.findAccessibilityNodeInfosByViewId(vid).isEmpty()
                else if (candidate.text != null) root.findAccessibilityNodeInfosByText(candidate.text!!).isEmpty()
                else true
            } finally {
                root.recycle()
            }
            Logger.d("[$activity] 结果验证 ${if (gone) "命中(目标消失)" else "miss(目标仍在)"} $label")
            if (gone) store.learnFromHit(candidate) else store.recordMiss(candidate)
        }, VERIFY_DELAY_MS)
    }

    private fun stopForegroundPoll() {
        pollRunning = false
        pollHandler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() { stopForegroundPoll() }

    override fun onDestroy() {
        stopForegroundPoll()
        super.onDestroy()
    }

    companion object {
        /** 触发内容匹配的事件类型 */
        private val MATCH_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        /** 同一 (pkg,activity) 两次深扫的最小间隔 */
        private const val MIN_SCAN_INTERVAL = 300L

        /** 同一 (pkg,activity) 两次点击动作的最小间隔：打断"事件风暴逐个点击并抓取新规则" */
        private const val ACTION_GAP_MS = 1500L

        /** 页面打开后延迟补扫一次，等待异步渲染出的广告/关闭按钮就绪 */
        private const val DEFERRED_SCAN_MS = 900L

        /** 前台轮询判定间隔：仅扫"有规则"的 App，无规则者零扫描开销 */
        private const val SCAN_POLL_MS = 1200L

        /** 有规则但尚未执行时的重扫间隔：广告随 App 前台后弹出（包名不变），需主动抓 */
        private const val PENDING_SCAN_MS = 3000L

        /** 轮询盯守上限：连续 30 秒仍未命中则停止轮询，交给事件驱动，避免无限空转 */
        private const val PENDING_DEADLINE_MS = 30000L

        /** 点击后等待多长时间再复查目标是否消失（结果验证） */
        private const val VERIFY_DELAY_MS = 700L
    }

    private var lastScanKey = ""
    private var lastScanTime = 0L
    private var lastPollScanTime = 0L
    private var pendingStartTime = 0L

    /** 本次服务生命周期内已成功执行过的规则指纹（用于把轮询从"快速"降级为"慢速兜底"） */
    private val firedRuleThisSession = HashSet<String>()

    /**
     * 与前台识别无关的"系统/瞬时包"，出现它们时不应视为真实前台切换：
     * 输入法、SystemUI(状态栏/通知栏)、权限控制器、APK安装器等。
     */
    private val transientPkgs = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller"
    )
}
