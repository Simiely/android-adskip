package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.simely.adskip.R
import com.simely.adskip.databinding.ActivityMainBinding
import com.simely.adskip.model.RuleSet
import com.simely.adskip.service.KeepAliveService
import com.simely.adskip.store.RuleStore
import com.simely.adskip.store.StatsStore
import com.simely.adskip.sync.GitHubSync
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ruleStore: RuleStore
    private lateinit var secure: SecurePrefs
    private lateinit var statsStore: StatsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ruleStore = RuleStore(this)
        secure = SecurePrefs(this)
        statsStore = StatsStore(this)

        // ── 权限按钮 ──
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        // ── 悬浮窗开关 ──
        binding.switchCapsule.setOnCheckedChangeListener { _, checked ->
            val intent = Intent(this, KeepAliveService::class.java).apply {
                action = if (checked) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // ── 可折叠面板 ──
        setupCollapsible(binding.headerStats, binding.panelStats, "panel_stats")
        setupCollapsible(binding.headerPermission, binding.panelPermission, "panel_permission")
        setupCollapsible(binding.headerRules, binding.panelRules, "panel_rules")
        // 日志子面板
        setupCollapsible(binding.headerLogs, binding.panelLogs, "panel_logs")

        // ── 总开关 ──
        binding.switchMaster.isChecked = secure.getMasterEnabled()
        binding.switchMaster.setOnCheckedChangeListener { _, c -> secure.setMasterEnabled(c) }
        binding.switchKeyword.isChecked = secure.getKeywordEnabled()
        binding.switchKeyword.setOnCheckedChangeListener { _, c -> secure.setKeywordEnabled(c) }

        // ── 关键词 ──
        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty()) { ruleStore.addKeyword(kw); binding.etKeyword.text.clear(); renderKeywords() }
        }

        // ── 统一密码门 ──
        if (!secure.isPasswordSet()) secure.setPasswordHash(SecurePrefs.hash("12345678"))
        if (!secure.isConfigPasswordSet()) secure.setConfigPasswordHash(SecurePrefs.hash("123"))
        binding.btnUnlock.setOnClickListener { handleUnlock() }
        binding.btnDownload.setOnClickListener { onDownload() }
        binding.btnUpload.setOnClickListener { onUpload() }
        binding.btnCfgDownload.setOnClickListener { onCfgDownload() }
        binding.btnCfgUpload.setOnClickListener { onCfgUpload() }
        binding.btnCfgUpdateToken.setOnClickListener { onCfgUpdateToken() }

        // ── 统计 ──
        binding.btnMonthly.setOnClickListener { showMonthlyStats() }
        binding.btnYearly.setOnClickListener { showYearlyStats() }
        binding.btnShare.setOnClickListener { shareStats() }

        refreshStats()
        renderKeywords()
        renderRules()

        // 检查是否有自动关闭总开关的提示
        showDisabledRuleNotice()
    }

    // ── 防死循环提示 ──
    private fun showDisabledRuleNotice() {
        val rule = secure.getDisabledRule()
        if (rule.isEmpty()) return
        secure.clearDisabledRule()
        // 更新开关 UI
        binding.switchMaster.isChecked = false
        val parts = rule.split("|")
        val pkgName = parts.getOrNull(0) ?: rule
        val btnText = parts.getOrNull(1) ?: ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("总开关已自动关闭")
            .setMessage("检测到应用 [$pkgName] 的按钮 [$btnText] 在 5 秒内触发了 3 次，为防止死循环已自动关闭总开关。\n\n如需恢复，请手动打开总开关。")
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val accOn = AccessibilityUtil.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        binding.tvStatus.text = if (accOn && overlayOn) getString(R.string.status_running) else getString(R.string.status_stopped)
        if (accOn && overlayOn) { binding.switchCapsule.isEnabled = true }
        else { binding.switchCapsule.isEnabled = false; binding.switchCapsule.isChecked = false }
        if (accOn) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        refreshStats()
    }

    // ── 统计 ──
    private fun refreshStats() {
        binding.tvTotalCount.text = "${statsStore.getTotalCount()}"
        binding.tvTodayCount.text = "${statsStore.getTodayCount()}"
        binding.tvUsageDays.text = "${statsStore.getUsageDays()}"
        binding.chartView.setData(statsStore.getLast7Days())
        renderLogs()
    }

    private fun showMonthlyStats() {
        val data = statsStore.getMonthlyMap().toSortedMap()
        val sb = StringBuilder("每月点击次数：\n")
        data.forEach { (m, c) -> sb.appendLine("$m : $c 次") }
        binding.tvDetailStats.text = sb.toString()
        binding.tvDetailStats.visibility = View.VISIBLE
    }

    private fun showYearlyStats() {
        val data = statsStore.getYearlyMap().toSortedMap()
        val sb = StringBuilder("每年点击次数：\n")
        data.forEach { (y, c) -> sb.appendLine("$y : $c 次") }
        binding.tvDetailStats.text = sb.toString()
        binding.tvDetailStats.visibility = View.VISIBLE
    }

    private fun shareStats() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, statsStore.getShareText())
        }
        startActivity(Intent.createChooser(intent, "分享统计"))
    }

    private fun renderLogs() {
        binding.panelLogs.removeAllViews()
        val logs = statsStore.getRecentLogs().take(20)
        for (log in logs) {
            val tv = TextView(this).apply {
                text = "${log.formattedTime()}  ${log.pkg.split(".").lastOrNull() ?: log.pkg}  —  ${log.text}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setPadding(0, 2, 0, 2)
            }
            binding.panelLogs.addView(tv)
        }
        if (logs.isEmpty()) {
            binding.panelLogs.addView(TextView(this).apply {
                text = "暂无记录"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
        }
    }

    // ── 规则渲染 ──
    private fun renderKeywords() {
        binding.listKeywords.removeAllViews()
        for (kw in ruleStore.getUserKeywords()) {
            binding.listKeywords.addView(makeRow(kw, null) { ruleStore.removeKeyword(kw); renderKeywords() })
        }
    }

    private fun renderRules() {
        binding.listRules.removeAllViews()
        val rules = ruleStore.getRules()
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        for (rule in rules) {
            val primary = rule.name ?: rule.text ?: rule.viewId ?: "(无文字)"
            val secondary = "${rule.pkg}  ·  ${rule.viewId ?: "无ID"}"
            binding.listRules.addView(makeRow(primary, secondary) { ruleStore.removeRule(rule.fingerprint()); renderRules() })
        }
    }

    private fun makeRow(primary: String, secondary: String?, onDelete: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4) }
        val tv = TextView(this).apply {
            text = if (secondary != null) "$primary\n$secondary" else primary
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val del = Button(this).apply {
            text = "删除"; textSize = 11f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onDelete() }
        }
        row.addView(tv); row.addView(del)
        return row
    }

    // ── 可折叠 ──
    private fun setupCollapsible(header: View, panel: View, key: String) {
        val headerTv = header as TextView
        val prefs = getSharedPreferences("main_ui", MODE_PRIVATE)
        val expanded = prefs.getBoolean(key, true)
        fun updateArrow() {
            val visible = panel.visibility == View.VISIBLE
            headerTv.text = headerTv.text.toString().replaceFirst(if (visible) "▼" else "▶", if (visible) "▶" else "▼")
        }
        if (!expanded) { panel.visibility = View.GONE; updateArrow() }
        header.setOnClickListener {
            if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE; prefs.edit().putBoolean(key, false).apply() }
            else { panel.visibility = View.VISIBLE; prefs.edit().putBoolean(key, true).apply() }
            updateArrow()
        }
    }

    // ── 统一密码门：1234→规则同步  123→配置同步 ──
    private fun handleUnlock() {
        val pwd = binding.etPassword.text.toString()
        if (pwd.isEmpty()) return toast("请输入密码")
        val hash = SecurePrefs.hash(pwd)
        when {
            hash == secure.getPasswordHash() -> {
                revealSyncPanel(); toast(R.string.toast_unlocked)
            }
            hash == secure.getConfigPasswordHash() -> {
                revealCfgPanel(); toast("配置面板已解锁")
            }
            else -> toast(R.string.toast_wrong_password)
        }
        binding.etPassword.text.clear()
    }

    private fun revealSyncPanel() {
        binding.syncPanel.visibility = View.VISIBLE; binding.cfgPanel.visibility = View.GONE
        binding.etRepoOwner.setText(secure.getRepoOwner())
        binding.etRepoName.setText(secure.getRepoName())
        binding.etRepoBranch.setText(secure.getRepoBranch())
        binding.etRepoPath.setText(secure.getRepoPath())
        binding.etToken.setText(secure.getToken())
    }

    private fun revealCfgPanel() {
        binding.cfgPanel.visibility = View.VISIBLE; binding.syncPanel.visibility = View.GONE
        binding.etCfgToken.setText(secure.getConfigToken())
    }

    private data class RepoConfig(val owner: String, val repo: String, val branch: String, val path: String)

    private fun readRepo(): RepoConfig = RepoConfig(
        binding.etRepoOwner.text.toString().trim(), binding.etRepoName.text.toString().trim(),
        binding.etRepoBranch.text.toString().trim().ifEmpty { "main" },
        binding.etRepoPath.text.toString().trim().ifEmpty { "rules.json" }
    )

    private fun persistRepo(cfg: RepoConfig) {
        secure.setRepoOwner(cfg.owner); secure.setRepoName(cfg.repo)
        secure.setRepoBranch(cfg.branch); secure.setRepoPath(cfg.path)
    }

    private fun onDownload() {
        val cfg = readRepo()
        if (cfg.owner.isEmpty() || cfg.repo.isEmpty()) return toast("请填写仓库 owner 与 repo")
        persistRepo(cfg); secure.setToken(binding.etToken.text.toString().trim())
        startSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = secure.getToken()
                val json = if (token.isNotEmpty()) GitHubSync.downloadApi(cfg.owner, cfg.repo, cfg.branch, cfg.path, token).first
                else GitHubSync.downloadRaw(cfg.owner, cfg.repo, cfg.branch, cfg.path)
                if (json.isNotEmpty()) ruleStore.mergeRemote(RuleSet.parse(json))
                withContext(Dispatchers.Main) { stopSync(); toast(R.string.toast_download_ok); renderRules(); renderKeywords() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopSync(); toast(getString(R.string.toast_sync_fail, e.message ?: "")) }
            }
        }
    }

    private fun onUpload() {
        val token = binding.etToken.text.toString().trim()
        if (token.isEmpty()) return toast(R.string.hint_no_token_upload)
        val cfg = readRepo()
        if (cfg.owner.isEmpty() || cfg.repo.isEmpty()) return toast("请填写仓库 owner 与 repo")
        persistRepo(cfg); secure.setToken(token)
        startSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = ruleStore.exportSet().toJsonString()
                val (_, sha) = GitHubSync.downloadApi(cfg.owner, cfg.repo, cfg.branch, cfg.path, token)
                val ok = GitHubSync.upload(cfg.owner, cfg.repo, cfg.branch, cfg.path, token, content, sha)
                withContext(Dispatchers.Main) { stopSync(); toast(if (ok) getString(R.string.toast_upload_ok) else getString(R.string.toast_sync_fail, "HTTP 失败")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopSync(); toast(getString(R.string.toast_sync_fail, e.message ?: "")) }
            }
        }
    }

    private fun startSync() {
        binding.pbSync.visibility = View.VISIBLE; binding.btnDownload.isEnabled = false; binding.btnUpload.isEnabled = false
        binding.tvSyncStatus.text = "同步中…"
    }
    private fun stopSync() {
        binding.pbSync.visibility = View.GONE; binding.btnDownload.isEnabled = true; binding.btnUpload.isEnabled = true
        binding.tvSyncStatus.text = ""
    }

    // ── 配置同步（密码 123，仓库预置） ──
    private val CFG_OWNER = "Simiely"
    private val CFG_REPO = "android-adskip"
    private val CFG_BRANCH = "main"
    private val CFG_PATH = "configs"

    private fun onCfgDownload() {
        val token = binding.etCfgToken.text.toString().trim()
        secure.setConfigToken(token)
        startCfgSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = if (token.isEmpty()) {
                    listOf("rules.json" to "https://raw.githubusercontent.com/$CFG_OWNER/$CFG_REPO/$CFG_BRANCH/$CFG_PATH/rules.json")
                } else {
                    GitHubSync.listFolder(CFG_OWNER, CFG_REPO, CFG_BRANCH, CFG_PATH, token)
                }
                if (files.isEmpty()) { withContext(Dispatchers.Main) { stopCfgSync(); toast("目录为空") }; return@launch }
                for ((name, url) in files) {
                    if (!name.endsWith(".json")) continue
                    val json = if (token.isEmpty()) GitHubSync.downloadRaw(CFG_OWNER, CFG_REPO, CFG_BRANCH, "$CFG_PATH/$name")
                    else GitHubSync.downloadRawContent(url)
                    runCatching { ruleStore.mergeRemote(RuleSet.parse(json)) }
                }
                withContext(Dispatchers.Main) { stopCfgSync(); toast("下载完成"); renderRules(); renderKeywords() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopCfgSync(); toast("下载失败: ${e.message}") }
            }
        }
    }

    private fun onCfgUpload() {
        val token = binding.etCfgToken.text.toString().trim()
        if (token.isEmpty()) { toast("请先输入 Token"); onCfgUpdateToken(); return }
        startCfgSync()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!GitHubSync.validateToken(token)) {
                    withContext(Dispatchers.Main) { stopCfgSync(); toast("Token 无效") }; return@launch
                }
                secure.setConfigToken(token)
                val filePath = "$CFG_PATH/rules.json"
                val (_, sha) = GitHubSync.downloadApi(CFG_OWNER, CFG_REPO, CFG_BRANCH, filePath, token)
                val ok = GitHubSync.upload(CFG_OWNER, CFG_REPO, CFG_BRANCH, filePath, token, ruleStore.exportSet().toJsonString(), sha)
                withContext(Dispatchers.Main) { stopCfgSync(); toast(if (ok) getString(R.string.toast_upload_ok) else "上传失败") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { stopCfgSync(); toast(getString(R.string.toast_sync_fail, e.message ?: "")) }
            }
        }
    }

    private fun onCfgUpdateToken() {
        val token = binding.etCfgToken.text.toString().trim()
        if (token.isEmpty()) return toast("请输入 Token")
        startCfgSync()
        lifecycleScope.launch(Dispatchers.IO) {
            val valid = GitHubSync.validateToken(token)
            withContext(Dispatchers.Main) {
                stopCfgSync()
                if (valid) { secure.setConfigToken(token); toast("Token 有效，已保存") } else toast("Token 无效")
            }
        }
    }

    private fun startCfgSync() {
        binding.pbCfgSync.visibility = View.VISIBLE; binding.btnCfgDownload.isEnabled = false
        binding.btnCfgUpload.isEnabled = false; binding.btnCfgUpdateToken.isEnabled = false
        binding.tvCfgStatus.text = "同步中…"
    }
    private fun stopCfgSync() {
        binding.pbCfgSync.visibility = View.GONE; binding.btnCfgDownload.isEnabled = true
        binding.btnCfgUpload.isEnabled = true; binding.btnCfgUpdateToken.isEnabled = true
        binding.tvCfgStatus.text = ""
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
