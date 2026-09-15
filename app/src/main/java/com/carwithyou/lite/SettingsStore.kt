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
}
