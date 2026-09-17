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

    /**
     * 只投单个 App（Android 14+ 的单应用捕获）：车机只收到那一个 App 的画面。
     * 不需要副屏，也不需要任何系统权限；与 [virtualMode] 互斥（本模式属于镜像家族）。
     */
    var singleApp: Boolean
        get() = sp.getBoolean("single_app", false)
        set(v) { sp.edit().putBoolean("single_app", v).apply() }

    /** 小米 CarWith 式结构化车联（音乐/电话同步） */
    var linkEnabled: Boolean
        get() = sp.getBoolean("link", true)
        set(v) { sp.edit().putBoolean("link", v).apply() }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) { sp.edit().putBoolean("keep_screen", v).apply() }
}
