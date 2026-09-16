package com.carwithyou.sender

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.sender.ui.SenderScreen
import com.carwithyou.sender.ui.theme.CarSenderTheme

class SenderActivity : AppCompatActivity() {

    private lateinit var prefs: SenderPrefs
    private val handler = Handler(Looper.getMainLooper())
    private var navPkgs = emptyList<String>()
    private var musicPkgs = emptyList<String>()
    private var pendingAfterOverlay = false

    private var navLabels by mutableStateOf(emptyList<String>())
    private var musicLabels by mutableStateOf(emptyList<String>())
    private var navIndex by mutableIntStateOf(0)
    private var musicIndex by mutableIntStateOf(0)
    private var virtualMode by mutableStateOf(true)
    private var keepScreen by mutableStateOf(true)
    private var adaptive by mutableStateOf(true)
    private var debugSave by mutableStateOf(false)
    private var ipText by mutableStateOf("本机IP：--")
    private var status by mutableStateOf("未投屏")
    private var stats by mutableStateOf("未投屏")

    private val overlayPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingAfterOverlay) {
            pendingAfterOverlay = false
            beginCast()
        } else if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已开")
        }
    }

    private val castPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startCastService(r.resultCode, r.data)
        } else {
            toast("要给录屏权限才能建车机屏（录的是虚拟屏，不是你手机画面）")
        }
    }

    private val needProjection = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            toast("系统要求一次录屏授权才能建独立副屏")
            requestProjection()
        }
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!ScreenCastService.running) {
                stats = "未投屏"
                return
            }
            val br = CastConfig.currentBitrate / 1_000_000f
            val drop = CastConfig.lastDropRatio * 100f
            val dec = CastConfig.lastDecodeMs
            val lat = CastConfig.lastLatencyMs
            val tier = CastConfig.currentResShort
            val brn = CastConfig.bitrateRange(tier)
            val minB = brn[0] / 1_000_000f
            val maxB = brn[1] / 1_000_000f
            val mode = if (ScreenCastService.virtualMode) "虚拟屏#${CastConfig.displayId}" else "镜像"
            val hint = ScreenCastService.lastHint.let { if (it.isBlank()) "" else "\n$it" }
            stats = "$mode | ${tier}p | %.1fM (%.1f–%.1fM) | 丢帧%.1f%% | 解码%.1fms | RTT%dms$hint".format(
                br, minB, maxB, drop, dec, lat
            )
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStatsPoll() { handler.removeCallbacks(statsRunnable); handler.post(statsRunnable) }
    private fun stopStatsPoll() { handler.removeCallbacks(statsRunnable) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SenderPrefs(this)
        fillAppSpinners()
        virtualMode = prefs.virtualMode
        keepScreen = prefs.keepScreenOn
        applyKeepScreen()
        ipText = "本机IP：${NetUtils.hotspotIp(this)}（车机连热点后填这个）"

        val filter = IntentFilter(ScreenCastService.ACTION_NEED_PROJECTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(needProjection, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(needProjection, filter)
        }

        setContent {
            CarSenderTheme {
                SenderScreen(
                    ip = ipText,
                    status = status,
                    stats = stats,
                    modeHint = modeHint(),
                    navLabels = navLabels,
                    musicLabels = musicLabels,
                    navIndex = navIndex,
                    musicIndex = musicIndex,
                    virtualMode = virtualMode,
                    keepScreen = keepScreen,
                    adaptive = adaptive,
                    debugSave = debugSave,
                    onNavIndex = { navIndex = it },
                    onMusicIndex = { musicIndex = it },
                    onVirtual = { virtualMode = it; prefs.virtualMode = it },
                    onKeepScreen = {
                        keepScreen = it
                        prefs.keepScreenOn = it
                        applyKeepScreen()
                    },
                    onAdaptive = { adaptive = it },
                    onDebug = { debugSave = it },
                    onStart = { startCast() },
                    onStop = {
                        stopService(Intent(this, ScreenCastService::class.java))
                        status = "已停止"
                        stopStatsPoll()
                        stats = "未投屏"
                    },
                    onRelaunch = {
                        if (!ScreenCastService.running) {
                            toast("还没开始投屏")
                        } else {
                            saveSelectedApps()
                            startService(Intent(this, ScreenCastService::class.java).apply {
                                action = ScreenCastService.ACTION_RELAUNCH
                                putExtra(ScreenCastService.EXTRA_NAV_PKG, selectedNav())
                                putExtra(ScreenCastService.EXTRA_MUSIC_PKG, selectedMusic())
                            })
                            toast("已重新把导航/音乐丢到车机屏")
                        }
                    },
                    onOverlay = {
                        pendingAfterOverlay = false
                        requestOverlayIfNeeded(force = true)
                    },
                    onAccess = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        toast("打开 Car投屏-手机端 的无障碍开关，车机才能反控")
                    },
                    onBattery = {
                        try {
                            val pm = getSystemService(POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            } else toast("已在电池白名单")
                        } catch (_: Exception) { toast("请手动：设置→电池→无限制") }
                    }
                )
            }
        }
    }

    private fun startCast() {
        saveSelectedApps()
        prefs.virtualMode = virtualMode
        prefs.keepScreenOn = keepScreen
        if (virtualMode && selectedNav().isBlank() && selectedMusic().isBlank()) {
            toast("先选导航或音乐")
            return
        }
        if (virtualMode && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            pendingAfterOverlay = true
            requestOverlayIfNeeded(force = false)
            return
        }
        beginCast()
    }

    private fun beginCast() {
        CastConfig.adaptiveEnabled = adaptive
        requestProjection()
    }

    private fun startCastService(resultCode: Int, data: Intent?) {
        val i = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_DATA, data)
            putExtra(ScreenCastService.EXTRA_DEBUG_SAVE, debugSave)
            putExtra(ScreenCastService.EXTRA_VIRTUAL_MODE, virtualMode)
            putExtra(ScreenCastService.EXTRA_NAV_PKG, selectedNav())
            putExtra(ScreenCastService.EXTRA_MUSIC_PKG, selectedMusic())
            putExtra(ScreenCastService.EXTRA_KEEP_SCREEN, keepScreen)
        }
        startForegroundService(i)
        val mode = if (virtualMode) "虚拟屏（手机可继续用）" else "整屏镜像"
        status = "投屏中 · $mode · 车机连 ${NetUtils.hotspotIp(this)}:8888"
        startStatsPoll()
    }

    private fun requestOverlayIfNeeded(force: Boolean) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            if (force) toast("悬浮窗权限已开")
            else beginCast()
            return
        }
        overlayPerm.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        toast("先给悬浮窗权限，车机屏上才会有切换栏")
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        castPerm.launch(mpm.createScreenCaptureIntent())
    }

    private fun fillAppSpinners() {
        val pm = packageManager
        navPkgs = KnownApps.installed(pm, KnownApps.NAV).ifEmpty { KnownApps.NAV }
        musicPkgs = KnownApps.installed(pm, KnownApps.MUSIC).ifEmpty { KnownApps.MUSIC }
        navLabels = navPkgs.map { KnownApps.label(pm, it) }
        musicLabels = musicPkgs.map { KnownApps.label(pm, it) }
        navIndex = navPkgs.indexOf(prefs.navPkg).coerceAtLeast(0)
        musicIndex = musicPkgs.indexOf(prefs.musicPkg).coerceAtLeast(0)
    }

    private fun selectedNav(): String = navPkgs.getOrNull(navIndex).orEmpty()
    private fun selectedMusic(): String = musicPkgs.getOrNull(musicIndex).orEmpty()

    private fun saveSelectedApps() {
        selectedNav().takeIf { it.isNotBlank() }?.let { prefs.navPkg = it }
        selectedMusic().takeIf { it.isNotBlank() }?.let { prefs.musicPkg = it }
    }

    private fun applyKeepScreen() {
        if (keepScreen) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun modeHint(): String = if (virtualMode) {
        "虚拟屏：导航/音乐开在独立副屏上，手机主屏还能刷微信。系统会要一次「录屏」授权——用来建这块副屏，不是录你手机。部分 ROM 可能把 App 弹回手机，那时再改镜像。"
    } else {
        "镜像：车上看的就是手机当前画面，需要你在手机上分屏。主屏会被占用。"
    }

    override fun onResume() {
        super.onResume()
        ipText = "本机IP：${NetUtils.hotspotIp(this)}（车机连热点后填这个）"
        if (ScreenCastService.running) startStatsPoll()
    }

    override fun onPause() {
        super.onPause()
        stopStatsPoll()
    }

    override fun onDestroy() {
        try { unregisterReceiver(needProjection) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
