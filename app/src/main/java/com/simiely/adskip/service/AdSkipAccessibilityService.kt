package com.simely.adskip.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.simely.adskip.AppState
import com.simely.adskip.float.ClickHintOverlay
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

            filterGuard = FilterGuard(se, bl)
            ruleMatcher = RuleMatcher(kw, rs, bl)
            captureManager = CaptureManager(rs, se).also {
                it.setRootProvider { rootInActiveWindow }
                it.screenW = screenW; it.screenH = screenH
            }
            clickExecutor = ClickExecutor(ruleMatcher!!, se).also {
                it.onVisualFeedback = { detail -> ClickHintOverlay.show(this, detail) }
            }
        } catch (e: Exception) {
            Logger.e("Failed to init", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = secure ?: return
        if (!s.getMasterEnabled()) return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == packageName) return
            if (AppState.isCapturing) {
                if (captureManager?.handleCaptureEvent(event, root) == true) return
            }
            if (filterGuard?.isPkgAllowed(pkg) != true) return
            val matcher = ruleMatcher ?: return
            clickExecutor?.tryClick(
                matcher.findTargets(root, pkg, s.getKeywordEnabled(), screenW, screenH),
                pkg, stats
            )
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {}
}
