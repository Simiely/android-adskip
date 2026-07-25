package com.simely.adskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        binding.btnBattery.setOnClickListener {
            // 应用管理页：在此设置省电无限制 / 自启动 / 任务栏锁定
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        if (accOn) {
            // 无障碍已开 → 拉起保活服务（带动悬浮胶囊）
            ContextCompat.startForegroundService(
                this, Intent(this, KeepAliveService::class.java)
            )
        }
    }

}
