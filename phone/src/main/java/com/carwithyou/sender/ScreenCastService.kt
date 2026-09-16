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
 * 推流：两种输出二选一。
 *
 * - 免安装模式（默认）：VirtualDisplay → ImageReader → MJPEG → HTTP 8080，
 *   车机用自带浏览器打开 http://192.168.43.1:8080/ 即可看，无需装 APK。
 *   触摸经浏览器 /tap /swipe 回传。
 *
 * - 旧 APK 模式：VirtualDisplay → H264 → 8888，需车机装 CarWithYou。
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
        const val BROWSER_PORT = 8080
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val EXTRA_DEBUG_SAVE = "debug_save"
        const val EXTRA_VIRTUAL_MODE = "virtual"
        const val EXTRA_BROWSER_MODE = "browser"
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
        @Volatile var browserMode = false
        @Volatile var displayDenied = false // 系统拒往虚拟屏放 Activity（部分机型）→ 已降级镜像
        @Volatile var lastHint = ""
        @Volatile var browserUrl = ""
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
    private var browserServer: BrowserCastServer? = null
    private var imageReader: android.media.ImageReader? = null
    private var overlay: CarControlOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectionCallback: MediaProjection.Callback? = null
    // 编码器 outputFormat 里抠出的 SPS/PPS（部分软编码流里不带，新客户端先发这对）
    @Volatile private var csdCache: ByteArray? = null
    @Volatile private var csdCache1: ByteArray? = null
    // live 流里嗅到的第一对 SPS/PPS（outputFormat 没有时的保底）
    @Volatile private var sniffedSps: ByteArray? = null
    @Volatile private var sniffedPps: ByteArray? = null
    /**
     * 通用 Baseline PPS（部分软编码实例流里永远不出现 PPS，不补这 4 字节解码器永不起）。
     * sps_id=0 的标准 Baseline 默认值，与真实 SPS 配对可解；只在嗅不到真 PPS 时用。
     */
    private val GENERIC_PPS = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x38.toByte(), 0x80.toByte())

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
            AppLog.i(TAG, "重新把导航/音乐丢到车机屏：$navPkg + $musicPkg")
            launchAppsOntoVirtual()
            return START_STICKY
        }
        if (running) return START_STICKY
        debugSave = intent?.getBooleanExtra(EXTRA_DEBUG_SAVE, false) == true
        virtualMode = intent?.getBooleanExtra(EXTRA_VIRTUAL_MODE, true) != false
        browserMode = intent?.getBooleanExtra(EXTRA_BROWSER_MODE, false) != false
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
            AppLog.w(TAG, lastHint + "（rc=$resultCode data=${if (projectionData == null) "null" else "ok"}）")
            return START_NOT_STICKY
        }
        AppLog.i(
            TAG, "开始投屏 mode=${if (virtualMode) "虚拟屏" else "镜像"} " +
                "输出=${if (browserMode) "浏览器免安装(:$BROWSER_PORT)" else "车机APK(:$VIDEO_PORT)"} " +
                "导航=$navPkg 音乐=$musicPkg " +
                "录屏授权=${if (resultCode == Activity.RESULT_OK) "有" else "无"} 保存H264=$debugSave 亮屏=$keepScreen"
        )
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
            browserMode && virtualMode -> "车机浏览器打开 http://192.168.43.1:$BROWSER_PORT/"
            browserMode -> "浏览器镜像中（车机免安装）"
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
        displayDenied = false
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
                AppLog.i(TAG, "已开 H264 保存：${dir.absolutePath}/cast.h264")
            } catch (e: Exception) {
                debugOut = null
                AppLog.w(TAG, "建 cast.h264 失败，不录码流：${e.message}")
            }
        }
        scope.launch {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                densityDpi = if (virtualMode) CastConfig.VIRTUAL_DPI else dm.densityDpi

                launch { startControlServer() }
                val ip = NetUtils.hotspotIp(this@ScreenCastService)
                if (browserMode) {
                    val srv = BrowserCastServer(BROWSER_PORT)
                    browserServer = srv
                    srv.start(scope)
                    browserUrl = "http://$ip:$BROWSER_PORT/"
                    AppLog.i(TAG, "免安装模式：车机浏览器打开 $browserUrl")
                } else {
                    videoServer = ServerSocket(VIDEO_PORT)
                    AppLog.i(TAG, "视频端口已监听 $VIDEO_PORT")
                }

                if (browserMode) {
                    if (virtualMode) {
                        runVirtualBrowserSession()
                    } else {
                        val proj = obtainProjection() ?: throw IllegalStateException("镜像需要录屏权限")
                        CastConfig.screenW = dm.widthPixels
                        CastConfig.screenH = dm.heightPixels
                        CastConfig.displayId = Display.DEFAULT_DISPLAY
                        runMirrorBrowserSession(proj, dm.densityDpi)
                    }
                } else if (virtualMode) {
                    runVirtualSession()
                } else {
                    val proj = obtainProjection() ?: throw IllegalStateException("镜像需要录屏权限")
                    CastConfig.screenW = dm.widthPixels
                    CastConfig.screenH = dm.heightPixels
                    CastConfig.displayId = Display.DEFAULT_DISPLAY
                    runMirrorSession(proj, dm.densityDpi)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "startCast failed", e)
                lastHint = "启动失败：${e.message}"
                stopSelf()
            }
        }
    }

    private fun obtainProjection(): MediaProjection? {
        val data = projectionData ?: return null
        if (resultCode != Activity.RESULT_OK) return null
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            AppLog.w(TAG, "getMediaProjection 返回 null（授权 token 过期？要重新点一次允许）")
            return null
        }
        projection = proj
        AppLog.i(TAG, "拿到 MediaProjection")
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.i(TAG, "MediaProjection stopped（系统收了录屏授权）")
                stopSelf()
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, mainHandler)
        return proj
    }

    /**
     * 虚拟屏只建一次，断线重连不重建，避免车上的 App 被杀掉。
     *
     * 机型无关的保底：点投屏后先试虚拟屏，系统拒绝往上放 Activity
     * （Permission Denial，部分机型必现）就当场降级 CarPlay 镜像——
     * 同一次录屏授权，不用用户重点，手机自动弹驾驶舱桌面。
     * 所以黑屏/连不上不再靠机型碰运气，任何手机都有路可走。
     */
    private suspend fun runVirtualSession() {
        val res = virtualRes(CastConfig.currentResShort)
        CastConfig.videoW = res.w
        CastConfig.videoH = res.h
        CastConfig.screenW = res.w
        CastConfig.screenH = res.h
        // projection 只拿一次（单次 token，拿两次抛 SecurityException）。
        // DisplayManager 建屏不消耗它，降级镜像时复用同一个。
        var proj: MediaProjection? = null
        if (projectionData != null && resultCode == Activity.RESULT_OK) {
            startFg(withProjection = true)
            proj = obtainProjection()
        }
        val (enc, surface) = createEncoder(res.w, res.h, CastConfig.currentBitrate)
        encoder = enc
        encoderSurface = surface
        // 先试 DisplayManager（自己的屏，Activity 有机会放上去）。
        // 建不出再走 MediaProjection（部分 ROM 限制副屏）。
        vdisplay = createIndependentDisplay(res.w, res.h, surface)
        if (vdisplay == null && proj != null) {
            try {
                vdisplay = createProjectionDisplay(proj, res.w, res.h, surface)
            } catch (e: Exception) {
                AppLog.w(TAG, "MediaProjection 建屏也失败：${e.message}")
            }
        }
        if (vdisplay != null) {
            val displayId = vdisplay?.display?.displayId ?: Display.INVALID_DISPLAY
            CastConfig.displayId = displayId
            AppLog.i(TAG, "virtual display id=$displayId ${res.w}x${res.h} dpi=$densityDpi")
            sendConfig(res.w, res.h)
            delay(400)
            // 同步试放桌面：被拒（SecurityException）= 此机不支持虚拟屏，直接降级
            if (tryDesktop(displayId)) {
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
                return
            }
            AppLog.w(TAG, "虚拟屏放不进 Activity，自动降级 CarPlay 镜像")
        }
        runMirrorFallback(proj, "此手机不支持独立虚拟屏，已自动切 CarPlay 镜像（驾驶舱在手机上）")
    }

    /** 同步放桌面，返回放没放上去（拒了就降级，不让用户看黑屏） */
    private fun tryDesktop(displayId: Int): Boolean {
        return try {
            startCarDesktop(displayId)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "放桌面被系统拒绝：${e.message}")
            false
        } catch (e: Exception) {
            AppLog.w(TAG, "放桌面失败：${e.message}")
            false
        }
    }

    /** CarPlay 式降级：镜像主屏 + 手机弹驾驶舱桌面，车机 Dock 照用（主屏切应用） */
    private suspend fun runMirrorFallback(proj: MediaProjection?, reason: String) {
        lastHint = reason
        AppLog.i(TAG, reason)
        displayDenied = true
        virtualMode = false
        releaseEncoderAndDisplay()
        try { overlay?.hide() } catch (_: Exception) {}
        overlay = null
        if (proj == null) {
            lastHint = "需要一次录屏授权（录的是投屏画面）"
            sendBroadcast(Intent(ACTION_NEED_PROJECTION).setPackage(packageName))
            stopSelf()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        densityDpi = dm.densityDpi
        CastConfig.screenW = dm.widthPixels
        CastConfig.screenH = dm.heightPixels
        CastConfig.displayId = Display.DEFAULT_DISPLAY
        openCarHome()
        sendBroadcast(Intent(ACTION_VIRTUAL_READY).setPackage(packageName))
        runMirrorSession(proj, dm.densityDpi)
    }

    /** 驾驶舱桌面（手机主屏，普通启动，不挑 Display，台台能开） */
    private fun openCarHome() {
        try {
            startActivity(Intent(this, CarModeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) {
            lastHint = "驾驶舱打不开：${e.message}"
            AppLog.w(TAG, lastHint)
        }
    }

    private suspend fun runMirrorSession(proj: MediaProjection, dpi: Int) {
        // 注意：同一 MediaProjection 实例只能 createVirtualDisplay 一次，
        // 第二次就抛 SecurityException（单次 token），所以镜像只建一次 Display，
        // 只自适应码率（和虚拟屏一致）。分辨率按启动时的档位锁死。
        // 车机断线重连由 pump 内的 accept 循环承接，不重建 Display。
        val res = mirrorRes(CastConfig.currentResShort, CastConfig.screenW, CastConfig.screenH)
        CastConfig.videoW = res.w
        CastConfig.videoH = res.h
        val (enc, surface) = createEncoder(res.w, res.h, CastConfig.currentBitrate)
        encoder = enc
        encoderSurface = surface
        vdisplay = proj.createVirtualDisplay(
            "carwithyou-mirror", res.w, res.h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, mainHandler
        )
        sendConfig(res.w, res.h)
        // 车机断线重连由这里承接：同一编码器/Display 上反复 accept，不重建。
        // allowResize=false：只热切码率（Single-use projection token 禁止二次建屏）。
        while (scope.isActive && running) {
            pumpWithAdapt(enc, CastConfig.currentResShort, allowResize = false)
            if (running) delay(300)
        }
    }

    // ── 免安装模式：VirtualDisplay → ImageReader → MJPEG → 浏览器 ──

    private suspend fun runVirtualBrowserSession() {
        val res = virtualRes(CastConfig.currentResShort)
        CastConfig.videoW = res.w
        CastConfig.videoH = res.h
        CastConfig.screenW = res.w
        CastConfig.screenH = res.h
        val reader = android.media.ImageReader.newInstance(res.w, res.h, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader
        browserServer?.videoW = res.w
        browserServer?.videoH = res.h
        val haveProjection = projectionData != null && resultCode == Activity.RESULT_OK
        if (haveProjection) {
            startFg(withProjection = true)
            val proj = obtainProjection() ?: run {
                sendBroadcast(Intent(ACTION_NEED_PROJECTION).setPackage(packageName))
                stopSelf()
                return
            }
            vdisplay = createProjectionDisplay(proj, res.w, res.h, reader.surface)
        } else {
            vdisplay = createIndependentDisplay(res.w, res.h, reader.surface)
            if (vdisplay == null) {
                lastHint = "系统不允许普通 App 建独立副屏，需要一次录屏授权（录的是虚拟屏，不是手机画面）"
                AppLog.e(TAG, lastHint)
                sendBroadcast(Intent(ACTION_NEED_PROJECTION).setPackage(packageName))
                stopSelf()
                return
            }
        }
        onBrowserDisplayReady(res.w, res.h)
        pumpMjpeg(reader, res.w, res.h)
    }

    private suspend fun runMirrorBrowserSession(proj: MediaProjection, dpi: Int) {
        val res = mirrorRes(CastConfig.currentResShort, CastConfig.screenW, CastConfig.screenH)
        CastConfig.videoW = res.w
        CastConfig.videoH = res.h
        val reader = android.media.ImageReader.newInstance(res.w, res.h, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader
        browserServer?.videoW = res.w
        browserServer?.videoH = res.h
        vdisplay = proj.createVirtualDisplay(
            "carwithyou-mirror", res.w, res.h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )
        AppLog.i(TAG, "浏览器镜像屏 ${res.w}x${res.h}")
        pumpMjpeg(reader, res.w, res.h)
    }

    private fun onBrowserDisplayReady(w: Int, h: Int) {
        val displayId = vdisplay?.display?.displayId ?: Display.INVALID_DISPLAY
        CastConfig.displayId = displayId
        AppLog.i(TAG, "browser virtual display id=$displayId ${w}x${h} dpi=$densityDpi")
        sendConfig(w, h)
        scope.launch { delay(400); mainHandler.post { startCarDesktop(displayId) } }
        sendBroadcast(Intent(ACTION_VIRTUAL_READY).setPackage(packageName))
        mainHandler.postDelayed({ showOverlay() }, 800)
        mainHandler.postDelayed({
            if (CarDesktopActivity.instance == null) {
                lastHint = "系统没把桌面放到副屏，已直接拉起导航/音乐。若它们出现在手机上，请改用镜像模式。"
                launchAppsOntoVirtual()
            }
        }, 1600)
    }

    /** ImageReader → Bitmap → JPEG(70) → BrowserCastServer，约15fps，车机浏览器原生可播 */
    private suspend fun pumpMjpeg(reader: android.media.ImageReader, w: Int, h: Int) {
        val out = java.io.ByteArrayOutputStream(128 * 1024)
        var lastAt = 0L
        AppLog.i(TAG, "MJPEG 推流开始 ${w}x${h} → :$BROWSER_PORT/video")
        while (scope.isActive && running) {
            try {
                val img = reader.acquireLatestImage()
                if (img == null) { delay(33); continue }
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastAt < 66) continue // ~15fps，省电省流量
                    lastAt = now
                    val bmp = imageToBitmap(img, w, h)
                    if (bmp != null) {
                        out.reset()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                        bmp.recycle()
                        val bytes = out.toByteArray()
                        if (bytes.isNotEmpty()) browserServer?.offerFrame(bytes)
                    }
                } finally { try { img.close() } catch (_: Exception) {} }
            } catch (e: Exception) {
                AppLog.wThrottle(TAG, "MJPEG 采集失败：${e.message}", 5_000)
                delay(100)
            }
        }
    }

    private fun imageToBitmap(img: android.media.Image, w: Int, h: Int): android.graphics.Bitmap? {
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPixels = rowStride / pixelStride
            val bmp = android.graphics.Bitmap.createBitmap(rowPixels, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            return if (rowPixels == w) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() }
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "Image→Bitmap 失败：${e.message}", 5_000)
            return null
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
                AppLog.i(TAG, "DisplayManager virtual display flags=$flags id=${vd.display.displayId}")
                return vd
            } catch (e: Exception) {
                AppLog.w(TAG, "DisplayManager flags=$flags rejected: ${e.message}")
            }
        }
        return null
    }

    private fun createProjectionDisplay(proj: MediaProjection, w: Int, h: Int, surface: Surface): VirtualDisplay {
        AppLog.i(TAG, "用 MediaProjection 建虚拟屏 ${w}x${h}@$densityDpi")
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
                AppLog.w(TAG, "projection display flags=$flags rejected: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("createVirtualDisplay failed")
    }

    private fun startCarDesktop(displayId: Int): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        val intent = Intent(this, CarDesktopActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(CarDesktopActivity.EXTRA_NAV, navPkg)
            putExtra(CarDesktopActivity.EXTRA_MUSIC, musicPkg)
            putExtra(CarDesktopActivity.EXTRA_DISPLAY_ID, displayId)
            putExtra(CarDesktopActivity.EXTRA_AUTO_LAUNCH, true)
        }
        val opts = android.app.ActivityOptions.makeBasic()
        opts.launchDisplayId = displayId
        return try {
            startActivity(intent, opts.toBundle())
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "start CarDesktop failed: ${e.message}")
            lastHint = "虚拟桌面启动失败：${e.message}"
            false
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

    /**
     * 建 H264 编码器。三档降级：部分手机/模拟器/车机的编码器会拒掉
     * KEY_LATENCY 或 Baseline + CBR 组合（configure 直接抛 CodecException），
     * 之前是直接“启动失败”，这就是“总是连接不上”的一种。现在逐档重试。
     */
    private fun createEncoder(w: Int, h: Int, bitrate: Int): Pair<MediaCodec, Surface> {
        var lastErr: Exception? = null
        for (variant in 0..2) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, CastConfig.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, CastConfig.IFRAME_INTERVAL)
                if (variant == 0) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                } else if (variant == 1) {
                    // 去低延迟键：部分软编码器拒它
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                // variant 2：最简配置，码率/模式全由芯片默认
            }
            try {
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Exception) {
                    try { enc.release() } catch (_: Exception) {}
                    throw e
                }
                val surface = enc.createInputSurface()
                enc.start()
                // 部分编码器（软编常见）码流里不带 SPS/PPS：从 outputFormat 抠 csd-0/csd-1，
                // 新客户端连上先发这对，再推 live 流，否则车机永远等不到 csd prosecuted 也黑屏。
                try {
                    val of = enc.outputFormat
                    if (of.containsKey("csd-0")) {
                        val bb = of.getByteBuffer("csd-0")
                        csdCache = bb?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    }
                    if (of.containsKey("csd-1")) {
                        val bb = of.getByteBuffer("csd-1")
                        csdCache1 = bb?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    }
                    AppLog.i(TAG, "编码器 csd：sps=${csdCache?.size ?: 0}B pps=${csdCache1?.size ?: 0}B")
                } catch (e: Exception) {
                    AppLog.w(TAG, "取编码器 csd 失败：${e.message}")
                }
                AppLog.i(TAG, "编码器已起[v$variant]：${enc.name} ${w}x${h} @%.1fM ${CastConfig.FPS}fps".format(bitrate / 1_000_000f))
                return enc to surface
            } catch (e: Exception) {
                lastErr = e
                AppLog.w(TAG, "编码器配置[v$variant]被拒（${e.javaClass.simpleName}），降级重试")
            }
        }
        lastHint = "手机编码器起不来（三档配置都被拒），换镜像模式或重启手机再试"
        throw lastErr ?: IllegalStateException("createEncoder failed")
    }

    /** 从输出 NAL 里嗅探 SPS/PPS，各存第一对（够新客户端起解码） */
    private fun sniffCsd(data: ByteArray) {
        val t = avcType(data)
        if (t != 7 && t != 8) return
        if (data.size > 256) return // csd 就几十字节，太大不是它
        try {
            if (t == 7 && sniffedSps == null) {
                sniffedSps = data.copyOf()
                AppLog.i(TAG, "嗅到 SPS ${data.size}B")
            } else if (t == 8 && sniffedPps == null) {
                sniffedPps = data.copyOf()
                AppLog.i(TAG, "嗅到 PPS ${data.size}B")
            }
        } catch (_: Exception) {}
    }

    /** NAL 类型：Annex-B（跳起始码）/裸/AVCC 都认 */
    private fun avcType(nal: ByteArray): Int {
        if (nal.isEmpty()) return 0
        var off = 0
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
            nal[2] == 0.toByte() && nal[3] == 1.toByte()
        ) {
            off = 4
        } else if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
            nal[2] == 1.toByte()
        ) {
            off = 3
        } else if (nal.size >= 5) {
            val len = ((nal[0].toInt() and 0xFF) shl 24) or
                ((nal[1].toInt() and 0xFF) shl 16) or
                ((nal[2].toInt() and 0xFF) shl 8) or
                (nal[3].toInt() and 0xFF)
            if (len == nal.size - 4) off = 4
        }
        if (off >= nal.size) return 0
        return nal[off].toInt() and 0x1F
    }

    private fun sendConfig(w: Int, h: Int) {
        AppLog.i(TAG, "发 CONFIG $w $h（触摸坐标系 ${CastConfig.screenW}x${CastConfig.screenH}）")
        try {
            controlOut?.println("CONFIG $w $h ${CastConfig.screenW} ${CastConfig.screenH}")
            controlOut?.flush()
        } catch (e: Exception) {
            AppLog.w(TAG, "CONFIG 发不出去：${e.message}")
        }
    }

    /**
     * 推流主循环。返回 true = 需要切分辨率（仅镜像模式会重建）。
     * 虚拟屏不允许重建 Display，否则车上的 App 会没。
     */
    private fun pumpWithAdapt(enc: MediaCodec, currentTier: Int, allowResize: Boolean): Boolean {
        var needResize = false
        // 协议要求：收到 STATS 后每 2 秒决策一次。之前是每帧都调，
        // 码率几十毫秒就顶满并误触发分辨率切换，直接把推流干死。
        var lastDecideAt = 0L
        try {
            val client = videoServer?.accept() ?: return false
            AppLog.i(TAG, "车机连上视频端口 $VIDEO_PORT")
            // 新车机连上立刻请一帧 SPS/PPS+IDR，不然它只能收到 P 帧，
            // 解码器等不到 csd 就永远黑屏——看着就是“连接不上”，跟机型无关。
            try {
                enc.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            } catch (e: Exception) {
                AppLog.wThrottle(TAG, "请关键帧失败：${e.message}", 10_000)
            }
            // 先发缓存的 SPS/PPS（码流里没有时全靠这对起解码器），再推 live 流。
            // PPS 嗅不到就用通用 Baseline 默认（有真 SPS 才发，否则跳过）。
            val sps = csdCache ?: sniffedSps
            var pps = csdCache1 ?: sniffedPps
            var ppsGeneric = false
            if (sps != null && pps == null) {
                pps = GENERIC_PPS
                ppsGeneric = true
            }
            client.use { sock ->
                sock.soTimeout = 30_000
                val out = DataOutputStream(sock.getOutputStream())
                if (sps != null && pps != null) {
                    try {
                        out.writeInt(sps.size); out.write(sps); out.flush()
                        out.writeInt(pps.size); out.write(pps); out.flush()
                        AppLog.i(TAG, "已发 csd（sps=${sps.size}B pps=${pps.size}B${if (ppsGeneric) "[通用]" else ""}），车机应秒出画面")
                    } catch (e: Exception) {
                        AppLog.w(TAG, "发 csd 失败：${e.message}")
                        return false
                    }
                }
                val info = MediaCodec.BufferInfo()
                var frames = 0L
                var bytes = 0L
                var lastLogAt = 0L
                val typeCount = LongArray(32)
                val acceptAt = System.currentTimeMillis()
                while (scope.isActive && running && !needResize) {
                    // 控制通道是视频的心跳：车机先连 8889 再连 8888；控制断了视频必死，
                    // 半开连接写不报错时会永远喂黑洞。控制 10 秒没影就掐掉等下一个。
                    if (controlOut == null && System.currentTimeMillis() - acceptAt > 10_000) {
                        AppLog.w(TAG, "视频连上 10 秒了控制还没来，掐掉等车机重连")
                        break
                    }
                    val idx = enc.dequeueOutputBuffer(info, 10_000)
                    if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx) ?: continue
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.get(data)
                        enc.releaseOutputBuffer(idx, false)
                        if (info.size > 0) {
                            frames++
                            bytes += data.size
                            try { typeCount[avcType(data)]++ } catch (_: Exception) {}
                            // 从 live 流里嗅探第一对 SPS/PPS（Annex-B/裸/AVCC 都认），
                            // 给后来连的车机用：没有这对它们永远起不来解码器。
                            if (sniffedSps == null || sniffedPps == null) sniffCsd(data)
                            try {
                                out.writeInt(data.size)
                                out.write(data)
                                out.flush()
                            } catch (e: Exception) {
                                AppLog.w(TAG, "写视频数据断车机：${e.message}")
                                break
                            }
                            try {
                                debugOut?.write(byteArrayOf(0, 0, 0, 1))
                                debugOut?.write(data)
                            } catch (_: Exception) {}
                        }
                    }
                    if (CastConfig.adaptiveEnabled) {
                        val logNow = System.currentTimeMillis()
                        if (logNow - lastLogAt >= 5000) {
                            lastLogAt = logNow
                            val dist = typeCount.mapIndexed { i, c -> if (c > 0) "$i=$c" else null }
                                .filterNotNull().joinToString(",")
                            AppLog.i(TAG, "推流中 frames=$frames ${bytes / 1024}KB idx=$idx types[$dist]")
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastDecideAt < 2000) continue
                        lastDecideAt = now
                        when (CastConfig.decide()) {
                            0 -> {
                                try {
                                    val params = android.os.Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, CastConfig.currentBitrate)
                                    }
                                    enc.setParameters(params)
                                } catch (e: Exception) {
                                    AppLog.wThrottle(TAG, "改码率失败：${e.message}", 10_000)
                                }
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
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "推流循环退出：${e.message ?: e.javaClass.name}", 5_000)
        }
        return needResize
    }

    private fun applyBitrate(enc: MediaCodec) {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, CastConfig.currentBitrate)
            }
            enc.setParameters(params)
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "applyBitrate 失败：${e.message}", 10_000)
        }
    }

    private fun startControlServer() {
        try {
            controlServer = ServerSocket(CONTROL_PORT)
            AppLog.i(TAG, "控端口已监听 $CONTROL_PORT，等车机连")
            while (scope.isActive && running) {
                val client = try {
                    controlServer?.accept() ?: break
                } catch (e: Exception) {
                    AppLog.w(TAG, "控端口 accept 退出：${e.message}")
                    break
                }
                scope.launch {
                    try {
                        client.use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                            val out = PrintWriter(sock.getOutputStream(), true)
                            controlOut = out
                            AppLog.i(TAG, "车机连上控端口 ${sock.inetAddress}:${sock.port}")
                            sendConfig(CastConfig.videoW, CastConfig.videoH)
                            while (isActive && running) {
                                val line = try {
                                    reader.readLine()
                                } catch (e: Exception) {
                                    AppLog.wThrottle(TAG, "控制通道读中断：${e.message}", 5_000)
                                    break
                                } ?: break
                                handleControl(line.trim())
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.wThrottle(TAG, "控制会话异常：${e.message}", 5_000)
                    } finally {
                        if (controlOut != null) controlOut = null
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "建控制端口失败（$CONTROL_PORT 被占？）：${e.message}", e)
            lastHint = "建控制端口失败：${e.message}"
        }
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
            "KEY" -> {
                // 车机 Dock 返回键（虚拟/镜像通用，走无障碍全局返回）
                if (p.getOrNull(1) == "back") {
                    val svc = TouchInjectorService.instance
                    if (svc == null) {
                        lastHint = "没开无障碍，返回键用不了"
                        AppLog.wThrottle(TAG, lastHint, 10_000)
                    } else svc.back()
                }
            }
            "APP" -> {
                // 车机左 Dock 发回来的切应用命令（CarPlay 式）：home/nav/music/split
                val cmd = p.getOrNull(1)?.lowercase().orEmpty()
                mainHandler.post { switchVirtualApp(cmd) }
            }
            "TAP" -> {
                val x = p.getOrNull(1)?.toFloatOrNull() ?: return
                val y = p.getOrNull(2)?.toFloatOrNull() ?: return
                val svc = TouchInjectorService.instance
                if (svc == null) {
                    lastHint = "没开无障碍，车机点了也注入不了"
                    AppLog.wThrottle(TAG, lastHint, 10_000)
                } else svc.tap(x, y)
            }
            "SWIPE" -> {
                val x0 = p.getOrNull(1)?.toFloatOrNull() ?: return
                val y0 = p.getOrNull(2)?.toFloatOrNull() ?: return
                val x1 = p.getOrNull(3)?.toFloatOrNull() ?: return
                val y1 = p.getOrNull(4)?.toFloatOrNull() ?: return
                val dur = p.getOrNull(5)?.toLongOrNull() ?: 300L
                val svc = TouchInjectorService.instance
                if (svc == null) {
                    lastHint = "没开无障碍，车机点了也注入不了"
                    AppLog.wThrottle(TAG, lastHint, 10_000)
                } else svc.swipe(x0, y0, x1, y1, dur)
            }
        }
    }

    /** 车机 Dock 切应用。虚拟屏正常时切副屏；降级/镜像时直接切手机主屏（镜像同步跟过去，台台可用） */
    private fun switchVirtualApp(cmd: String) {
        if (displayDenied || !virtualMode) {
            switchMainDisplayApp(cmd)
            return
        }
        val id = CastConfig.displayId
        if (id == Display.INVALID_DISPLAY || id == Display.DEFAULT_DISPLAY) {
            lastHint = "虚拟屏还没建好，稍后再点"
            AppLog.w(TAG, "APP $cmd 被拒：displayId=$id")
            return
        }
        val w = CastConfig.screenW
        val h = CastConfig.screenH
        when (cmd) {
            "home" -> startCarDesktop(id)
            "nav" -> if (!AppLauncher.launch(this, navPkg, id, AppLauncher.fullBounds(w, h))) {
                lastHint = "导航打不开（$navPkg 装了吗）"
            }
            "music" -> if (!AppLauncher.launch(this, musicPkg, id, AppLauncher.fullBounds(w, h))) {
                lastHint = "音乐打不开（$musicPkg 装了吗）"
            }
            "split" -> launchAppsOntoVirtual()
            else -> return
        }
        AppLog.i(TAG, "车机Dock切应用：$cmd")
    }

    private fun switchMainDisplayApp(cmd: String) {
        when (cmd) {
            "home" -> openCarHome()
            "nav" -> if (!AppLauncher.launch(this, navPkg, Display.DEFAULT_DISPLAY)) {
                lastHint = "导航打不开（$navPkg 装了吗）"
            }
            "music" -> if (!AppLauncher.launch(this, musicPkg, Display.DEFAULT_DISPLAY)) {
                lastHint = "音乐打不开（$musicPkg 装了吗）"
            }
            "split" -> {
                AppLauncher.launch(this, navPkg, Display.DEFAULT_DISPLAY)
                lastHint = "主屏分屏请手动点分屏键，导航已打开"
            }
            else -> return
        }
        AppLog.i(TAG, "车机Dock切主屏应用：$cmd")
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
            AppLog.i(TAG, "已持 wakeLock（${if (keepScreen) "SCREEN_DIM" else "PARTIAL"}）")
        } catch (e: Exception) {
            AppLog.w(TAG, "拿 wakeLock 失败，灭屏可能断投屏：${e.message}")
        }
    }

    private fun releaseEncoderAndDisplay() {
        try { vdisplay?.release() } catch (_: Exception) {}
        vdisplay = null
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        // 编码器换了，旧 SPS/PPS 作废（分辨率可能变了）
        csdCache = null; csdCache1 = null; sniffedSps = null; sniffedPps = null
    }

    override fun onDestroy() {
        AppLog.i(TAG, "ScreenCastService 退出")
        running = false
        try { sendBroadcast(Intent(CarDesktopActivity.ACTION_STOP).setPackage(packageName)) } catch (_: Exception) {}
        try { overlay?.hide() } catch (_: Exception) {}
        overlay = null
        try { videoServer?.close() } catch (_: Exception) {}
        try { controlServer?.close() } catch (_: Exception) {}
        try { browserServer?.stop() } catch (_: Exception) {}
        browserServer = null
        browserUrl = ""
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
