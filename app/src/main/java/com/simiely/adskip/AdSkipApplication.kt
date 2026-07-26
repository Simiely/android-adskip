package com.simely.adskip

import android.app.Application

class AdSkipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // RuleStore / SecurePrefs 为懒加载单例式封装，无需在此预热。
        // 保活服务由 MainActivity 与 BootReceiver 启动。
    }
}
