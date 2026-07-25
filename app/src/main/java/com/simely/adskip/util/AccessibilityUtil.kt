package com.simely.adskip.util

import android.content.Context
import android.provider.Settings

/**
 * 无障碍服务检测工具，避免 MainActivity / BootReceiver 中重复代码。
 */
object AccessibilityUtil {

    /**
     * 检测 AdSkip 无障碍服务是否已在系统设置中开启。
     * 注意：系统存储的是完整类名，不能用 manifest 短格式。
     */
    fun isEnabled(context: Context): Boolean {
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return services.contains(SERVICE_FULL_NAME)
    }

    private const val SERVICE_FULL_NAME =
        "com.simiely.adskip/com.simiely.adskip.service.AdSkipAccessibilityService"
}
