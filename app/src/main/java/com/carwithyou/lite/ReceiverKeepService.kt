package com.carwithyou.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings

/**
 * 车机保活：前台 dataSync Service，常驻通知降低被杀概率。
 * 终版第4/6节：开机自启（BootReceiver）+ 电池白名单 + 断线重连（重连循环在 StreamReceiverActivity）。
 */
class ReceiverKeepService : Service() {

    companion object {
        const val CH = "keep"

        fun start(ctx: Context) {
            val i = Intent(ctx, ReceiverKeepService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun ignoringBattery(ctx: Context): Boolean {
            val pm = ctx.getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        fun requestWhitelist(act: android.app.Activity) {
            try {
                if (!ignoringBattery(act)) {
                    act.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${act.packageName}")
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CH, "投屏保活", NotificationManager.IMPORTANCE_MIN)
                )
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH)
                .setContentTitle("CarWithYou 保活运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CarWithYou 保活运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(3001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(3001, n)
        }
    }
}
