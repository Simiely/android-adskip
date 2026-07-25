package com.simely.adskip.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.simely.adskip.service.AdSkipAccessibilityService

/**
 * 无障碍服务检测工具。
 * 使用 AccessibilityManager.getEnabledAccessibilityServiceList() 而非 Settings.Secure，
 * 因为后者在 MIUI/HyperOS 上不可靠。
 */
object AccessibilityUtil {

    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val targetName = AdSkipAccessibilityService::class.java.name
        return list.any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
                    info.resolveInfo.serviceInfo.name == targetName
        }
    }
}
