package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simely.adskip.R
import com.simely.adskip.databinding.ActivityMainBinding
import com.simely.adskip.service.KeepAliveService
import com.simely.adskip.util.AccessibilityUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 权限按钮
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 悬浮窗开关
        binding.switchCapsule.setOnCheckedChangeListener { _, checked ->
            val intent = Intent(this, KeepAliveService::class.java).apply {
                action = if (checked) KeepAliveService.ACTION_SHOW_CAPSULE
                         else KeepAliveService.ACTION_HIDE_CAPSULE
            }
            ContextCompat.startForegroundService(this, intent)
        }

        // 可折叠面板
        setupCollapsible(binding.headerPermission, binding.panelPermission, "panel_permission")
        setupCollapsible(binding.headerSettings, binding.panelSettings, "panel_settings")
    }

    override fun onResume() {
        super.onResume()
        val accOn = AccessibilityUtil.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        binding.tvStatus.text = if (accOn && overlayOn) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }

        // 悬浮窗开关同步状态
        if (accOn && overlayOn) {
            binding.switchCapsule.isEnabled = true
            // 不自动切换开关状态，由用户控制
        } else {
            binding.switchCapsule.isEnabled = false
            binding.switchCapsule.isChecked = false
        }

        if (accOn) {
            ContextCompat.startForegroundService(
                this, Intent(this, KeepAliveService::class.java)
            )
        }
    }

    private fun setupCollapsible(headerView: View, panel: View, key: String) {
        val header = headerView as android.widget.TextView
        val prefs = getSharedPreferences("main_ui", MODE_PRIVATE)
        val expanded = prefs.getBoolean(key, true) // 默认展开

        fun updateState() {
            val isExpanded = panel.visibility == View.VISIBLE
            header.text = header.text.toString().replaceFirst(if (isExpanded) "▼" else "▶", if (isExpanded) "▶" else "▼")
        }

        if (!expanded) {
            panel.visibility = View.GONE
            header.text = header.text.toString().replace("▼", "▶")
        }

        header.setOnClickListener {
            if (panel.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
                prefs.edit().putBoolean(key, false).apply()
            } else {
                panel.visibility = View.VISIBLE
                prefs.edit().putBoolean(key, true).apply()
            }
            updateState()
        }
    }
}
