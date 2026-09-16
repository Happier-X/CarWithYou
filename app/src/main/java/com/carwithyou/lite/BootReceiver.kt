package com.carwithyou.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            AppLog.i("Boot", "开机广播收到，拉起保活（autoStart=${SettingsStore(ctx).autoStart}）")
            // 保活先起，保证投屏随时可连；桌面按设置决定是否弹
            try {
                ReceiverKeepService.start(ctx)
            } catch (e: Exception) {
                AppLog.w("Boot", "开机拉保活失败：${e.message}")
            }
            val store = SettingsStore(ctx)
            if (!store.autoStart) return
            // vivo 式上车即连：记住的手机 IP 直接进收流页自动连；关了 autoConnect 才停在桌面
            if (store.autoConnect) {
                AppLog.i("Boot", "开机自动进收流页连 ${store.phoneIp}")
                ctx.startActivity(Intent(ctx, StreamReceiverActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(StreamReceiverActivity.EXTRA_IP, store.phoneIp)
                    putExtra(StreamReceiverActivity.EXTRA_AUTO, true)
                })
            } else {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}
