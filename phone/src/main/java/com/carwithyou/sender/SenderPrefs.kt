package com.carwithyou.sender

import android.content.Context

class SenderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sender", Context.MODE_PRIVATE)

    var navPkg: String
        get() = sp.getString("nav", KnownApps.AMAP) ?: KnownApps.AMAP
        set(v) { sp.edit().putString("nav", v).apply() }

    var musicPkg: String
        get() = sp.getString("music", KnownApps.QQ_MUSIC) ?: KnownApps.QQ_MUSIC
        set(v) { sp.edit().putString("music", v).apply() }

    var virtualMode: Boolean
        get() = sp.getBoolean("virtual", true)
        set(v) { sp.edit().putBoolean("virtual", v).apply() }

    var browserMode: Boolean
        get() = sp.getBoolean("browser", false)
        set(v) { sp.edit().putBoolean("browser", v).apply() }

    /** 小米 CarWith 式结构化车联（音乐/电话同步） */
    var linkEnabled: Boolean
        get() = sp.getBoolean("link", true)
        set(v) { sp.edit().putBoolean("link", v).apply() }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) { sp.edit().putBoolean("keep_screen", v).apply() }
}
