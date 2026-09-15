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
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 整屏镜像推流（8888视频 + 8889控制）：
 * MediaProjection → VirtualDisplay → MediaCodec(H264) → TCP
 * 自适应码率+分辨率（基于车机 STATS 回报）。
 *
 * 码率调整：PARAMETER_KEY_VIDEO_BITRATE 热切换，不重建编码器。
 * 分辨率切换：重建 VirtualDisplay + MediaCodec（编码器必须重建，Android限制）。
 */
class ScreenCastService : Service() {

    companion object {
        const val VIDEO_PORT = 8888
        const val CONTROL_PORT = 8889
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val EXTRA_DEBUG_SAVE = "debug_save"
        @Volatile var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var vdisplay: VirtualDisplay? = null
    private var videoServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null
    private var controlOut: PrintWriter? = null

    private var resultCode = 0
    private var projectionData: Intent? = null
    private var debugSave = false
    private var debugOut: java.io.FileOutputStream? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        } ?: return START_NOT_STICKY
        debugSave = intent.getBooleanExtra(EXTRA_DEBUG_SAVE, false)
        startCast()
        return START_STICKY
    }

    private fun startFg() {
        val chId = "cast"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(chId, "投屏中", NotificationManager.IMPORTANCE_MIN))
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId).setContentTitle("CarWithYou投屏运行中").setSmallIcon(android.R.drawable.ic_media_play).build()
        else @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("CarWithYou投屏运行中").setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(2001, n)
    }

    // ── 分辨率档位：短边→宽高 ─────────────────────
    private data class Res(val w: Int, val h: Int)
    private fun resForTier(short: Int, sw: Int, sh: Int): Res {
        val ratio = sh.toFloat() / sw
        return if (sw < sh) Res(short, (short * ratio).roundToInt()) else Res((short * ratio).roundToInt(), short)
    }

    // ── 主流程 ─────────────────────────────────────
    private fun startCast() {
        running = true
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
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(resultCode, projectionData!!)

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                CastConfig.screenW = dm.widthPixels
                CastConfig.screenH = dm.heightPixels

                // 控制端口（收 STATS / 回 PONG）和视频端口
                launch { startControlServer() }
                videoServer = ServerSocket(VIDEO_PORT)

                // 主循环：支持动态切分辨率（重建编码器+VirtualDisplay）
                var currentTier = CastConfig.currentResShort
                while (isActive && running) {
                    val res = resForTier(currentTier, CastConfig.screenW, CastConfig.screenH)
                    CastConfig.videoW = res.w; CastConfig.videoH = res.h
                    CastConfig.currentResShort = currentTier

                    // 创建编码器+inputSurface
                    val (enc, surface) = createEncoder(res.w, res.h, CastConfig.currentBitrate)
                    encoder = enc

                    // VirtualDisplay 输出到 inputSurface
                    vdisplay = projection!!.createVirtualDisplay(
                        "carwithyou", res.w, res.h, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null
                    )

                    sendConfig(res.w, res.h)
                    val resized = pumpWithAdapt(enc, currentTier)

                    // 释放当前编码器
                    try { vdisplay?.release() } catch (_: Exception) {}
                    try { enc.stop(); enc.release() } catch (_: Exception) {}
                    encoder = null; vdisplay = null

                    if (resized) {
                        currentTier = CastConfig.currentResShort
                        delay(300) // 给车机解码器清空的窗口
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
    }

    /**
     * 创建编码器，返回 (MediaCodec, inputSurface)。
     * createInputSurface 必须在 configure() 之后、start() 之前调用。
     */
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
        val surface = enc.createInputSurface() // configure 后、start 前
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
     * 推流主循环：编码→写TCP，同时收车机 STATS 做自适应。
     * 返回 true = 需要切分辨率（重建编码器）。
     */
    private fun pumpWithAdapt(enc: MediaCodec, currentTier: Int): Boolean {
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
                            // Annex-B 存档（调试用）
                            try {
                                debugOut?.write(byteArrayOf(0, 0, 0, 1))
                                debugOut?.write(data)
                            } catch (_: Exception) {}
                        }
                    }
                    // 自适应决策
                    if (CastConfig.adaptiveEnabled) {
                        when (CastConfig.decide()) {
                            0 -> {
                                // 热切换码率，不重建编码器
                                try {
                                    val params = android.os.Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, CastConfig.currentBitrate)
                                    }
                                    enc.setParameters(params)
                                } catch (_: Exception) {}
                            }
                            1 -> {
                                // 升分辨率
                                val idx = CastConfig.RES_TIERS.indexOf(currentTier)
                                if (idx in 0 until CastConfig.RES_TIERS.lastIndex) {
                                    CastConfig.currentResShort = CastConfig.RES_TIERS[idx + 1]
                                    val range = CastConfig.bitrateRange(CastConfig.currentResShort)
                                    CastConfig.currentBitrate = (range[0] + range[1]) / 2
                                    needResize = true
                                }
                            }
                            -1 -> {
                                // 降分辨率
                                val idx = CastConfig.RES_TIERS.indexOf(currentTier)
                                if (idx > 0) {
                                    CastConfig.currentResShort = CastConfig.RES_TIERS[idx - 1]
                                    val range = CastConfig.bitrateRange(CastConfig.currentResShort)
                                    CastConfig.currentBitrate = (range[0] + range[1]) / 2
                                    needResize = true
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return needResize
    }

    // ── 控制端口：收车机 STATS / PING 回 PONG ─────────
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
                        controlOut = null
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
        }
    }

    override fun onDestroy() {
        running = false
        try { videoServer?.close() } catch (_: Exception) {}
        try { controlServer?.close() } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { debugOut?.flush(); debugOut?.close() } catch (_: Exception) {}
        debugOut = null
        scope.cancel()
        super.onDestroy()
    }
}
