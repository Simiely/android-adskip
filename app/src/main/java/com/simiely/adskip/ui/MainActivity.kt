package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.simely.adskip.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var ruleStore: RuleStore
    private lateinit var secure: SecurePrefs
    private lateinit var stats: StatsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ruleStore = RuleStore(this)
        secure = SecurePrefs(this)
        stats = StatsStore(this)

        // 权限按钮
        b.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnOverlay.setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        b.btnBattery.setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }

        // 悬浮窗开关
        b.switchCapsule.setOnCheckedChangeListener { _, checked ->
            val intent = Intent(this, KeepAliveService::class.java).apply {
                action = if (checked) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // 折叠面板
        setupCollapsible(b.headerPermission, b.panelPermission, "panel_perm")
        setupCollapsible(b.headerRules, b.panelRules, "panel_rules")

        // 总开关 + 关键词
        b.switchMaster.isChecked = secure.getMasterEnabled()
        b.switchMaster.setOnCheckedChangeListener { _, c -> secure.setMasterEnabled(c) }
        b.switchKeyword.isChecked = secure.getKeywordEnabled()
        b.switchKeyword.setOnCheckedChangeListener { _, c -> secure.setKeywordEnabled(c) }

        // 关键词
        b.btnAddKeyword.setOnClickListener {
            val kw = b.etKeyword.text.toString().trim()
            if (kw.isNotEmpty()) { ruleStore.addKeyword(kw); b.etKeyword.text.clear(); renderKeywords() }
        }

        // 统一密码门
        if (!secure.isPasswordSet()) secure.setPasswordHash(SecurePrefs.hash("12345678"))
        if (!secure.isConfigPasswordSet()) secure.setConfigPasswordHash(SecurePrefs.hash("123"))
        b.btnUnlock.setOnClickListener { handleUnlock() }

        // 规则同步
        b.btnDownload.setOnClickListener { onDownload() }
        b.btnUpload.setOnClickListener { onUpload() }

        // 配置同步
        b.btnCfgDownload.setOnClickListener { onCfgDownload() }
        b.btnCfgUpload.setOnClickListener { onCfgUpload() }
        b.btnCfgUpdateToken.setOnClickListener { onCfgUpdateToken() }

        // 分享
        b.btnShare.setOnClickListener { shareStats() }

        refreshStats()
        renderKeywords()
        renderRules()
        showDisabledRuleNotice()
    }

    override fun onResume() {
        super.onResume()
        val accOn = isAccessibilityEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        b.tvStatus.text = if (accOn && overlayOn) getString(R.string.status_running) else getString(R.string.status_stopped)
        b.switchCapsule.isEnabled = accOn && overlayOn
        if (!b.switchCapsule.isEnabled) b.switchCapsule.isChecked = false
        if (accOn) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        refreshStats()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val svc = "$packageName/com.simely.adskip.service.AdSkipAccessibilityService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(svc) == true
    }

    // ── 统计 ──
    private fun refreshStats() {
        b.tvTotalCount.text = "${stats.getTotalCount()}"
        b.tvTodayCount.text = "${stats.getTodayCount()}"
        b.tvUsageDays.text = "${stats.getUsageDays()}"
    }

    private fun shareStats() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, stats.getShareText())
        }
        startActivity(Intent.createChooser(intent, "分享统计"))
    }

    // ── 防死循环提示 ──
    private fun showDisabledRuleNotice() {
        val rule = secure.getDisabledRule()
        if (rule.isEmpty()) return
        secure.clearDisabledRule()
        b.switchMaster.isChecked = false
        AlertDialog.Builder(this)
            .setTitle("总开关已自动关闭")
            .setMessage("检测到 [$rule] 在 5 秒内触发了 3 次，已自动关闭总开关。")
            .setPositiveButton("知道了", null).show()
    }

    // ── 可折叠面板 ──
    private fun setupCollapsible(header: View, panel: View, key: String) {
        val visible = secure.prefs().getBoolean(key, true)
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        (header as? TextView)?.text = (header as TextView).text.toString().let { if (visible) it.replace("▶", "▼") else it.replace("▼", "▶") }
        header.setOnClickListener {
            val v = panel.visibility != View.VISIBLE
            panel.visibility = if (v) View.VISIBLE else View.GONE
            secure.prefs().edit().putBoolean(key, v).apply()
            (header as TextView).text = (header as TextView).text.toString().let { if (v) it.replace("▶", "▼") else it.replace("▼", "▶") }
            if (v && key == "panel_rules") { renderKeywords(); renderRules() }
        }
    }

    // ── 密码门 ──
    private fun handleUnlock() {
        val pw = b.etPassword.text.toString()
        if (SecurePrefs.hash(pw) == secure.getConfigPasswordHash()) {
            revealCfgPanel()
            toast("配置同步已解锁")
        } else if (SecurePrefs.hash(pw) == secure.getPasswordHash()) {
            revealSyncPanel()
            toast("规则同步已解锁")
        } else toast("密码错误")
    }

    private fun revealSyncPanel() {
        b.syncPanel.visibility = View.VISIBLE
        b.cfgPanel.visibility = View.GONE
        b.etRepoOwner.setText(secure.getRepoOwner())
        b.etRepoName.setText(secure.getRepoName())
        b.etRepoBranch.setText(secure.getRepoBranch())
        b.etRepoPath.setText(secure.getRepoPath())
        b.etToken.setText(secure.getToken())
    }

    private fun revealCfgPanel() {
        b.cfgPanel.visibility = View.VISIBLE
        b.syncPanel.visibility = View.GONE
        b.etCfgToken.setText(secure.getConfigToken())
    }

    // ── 规则同步 ──
    private fun onDownload() { syncOperation(true) }
    private fun onUpload() { syncOperation(false) }

    private fun syncOperation(download: Boolean) {
        val owner = b.etRepoOwner.text.toString().trim()
        val repo = b.etRepoName.text.toString().trim()
        val branch = b.etRepoBranch.text.toString().trim().ifEmpty { "main" }
        val path = b.etRepoPath.text.toString().trim().ifEmpty { "rules.json" }
        val token = b.etToken.text.toString().trim()

        secure.setRepoOwner(owner); secure.setRepoName(repo); secure.setRepoBranch(branch); secure.setRepoPath(path)
        if (token.isNotEmpty()) secure.setToken(token)

        if (owner.isEmpty() || repo.isEmpty()) { toast("请填写仓库信息"); return }

        b.pbSync.visibility = View.VISIBLE
        b.tvSyncStatus.text = ""

        lifecycleScope.launch {
            try {
                val sync = GitHubSync(owner, repo, branch, token.ifEmpty { null })
                if (download) {
                    val json = sync.downloadRaw(owner, repo, branch, path)
                    val rsParse = RuleSet.parse(json)
                    if (rsParse != null) { ruleStore.mergeRemote(rsParse); renderKeywords(); renderRules() }
                    b.tvSyncStatus.text = "下载完成"
                } else {
                    val rs = RuleSet(ruleStore.getKeywords(), ruleStore.getRules())
                    sync.upload(owner, repo, branch, path, token, rs.toJsonString(), null)
                    b.tvSyncStatus.text = "上传完成"
                }
            } catch (e: Exception) {
                b.tvSyncStatus.text = "失败: ${e.message}"
            }
            b.pbSync.visibility = View.GONE
        }
    }

    // ── 配置同步 ──
    private fun onCfgDownload() { cfgSync(true) }
    private fun onCfgUpload() { cfgSync(false) }

    private fun cfgSync(download: Boolean) {
        val token = b.etCfgToken.text.toString().trim()
        if (token.isNotEmpty()) secure.setConfigToken(token)

        b.pbCfgSync.visibility = View.VISIBLE
        b.tvCfgStatus.text = ""

        lifecycleScope.launch {
            try {
                val sync = GitHubSync("Simiely", "android-adskip", "main", token.ifEmpty { null })
                if (download) {
                    val json = sync.downloadRaw("Simiely", "android-adskip", "main", "configs/rules.json")
                    val rsParse = RuleSet.parse(json)
                    if (rsParse != null) { ruleStore.mergeRemote(rsParse); renderKeywords(); renderRules() }
                    b.tvCfgStatus.text = "配置下载完成"
                } else {
                    val rs = RuleSet(ruleStore.getKeywords(), ruleStore.getRules())
                    sync.upload("Simiely", "android-adskip", "main", "configs/rules.json", token, rs.toJsonString(), null)
                    b.tvCfgStatus.text = "配置上传完成"
                }
            } catch (e: Exception) {
                b.tvCfgStatus.text = "失败: ${e.message}"
            }
            b.pbCfgSync.visibility = View.GONE
        }
    }

    private fun onCfgUpdateToken() {
        secure.setConfigToken(b.etCfgToken.text.toString().trim())
        toast("Token 已更新")
    }

    // ── 关键词/规则列表 ──
    private fun renderKeywords() {
        b.listKeywords.removeAllViews()
        for (kw in ruleStore.getKeywords()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = kw; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = "删除"; textSize = 11f; setOnClickListener { ruleStore.removeKeyword(kw); renderKeywords() }
            })
            b.listKeywords.addView(row)
        }
    }

    private fun renderRules() {
        b.listRules.removeAllViews()
        val rules = ruleStore.getRules()
        b.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        for (r in rules) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "${r.pkg} / ${r.text ?: "—"}"; textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = "×"; textSize = 12f; setOnClickListener { ruleStore.removeRule(r.fingerprint()); renderRules() }
            })
            b.listRules.addView(row)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
