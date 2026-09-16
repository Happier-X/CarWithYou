package com.carwithyou.sender

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display

/**
 * 手机端进程入口：先把诊断日志立起来（崩溃handler 要在最早装），
 * 再登记一段「当前状态」，让复制出去的日志自带现场信息。
 */
class SenderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.stateProvider = { stateText(this) }
    }

    private fun stateText(ctx: Application): String {
        val running = ScreenCastService.running
        val mode = if (ScreenCastService.virtualMode) "虚拟屏" else "整屏镜像"
        val sb = StringBuilder()
        sb.append("投屏：").append(if (running) "运行中 · $mode" else "未运行").append('\n')
        if (running) {
            val did = CastConfig.displayId
            sb.append("Display：")
                .append(if (did == Display.DEFAULT_DISPLAY) "主屏(镜像)" else "虚拟屏#$did")
                .append(" · 编码 ${CastConfig.videoW}x${CastConfig.videoH}")
                .append(" · 坐标 ${CastConfig.screenW}x${CastConfig.screenH}")
                .append(" · 码率 %.1fM".format(CastConfig.currentBitrate / 1_000_000f))
                .append(" · 档位 ${CastConfig.currentResShort}p")
                .append(" · 自适应 ${if (CastConfig.adaptiveEnabled) "开" else "关"}")
                .append('\n')
            sb.append("车机回报：丢帧 %.1f%% · 解码 %.1fms · RTT %dms".format(
                CastConfig.lastDropRatio * 100f, CastConfig.lastDecodeMs, CastConfig.lastLatencyMs
            )).append('\n')
        }
        val hint = ScreenCastService.lastHint
        if (hint.isNotBlank()) sb.append("界面提示：").append(hint).append('\n')
        sb.append("权限：悬浮窗 ").append(
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) "缺" else "OK"
        )
        sb.append(" · 无障碍 ").append(
            if (TouchInjectorService.instance != null) "已连" else accessibilityHint(ctx)
        )
        val pm = ctx.getSystemService(POWER_SERVICE) as PowerManager
        sb.append(" · 电池白名单 ").append(
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "OK" else "未加"
        )
        sb.append(" · 热点IP ").append(NetUtils.hotspotIp(ctx)).append('\n')
        return sb.toString().trimEnd()
    }

    private fun accessibilityHint(ctx: Application): String {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_accessibility_services"
        ).orEmpty()
        return if (flat.contains(ComponentName(ctx, TouchInjectorService::class.java).flattenToString())) {
            "开了但服务没连上"
        } else "未开"
    }
}
