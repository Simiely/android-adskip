package com.simely.adskip

import android.app.Application

class AdSkipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 保活服务由 MainActivity 与 BootReceiver 按需启动。
        // RuleStore / SecurePrefs 为按需初始化，无需在此预热。
    }
}
