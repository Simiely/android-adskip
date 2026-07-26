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
    private var initDone = false
    private var showAllLogs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ruleStore = RuleStore(this)
        secure = SecurePrefs(this)
        stats = StatsStore(this)

        b.btnAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnOverlay.setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        b.btnBattery.setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }

        b.switchCapsule.isChecked = getSharedPreferences("adskip_prefs", MODE_PRIVATE).getBoolean("capsule", true)
        b.switchCapsule.setOnCheckedChangeListener { _, c ->
            getSharedPreferences("adskip_prefs", MODE_PRIVATE).edit().putBoolean("capsule", c).apply()
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java).apply {
                action = if (c) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            })
        }

        setupCollapsible(b.headerLogs, b.listLogs, b.arrowLogs, "logs_open")
        setupCollapsible(b.headerFilter, b.panelFilter, b.arrowFilter, "filter_open")
        setupCollapsible(b.headerKeywords, b.panelKeywords, b.arrowKw, "kw_open")
        setupCollapsible(b.headerCapture, b.panelCapture, b.arrowCap, "cap_open")
        setupCollapsible(b.headerPermission, b.panelPermission, b.arrowPerm, "perm_open")
        setupCollapsible(b.headerHidden, b.panelHidden, b.arrowHid, "hid_open")

        b.switchMaster.setOnCheckedChangeListener { _, c -> if (initDone) secure.setMasterEnabled(c) }
        b.switchMaster.isChecked = secure.getMasterEnabled()

        b.switchKeyword.setOnCheckedChangeListener { _, c -> if (initDone) secure.setKeywordEnabled(c) }
        b.switchKeyword.isChecked = secure.getKeywordEnabled()

        b.btnAddKeyword.setOnClickListener {
            val kw = b.etKeyword.text.toString().trim()
            if (kw.isNotEmpty()) { ruleStore.addKeyword(kw); b.etKeyword.text.clear(); renderKeywords() }
        }

        setupFilterMode()
        b.btnAddFilter.setOnClickListener { showAppPicker() }
        b.btnClearRules.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空所有规则").setMessage("确定删除所有捕获规则？")
                .setPositiveButton("清空") { _, _ -> ruleStore.clear(); renderRuleGroups() }
                .setNegativeButton("取消", null).show()
        }

        if (!secure.isPasswordSet()) secure.setPasswordHash(SecurePrefs.hash("12345678"))
        if (!secure.isConfigPasswordSet()) secure.setConfigPasswordHash(SecurePrefs.hash("123"))

        // 如果之前解锁过，直接显示面板，不要求重复输入密码
        val unlockedSync = secure.prefs().getBoolean("unlocked_sync", false)
        val unlockedCfg = secure.prefs().getBoolean("unlocked_cfg", false)
        if (unlockedSync) {
            b.etPassword.visibility = View.GONE; b.btnUnlock.visibility = View.GONE
            b.syncPanel.visibility = View.VISIBLE
            b.etRepoOwner.setText(secure.getRepoOwner()); b.etRepoName.setText(secure.getRepoName())
            b.etRepoBranch.setText(secure.getRepoBranch()); b.etRepoPath.setText(secure.getRepoPath())
            b.etToken.setText(secure.getToken())
        }
        if (unlockedCfg) {
            b.etPassword.visibility = View.GONE; b.btnUnlock.visibility = View.GONE
            b.cfgPanel.visibility = View.VISIBLE
            b.etCfgToken.setText(secure.getConfigToken())
        }
        b.btnUnlock.setOnClickListener { handleUnlock() }
        b.btnDownload.setOnClickListener { syncOp(true) }
        b.btnUpload.setOnClickListener { syncOp(false) }
        b.btnCfgDownload.setOnClickListener { cfgSync(true) }
        b.btnCfgUpload.setOnClickListener { cfgSync(false) }
        b.btnCfgUpdateToken.setOnClickListener { onCfgUpdateToken() }

        b.btnShare.setOnClickListener { shareStats() }
        b.btnClearStats.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空统计").setMessage("确定清空点击次数、运行天数和最近点击记录吗？")
                .setPositiveButton("清空") { _, _ -> stats.clear(); refreshStats(); renderLogs(); toast("已清空") }
                .setNegativeButton("取消", null).show()
        }
        b.btnRestart.setOnClickListener {
            stopService(Intent(this, KeepAliveService::class.java))
            if (isAccessibilityEnabled()) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
            refreshStats(); toast("服务已重启")
        }
        b.btnExit.setOnClickListener { stopService(Intent(this, KeepAliveService::class.java)); finishAffinity() }

        refreshStats(); renderFilterList(); renderKeywords(); renderRuleGroups(); renderLogs(); showDisabledRuleNotice()
        initDone = true
    }

    override fun onResume() {
        super.onResume()
        val accOn = isAccessibilityEnabled(); val overlayOn = Settings.canDrawOverlays(this)
        b.vStatusIcon.setBackgroundResource(if (accOn && overlayOn) R.drawable.bg_status_on else R.drawable.bg_status_off)
        b.tvStatusTitle.text = if (accOn && overlayOn) "服务运行中" else "服务未启动"
        b.tvStatusSub.text = if (accOn && overlayOn) "无障碍服务已开启" else "请开启权限"
        b.switchCapsule.isEnabled = accOn && overlayOn
        b.switchCapsule.isChecked = getSharedPreferences("adskip_prefs", MODE_PRIVATE).getBoolean("capsule", true)
        if (!b.switchCapsule.isEnabled) b.switchCapsule.isChecked = false
        b.switchKeyword.isChecked = secure.getKeywordEnabled()
        b.switchMaster.isChecked = secure.getMasterEnabled()
        refreshStats(); renderKeywords(); renderRuleGroups(); renderLogs()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val svc = "$packageName/com.simely.adskip.service.AdSkipAccessibilityService"
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(svc) == true
    }

    private fun setupFilterMode() {
        b.switchFilter.isChecked = secure.isFilterEnabled()
        b.switchFilter.setOnCheckedChangeListener { _, c -> secure.setFilterEnabled(c) }
        updateModeUI(secure.getFilterMode())
        b.btnModeBlack.setOnClickListener { secure.setFilterMode(true); updateModeUI(true) }
        b.btnModeWhite.setOnClickListener { secure.setFilterMode(false); updateModeUI(false) }
    }
    private fun updateModeUI(bl: Boolean) {
        if (bl) {
            b.btnModeBlack.setBackgroundResource(R.drawable.bg_chip_selected); b.btnModeBlack.setTextColor(getColorCompat(R.color.text_primary))
            b.btnModeWhite.setBackgroundResource(R.drawable.bg_chip_normal); b.btnModeWhite.setTextColor(getColorCompat(R.color.text_secondary))
            b.tvModeHint.text = "黑名单：对名单外的所有应用生效"
        } else {
            b.btnModeWhite.setBackgroundResource(R.drawable.bg_chip_selected); b.btnModeWhite.setTextColor(getColorCompat(R.color.text_primary))
            b.btnModeBlack.setBackgroundResource(R.drawable.bg_chip_normal); b.btnModeBlack.setTextColor(getColorCompat(R.color.text_secondary))
            b.tvModeHint.text = "白名单：只对名单内的应用生效"
        }
    }
    private fun getColorCompat(id: Int) = resources.getColor(id, null)

    private fun showAppPicker() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) null
            else Triple(pkg, ri.loadLabel(pm).toString(), pkg in secure.getFilterList())
        }.sortedBy { it.second.lowercase() }.distinctBy { it.first }
        val names = apps.map { "${it.second} (${it.first})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].third }
        AlertDialog.Builder(this)
            .setTitle("选择应用 (${apps.size})")
            .setMultiChoiceItems(names, checked) { _, i, c ->
                if (c) secure.addFilterPkg(apps[i].first) else secure.removeFilterPkg(apps[i].first)
            }
            .setPositiveButton("确定") { _, _ -> renderFilterList() }
            .setNegativeButton("取消", null).show()
    }

    private fun renderKeywords() {
        b.listKeywords.removeAllViews()
        val colors = intArrayOf(0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        ruleStore.getKeywords().forEachIndexed { i, kw ->
            val chip = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                background = bgChip(colors[i % colors.size])
            }
            chip.addView(android.widget.TextView(this).apply { text = kw; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()) })
            chip.addView(android.widget.TextView(this).apply {
                text = " x"; textSize = 13f; setTextColor(0x88FFFFFF.toInt()); setPadding(6.dp, 0, 0, 0)
                setOnClickListener { ruleStore.removeKeyword(kw); renderKeywords() }
            })
            (chip.layoutParams as? android.widget.LinearLayout.LayoutParams)?.setMargins(0, 0, 4.dp, 4.dp)
            b.listKeywords.addView(chip)
        }
    }

    private fun renderFilterList() {
        b.listFilters.removeAllViews()
        val pm = packageManager
        val tp = getColorCompat(R.color.text_primary); val ts = getColorCompat(R.color.text_secondary)
        val colors = intArrayOf(0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        secure.getFilterList().forEachIndexed { i, pkg ->
            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 6.dp, 0, 6.dp) }
            row.addView(android.widget.TextView(this).apply {
                text = pkg.take(1).uppercase(); textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
                background = bgChip(colors[i % colors.size]); layoutParams = android.widget.LinearLayout.LayoutParams(32.dp, 32.dp)
            })
            row.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL; layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp }
                addView(android.widget.TextView(this@MainActivity).apply { text = appName; textSize = 14f; setTextColor(tp) })
                addView(android.widget.TextView(this@MainActivity).apply { text = pkg; textSize = 11f; setTextColor(ts) })
            })
            row.addView(android.widget.TextView(this).apply {
                text = "移除"; textSize = 12f; setTextColor(ts); setPadding(12.dp, 0, 0, 0)
                setOnClickListener { secure.removeFilterPkg(pkg); renderFilterList() }
            })
            b.listFilters.addView(row)
        }
    }

    private fun renderRuleGroups() {
        b.ruleGroups.removeAllViews()
        val rules = ruleStore.getRules()
        if (rules.isEmpty()) return
        val groups = rules.groupBy { it.pkg }
        val colors = intArrayOf(0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt())
        var ci = 0
        for ((pkg, pkgRules) in groups) {
            b.ruleGroups.addView(makeGroup(pkg, pkgRules, colors[ci % colors.size]))
            ci++
        }
    }

    private fun makeGroup(pkg: String, rules: List<com.simely.adskip.model.Rule>, color: Int): View {
        val tp = getColorCompat(R.color.text_primary); val ts = getColorCompat(R.color.text_secondary)
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, 4.dp, 0, 4.dp) }
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(4.dp, 6.dp, 4.dp, 6.dp)
        }
        header.addView(android.widget.TextView(this).apply {
            text = pkg.take(1).uppercase(); textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
            background = bgChip(color); layoutParams = android.widget.LinearLayout.LayoutParams(24.dp, 24.dp)
        })
        header.addView(android.widget.TextView(this).apply {
            text = pkg; textSize = 12f; setTextColor(tp)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp }
        })
        header.addView(android.widget.TextView(this).apply {
            text = "${rules.size} 条"; textSize = 10f; setTextColor(ts)
        })
        val panel = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; visibility = View.GONE }
        rules.forEach { r -> panel.addView(makeRuleRow(r)) }
        container.addView(header); container.addView(panel)
        header.setOnClickListener { panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE }
        return container
    }

    private fun makeRuleRow(rule: com.simely.adskip.model.Rule): View {
        val tp = getColorCompat(R.color.text_primary); val ts = getColorCompat(R.color.text_secondary)
        val tt = getColorCompat(R.color.text_tertiary)
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
        }
        val infoCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        }
        // 主文字（text 优先，其次 contentDescription）
        val mainText = rule.text ?: rule.contentDescription ?: rule.viewId ?: "(空)"
        infoCol.addView(android.widget.TextView(this).apply { text = mainText; textSize = 14f; setTextColor(tp) })
        // 次要信息行
        val details = buildString {
            if (!rule.viewId.isNullOrEmpty()) append("ID: ${rule.viewId}")
            if (!rule.contentDescription.isNullOrEmpty() && rule.contentDescription != rule.text) {
                if (isNotEmpty()) append("\n"); append("描述: ${rule.contentDescription}")
            }
            if (!rule.className.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n"); append("类: ${rule.className}")
            }
            if (!rule.activity.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n"); append("Activity: ${rule.activity}")
            }
        }
        if (details.isNotEmpty()) {
            infoCol.addView(android.widget.TextView(this).apply {
                text = details; textSize = 10f; setTextColor(tt); maxLines = 3
            })
        }
        row.addView(infoCol)
        row.addView(android.widget.TextView(this).apply {
            text = "编辑"; textSize = 13f; setTextColor(0xFF007AFF.toInt()); setPadding(12.dp, 8.dp, 16.dp, 8.dp)
            setOnClickListener {
                val container = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                }
                fun addField(label: String, value: String?) {
                    container.addView(android.widget.TextView(this@MainActivity).apply {
                        text = label; textSize = 11f; setTextColor(ts); setPadding(0, 8.dp, 0, 2.dp)
                    })
                    val et = android.widget.EditText(this@MainActivity).apply { setText(value ?: ""); textSize = 14f; setTextColor(tp) }
                    container.addView(et)
                }
                addField("文字 (text)", rule.text)
                addField("控件ID (viewId)", rule.viewId)
                addField("内容描述 (contentDesc)", rule.contentDescription)
                addField("类名 (className)", rule.className)
                addField("Activity", rule.activity)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("编辑规则 — ${rule.pkg}")
                    .setView(container)
                    .setPositiveButton("保存") { _, _ ->
                        // 保存：更新全部可编辑字段
                        val ets = (0 until container.childCount / 2).mapNotNull { i ->
                            (container.getChildAt(i * 2 + 1) as? android.widget.EditText)?.text?.toString()?.trim()
                        }
                        val updated = rule.copy(
                            text = ets.getOrNull(0)?.takeIf { it.isNotEmpty() },
                            viewId = ets.getOrNull(1)?.takeIf { it.isNotEmpty() },
                            contentDescription = ets.getOrNull(2)?.takeIf { it.isNotEmpty() },
                            className = ets.getOrNull(3)?.takeIf { it.isNotEmpty() },
                            activity = ets.getOrNull(4)?.takeIf { it.isNotEmpty() },
                            name = ets.getOrNull(0)?.takeIf { it.isNotEmpty() }
                        )
                        if (updated.fingerprint() != rule.fingerprint()) {
                            ruleStore.removeRule(rule.fingerprint())
                            ruleStore.addRule(updated)
                        }
                        renderRuleGroups()
                    }
                    .setNegativeButton("取消", null).show()
            }
        })
        row.addView(android.widget.TextView(this).apply {
            text = " 删除 "; textSize = 12f; setTextColor(0xFFFF3B30.toInt()); setPadding(12.dp, 8.dp, 8.dp, 8.dp)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除规则")
                    .setMessage("确定删除「${rule.text ?: rule.viewId ?: "-"}」吗？")
                    .setPositiveButton("删除") { _, _ -> ruleStore.removeRule(rule.fingerprint()); renderRuleGroups() }
                    .setNegativeButton("取消", null).show()
            }
        })
        return row
    }

    private fun refreshStats() {
        b.tvTotalCount.text = fmt(stats.getTotalCount())
        b.tvTodayCount.text = "${stats.getTodayCount()}"
        b.tvUsageDays.text = "${stats.getUsageDays()}"
    }
    private fun fmt(n: Long): String = if (n >= 1000) "${n/1000}.${(n%1000)/100}k" else "$n"
    private fun getYesterdayCount(): Int {
        val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return stats.getDailyCount(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(c.time))
    }
    private fun shareStats() { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, stats.getShareText()) }, "分享统计")) }
    private fun showDisabledRuleNotice() {
        val r = secure.getDisabledRule(); if (r.isEmpty()) return
        b.switchMaster.isChecked = false
        AlertDialog.Builder(this).setTitle("总开关已自动关闭").setMessage("检测到 [$r] 5 秒内触发 3 次，已关闭。").setPositiveButton("知道了") { _, _ -> secure.clearDisabledRule() }.show()
    }

    private fun setupCollapsible(header: View, panel: View, arrow: android.widget.TextView, key: String) {
        val v = secure.prefs().getBoolean(key, panel.visibility == View.VISIBLE)
        panel.visibility = if (v) View.VISIBLE else View.GONE; arrow.text = if (v) "-" else "+"
        header.setOnClickListener {
            val nv = panel.visibility != View.VISIBLE; panel.visibility = if (nv) View.VISIBLE else View.GONE
            arrow.text = if (nv) "-" else "+"; secure.prefs().edit().putBoolean(key, nv).apply()
            if (nv) when(key) { "kw_open"->renderKeywords(); "cap_open"->renderRuleGroups(); "logs_open"->renderLogs(); "filter_open"->renderFilterList() }
        }
    }

    private fun handleUnlock() {
        val pw = b.etPassword.text.toString()
        if (SecurePrefs.hash(pw) == secure.getConfigPasswordHash()) {
            b.cfgPanel.visibility=View.VISIBLE; b.syncPanel.visibility=View.GONE
            b.etCfgToken.setText(secure.getConfigToken())
            b.etPassword.visibility=View.GONE; b.btnUnlock.visibility=View.GONE
            secure.prefs().edit().putBoolean("unlocked_cfg", true).apply()
            toast("配置同步已解锁")
        } else if (SecurePrefs.hash(pw) == secure.getPasswordHash()) {
            b.syncPanel.visibility=View.VISIBLE; b.cfgPanel.visibility=View.GONE
            b.etRepoOwner.setText(secure.getRepoOwner()); b.etRepoName.setText(secure.getRepoName())
            b.etRepoBranch.setText(secure.getRepoBranch()); b.etRepoPath.setText(secure.getRepoPath())
            b.etToken.setText(secure.getToken())
            b.etPassword.visibility=View.GONE; b.btnUnlock.visibility=View.GONE
            secure.prefs().edit().putBoolean("unlocked_sync", true).apply()
            toast("规则同步已解锁")
        } else toast("密码错误")
    }

    private fun syncOp(download: Boolean) {
        val o=b.etRepoOwner.text.toString().trim(); val r=b.etRepoName.text.toString().trim(); val br=b.etRepoBranch.text.toString().trim().ifEmpty{"main"}; val p=b.etRepoPath.text.toString().trim().ifEmpty{"rules.json"}; val t=b.etToken.text.toString().trim()
        secure.setRepoOwner(o); secure.setRepoName(r); secure.setRepoBranch(br); secure.setRepoPath(p); if(t.isNotEmpty())secure.setToken(t)
        if(o.isEmpty()||r.isEmpty()){toast("请填写仓库信息");return}
        b.pbSync.visibility=View.VISIBLE; b.tvSyncStatus.text=""
        lifecycleScope.launch(Dispatchers.IO) { try{
            if(download){
                // 有 token 用 API，无 token 用 raw URL
                val (json, _) = if(t.isNotEmpty())
                    GitHubSync.downloadApi(o,r,br,p,t)
                else
                    GitHubSync.downloadRaw(o,r,br,p) to null
                if(json.isNotEmpty()){
                    val rs=RuleSet.parse(json)
                    if(rs!=null){ ruleStore.mergeRemote(rs); withContext(Dispatchers.Main){ renderRuleGroups() } }
                }
                withContext(Dispatchers.Main){ b.tvSyncStatus.text="下载完成" }
            }else{
                val rs=RuleSet(ruleStore.getKeywords(),ruleStore.getRules())
                // 上传前先获取当前 sha（更新文件需要 sha）
                val sha = if(t.isNotEmpty())
                    GitHubSync.downloadApi(o,r,br,p,t).second
                else null
                GitHubSync.upload(o,r,br,p,t,rs.toJsonString(),sha)
                withContext(Dispatchers.Main){ b.tvSyncStatus.text="上传完成" }
            }
        }catch(e:Exception){ withContext(Dispatchers.Main){ b.tvSyncStatus.text="失败:${e.message}" } }
            withContext(Dispatchers.Main){ b.pbSync.visibility=View.GONE } } }
    private fun cfgSync(download: Boolean) {
        val t=b.etCfgToken.text.toString().trim(); if(t.isNotEmpty())secure.setConfigToken(t)
        b.pbCfgSync.visibility=View.VISIBLE; b.tvCfgStatus.text=""
        lifecycleScope.launch(Dispatchers.IO) { try{
            if(download){
                val j=GitHubSync.downloadRaw("Simiely","android-adskip","main","configs/rules.json"); val rs=RuleSet.parse(j)
                if(rs!=null){ ruleStore.mergeRemote(rs); withContext(Dispatchers.Main){ renderRuleGroups() } }
                withContext(Dispatchers.Main){ b.tvCfgStatus.text="配置下载完成" }
            }else{
                val rs=RuleSet(ruleStore.getKeywords(),ruleStore.getRules())
                val sha = if(t.isNotEmpty()) GitHubSync.downloadApi("Simiely","android-adskip","main","configs/rules.json",t).second else null
                GitHubSync.upload("Simiely","android-adskip","main","configs/rules.json",t,rs.toJsonString(),sha)
                withContext(Dispatchers.Main){ b.tvCfgStatus.text="配置上传完成" }
            }
        }catch(e:Exception){ withContext(Dispatchers.Main){ b.tvCfgStatus.text="失败:${e.message}" } }
            withContext(Dispatchers.Main){ b.pbCfgSync.visibility=View.GONE } } }
    private fun onCfgUpdateToken(){secure.setConfigToken(b.etCfgToken.text.toString().trim());toast("Token 已更新")}

    private fun renderLogs() {
        b.listLogs.removeAllViews()
        val logs = stats.getRecentLogs(if (showAllLogs) 100 else 10)
        val tp = getColorCompat(R.color.text_primary); val ts = getColorCompat(R.color.text_secondary)
        if (logs.isEmpty()) { b.listLogs.addView(android.widget.TextView(this).apply{text="暂无记录";textSize=12f;setTextColor(ts);setPadding(12.dp,8.dp,12.dp,8.dp)}); return }
        val colors = intArrayOf(0xFF007AFF.toInt(),0xFFFF3B30.toInt(),0xFFFF9500.toInt(),0xFF34C759.toInt(),0xFFAF52DE.toInt())
        var ci = 0
        for (log in logs) {
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(12.dp, 6.dp, 12.dp, 6.dp) }
            row.addView(android.widget.TextView(this).apply { text=log.app.take(1).uppercase();textSize=11f;setTextColor(0xFFFFFFFF.toInt());gravity=android.view.Gravity.CENTER;background=bgChip(colors[ci%colors.size]);layoutParams=android.widget.LinearLayout.LayoutParams(28.dp,28.dp) })
            row.addView(android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.VERTICAL; layoutParams=android.widget.LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=10.dp}
                addView(android.widget.TextView(context).apply{text=log.app;textSize=12f;setTextColor(tp)})
                addView(android.widget.TextView(context).apply{text="点击: ${log.text.ifEmpty{"-"}}  ${log.formattedTime()}";textSize=10f;setTextColor(ts)})
            })
            // 添加按钮（已添加时点击可撤销）
            val textKey = log.text.ifEmpty { log.app }
            val exists = ruleStore.getRules().any { it.pkg == log.app && it.text == textKey }
            row.addView(android.widget.TextView(this).apply {
                text = if (exists) "已添加" else "添加"; textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(10.dp, 4.dp, 10.dp, 4.dp)
                if (exists) {
                    // 已添加状态：蓝色可点击，点击即撤销
                    setTextColor(0xFF007AFF.toInt()); background = bgChip(0x18007AFF.toInt()); isEnabled = true
                    setOnClickListener {
                        val r = ruleStore.getRules().find { it.pkg == log.app && it.text == textKey }
                        if (r != null) { ruleStore.removeRule(r.fingerprint()); renderLogs(); renderRuleGroups() }
                    }
                } else {
                    setTextColor(0xFF000000.toInt()); background = bgChip(0xFFFF9292.toInt()); isEnabled = true
                    setOnClickListener {
                        ruleStore.addRule(com.simely.adskip.model.Rule(
                            text = textKey, pkg = log.app, action = "click",
                            viewId = log.viewId.ifEmpty { null }, activity = null, name = textKey
                        ))
                        // 自动加入过滤名单
                        if (secure.isFilterEnabled() && log.app !in secure.getFilterList()) secure.addFilterPkg(log.app)
                        renderLogs(); renderRuleGroups()
                    }
                }
            })
            // 屏蔽按钮（优先级最高）
            val blocked = ruleStore.isBlocked(log.app, log.text.ifEmpty { null }, log.viewId.ifEmpty { null })
            row.addView(android.widget.TextView(this).apply {
                text = if (blocked) "已屏蔽" else "屏蔽"; textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(8.dp, 4.dp, 8.dp, 4.dp)
                if (blocked) {
                    setTextColor(0x448E8E93.toInt()); background = bgChip(0x18FFFFFF.toInt()); isEnabled = false
                } else {
                    setTextColor(0xFFFF4444.toInt()); isEnabled = true
                    setOnClickListener {
                        ruleStore.addBlocked(log.app, log.text.ifEmpty { log.app }, log.viewId)
                        renderLogs()
                    }
                }
            })
            b.listLogs.addView(row); ci++
        }
        val btnRow = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(12.dp, 4.dp, 12.dp, 8.dp) }
        if (stats.getRecentLogs(11).size > 10) btnRow.addView(android.widget.TextView(this).apply {
            text = if(showAllLogs)"收起" else "显示更多"; textSize = 11f; setTextColor(ts); setPadding(0, 4.dp, 12.dp, 4.dp)
            setOnClickListener { showAllLogs = !showAllLogs; renderLogs() }
        })
        btnRow.addView(android.widget.TextView(this).apply {
            text = "清空记录"; textSize = 11f; setTextColor(0xFFFF3B30.toInt()); setPadding(8.dp, 4.dp, 0, 4.dp)
            setOnClickListener { stats.clearLogs(); renderLogs() }
        })
        b.listLogs.addView(btnRow)
    }

    private fun bgChip(color: Int): android.graphics.drawable.GradientDrawable { val d=android.graphics.drawable.GradientDrawable();d.setColor(color);d.cornerRadius=6.dp.toFloat();return d }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
