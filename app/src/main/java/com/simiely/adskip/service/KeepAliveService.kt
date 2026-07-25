package com.simely.adskip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.simely.adskip.R
import com.simely.adskip.float.FloatWindowManager
import com.simely.adskip.ui.MainActivity
import com.simely.adskip.util.logi

/**
 * 前台保活服务：常驻通知 + 持有悬浮胶囊，降低被 HyperOS 回收的概率。
 * - 通知栏点击「显示悬浮窗」→ 显示胶囊
 * - 长按胶囊 → 隐藏胶囊
 * - 配合系统白名单（省电无限制 / 自启动 / 任务栏锁定）效果最佳。
 */
class KeepAliveService : Service() {

    private lateinit var floatManager: FloatWindowManager

    override fun onCreate() {
        super.onCreate()
        logi { "KeepAliveService starting" }
        floatManager = FloatWindowManager(this)
        floatManager.onVisibilityChanged = { visible ->
            updateNotification(visible)
        }
        startForegroundInternal()
        floatManager.showCapsule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CAPSULE -> floatManager.showCapsule()
            ACTION_HIDE_CAPSULE -> floatManager.hideCapsuleAndNotify()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        logi { "KeepAliveService stopping" }
        floatManager.hideCapsule()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundInternal() {
        val notif = buildNotification(isCapsuleVisible = floatManager.isVisible())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 胶囊显隐变化时更新通知，让用户看到当前状态 */
    private fun updateNotification(visible: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(visible))
    }

    private fun buildNotification(isCapsuleVisible: Boolean): Notification {
        createChannel()
        val title = "AdSkip 运行中"
        val text = if (isCapsuleVisible) "悬浮窗已显示，长按可隐藏" else "悬浮窗已隐藏，点击下方按钮显示"

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val showIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_SHOW_CAPSULE
        }
        val showPi = PendingIntent.getService(
            this, 1, showIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(mainPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .apply {
                if (!isCapsuleVisible) {
                    addAction(0, "显示悬浮窗", showPi)
                }
            }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "AdSkip 保活", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持跳过广告服务后台运行"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "adskip_keepalive"
        const val ACTION_SHOW_CAPSULE = "com.simely.adskip.SHOW_CAPSULE"
        const val ACTION_HIDE_CAPSULE = "com.simely.adskip.HIDE_CAPSULE"
    }
}
