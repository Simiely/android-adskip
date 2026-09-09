package com.simely.adskip

import android.app.Application
import android.content.pm.ApplicationInfo
import com.simely.adskip.util.Logger

class AdSkipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Logger.isDebug = debuggable
        // 保活服务由 MainActivity 与 BootReceiver 按需启动。
        // RuleStore / SecurePrefs 为按需初始化，无需在此预热。
    }
}
