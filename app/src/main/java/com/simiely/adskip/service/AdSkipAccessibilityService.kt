package com.simely.adskip.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.simely.adskip.AppState
import com.simely.adskip.float.ClickHintOverlay
import com.simely.adskip.service.capturer.CaptureManager
import com.simely.adskip.service.executor.ClickExecutor
import com.simely.adskip.service.guard.FilterGuard
import com.simely.adskip.service.learning.RuleLifecycle
import com.simely.adskip.service.matcher.RuleMatcher
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.ActionHistory
import com.simely.adskip.util.DebugFlags
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

    /** 规则学习引擎：承接已执行集合 + 点击后结果验证 + 命中/失验入库 */
    private var lifecycle: RuleLifecycle? = null

    /** 最近一次 WINDOW_STATE_CHANGED 记录到的 Activity 类名，用于规则的意义作用域 */
    private var currentActivity: String? = null
    private var lastActionKey = ""
    private var lastActionTime = 0L
    private val deferredScanHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunning = false
    private var lastPolledPkg = ""

    private var ruleControlReceiver: RuleControlReceiver? = null

    /** 调试用运行总开关：true 时完全停止扫描/点击（供 adb 随时叫停误触） */
    @Volatile
    private var debugPaused = false
    private var lastPollSeePkg: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 明文广播给 Manifest 静态接收器会被澎湃OS后台执行策略在入队时丢弃("Background execution not allowed")。
        // 改为在常驻的无障碍服务进程内动态注册，广播无需唤醒任何进程，直接送达运行的接收器。
        registerRuleControlReceiver()
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
            lifecycle = RuleLifecycle(rs).also { it.rootProvider = { rootInActiveWindow } }
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
        deferredScanHandler.postDelayed({
            deferredScanKeys.remove(key)
            try { scanAndClick(pkg, activity) } catch (_: Exception) {}
        }, DEFERRED_SCAN_MS)
    }

    private fun scanAndClick(pkg: String, activity: String) {
        if (pkg.isEmpty()) return
        if (debugPaused) return
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
            ActionHistory.record("扫描", "[$pkg] 目标=${combined.size}")
            val audit = clickExecutor?.tryClick(combined, pkg, activity, stats)
            if (audit != null && audit.logText != "blocked") {
                lastActionKey = actionKey
                lastActionTime = now
                audit.candidate?.let { lifecycle?.markFired(it.fingerprint()); lifecycle?.scheduleVerification(it, activity) } // 标记该规则"已执行"并安排结果验证
                ActionHistory.record("命中", "[$pkg] ${audit.candidate?.shortDescription() ?: audit.logText}")
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
                val target = pkg?.takeIf {
                    it.isNotBlank() && it != packageName && ruleStore?.hasActiveRuleFor(it) == true
                }
                if (target != null) {
                    val now = System.currentTimeMillis()
                    // 前台真正变化（含锁屏后重新回到本 App）= 新的广告上下文：清掉"已执行"标记、重新盯守，
                    // 这样解锁后新弹的广告（异步渲染）也能被再次扫描，而不是因为"规则已执行"被漏扫。
                    if (target != lastPollSeePkg) {
                        lastPollSeePkg = target
                        if (target != lastPolledPkg) {
                            lastPolledPkg = target
                            lifecycle?.reset()
                            lastPollScanTime = 0L
                            pendingStartTime = now
                            Logger.d("[$target] 前台重入，重新盯守(至多 ${PENDING_DEADLINE_MS}ms)")
                        }
                    }
                    val pending = lifecycle?.hasPendingRuleFor(target) ?: false
                    if (!pending) {
                        lastPolledPkg = "" // 松开盯守，下次再现未执行规则时重新起盯守
                        return
                    }
                    if (now - pendingStartTime > PENDING_DEADLINE_MS) return // 超过 30s 未命中，停止轮询
                    if (now - lastPollScanTime >= PENDING_SCAN_MS) {
                        lastPollScanTime = now
                        scanAndClick(target, currentActivity ?: "")
                    }
                } else {
                    lastPollSeePkg = null
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

    // ================= 调试命令处理（RuleControlReceiver 通过广播触发，无需重编译） =================
    private fun registerRuleControlReceiver() {
        runCatching {
            if (ruleControlReceiver != null) return
            val rc = RuleControlReceiver()
            val filter = IntentFilter().apply {
                addAction(RuleControlReceiver.ACTION_SET)
                addAction(RuleControlReceiver.ACTION_CLEAR_ALL)
                addAction(RuleControlReceiver.ACTION_CLEAR_PKG)
                addAction(RuleControlReceiver.ACTION_DUMP)
                addAction(RuleControlReceiver.ACTION_SCAN)
                addAction(RuleControlReceiver.ACTION_PAUSE)
                addAction(RuleControlReceiver.ACTION_RESUME)
                addAction(RuleControlReceiver.ACTION_DUMP_STATE)
                addAction(RuleControlReceiver.ACTION_DUMP_TREE)
                addAction(RuleControlReceiver.ACTION_TRACE)
                addAction(RuleControlReceiver.ACTION_DUMP_HISTORY)
                addAction(RuleControlReceiver.ACTION_DUMP_FIRED)
                addAction(RuleControlReceiver.ACTION_RESET_FIRED)
            }
            // 必须 EXPORTED：adb shell am broadcast 以 shell UID（≠ 应用 UID）发广播，
            // 非导出接收器会拦截其他 UID 的送达，导致调试接口不可用。个人调试接口可接受此暴露面。
            registerReceiver(rc, filter, Context.RECEIVER_EXPORTED)
            ruleControlReceiver = rc
            Logger.d("调试接收器已动态注册")
        }.onFailure { Logger.e("调试接收器注册失败", it) }
    }

    fun debugScanNow() {
        val pkg = foregroundPkg()
        Logger.d("[调试] 手动扫描  前台=$pkg activity=$currentActivity paused=$debugPaused")
        if (debugPaused) { Logger.d("[调试] 已暂停，跳过扫描"); return }
        if (pkg != null && pkg.isNotBlank() && ruleStore?.hasActiveRuleFor(pkg) == true)
            scanAndClick(pkg, currentActivity ?: "")
    }

    fun debugSetPaused(paused: Boolean) {
        debugPaused = paused
        Logger.d("[调试] 服务已${if (paused) "暂停" else "恢复"}")
    }

    fun debugSetTrace(on: Boolean) {
        DebugFlags.traceEnabled = on
        Logger.d("[调试] 匹配追踪已${if (on) "开启" else "关闭"}")
    }

    fun debugDumpHistory() {
        val list = ActionHistory.dump()
        Logger.d("[调试] ---- 最近动作历史(共 ${list.size} 条，最新在最后) ----")
        list.forEach { Logger.d("[调试]   $it") }
        Logger.d("[调试] ---- 历史结束 ----")
    }

    fun debugDumpFired() {
        lifecycle?.dumpFired() ?: Logger.d("[调试] 学习引擎未初始化")
    }

    fun debugResetFired() {
        val n = lifecycle?.firedCount ?: 0
        lifecycle?.reset()
        lastPollSeePkg = null
        lastPolledPkg = ""
        pendingStartTime = System.currentTimeMillis()
        Logger.d("[调试] 已清空本轮已执行规则($n)条，可重新盯守")
    }

    fun debugDumpState() {
        val f = foregroundPkg()
        Logger.d("[调试] 前台=$f activity=$currentActivity master=${secure?.getMasterEnabled()} " +
            "paused=$debugPaused 规则数=${ruleStore?.allRules()?.size} " +
            "poll=$pollRunning 本轮已执行规则=${lifecycle?.firedCount ?: 0} " +
            "screen=${screenW}x${screenH}")
    }

    fun debugDumpTree() {
        val root = rootInActiveWindow
        Logger.d("[调试] ---- 前台活动窗口节点树 ----")
        if (root == null) { Logger.d("[调试] root=null"); return }
        try {
            dumpNode(root, 0)
        } finally {
            root.recycle()
        }
        Logger.d("[调试] ---- 节点树结束 ----")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 14) return
        val b = Rect()
        runCatching { node.getBoundsInScreen(b) }
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val txt = node.text?.toString()
        val vid = node.viewIdResourceName
        val desc = node.contentDescription?.toString()
        val asyncText = StringBuilder()
        if (!node.isVisibleToUser) asyncText.append(" invisible")
        if (node.isClickable) asyncText.append(" click")
        node.viewIdResourceName?.let { if (it.startsWith("com.oray.sunlogin:id/") || it.endsWith("close") || it.endsWith("ad")) asyncText.append(" id") }
        val detail = buildList {
            if (!txt.isNullOrBlank()) add("text=$txt")
            if (!desc.isNullOrBlank()) add("desc=$desc")
            if (vid != null) add("vid=$vid")
            add("[$b]")
        }.joinToString(" ")
        Logger.d("[调试]${"  ".repeat(depth)}$cls clickable=${node.isClickable} $detail${asyncText}")
        val childCount = node.childCount
        if (childCount > 128) return
        for (i in 0 until childCount) {
            node.getChild(i)?.let { c ->
                dumpNode(c, depth + 1)
                runCatching { c.recycle() }
            }
        }
    }

    private fun stopForegroundPoll() {
        pollRunning = false
        pollHandler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() { stopForegroundPoll() }

    override fun onDestroy() {
        instance = null
        lifecycle?.shutdown()
        lifecycle = null
        ruleControlReceiver?.let { runCatching { unregisterReceiver(it) } }
        ruleControlReceiver = null
        stopForegroundPoll()
        super.onDestroy()
    }

    companion object {
        /** 当前存活的无障碍服务实例，供调试接收器(RuleControlReceiver)转发服务级命令 */
        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set

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
    }

    private var lastScanKey = ""
    private var lastScanTime = 0L
    private var lastPollScanTime = 0L
    private var pendingStartTime = 0L

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
