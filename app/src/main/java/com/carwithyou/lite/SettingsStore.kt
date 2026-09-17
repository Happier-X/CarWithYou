package com.carwithyou.lite

import android.content.Context

/** 自用设置，存包名+歌词样式 */
class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("carwithyou", Context.MODE_PRIVATE)

    var navPkg: String
        get() = sp.getString("navPkg", SplitHelper.AMAP_AUTO) ?: SplitHelper.AMAP_AUTO
        set(v) = sp.edit().putString("navPkg", v).apply()

    var musicPkg: String
        get() = sp.getString("musicPkg", SplitHelper.QQ_MUSIC_CAR) ?: SplitHelper.QQ_MUSIC_CAR
        set(v) = sp.edit().putString("musicPkg", v).apply()

    var lyricTextSize: Float
        get() = sp.getFloat("lyricSize", 22f)
        set(v) = sp.edit().putFloat("lyricSize", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** 上次连上的手机热点 IP，自动连接用 */
    var phoneIp: String
        get() = sp.getString("phoneIp", "192.168.43.1") ?: "192.168.43.1"
        set(v) = sp.edit().putString("phoneIp", v).apply()

    /** 开机/打开桌面自动连手机 */
    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", true)
        set(v) = sp.edit().putBoolean("autoConnect", v).apply()

    /** 记住的手机蓝牙 MAC，蓝牙连上即自动连车联（亿连式上车即连） */
    var phoneBtMac: String
        get() = sp.getString("phoneBtMac", "").orEmpty()
        set(v) = sp.edit().putString("phoneBtMac", v).apply()

    var btAuto: Boolean
        get() = sp.getBoolean("btAuto", true)
        set(v) = sp.edit().putBoolean("btAuto", v).apply()

    // ─────────────────────────── A 路线（ADB 链路，手机零安装）

    /** 手机 adbd 地址 `host:port`（无线调试或 USB 转发出来的端口）；空=没配 */
    var adbTarget: String
        get() = sp.getString("adbTarget", "").orEmpty()
        set(v) = sp.edit().putString("adbTarget", v).apply()

    /** true=镜像手机主屏；false（默认）=在手机上新建一块独立虚拟屏放 App */
    var adbMirror: Boolean
        get() = sp.getBoolean("adbMirror", false)
        set(v) = sp.edit().putBoolean("adbMirror", v).apply()

    /** 虚拟屏尺寸；0 = 自动（取手机 wm size） */
    var adbWidth: Int
        get() = sp.getInt("adbWidth", 0)
        set(v) = sp.edit().putInt("adbWidth", v).apply()

    var adbHeight: Int
        get() = sp.getInt("adbHeight", 0)
        set(v) = sp.edit().putInt("adbHeight", v).apply()

    /** 虚拟屏密度；0 = 自动（取手机 wm density） */
    var adbDensity: Int
        get() = sp.getInt("adbDensity", 0)
        set(v) = sp.edit().putInt("adbDensity", v).apply()

    /** ADB 链路下「导航」Dock 要拉起的**手机侧**包名（不是车机侧） */
    var adbNavPkg: String
        get() = sp.getString("adbNavPkg", "com.autonavi.minimap").orEmpty()
        set(v) = sp.edit().putString("adbNavPkg", v).apply()

    /** ADB 链路下「音乐」Dock 要拉起的手机侧包名 */
    var adbMusicPkg: String
        get() = sp.getString("adbMusicPkg", "com.tencent.qqmusic").orEmpty()
        set(v) = sp.edit().putString("adbMusicPkg", v).apply()

    /**
     * 连上后自动把导航 App 投到新建的虚拟屏。
     * 默认必须为 true：新建的独立屏是**空屏**，空屏没有任何图层 → SurfaceFlinger 不合成
     * → 编码器一帧都拿不到（黑屏）。投个 App 进去才有画面。
     */
    var adbAutoLaunch: Boolean
        get() = sp.getBoolean("adbAutoLaunch", true)
        set(v) = sp.edit().putBoolean("adbAutoLaunch", v).apply()

    var adbFps: Int
        get() = sp.getInt("adbFps", 60)
        set(v) = sp.edit().putInt("adbFps", v).apply()

    var adbBitrate: Int
        get() = sp.getInt("adbBitrate", 8_000_000)
        set(v) = sp.edit().putInt("adbBitrate", v).apply()
}
