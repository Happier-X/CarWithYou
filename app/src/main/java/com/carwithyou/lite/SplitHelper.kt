package com.carwithyou.lite

import android.content.Context
import android.content.Intent

/** 车机常见包名，自用先写死，可在设置页改 */
object SplitHelper {
    const val AMAP_AUTO = "com.autonavi.amapauto"      // 高德车机版（优先）
    const val AMAP_PHONE = "com.autonavi.minimap"     // 高德手机版（兜底）
    const val QQ_MUSIC_CAR = "com.tencent.qqmusiccar" // QQ音乐车机版
    const val NETEASE = "com.netease.cloudmusic"
    const val KUGOU_CAR = "com.kugou.android.auto"

    fun installedNavPackages(pm: android.content.pm.PackageManager): List<String> =
        listOf(AMAP_AUTO, AMAP_PHONE).filter { isInstalled(pm, it) }

    fun installedMusicPackages(pm: android.content.pm.PackageManager): List<String> =
        listOf(QQ_MUSIC_CAR, NETEASE, KUGOU_CAR).filter { isInstalled(pm, it) }

    fun isInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean =
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) { false }

    fun launch(ctx: Context, pkg: String, adjacent: Boolean = false): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) {
            // Android 7+ 分屏关键：让第二个应用在相邻窗口打开
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    /** 一键分屏：先起导航，再相邻起音乐。车机不同ROM行为略有差异，多试两次。 */
    fun launchSplit(ctx: Context, navPkg: String, musicPkg: String) {
        launch(ctx, navPkg, adjacent = false)
        // 给导航一点启动时间，否则adjacent可能失效
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            launch(ctx, musicPkg, adjacent = true)
        }, 800)
    }
}
