package com.simely.adskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.simely.adskip.service.KeepAliveService

/**
 * 开机 / 应用更新后自启保活服务（前提是用户已开启无障碍服务）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (isAccessibilityEnabled(context)) {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return services.contains("com.simely.adskip/.service.AdSkipAccessibilityService")
    }
}
