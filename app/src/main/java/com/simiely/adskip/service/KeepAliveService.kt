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

/**
 * 前台保活服务：常驻通知 + 持有悬浮胶囊，降低被 HyperOS 回收的概率。
 * 配合系统白名单（省电无限制 / 自启动 / 任务栏锁定）效果最佳。
 */
class KeepAliveService : Service() {

    private lateinit var floatManager: FloatWindowManager

    override fun onCreate() {
        super.onCreate()
        floatManager = FloatWindowManager(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        floatManager.showCapsule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被杀后系统会尝试重建
        return START_STICKY
    }

    override fun onDestroy() {
        floatManager.hideCapsule()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        createChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdSkip 运行中")
            .setContentText("监听界面并自动跳过广告")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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
    }
}
