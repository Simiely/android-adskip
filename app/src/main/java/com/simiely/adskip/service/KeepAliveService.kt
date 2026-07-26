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
import com.simely.adskip.util.Logger
import com.simely.adskip.util.SecurePrefs

/**
 * 前台保活服务：常驻通知 + 持有悬浮胶囊。
 * 配合系统省电无限制 / 自启动 / 任务栏锁定效果最佳。
 */
class KeepAliveService : Service() {

    private lateinit var floatManager: FloatWindowManager

    override fun onCreate() {
        super.onCreate()
        createChannel()

        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Logger.e("startForeground FAIL: ${e.message}", e)
        }

        try {
            floatManager = FloatWindowManager(this)
            floatManager.onVisibilityChanged = { updateNotification() }
            floatManager.onCaptureStateChanged = { updateNotification() }
            // 根据记忆状态决定是否显示悬浮窗
            if (SecurePrefs(this).isCapsuleEnabled()) {
                floatManager.showCapsule()
            }
        } catch (e: Exception) {
            Logger.e("FloatManager FAIL: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::floatManager.isInitialized) return START_STICKY
        when (intent?.action) {
            ACTION_SHOW_CAPSULE -> {
                floatManager.showCapsule()
                SecurePrefs(this).setCapsuleEnabled(true)
            }
            ACTION_HIDE_CAPSULE -> {
                floatManager.hideCapsuleAndNotify()
                SecurePrefs(this).setCapsuleEnabled(false)
            }
            ACTION_CANCEL_CAPTURE -> floatManager.cancelCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::floatManager.isInitialized) floatManager.hideCapsule()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val visible = if (::floatManager.isInitialized) floatManager.isVisible() else false
        val capturing = com.simely.adskip.AppState.isCapturing

        val title = "AdSkip 运行中"
        val text = when {
            capturing -> "捕获模式中，点悬浮球可取消"
            !visible -> "监听中（悬浮球已隐藏）"
            else -> "监听界面并自动跳过广告"
        }

        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(mainPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .apply {
                if (capturing) {
                    val cancelIntent = Intent(this@KeepAliveService, KeepAliveService::class.java).apply {
                        action = ACTION_CANCEL_CAPTURE
                    }
                    val cancelPi = PendingIntent.getService(
                        this@KeepAliveService, 2, cancelIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, "取消捕获", cancelPi)
                }
            }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "AdSkip 保活", NotificationManager.IMPORTANCE_DEFAULT
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
        const val ACTION_CANCEL_CAPTURE = "com.simely.adskip.CANCEL_CAPTURE"
    }
}
