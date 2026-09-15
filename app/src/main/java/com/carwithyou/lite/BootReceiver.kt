package com.carwithyou.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            // 保活先起，保证投屏随时可连；桌面按设置决定是否弹
            try { ReceiverKeepService.start(ctx) } catch (_: Exception) {}
            val store = SettingsStore(ctx)
            if (!store.autoStart) return
            ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
