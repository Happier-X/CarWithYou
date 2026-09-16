package com.carwithyou.sender

import android.content.pm.PackageManager

/** 手机上常见的导航 / 音乐包名，投到独立虚拟屏用 */
object KnownApps {
    const val AMAP = "com.autonavi.minimap"
    const val AMAP_AUTO = "com.autonavi.amapauto"
    const val BAIDU_MAP = "com.baidu.BaiduMap"
    const val QQ_MUSIC = "com.tencent.qqmusic"
    const val NETEASE = "com.netease.cloudmusic"
    const val KUGOU = "com.kugou.android"
    const val KUWO = "cn.kuwo.player"

    val NAV = listOf(AMAP, AMAP_AUTO, BAIDU_MAP)
    val MUSIC = listOf(QQ_MUSIC, NETEASE, KUGOU, KUWO)

    fun installed(pm: PackageManager, candidates: List<String>): List<String> =
        candidates.filter { pkg ->
            try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

    fun label(pm: PackageManager, pkg: String): String {
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            "${pm.getApplicationLabel(ai)} ($pkg)"
        } catch (_: Exception) {
            pkg
        }
    }
}
