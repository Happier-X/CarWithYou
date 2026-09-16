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
}
