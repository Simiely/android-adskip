package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simely.adskip.R
import com.simely.adskip.databinding.ActivityMainBinding
import com.simely.adskip.service.KeepAliveService
import com.simely.adskip.store.StatsStore
import com.simely.adskip.util.AccessibilityUtil
import com.simely.adskip.util.SecurePrefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var secure: SecurePrefs
    private lateinit var stats: StatsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secure = SecurePrefs(this)
        stats = StatsStore(this)

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.switchCapsule.setOnCheckedChangeListener { _, checked ->
            val intent = Intent(this, KeepAliveService::class.java).apply {
                action = if (checked) KeepAliveService.ACTION_SHOW_CAPSULE else KeepAliveService.ACTION_HIDE_CAPSULE
            }
            ContextCompat.startForegroundService(this, intent)
        }

        refreshStats()
        showDisabledRuleNotice()
    }

    override fun onResume() {
        super.onResume()
        val accOn = AccessibilityUtil.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        binding.tvStatus.text = if (accOn && overlayOn) getString(R.string.status_running) else getString(R.string.status_stopped)
        binding.switchCapsule.isEnabled = accOn && overlayOn
        if (!binding.switchCapsule.isEnabled) binding.switchCapsule.isChecked = false
        if (accOn) ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        refreshStats()
    }

    private fun refreshStats() {
        binding.tvTotalCount.text = "${stats.getTotalCount()}"
        binding.tvTodayCount.text = "${stats.getTodayCount()}"
        binding.tvUsageDays.text = "${stats.getUsageDays()}"
    }

    private fun showDisabledRuleNotice() {
        val rule = secure.getDisabledRule()
        if (rule.isEmpty()) return
        secure.clearDisabledRule()
        val parts = rule.split("|", limit = 2)
        AlertDialog.Builder(this)
            .setTitle("总开关已自动关闭")
            .setMessage("检测到 ${parts.getOrElse(1) { parts[0] }} 在 5 秒内触发了 3 次，已自动关闭总开关。")
            .setPositiveButton("知道了", null)
            .show()
    }
}
