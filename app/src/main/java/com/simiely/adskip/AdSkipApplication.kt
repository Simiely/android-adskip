package com.simely.adskip

import android.app.Application
import com.simely.adskip.util.Logger

class AdSkipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
