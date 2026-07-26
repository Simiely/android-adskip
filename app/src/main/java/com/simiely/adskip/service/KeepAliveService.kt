package com.simely.adskip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.simely.adskip.R
import com.simely.adskip.float.FloatWindowManager
import com.simely.adskip.ui.MainActivity

/**
<<<<<<< HEAD
 * 前台保活服务：常驻通知 + 持有悬浮胶囊。
 * - 通知栏按钮：显示/隐藏悬浮窗、取消捕获
 * - 长按胶囊 → 隐藏
 * - 点击胶囊 → 进入捕获模式（通知栏出现「取消捕获」）
=======
 * 前台保活服务：常驻通知 + 持有悬浮胶囊，降低被 HyperOS 回收的概率。
 * 配合系统白名单（省电无限制 / 自启动 / 任务栏锁定）效果最佳。
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
 */
class KeepAliveService : Service() {

    private lateinit var floatManager: FloatWindowManager

    override fun onCreate() {
        super.onCreate()
<<<<<<< HEAD
        android.util.Log.e("AdSkip", "KeepAlive onCreate START")
        try {
            createChannel()
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AdSkip")
                .setContentText("运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            android.util.Log.e("AdSkip", "startForeground OK")
        } catch (e: Exception) {
            android.util.Log.e("AdSkip", "startForeground FAIL: ${e.message}", e)
        }
        try {
            floatManager = FloatWindowManager(this)
            floatManager.onVisibilityChanged = { updateNotification() }
            floatManager.onCaptureStateChanged = { updateNotification() }
            floatManager.showCapsule()
            android.util.Log.e("AdSkip", "FloatManager OK")
        } catch (e: Exception) {
            android.util.Log.e("AdSkip", "FloatManager FAIL: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::floatManager.isInitialized) return START_STICKY
        when (intent?.action) {
            ACTION_SHOW_CAPSULE -> {
                floatManager.showCapsule()
                getSharedPreferences("adskip_prefs", MODE_PRIVATE).edit().putBoolean("capsule", true).apply()
            }
            ACTION_HIDE_CAPSULE -> {
                floatManager.hideCapsuleAndNotify()
                getSharedPreferences("adskip_prefs", MODE_PRIVATE).edit().putBoolean("capsule", false).apply()
            }
            ACTION_CANCEL_CAPTURE -> floatManager.cancelCapture()
        }
=======
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
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
        return START_STICKY
    }

    override fun onDestroy() {
<<<<<<< HEAD
        if (::floatManager.isInitialized) floatManager.hideCapsule()
=======
        floatManager.hideCapsule()
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

<<<<<<< HEAD
    private fun startForegroundInternal(notif: Notification) {
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildSimpleNotification(): Notification {
        createChannel()
        val intent = Intent(this, com.simely.adskip.ui.MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdSkip 运行中")
            .setContentText("正在初始化...")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val visible = if (::floatManager.isInitialized) floatManager.isVisible() else false
        val capturing = com.simely.adskip.AppState.isCapturing

        val title = "AdSkip 运行中"
        val text = if (capturing) "捕获模式中，点悬浮球可取消" else "监听界面并自动跳过广告"

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

=======
    private fun buildNotification(): Notification {
        createChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdSkip 运行中")
            .setContentText("监听界面并自动跳过广告")
            .setSmallIcon(R.drawable.ic_notify)
<<<<<<< HEAD
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
=======
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
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
<<<<<<< HEAD
        const val ACTION_SHOW_CAPSULE = "com.simely.adskip.SHOW_CAPSULE"
        const val ACTION_HIDE_CAPSULE = "com.simely.adskip.HIDE_CAPSULE"
        const val ACTION_CANCEL_CAPTURE = "com.simely.adskip.CANCEL_CAPTURE"
=======
>>>>>>> 3038caf6cf3cfd455ae63c3e61dc2493ca600a14
    }
}
