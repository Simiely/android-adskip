package com.simely.adskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simely.adskip.service.KeepAliveService
import com.simely.adskip.util.AccessibilityUtil

/**
 * 开机 / 应用更新后自启保活服务（前提是用户已开启无障碍服务）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (AccessibilityUtil.isEnabled(context)) {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }
    }
}
