package com.carwithyou.lite

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.provider.Settings

/**
 * 车机端进程入口：先立起诊断日志（崩溃 handler 要最早装），
 * 再登记一段「当前状态」，让复制出去的日志自带现场信息。
 */
class LiteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.stateProvider = { stateText(this) }
    }

    private fun stateText(ctx: Application): String {
        val sb = StringBuilder()
        sb.append("收流：").append(StreamReceiverActivity.statusText).append('\n')
        sb.append("解码：")
            .append(if (StreamReceiverActivity.decoderUp) "已起 " else "未起 ")
            .append("${StreamReceiverActivity.lastW}x${StreamReceiverActivity.lastH}")
            .append(" · 目标 ").append(StreamReceiverActivity.targetIp)
            .append(":").append(CastProtocol.CONTROL_PORT).append('/')
            .append(CastProtocol.VIDEO_PORT).append('\n')
        if (StreamReceiverActivity.lastError.isNotBlank()) {
            sb.append("最近错误：").append(StreamReceiverActivity.lastError).append('\n')
        }
        sb.append("ADB 链路：").append(AdbStreamActivity.statusText)
        if (AdbStreamActivity.lastW > 0) {
            sb.append(" ").append(AdbStreamActivity.lastW).append('x').append(AdbStreamActivity.lastH)
        }
        if (AdbStreamActivity.lastError.isNotBlank()) {
            sb.append("（").append(AdbStreamActivity.lastError.take(120)).append("）")
        }
        sb.append('\n')
        sb.append("悬浮窗 ").append(
            if (LyricOverlayService.canDrawOverlays(ctx)) "OK" else "缺权限"
        )
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        sb.append(" · 通知监听 ").append(
            if (flat.contains(ComponentName(ctx, MusicListenerService::class.java).flattenToString())) "OK" else "未开"
        )
        sb.append(" · 保活 ").append(
            if (ReceiverKeepService.running) "运行中" else "未运行"
        )
        sb.append(" · 屏幕 ").append(if (isScreenOn(ctx)) "亮" else "灭").append('\n')
        return sb.toString().trimEnd()
    }

    private fun isScreenOn(ctx: Application): Boolean = try {
        val pm = ctx.getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else @Suppress("DEPRECATION") pm.isScreenOn
    } catch (_: Exception) {
        true
    }
}
