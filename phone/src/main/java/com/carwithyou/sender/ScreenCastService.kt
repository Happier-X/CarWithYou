package com.carwithyou.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.math.roundToInt

/**
 * 推流（8888 视频 + 8889 控制）。
 *
 * 虚拟屏模式（默认，CarPlus 同款）：
 *   MediaProjection + PRESENTATION|OWN_CONTENT_ONLY → 独立 Display
 *   导航/音乐 launchDisplayId 到这块屏，手机主屏空出来。
 *   系统仍要点一次「允许录屏」，但录的是虚拟屏而不是手机画面。
 *
 * 镜像模式（兜底）：
 *   AUTO_MIRROR 整屏镜像，手机必须分屏，主屏被占用。
 */
class ScreenCastService : Service() {

    companion object {
        const val VIDEO_PORT = 8888
        const val CONTROL_PORT = 8889
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val EXTRA_DEBUG_SAVE = "debug_save"
        const val EXTRA_VIRTUAL_MODE = "virtual"
        const val EXTRA_NAV_PKG = "nav"
        const val EXTRA_MUSIC_PKG = "music"
        const val EXTRA_KEEP_SCREEN = "keep_screen"
        const val ACTION_RELAUNCH = "com.carwithyou.sender.RELAUNCH"
        const val ACTION_VIRTUAL_READY = "com.carwithyou.sender.VIRTUAL_READY"
        const val ACTION_NEED_PROJECTION = "com.carwithyou.sender.NEED_PROJECTION"
        private const val TAG = "CarWithYou"
        // hidden DisplayManager flags
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        @Volatile var running = false
        @Volatile var virtualMode = true
        @Volatile var lastHint = ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var vdisplay: VirtualDisplay? = null
    private var videoServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null
    private var controlOut: PrintWriter? = null
    private var overlay: CarControlOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var resultCode = 0
    private var projectionData: Intent? = null
    private var debugSave = false
    private var debugOut: java.io.FileOutputStream? = null
    private var navPkg = ""
    private var musicPkg = ""
    private var densityDpi = 240
    private var keepScreen = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg(withProjection = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELAUNCH && running) {
            intent.getStringExtra(EXTRA_NAV_PKG)?.let { navPkg = it }
            intent.getStringExtra(EXTRA_MUSIC_PKG)?.let { musicPkg = it }
            launchAppsOntoVirtual()
            return START_STICKY
        }
        if (running) return START_STICKY
        debugSave = intent?.getBooleanExtra(EXTRA_DEBUG_SAVE, false) == true
        virtualMode = intent?.getBooleanExtra(EXTRA_VIRTUAL_MODE, true) != false
        CastConfig.resolutionLocked = virtualMode
        navPkg = intent?.getStringExtra(EXTRA_NAV_PKG).orEmpty()
        musicPkg = intent?.getStringExtra(EXTRA_MUSIC_PKG).orEmpty()
        keepScreen = intent?.getBooleanExtra(EXTRA_KEEP_SCREEN, true) != false
        resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        projectionData = if (intent == null) null else if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (!virtualMode && (resultCode != Activity.RESULT_OK || projectionData == null)) {
            lastHint = "镜像模式需要录屏权限"
            return START_NOT_STICKY
        }
        startFg(withProjection = !virtualMode)
        startCast()
        return START_STICKY
    }

    private fun startFg(withProjection: Boolean) {
        val chId = "cast"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(chId, "投屏中", NotificationManager.IMPORTANCE_MIN))
        }
        val title = when {
            virtualMode && withProjection -> "车机虚拟屏运行中（录屏授权）"
            virtualMode -> "车机虚拟屏运行中，手机可正常使用"
            else -> "CarWithYou 整屏镜像中"
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId).setContentTitle(title).setSmallIcon(android.R.drawable.ic_media_play).build()
        else @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle(title).setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withProjection) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(2001, n, type)
        } else {
            startForeground(2001, n)
        }
    }

    private data class Res(val w: Int, val h: Int)

    /** 镜像模式：跟手机物理屏比例 */
    private fun mirrorRes(short: Int, sw: Int, sh: Int): Res {
        val ratio = sh.toFloat() / sw.coerceAtLeast(1)
        return if (sw < sh) {
            Res(even(short), even((short * ratio).roundToInt()))
        } else {
            Res(even((short * ratio).roundToInt()), even(short))
        }
    }

    /** 虚拟屏：车机横屏 16:9 */
    private fun virtualRes(short: Int): Res = Res(even(short * 16 / 9), even(short))

    private fun even(n: Int) = n and 1.inv()

    private fun startCast() {
        running = true
        lastHint = ""
        acquireWakeLock()
        if (debugSave) {
            try {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                    "carwithyou"
                )
                dir.mkdirs()
                debugOut = java.io.FileOutputStream(java.io.File(dir, "cast.h264"))
            } catch (_: Exception) { debugOut = null }
        }
        scope.launch {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                densityDpi = if (virtualMode) CastConfig.VIRTUAL_DPI else dm.densityDpi

                launch { startControlServer() }
                videoServer = ServerSocket(VIDEO_PORT)

                if (virtualMode) {
                    runVirtualSession()
                } else {
                    val proj = obtainProjection() ?: throw IllegalStateException("镜像需要录屏权限")
                    CastConfig.screenW = dm.widthPixels
                    CastConfig.screenH = dm.heightPixels
                    CastConfig.displayId = Display.DEFAULT_DISPLAY
                    runMirrorSession(proj, dm.densityDpi)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startCast failed", e)
                lastHint = "启动失败：${e.message}"
                stopSelf()
            }
        }
    }

    private fun obtainProjection(): MediaProjection? {
        val data = projectionData ?: return null
        if (resultCode != Activity.RESULT_OK) return null
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: return null
        projection = proj
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, mainHandler)
        return proj
    }

    /** 虚拟屏只建一次，断线重连不重建，避免车上的 App 被杀掉 */
    private suspend fun runVirtualSession() {
        val res = virtualRes(CastConfig.currentResShort)
        CastConfig.videoW = res.w
        CastConfig.videoH = res.h
        CastConfig.screenW = res.w
        CastConfig.screenH = res.h
        val (enc, surface) = createEncoder(res.w, res.h, CastConfig.currentBitrate)
        encoder = enc
        encoderSurface = surface
        // 有录屏授权时优先 MediaProjection + PUBLIC，第三方 App 才能落到副屏（CarPlus 同款）。
        // DisplayManager 无 PUBLIC 时高德/音乐会弹回手机，只作无授权兜底。
        val haveProjection = projectionData != null && resultCode == Activity.RESULT_OK
        if (haveProjection) {
            startFg(withProjection = true)
            val proj = obtainProjection() ?: run {
                sendBroadcast(Intent(ACTION_NEED_PROJECTION).setPackage(packageName))
                stopSelf()
                return
            }
            vdisplay = createProjectionDisplay(proj, res.w, res.h, surface)
        } else {
            vdisplay = createIndependentDisplay(res.w, res.h, surface)
            if (vdisplay == null) {
                lastHint = "系统不允许普通 App 建独立副屏，需要一次录屏授权（录的是虚拟屏，不是手机画面）"
                sendBroadcast(Intent(ACTION_NEED_PROJECTION).setPackage(packageName))
                stopSelf()
                return
            }
        }
        val displayId = vdisplay?.display?.displayId ?: Display.INVALID_DISPLAY
        CastConfig.displayId = displayId
        Log.i(TAG, "virtual display id=$displayId ${res.w}x${res.h} dpi=$densityDpi")
        sendConfig(res.w, res.h)
        delay(400)
        mainHandler.post { startCarDesktop(displayId) }
        sendBroadcast(Intent(ACTION_VIRTUAL_READY).setPackage(packageName))
        mainHandler.postDelayed({ showOverlay() }, 800)
        mainHandler.postDelayed({
            if (CarDesktopActivity.instance == null) {
                lastHint = "系统没把桌面放到副屏，已直接拉起导航/音乐。若它们出现在手机上，请改用镜像模式。"
                launchAppsOntoVirtual()
            }
        }, 1600)

        while (scope.isActive && running) {
            pumpWithAdapt(enc, CastConfig.currentResShort, allowResize = false)
        }
    }

    private suspend fun runMirrorSession(proj: MediaProjection, dpi: Int) {
        var currentTier = CastConfig.currentResShort
        while (scope.isActive && running) {
            val res = mirrorRes(currentTier, CastConfig.screenW, CastConfig.screenH)
            CastConfig.videoW = res.w
            CastConfig.videoH = res.h
            CastConfig.currentResShort = currentTier
            val (enc, surface) = createEncoder(res.w, res.h, CastConfig.currentBitrate)
            encoder = enc
            encoderSurface = surface
            vdisplay = proj.createVirtualDisplay(
                "carwithyou-mirror", res.w, res.h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, mainHandler
            )
            sendConfig(res.w, res.h)
            val resized = pumpWithAdapt(enc, currentTier, allowResize = true)
            releaseEncoderAndDisplay()
            if (resized) {
                currentTier = CastConfig.currentResShort
                delay(300)
            } else if (running) {
                delay(300)
            }
        }
    }

    /** 不镜像主屏：优先 DisplayManager（无录屏条），失败再走 MediaProjection */
    private fun createIndependentDisplay(w: Int, h: Int, surface: Surface): VirtualDisplay? {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val presentation = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val publicFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or presentation
        val hiddenFlags = FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val attempts = intArrayOf(publicFlags or hiddenFlags, publicFlags, presentation)
        for (flags in attempts) {
            try {
                val vd = dm.createVirtualDisplay(
                    "carwithyou-virtual", w, h, densityDpi, surface, flags, null, mainHandler
                )
                Log.i(TAG, "DisplayManager virtual display flags=$flags id=${vd.display.displayId}")
                return vd
            } catch (e: Exception) {
                Log.w(TAG, "DisplayManager flags=$flags rejected: ${e.message}")
            }
        }
        return null
    }

    private fun createProjectionDisplay(proj: MediaProjection, w: Int, h: Int, surface: Surface): VirtualDisplay {
        val presentation = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val publicFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or presentation
        val hiddenFlags = FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED
        val attempts = intArrayOf(publicFlags or hiddenFlags, publicFlags, presentation)
        var last: Exception? = null
        for (flags in attempts) {
            try {
                val vd = proj.createVirtualDisplay(
                    "carwithyou-virtual", w, h, densityDpi,
                    flags, surface, null, mainHandler
                ) ?: throw IllegalStateException("createVirtualDisplay returned null")
                return vd
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "projection display flags=$flags rejected: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("createVirtualDisplay failed")
    }

    private fun startCarDesktop(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        val intent = Intent(this, CarDesktopActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(CarDesktopActivity.EXTRA_NAV, navPkg)
            putExtra(CarDesktopActivity.EXTRA_MUSIC, musicPkg)
            putExtra(CarDesktopActivity.EXTRA_DISPLAY_ID, displayId)
            putExtra(CarDesktopActivity.EXTRA_AUTO_LAUNCH, true)
        }
        val opts = android.app.ActivityOptions.makeBasic()
        opts.launchDisplayId = displayId
        try {
            startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "start CarDesktop failed: ${e.message}")
            lastHint = "虚拟桌面启动失败：${e.message}"
        }
    }

    private fun launchAppsOntoVirtual() {
        val displayId = CastConfig.displayId
        if (!virtualMode || displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val w = CastConfig.screenW
        val h = CastConfig.screenH
        AppLauncher.launch(this, musicPkg, displayId, AppLauncher.rightBounds(w, h), adjacent = false)
        mainHandler.postDelayed({
            AppLauncher.launch(this, navPkg, displayId, AppLauncher.leftBounds(w, h), adjacent = true)
        }, 900)
    }

    private fun showOverlay() {
        val display = vdisplay?.display ?: return
        overlay?.hide()
        overlay = CarControlOverlay(applicationContext).also { bar ->
            bar.show(
                display,
                onNav = {
                    AppLauncher.launch(
                        this, navPkg, CastConfig.displayId,
                        AppLauncher.leftBounds(CastConfig.screenW, CastConfig.screenH),
                        adjacent = true
                    )
                },
                onMusic = {
                    AppLauncher.launch(
                        this, musicPkg, CastConfig.displayId,
                        AppLauncher.rightBounds(CastConfig.screenW, CastConfig.screenH)
                    )
                },
                onSplit = { launchAppsOntoVirtual() }
            )
        }
    }

    private fun createEncoder(w: Int, h: Int, bitrate: Int): Pair<MediaCodec, Surface> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, CastConfig.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, CastConfig.IFRAME_INTERVAL)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        return enc to surface
    }

    private fun sendConfig(w: Int, h: Int) {
        try {
            controlOut?.println("CONFIG $w $h ${CastConfig.screenW} ${CastConfig.screenH}")
            controlOut?.flush()
        } catch (_: Exception) {}
    }

    /**
     * 推流主循环。返回 true = 需要切分辨率（仅镜像模式会重建）。
     * 虚拟屏不允许重建 Display，否则车上的 App 会没。
     */
    private fun pumpWithAdapt(enc: MediaCodec, currentTier: Int, allowResize: Boolean): Boolean {
        var needResize = false
        try {
            val client = videoServer?.accept() ?: return false
            client.use { sock ->
                val out = DataOutputStream(sock.getOutputStream())
                val info = MediaCodec.BufferInfo()
                while (scope.isActive && running && !needResize) {
                    val idx = enc.dequeueOutputBuffer(info, 10_000)
                    if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx) ?: continue
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.get(data)
                        enc.releaseOutputBuffer(idx, false)
                        if (info.size > 0) {
                            try {
                                out.writeInt(data.size)
                                out.write(data)
                                out.flush()
                            } catch (_: Exception) { break }
                            try {
                                debugOut?.write(byteArrayOf(0, 0, 0, 1))
                                debugOut?.write(data)
                            } catch (_: Exception) {}
                        }
                    }
                    if (CastConfig.adaptiveEnabled) {
                        when (CastConfig.decide()) {
                            0 -> {
                                try {
                                    val params = android.os.Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, CastConfig.currentBitrate)
                                    }
                                    enc.setParameters(params)
                                } catch (_: Exception) {}
                            }
                            1 -> {
                                if (allowResize) {
                                    val i = CastConfig.RES_TIERS.indexOf(currentTier)
                                    if (i in 0 until CastConfig.RES_TIERS.lastIndex) {
                                        CastConfig.currentResShort = CastConfig.RES_TIERS[i + 1]
                                        val range = CastConfig.bitrateRange(CastConfig.currentResShort)
                                        CastConfig.currentBitrate = (range[0] + range[1]) / 2
                                        needResize = true
                                    }
                                } else {
                                    val range = CastConfig.bitrateRange(currentTier)
                                    if (CastConfig.currentBitrate < range[1]) {
                                        CastConfig.currentBitrate =
                                            (CastConfig.currentBitrate * 1.2f).toInt().coerceAtMost(range[1])
                                    }
                                    applyBitrate(enc)
                                }
                            }
                            -1 -> {
                                if (allowResize) {
                                    val i = CastConfig.RES_TIERS.indexOf(currentTier)
                                    if (i > 0) {
                                        CastConfig.currentResShort = CastConfig.RES_TIERS[i - 1]
                                        val range = CastConfig.bitrateRange(CastConfig.currentResShort)
                                        CastConfig.currentBitrate = (range[0] + range[1]) / 2
                                        needResize = true
                                    }
                                } else {
                                    val range = CastConfig.bitrateRange(currentTier)
                                    val newB = (CastConfig.currentBitrate * 0.7f).toInt().coerceAtLeast(range[0])
                                    if (newB < CastConfig.currentBitrate) CastConfig.currentBitrate = newB
                                    applyBitrate(enc)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return needResize
    }

    private fun applyBitrate(enc: MediaCodec) {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, CastConfig.currentBitrate)
            }
            enc.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun startControlServer() {
        try {
            controlServer = ServerSocket(CONTROL_PORT)
            while (scope.isActive && running) {
                val client = try { controlServer?.accept() ?: break } catch (_: Exception) { break }
                scope.launch {
                    try {
                        client.use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                            val out = PrintWriter(sock.getOutputStream(), true)
                            controlOut = out
                            sendConfig(CastConfig.videoW, CastConfig.videoH)
                            while (isActive && running) {
                                val line = try { reader.readLine() } catch (_: Exception) { break } ?: break
                                handleControl(line.trim())
                            }
                        }
                    } catch (_: Exception) {} finally {
                        if (controlOut != null) controlOut = null
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleControl(line: String) {
        val p = line.split(" ")
        when (p.getOrNull(0)) {
            "STATS" -> {
                CastConfig.lastDropRatio = p.getOrNull(1)?.toFloatOrNull() ?: 0f
                CastConfig.lastDecodeMs = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                CastConfig.lastBitrate = p.getOrNull(3)?.toLongOrNull() ?: 0L
                CastConfig.lastLatencyMs = p.getOrNull(4)?.toLongOrNull() ?: 0L
            }
            "PING" -> try { controlOut?.println("PONG ${p.getOrNull(1) ?: ""}"); controlOut?.flush() } catch (_: Exception) {}
            "TAP" -> {
                val x = p.getOrNull(1)?.toFloatOrNull() ?: return
                val y = p.getOrNull(2)?.toFloatOrNull() ?: return
                val svc = TouchInjectorService.instance
                if (svc == null) lastHint = "没开无障碍，车机点了也注入不了"
                else svc.tap(x, y)
            }
            "SWIPE" -> {
                val x0 = p.getOrNull(1)?.toFloatOrNull() ?: return
                val y0 = p.getOrNull(2)?.toFloatOrNull() ?: return
                val x1 = p.getOrNull(3)?.toFloatOrNull() ?: return
                val y1 = p.getOrNull(4)?.toFloatOrNull() ?: return
                val dur = p.getOrNull(5)?.toLongOrNull() ?: 300L
                val svc = TouchInjectorService.instance
                if (svc == null) lastHint = "没开无障碍，车机点了也注入不了"
                else svc.swipe(x0, y0, x1, y1, dur)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val flags = if (keepScreen) {
                @Suppress("DEPRECATION")
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            } else {
                PowerManager.PARTIAL_WAKE_LOCK
            }
            wakeLock = pm.newWakeLock(flags, "carwithyou:cast").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseEncoderAndDisplay() {
        try { vdisplay?.release() } catch (_: Exception) {}
        vdisplay = null
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }

    override fun onDestroy() {
        running = false
        try { sendBroadcast(Intent(CarDesktopActivity.ACTION_STOP).setPackage(packageName)) } catch (_: Exception) {}
        try { overlay?.hide() } catch (_: Exception) {}
        overlay = null
        try { videoServer?.close() } catch (_: Exception) {}
        try { controlServer?.close() } catch (_: Exception) {}
        releaseEncoderAndDisplay()
        try {
            projectionCallback?.let { cb ->
                projection?.unregisterCallback(cb)
            }
        } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { debugOut?.flush(); debugOut?.close() } catch (_: Exception) {}
        debugOut = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        CastConfig.displayId = Display.DEFAULT_DISPLAY
        CastConfig.resolutionLocked = false
        lastHint = ""
        scope.cancel()
        super.onDestroy()
    }
}
