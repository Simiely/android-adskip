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

        // 设置入口
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // ── 悬浮窗开关 ──
        binding.switchCapsule.setOnCheckedChangeListener { _, checked ->
            val intent = Intent(this, KeepAliveService::class.java).apply {
                action = if (checked) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // ── 统计 ──
        try { refreshStats() } catch (_: Exception) {}

        // 防死循环提示
        try { showDisabledRuleNotice() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        val accOn = AccessibilityUtil.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        binding.tvStatus.text = if (accOn && overlayOn) getString(R.string.status_running) else getString(R.string.status_stopped)
        binding.switchCapsule.isEnabled = accOn && overlayOn
        if (!binding.switchCapsule.isEnabled) binding.switchCapsule.isChecked = false
        if (accOn) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        try { refreshStats() } catch (_: Exception) {}
    }

    // ── 统计（简单版，避免崩溃） ──
    private fun refreshStats() {
        binding.tvTotalCount.text = "${statsStore.getTotalCount()}"
        binding.tvTodayCount.text = "${statsStore.getTodayCount()}"
        binding.tvUsageDays.text = "${statsStore.getUsageDays()}"
    }

    // ── 防死循环提示 ──
    private fun showDisabledRuleNotice() {
        val rule = secure.getDisabledRule()
        if (rule.isEmpty()) return
        secure.clearDisabledRule()
        binding.switchMaster.isChecked = false
        val parts = rule.split("|")
        val pkgName = parts.getOrNull(0) ?: rule
        val btnText = parts.getOrNull(1) ?: ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("总开关已自动关闭")
            .setMessage("检测到 [$pkgName] 的 [$btnText] 在 5 秒内触发了 3 次，已自动关闭总开关。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
