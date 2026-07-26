package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.simely.adskip.R
import com.simely.adskip.databinding.ActivityMainBinding
import com.simely.adskip.service.KeepAliveService
import com.simely.adskip.store.BlockedRuleStore
import com.simely.adskip.store.KeywordStore
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.ui.components.FilterSection
import com.simely.adskip.ui.components.KeywordSection
import com.simely.adskip.ui.components.LogSection
import com.simely.adskip.ui.components.RuleSection
import com.simely.adskip.ui.components.SyncSection
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.SecurePrefs

/**
 * 主界面（协调器）：持有各面板组件，负责权限导航、状态切换、全局操作。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var ruleStore: RuleStore
    private lateinit var keywordStore: KeywordStore
    private lateinit var blockedStore: BlockedRuleStore
    private lateinit var secure: SecurePrefs
    private lateinit var stats: StatsStore
    private var initDone = false

    // 组件
    private lateinit var keywordSection: KeywordSection
    private lateinit var ruleSection: RuleSection
    private lateinit var filterSection: FilterSection
    private lateinit var logSection: LogSection
    private lateinit var syncSection: SyncSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ruleStore = RuleStore(this)
        keywordStore = KeywordStore(this)
        blockedStore = BlockedRuleStore(this)
        secure = SecurePrefs(this)
        stats = StatsStore(this)

        initComponents()
        setupPermissionButtons()
        setupCollapsibleSections()
        setupSwitchs()
        setupGlobalButtons()
        ensureDefaultPasswords()

        // 恢复解锁状态
        syncSection.setup()

        // 初始渲染
        refreshAll()
        showDisabledRuleNotice()
        initDone = true
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard()
        refreshAll()
    }

    // ── 初始化组件 ──

    private fun initComponents() {
        keywordSection = KeywordSection(this, b.listKeywords, keywordStore)
        ruleSection = RuleSection(this, b.ruleGroups, ruleStore, blockedStore)
        filterSection = FilterSection(this, b.panelBlacklist, b.panelWhitelist, secure)
        logSection = LogSection(this, b.listLogs, ruleStore, blockedStore, stats, secure).also {
            it.onRulesChanged = { ruleSection.render() }
        }

        syncSection = SyncSection(
            activity = this,
            scope = lifecycleScope,
            etPassword = b.etPassword,
            btnUnlock = b.btnUnlock,
            syncPanel = b.syncPanel,
            etRepoOwner = b.etRepoOwner,
            etRepoName = b.etRepoName,
            etRepoBranch = b.etRepoBranch,
            etRepoPath = b.etRepoPath,
            etToken = b.etToken,
            btnDownload = b.btnDownload,
            btnUpload = b.btnUpload,
            pbSync = b.pbSync,
            tvSyncStatus = b.tvSyncStatus,
            cfgPanel = b.cfgPanel,
            etCfgToken = b.etCfgToken,
            btnCfgDownload = b.btnCfgDownload,
            btnCfgUpload = b.btnCfgUpload,
            pbCfgSync = b.pbCfgSync,
            tvCfgStatus = b.tvCfgStatus,
            ruleStore = ruleStore,
            keywordStore = keywordStore,
            secure = secure
        )
    }

    // ── 权限导航 ──

    private fun setupPermissionButtons() {
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ── 可折叠面板 ──

    private fun setupCollapsibleSections() {
        setupCollapsible(b.headerLogs, b.listLogs, b.arrowLogs, "logs_open") { logSection.render() }
        setupCollapsible(b.headerFilter, b.panelFilter, b.arrowFilter, "filter_open") { filterSection.render() }
        setupCollapsible(b.headerBlacklist, b.panelBlacklist, b.arrowBlacklist, "blacklist_open") {}
        setupCollapsible(b.headerWhitelist, b.panelWhitelist, b.arrowWhitelist, "whitelist_open") {}
        setupCollapsible(b.headerKeywords, b.panelKeywords, b.arrowKw, "kw_open") { keywordSection.render() }
        setupCollapsible(b.headerCapture, b.panelCapture, b.arrowCap, "cap_open") { ruleSection.render() }
        setupCollapsible(b.headerPermission, b.panelPermission, b.arrowPerm, "perm_open") {}
        setupCollapsible(b.headerHidden, b.panelHidden, b.arrowHid, "hid_open") {}
    }

    private fun setupCollapsible(
        header: View, panel: View, arrow: android.widget.TextView,
        key: String, onOpen: () -> Unit
    ) {
        val v = secure.prefs().getBoolean(key, panel.visibility == View.VISIBLE)
        panel.visibility = if (v) View.VISIBLE else View.GONE
        arrow.text = if (v) "-" else "+"
        header.setOnClickListener {
            val nv = panel.visibility != View.VISIBLE
            panel.visibility = if (nv) View.VISIBLE else View.GONE
            arrow.text = if (nv) "-" else "+"
            secure.prefs().edit().putBoolean(key, nv).apply()
            if (nv) onOpen()
        }
    }

    // ── 开关 ──

    private fun setupSwitchs() {
        // 悬浮窗开关
        b.switchCapsule.isChecked = secure.isCapsuleEnabled()
        b.switchCapsule.setOnCheckedChangeListener { _, c ->
            secure.setCapsuleEnabled(c)
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java).apply {
                action = if (c) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            })
        }

        // 总开关
        b.switchMaster.isChecked = secure.getMasterEnabled()
        b.switchMaster.setOnCheckedChangeListener { _, c -> if (initDone) secure.setMasterEnabled(c) }

        // 关键词开关
        b.switchKeyword.isChecked = secure.getKeywordEnabled()
        b.switchKeyword.setOnCheckedChangeListener { _, c -> if (initDone) secure.setKeywordEnabled(c) }

        // 过滤开关 + 模式
        b.switchFilter.isChecked = secure.isFilterEnabled()
        b.switchFilter.setOnCheckedChangeListener { _, c -> secure.setFilterEnabled(c) }
    }


    // ── 全局按钮 ──

    private fun setupGlobalButtons() {
        // 关键词
        b.btnAddKeyword.setOnClickListener {
            val kw = b.etKeyword.text.toString().trim()
            if (kw.isNotEmpty()) { keywordStore.add(kw); b.etKeyword.text.clear(); keywordSection.render() }
        }
        // 过滤
        b.btnAddFilter.setOnClickListener { filterSection.showAppPicker() }
        // 清空规则
        b.btnClearRules.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空所有规则").setMessage("确定删除所有捕获规则？")
                .setPositiveButton("清空") { _, _ -> ruleStore.clear(); ruleSection.render() }
                .setNegativeButton("取消", null).show()
        }
        // 清空屏蔽
        b.btnClearBlocked.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空所有屏蔽").setMessage("确定清空所有屏蔽规则？")
                .setPositiveButton("清空") { _, _ -> blockedStore.clear(); ruleSection.render() }
                .setNegativeButton("取消", null).show()
        }
        // 同步
        b.btnUnlock.setOnClickListener { syncSection.handleUnlock() }
        b.btnDownload.setOnClickListener { syncSection.syncOp(true) }
        b.btnUpload.setOnClickListener { syncSection.syncOp(false) }
        b.btnCfgDownload.setOnClickListener { syncSection.cfgSync(true) }
        b.btnCfgUpload.setOnClickListener { syncSection.cfgSync(false) }
        b.btnCfgUpdateToken.setOnClickListener {
            secure.setConfigToken(b.etCfgToken.text.toString().trim()); toast("Token 已更新")
        }
        // 分享 + 清空统计
        b.btnShare.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, stats.getShareText())
            }, "分享统计"))
        }
        b.btnClearStats.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空统计").setMessage("确定清空点击次数、运行天数和最近点击记录吗？")
                .setPositiveButton("清空") { _, _ -> stats.clear(); refreshAll(); toast("已清空") }
                .setNegativeButton("取消", null).show()
        }
        // 重启 / 退出
        b.btnRestart.setOnClickListener {
            stopService(Intent(this, KeepAliveService::class.java))
            if (AccessibilityUtil.isAccessibilityEnabled(this)) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
            refreshAll(); toast("服务已重启")
        }
        b.btnExit.setOnClickListener {
            stopService(Intent(this, KeepAliveService::class.java)); finishAffinity()
        }
    }

    // ── 状态 ──

    private fun updateStatusCard() {
        val accOn = AccessibilityUtil.isAccessibilityEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        b.vStatusIcon.setBackgroundResource(if (accOn && overlayOn) R.drawable.bg_status_on else R.drawable.bg_status_off)
        b.tvStatusTitle.text = if (accOn && overlayOn) "服务运行中" else "服务未启动"
        b.tvStatusSub.text = if (accOn && overlayOn) "无障碍服务已开启" else "请开启权限"
        b.switchCapsule.isEnabled = accOn && overlayOn
        if (!b.switchCapsule.isEnabled) b.switchCapsule.isChecked = false
        else b.switchCapsule.isChecked = secure.isCapsuleEnabled()
        b.switchKeyword.isChecked = secure.getKeywordEnabled()
        b.switchMaster.isChecked = secure.getMasterEnabled()
    }

    private fun refreshAll() {
        refreshStats()
        keywordSection.render()
        ruleSection.render()
        filterSection.render()
        logSection.render()
        // 过滤列表有数据时自动展开面板
        if (secure.getFilterList().isNotEmpty() && b.panelFilter.visibility != View.VISIBLE) {
            b.panelFilter.visibility = View.VISIBLE
            b.arrowFilter.text = "-"
        }
    }

    private fun refreshStats() {
        b.tvTotalCount.text = fmt(stats.getTotalCount())
        b.tvTodayCount.text = "${stats.getTodayCount()}"
        b.tvUsageDays.text = "${stats.getUsageDays()}"
    }

    private fun showDisabledRuleNotice() {
        val r = secure.getDisabledRule()
        if (r.isEmpty()) return
        secure.setMasterEnabled(false)
        b.switchMaster.isChecked = false
        AlertDialog.Builder(this).setTitle("总开关已自动关闭")
            .setMessage("检测到 [$r] 5 秒内触发 3 次，已关闭。")
            .setPositiveButton("知道了") { _, _ -> secure.clearDisabledRule() }.show()
    }

    private fun ensureDefaultPasswords() {
        if (!secure.isPasswordSet()) secure.setPasswordHash(SecurePrefs.hash("12345678"))
        if (!secure.isConfigPasswordSet()) secure.setConfigPasswordHash(SecurePrefs.hash("123"))
        blockedStore.ensureSystemBlocked()
    }

    // ── 工具 ──

    private fun fmt(n: Long): String = if (n >= 1000) "${n / 1000}.${(n % 1000) / 100}k" else "$n"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
