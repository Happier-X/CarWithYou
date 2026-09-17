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
import androidx.core.content.ContextCompat
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
    private var browserMode by mutableStateOf(false)
    private var singleAppMode by mutableStateOf(false)
    /** 单应用捕获是 Android 14(SDK34) 才加的 API */
    private val singleAppSupported = Build.VERSION.SDK_INT >= 34
    private var linkOn by mutableStateOf(true)
    private var keepScreen by mutableStateOf(true)
    private var adaptive by mutableStateOf(true)
    private var debugSave by mutableStateOf(false)
    private var ipText by mutableStateOf("本机IP：--")
    private var status by mutableStateOf("未投屏")
    private var stats by mutableStateOf("未投屏")
    private var updateHint by mutableStateOf(UpdateChecker.idleHint)
    private var logHint by mutableStateOf(Diagnostics.hint())

    private var lastLoggedHint = ""

    private val overlayPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingAfterOverlay) {
            pendingAfterOverlay = false
            if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
                AppLog.i(TAG, "悬浮窗权限已拿到，继续投屏")
                beginCast()
            } else {
                AppLog.w(TAG, "悬浮窗权限还是没给，投屏没继续")
            }
        } else if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已开")
        }
    }

    private val castPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            AppLog.i(TAG, "用户已允许录屏")
            startCastService(r.resultCode, r.data)
        } else {
            AppLog.w(TAG, "录屏授权被拒（rc=${r.resultCode} data=${r.data != null}）")
            toast("要给录屏权限才能建车机屏（录的是虚拟屏，不是你手机画面）")
        }
    }

    private val phonePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            AppLog.i(TAG, "电话权限已齐，起车联服务")
            LinkService.start(this)
            linkOn = true
            prefs.linkEnabled = true
        } else {
            AppLog.w(TAG, "电话权限没给全，来电只显示状态、不能代接/代挂")
            toast("电话权限没给全：来电只显示、不能在车机上接/挂")
            LinkService.start(this)
            linkOn = true
            prefs.linkEnabled = true
        }
    }

    private val needProjection = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.w(TAG, "服务要求重新拿一次录屏授权")
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
            val hint = ScreenCastService.lastHint.let { if (it.isBlank()) "" else "\n$it" }
            if (hint.isNotBlank() && hint != "\n$lastLoggedHint") {
                lastLoggedHint = ScreenCastService.lastHint
                AppLog.wThrottle(TAG, "界面提示：${ScreenCastService.lastHint}", 20_000)
            }
            stats = if (ScreenCastService.browserMode) {
                val mode = if (ScreenCastService.virtualMode) "虚拟屏#${CastConfig.displayId}" else "镜像"
                val url = ScreenCastService.browserUrl.ifBlank { "http://192.168.43.1:${ScreenCastService.BROWSER_PORT}/" }
                "$mode · 浏览器免安装 · ${CastConfig.videoW}x${CastConfig.videoH} ~15fps\n$url$hint"
            } else {
                val br = CastConfig.currentBitrate / 1_000_000f
                val drop = CastConfig.lastDropRatio * 100f
                val dec = CastConfig.lastDecodeMs
                val lat = CastConfig.lastLatencyMs
                val tier = CastConfig.currentResShort
                val brn = CastConfig.bitrateRange(tier)
                val minB = brn[0] / 1_000_000f
                val maxB = brn[1] / 1_000_000f
                val mode = when {
                    virtualMode -> "虚拟屏#${CastConfig.displayId}"
                    ScreenCastService.singleAppMode -> "单App"
                    else -> "镜像"
                }
                "$mode | ${tier}p | %.1fM (%.1f–%.1fM) | 丢帧%.1f%% | 解码%.1fms | RTT%dms$hint".format(
                    br, minB, maxB, drop, dec, lat
                )
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStatsPoll() { handler.removeCallbacks(statsRunnable); handler.post(statsRunnable) }
    private fun stopStatsPoll() { handler.removeCallbacks(statsRunnable) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SenderPrefs(this)
        AppLog.i(TAG, "打开手机端设置页")
        fillAppSpinners()
        virtualMode = prefs.virtualMode
        browserMode = prefs.browserMode
        singleAppMode = prefs.singleApp && singleAppSupported
        linkOn = prefs.linkEnabled
        if (linkOn) LinkService.start(this)
        keepScreen = prefs.keepScreenOn
        applyKeepScreen()
        ipText = "手机热点IP：${NetUtils.hotspotIp(this)}（车机连热点，车机App填这个连）"

        val filter = IntentFilter(ScreenCastService.ACTION_NEED_PROJECTION)
        ContextCompat.registerReceiver(this, needProjection, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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
                    browserMode = browserMode,
                    singleApp = singleAppMode,
                    singleAppSupported = singleAppSupported,
                    linkOn = linkOn,
                    keepScreen = keepScreen,
                    adaptive = adaptive,
                    debugSave = debugSave,
                    onNavIndex = { navIndex = it },
                    onMusicIndex = { musicIndex = it },
                    onVirtual = {
                        virtualMode = it
                        prefs.virtualMode = it
                        if (it && singleAppMode) { singleAppMode = false; prefs.singleApp = false }
                    },
                    onBrowser = { browserMode = it; prefs.browserMode = it },
                    onSingleApp = { on ->
                        if (on && !singleAppSupported) {
                            toast("只投单个 App 需要 Android 14+（本机 Android ${Build.VERSION.RELEASE}），先用虚拟屏或镜像")
                        } else {
                            singleAppMode = on
                            prefs.singleApp = on
                            if (on && virtualMode) { virtualMode = false; prefs.virtualMode = false }
                            toast(
                                if (on) "单 App 模式：点「开始投屏」后系统会让你选一个应用，车机只看到它"
                                else "已关掉单 App 模式"
                            )
                        }
                    },
                    onLink = { setLink(it) },
                    onCarMode = {
                        startActivity(Intent(this, CarModeActivity::class.java))
                    },
                    onKeepScreen = {
                        keepScreen = it
                        prefs.keepScreenOn = it
                        applyKeepScreen()
                    },
                    onAdaptive = { adaptive = it },
                    onDebug = { debugSave = it },
                    onStart = { startCast() },
                    onStop = {
                        AppLog.i(TAG, "用户点停止")
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
                    onNotif = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        toast("打开 Car投屏-音乐同步监听，车机才能看歌名/切歌")
                    },
                    onBattery = {
                        try {
                            val pm = getSystemService(POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            } else toast("已在电池白名单")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "拉电池白名单页面失败：${e.message}")
                            toast("请手动：设置→电池→无限制")
                        }
                    },
                    updateHint = updateHint,
                    onUpdate = { UpdateChecker.checkFrom(this) { updateHint = it } },
                    logHint = logHint,
                    onShowLog = { Diagnostics.show(this) { logHint = Diagnostics.hint() } },
                    onCopyLog = {
                        Diagnostics.copy(this)
                        logHint = Diagnostics.hint()
                    },
                    onCopyStatus = {
                        Diagnostics.copyText(this, "CarWithYou 当前状态", "$ipText\n$status\n$stats")
                        AppLog.i(TAG, "用户复制了当前状态")
                    }
                )
            }
        }
    }

    private fun startCast() {
        saveSelectedApps()
        prefs.virtualMode = virtualMode
        prefs.browserMode = browserMode
        prefs.singleApp = singleAppMode
        prefs.keepScreenOn = keepScreen
        AppLog.i(
            TAG,
            "点「开始投屏」mode=${if (virtualMode) "虚拟屏" else if (singleAppMode) "单App" else "镜像"} 自适应=${if (adaptive) "开" else "关"}"
        )
        if (virtualMode && selectedNav().isBlank() && selectedMusic().isBlank()) {
            toast("先选导航或音乐")
            return
        }
        if (virtualMode && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "没给悬浮窗权限，先去申请")
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
            putExtra(ScreenCastService.EXTRA_BROWSER_MODE, browserMode)
            putExtra(ScreenCastService.EXTRA_SINGLE_APP, singleAppMode)
            putExtra(ScreenCastService.EXTRA_NAV_PKG, selectedNav())
            putExtra(ScreenCastService.EXTRA_MUSIC_PKG, selectedMusic())
            putExtra(ScreenCastService.EXTRA_KEEP_SCREEN, keepScreen)
        }
        startForegroundService(i)
        val mode = when {
            virtualMode -> "虚拟屏（手机可继续用）"
            singleAppMode -> "只投单个 App（系统弹窗里选一个）"
            else -> "整屏镜像"
        }
        val out = if (browserMode) "车机浏览器打开 http://${NetUtils.hotspotIp(this)}:${ScreenCastService.BROWSER_PORT}/（降级）" else "车机App连 ${NetUtils.hotspotIp(this)}:8888"
        status = "投屏中 · $mode · $out"
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

    /**
     * Android 14 起，用户选「共享整个屏幕」后系统会把录屏授权记下来（appop=allow），
     * 之后再投屏就直接放行、不再弹窗——于是「单 App 模式」会静默退化成整屏镜像。
     * 这里提前告诉用户，不然他会以为功能没生效。
     */
    private fun projectionConsentRemembered(): Boolean = try {
        val aom = getSystemService(android.app.AppOpsManager::class.java)
        aom.unsafeCheckOpNoThrow(
            "android:project_media", android.os.Process.myUid(), packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        false
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+ 单应用捕获：createConfigForUserChoice() 让系统弹窗多出「共享单个应用」，
        // 用户选哪个 App，投影里就只有那个 App 的内容。这是普通 App 能实现
        // 「车机只看导航、手机还能用」的唯一官方路子（不需要副屏，也就绕开了副屏的权限墙）。
        val intent = if (singleAppMode && singleAppSupported) {
            if (projectionConsentRemembered()) {
                AppLog.w(TAG, "系统记住了上次的整屏录屏授权，这次不会弹「选应用」，会变成整屏镜像")
                toast("系统记住了上次的整屏录屏授权，这次不会再弹「选应用」。想只投一个 App，请先去 设置→应用→Car投屏-手机端 撤销「录屏/投影」权限，再重新投屏")
            }
            AppLog.i(TAG, "拉起录屏授权弹窗（单App：在弹窗里选「单个应用」）")
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            AppLog.i(TAG, "拉起录屏授权弹窗")
            mpm.createScreenCaptureIntent()
        }
        castPerm.launch(intent)
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

    private fun setLink(on: Boolean) {
        if (on) {
            AppLog.i(TAG, "开车联服务（音乐/电话同步 :${LinkService.PORT}）")
            phonePerms.launch(
                arrayOf(
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.ANSWER_PHONE_CALLS
                )
            )
        } else {
            AppLog.i(TAG, "关车联服务")
            LinkService.stop(this)
            linkOn = false
            prefs.linkEnabled = false
        }
    }

    private fun modeHint(): String = if (browserMode) {
        "降级模式：车机不用装App，用浏览器打开上面的地址看。画质约15fps，首选还是车机装App走H264。"
    } else if (virtualMode) {
        "虚拟屏：导航/音乐开在独立副屏上，手机主屏还能刷微信。系统会要一次「录屏」授权——用来建这块副屏，不是录你手机。部分 ROM 可能把 App 弹回手机，那时再改镜像。"
    } else {
        "镜像：车上看的就是手机当前画面，需要你在手机上分屏。主屏会被占用。"
    }

    override fun onResume() {
        super.onResume()
        ipText = "手机热点IP：${NetUtils.hotspotIp(this)}（车机连热点，车机App填这个连）"
        linkOn = LinkService.running || prefs.linkEnabled
        logHint = Diagnostics.hint()
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

    private fun toast(s: String) {
        AppLog.i(TAG, s)
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "CarWithYou"
    }
}
