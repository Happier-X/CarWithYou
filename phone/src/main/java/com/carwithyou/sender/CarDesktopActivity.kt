package com.carwithyou.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.sender.ui.DesktopScreen
import com.carwithyou.sender.ui.theme.CarSenderTheme

/**
 * 跑在独立虚拟屏上的车机桌面：导航/音乐从这里启动，更容易落在同一块屏。
 * 若系统把本 Activity 丢回手机主屏，立刻 finish，避免占手机。
 */
class CarDesktopActivity : AppCompatActivity() {

    private var navPkg = ""
    private var musicPkg = ""
    private var displayId = Display.INVALID_DISPLAY
    private var hint by mutableStateOf("车机虚拟桌面。导航和音乐会在这块屏上打开，手机主屏可以继续用。")

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        navPkg = intent.getStringExtra(EXTRA_NAV).orEmpty()
        musicPkg = intent.getStringExtra(EXTRA_MUSIC).orEmpty()
        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY)
        instance = this
        hint = "这块是车机虚拟屏 #$displayId。导航/音乐会叠在上面，手机主屏可以继续用。"

        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopReceiver, filter)
        }

        setContent {
            CarSenderTheme {
                DesktopScreen(
                    hint = hint,
                    onNav = { launchNav() },
                    onMusic = { launchMusic() }
                )
            }
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, true) && savedInstanceState == null) {
            window.decorView.post { autoLaunch() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val gotId = currentDisplayId()
        if (gotId != Display.DEFAULT_DISPLAY) displayId = gotId
        if (displayId != Display.INVALID_DISPLAY && gotId == Display.DEFAULT_DISPLAY && gotId != displayId) {
            finish()
        }
    }

    fun autoLaunch() {
        launchMusic()
        window.decorView.postDelayed({ launchNav(adjacent = true) }, 900)
    }

    private fun launchNav(adjacent: Boolean = false) {
        val w = CastConfig.screenW
        val h = CastConfig.screenH
        AppLauncher.launch(
            this, navPkg, displayId,
            bounds = AppLauncher.leftBounds(w, h),
            adjacent = adjacent
        )
    }

    private fun launchMusic() {
        val w = CastConfig.screenW
        val h = CastConfig.screenH
        AppLauncher.launch(
            this, musicPkg, displayId,
            bounds = AppLauncher.rightBounds(w, h),
            adjacent = false
        )
    }

    private fun currentDisplayId(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAV = "nav"
        const val EXTRA_MUSIC = "music"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_AUTO_LAUNCH = "auto_launch"
        const val ACTION_STOP = "com.carwithyou.sender.STOP_DESKTOP"

        @Volatile var instance: CarDesktopActivity? = null
    }
}
