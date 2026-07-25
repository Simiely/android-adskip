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
 * 前台保活服务：常驻通知 + 持有悬浮胶囊。
 * - 通知栏按钮：显示/隐藏悬浮窗、取消捕获
 * - 长按胶囊 → 隐藏
 * - 点击胶囊 → 进入捕获模式（通知栏出现「取消捕获」）
 */
class KeepAliveService : Service() {

    private lateinit var floatManager: FloatWindowManager

    override fun onCreate() {
        super.onCreate()
        logi { "KeepAliveService starting" }
        floatManager = FloatWindowManager(this)
        floatManager.onVisibilityChanged = { updateNotification() }
        floatManager.onCaptureStateChanged = { updateNotification() }
        startForegroundInternal()
        floatManager.showCapsule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_CAPSULE -> floatManager.showCapsule()
            ACTION_HIDE_CAPSULE -> floatManager.hideCapsuleAndNotify()
            ACTION_CANCEL_CAPTURE -> floatManager.cancelCapture()
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
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        createChannel()
        val visible = floatManager.isVisible()
        val capturing = com.simely.adskip.AppState.isCapturing

        val title = "AdSkip 运行中"
        val text = when {
            capturing -> "捕获模式中，请点击目标按钮；通知栏可取消"
            visible -> "悬浮窗已显示，长按可隐藏"
            else -> "悬浮窗已隐藏"
        }

        val mainIntent = Intent(this, MainActivity::class.java)
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
                } else if (!visible) {
                    val showIntent = Intent(this@KeepAliveService, KeepAliveService::class.java).apply {
                        action = ACTION_SHOW_CAPSULE
                    }
                    val showPi = PendingIntent.getService(
                        this@KeepAliveService, 1, showIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, "显示悬浮窗", showPi)
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
