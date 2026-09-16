package com.carwithyou.lite

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.lite.ui.StreamScreen
import com.carwithyou.lite.ui.theme.CarWithYouTheme
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * 车机收流（协议 v1.3，见 docs/PROTOCOL.md）：
 * 连手机 8888 看画面，8889 走控制（CONFIG/触摸/心跳/STATS）。
 * 画面仍用 SurfaceView 解码，外壳是 Compose。
 */
class StreamReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var decoder: MediaCodec? = null
    private var touchOut: PrintWriter? = null
    private var surfaceView: SurfaceView? = null

    @Volatile private var wantConnect = false
    @Volatile private var connected = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var lastPong = 0L

    private var downNx = 0f; private var downNy = 0f; private var downT = 0L; private var downValid = false
    @Volatile private var pendingAppCmd = ""
    @Volatile private var feedErrors = 0
    @Volatile private var touchSendErrors = 0


    @Volatile private var nalFed = 0
    @Volatile private var nalExpected = 0
    @Volatile private var totalDecodeNs = 0L
    @Volatile private var totalBytes = 0L
    private var lastPingTs = 0L

    private var ip by mutableStateOf("192.168.43.1")
    private var status by mutableStateOf("未连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.i(TAG, "进收流页：解码走 SurfaceView，控制走 8889")
        ReceiverKeepService.start(this)
        // CarPlay 式直达：带 APP_CMD 进来，连上控制通道就自动发一次
        pendingAppCmd = intent.getStringExtra(EXTRA_APP_CMD).orEmpty()
        ip = intent.getStringExtra(EXTRA_IP)?.takeIf { it.isNotBlank() }
            ?: SettingsStore(this).phoneIp.ifBlank { "192.168.43.1" }
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        setContent {
            CarWithYouTheme {
                StreamScreen(
                    ip = ip,
                    status = status,
                    onIpChange = { ip = it },
                    onConnect = { startLoop() },
                    onDisconnect = { stopLoop() },
                    onCopyLog = { Diagnostics.copy(this@StreamReceiverActivity) },
                    onSurface = { attachSurface(it) },
                    onAppCmd = { sendApp(it) }
                )
            }
        }
        if (auto && !wantConnect) {
            AppLog.i(TAG, "自动连接手机 $ip")
            // 等 Surface 就绪再连：SurfaceView 创建后 attachSurface 回调，延迟 500ms 起连
            window.decorView.postDelayed({ startLoop() }, 500)
        }
    }

    private fun attachSurface(view: SurfaceView) {
        if (surfaceView === view) return
        surfaceView?.holder?.removeCallback(this)
        surfaceView = view
        view.holder.addCallback(this)
        view.setOnTouchListener { v, e ->
            if (!connected) return@setOnTouchListener false
            val vw = videoW.toFloat(); val vh = videoH.toFloat()
            val mapped = if (vw > 0 && vh > 0) {
                CastProtocol.mapToVideo(e.x, e.y, v.width.toFloat(), v.height.toFloat(), vw, vh)
            } else {
                (e.x / v.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) to
                    (e.y / v.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            } ?: return@setOnTouchListener true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downNx = mapped.first; downNy = mapped.second
                    downT = System.currentTimeMillis(); downValid = true
                }
                MotionEvent.ACTION_UP -> {
                    if (!downValid) return@setOnTouchListener true; downValid = false
                    val dur = System.currentTimeMillis() - downT
                    val dist = abs(mapped.first - downNx) + abs(mapped.second - downNy)
                    val line = if (dist < 0.03 && dur < 500) "TAP ${mapped.first} ${mapped.second}"
                    else "SWIPE $downNx $downNy ${mapped.first} ${mapped.second} $dur"
                    scope.launch {
                        try {
                            touchOut?.println(line)
                            touchOut?.flush()
                        } catch (e: Exception) {
                            touchSendErrors++
                            AppLog.wThrottle(
                                TAG,
                                "触摸发不出去（累计 $touchSendErrors 次）：${e.message}，看看手机那边无障碍开了没",
                                5_000,
                                key = "touchSend"
                            )
                        }
                    }
                }
            }
            true
        }
    }

    private fun startLoop() {
        if (wantConnect) return
        wantConnect = true
        val target = ip.trim().ifBlank { "192.168.43.1" }
        targetIp = target
        try { SettingsStore(this).phoneIp = target } catch (_: Exception) {}
        AppLog.i(TAG, "开始连接手机 $target（${CastProtocol.CONTROL_PORT}/${CastProtocol.VIDEO_PORT}）")
        loopJob?.cancel()
        var attempt = 0
        loopJob = scope.launch {
            while (wantConnect && isActive) {
                try {
                    setStatus("连接 $target …（第${attempt + 1}次）")
                    runSession(target)
                    attempt = 0
                } catch (e: Exception) {
                    if (!wantConnect) break
                    val wait = CastProtocol.backoff(attempt++)
                    lastError = "连不上 $target：${e.message ?: e.javaClass.name}"
                    AppLog.wThrottle(TAG, "$lastError（${wait / 1000}s 后重连）", 3_000)
                    setStatus("连不上/${e.message}，${wait / 1000}s后重连…")
                    delay(wait)
                }
            }
            withContext(Dispatchers.Main) { disconnectUI() }
        }
    }

    private suspend fun runSession(ip: String) {
        val ctrl = Socket()
        ctrl.connect(InetSocketAddress(ip, CastProtocol.CONTROL_PORT), 5000)
        val video = Socket()
        try {
            video.connect(InetSocketAddress(ip, CastProtocol.VIDEO_PORT), 5000)
        } catch (e: Exception) {
            try { ctrl.close() } catch (_: Exception) {}
            throw e
        }
        try {
            ctrl.use { cs ->
                video.use { vs ->
                    touchOut = PrintWriter(cs.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(cs.getInputStream()))
                    lastPong = System.currentTimeMillis()
                    connected = true
                    AppLog.i(TAG, "控制/视频通道已连上 $ip")
                    setStatus("已连接，等画面…")
                    if (pendingAppCmd.isNotBlank()) {
                        val cmd = pendingAppCmd
                        pendingAppCmd = ""
                        scope.launch {
                            delay(800)
                            try {
                                touchOut?.println("APP $cmd")
                                touchOut?.flush()
                                AppLog.i(TAG, "直达命令已发：$cmd")
                            } catch (_: Exception) {}
                        }
                    }

                    val hb = scope.launch {
                        while (isActive && wantConnect && connected) {
                            delay(CastProtocol.HB_INTERVAL_MS)
                            lastPingTs = System.currentTimeMillis()
                            try {
                                touchOut?.println("PING $lastPingTs")
                                touchOut?.flush()
                            } catch (_: Exception) { break }
                            if (System.currentTimeMillis() - lastPong > CastProtocol.HB_TIMEOUT_MS) {
                                lastError = "心跳超时（${CastProtocol.HB_TIMEOUT_MS}ms 没收到 PONG），主动断开"
                                AppLog.w(TAG, lastError)
                                try { vs.close() } catch (_: Exception) {}
                                try { cs.close() } catch (_: Exception) {}
                                break
                            }
                        }
                    }
                    val rd = scope.launch {
                        while (isActive && wantConnect) {
                            val line = try {
                                reader.readLine()
                            } catch (e: Exception) {
                                AppLog.wThrottle(TAG, "控制通道读取中断：${e.message}", 5_000)
                                break
                            } ?: break
                            onControlLine(line.trim())
                        }
                    }
                    val statsJob = scope.launch {
                        while (isActive && wantConnect && connected) {
                            delay(2000)
                            sendStats()
                            resetStats()
                        }
                    }
                    try {
                        pumpVideo(vs)
                    } finally {
                        hb.cancel(); rd.cancel(); statsJob.cancel()
                    }
                }
            }
        } finally {
            connected = false; touchOut = null
            withContext(Dispatchers.Main) { releaseDecoder() }
        }
        if (wantConnect) {
            AppLog.w(TAG, "会话结束：手机 $ip 连接中断")
            throw RuntimeException("连接中断")
        }
    }

    private fun resetStats() {
        nalFed = 0; nalExpected = EXPECTED_FPS * 2; totalDecodeNs = 0L; totalBytes = 0L
    }

    private fun sendStats() {
        val dropRatio = if (nalExpected > 0) {
            (1f - nalFed.toFloat() / nalExpected).coerceIn(0f, 1f)
        } else 0f
        val avgDecodeMs = if (nalFed > 0) totalDecodeNs.toFloat() / nalFed / 1_000_000f else 0f
        val bitrate = if (totalBytes > 0) totalBytes * 8 / 2 else 0L
        val rtt = if (lastPingTs > 0) System.currentTimeMillis() - lastPingTs else 0L
        try {
            touchOut?.println("STATS %.3f %.1f %d %d".format(dropRatio, avgDecodeMs, bitrate, rtt))
            touchOut?.flush()
        } catch (_: Exception) {}
    }

    private fun onControlLine(line: String) {
        val p = line.split(" ")
        when (p.getOrNull(0)) {
            "CONFIG" -> {
                videoW = p.getOrNull(1)?.toIntOrNull() ?: 0
                videoH = p.getOrNull(2)?.toIntOrNull() ?: 0
                lastW = videoW; lastH = videoH
                AppLog.i(TAG, "收到 CONFIG ${videoW}x${videoH}（手机屏 ${p.getOrNull(3)}x${p.getOrNull(4)}），重建解码器")
                scope.launch { withContext(Dispatchers.Main) { restartDecoder() } }
            }
            "PONG" -> {
                lastPong = System.currentTimeMillis()
                if (lastPingTs > 0) {
                    CastProtocol.lastRtt = System.currentTimeMillis() - lastPingTs
                }
            }
            "PING" -> scope.launch {
                try {
                    touchOut?.println("PONG ${p.getOrNull(1) ?: ""}")
                    touchOut?.flush()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun pumpVideo(s: Socket) {
        val `in` = DataInputStream(s.getInputStream())
        val csd0 = mutableListOf<ByteArray>()
        var nals = 0
        while (scope.isActive && wantConnect && connected) {
            val len = try {
                `in`.readInt()
            } catch (e: Exception) {
                AppLog.w(TAG, "视频通道断了：${e.message ?: e.javaClass.name}")
                break
            }
            if (len <= 0 || len > 2_000_000) {
                lastError = "码流长度异常 $len，断开重连"
                AppLog.e(TAG, lastError)
                break
            }
            val nal = ByteArray(len)
            `in`.readFully(nal)
            totalBytes += len + 4
            if (decoder == null && nals < 3) {
                AppLog.i(TAG, "收流首包#$nals len=$len head=${nal.take(8).joinToString("") { "%02x".format(it) }}")
            }
            nals++

            var dec = decoder
            if (dec == null) {
                val type = nalType(nal)
                if (type == 7 || type == 8) csd0 += nal
                if (csd0.size >= 2) {
                    withContext(Dispatchers.Main) { startDecoder(csd0) }
                    var waits = 0
                    while (decoder == null && waits++ < 50) delay(100)
                    dec = decoder
                    if (dec == null) {
                        lastError = "等了 5s 解码器还没起来（看 startDecoder 的报错）"
                        AppLog.eThrottle(TAG, lastError, null, 10_000)
                    }
                }
                continue
            }
            val t0 = System.nanoTime()
            feedNal(dec, nal)
            totalDecodeNs += System.nanoTime() - t0
            nalFed++
        }
    }

    /**
     * 取 NAL 类型，兼容三种封装（不同手机编码器输出不一样，跟机型无关都要认）：
     * Annex-B（00 00 00 01 xx / 00 00 01 xx）、裸 NAL（首字节即头）、AVCC（4 字节长度前缀）。
     * 之前只认首字节，Annex-B 流的 0x00 被当成 type 0，解码器永远起不来——黑屏即“连不上”。
     */
    private fun nalType(nal: ByteArray): Int {
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
            // AVCC：前 4 字节是大端长度，长度对得上就是它
            val len = ((nal[0].toInt() and 0xFF) shl 24) or
                ((nal[1].toInt() and 0xFF) shl 16) or
                ((nal[2].toInt() and 0xFF) shl 8) or
                (nal[3].toInt() and 0xFF)
            if (len == nal.size - 4) off = 4
        }
        if (off >= nal.size) return 0
        return nal[off].toInt() and 0x1F
    }

    private fun startDecoder(csd: List<ByteArray>) {
        val surface = surfaceView?.holder?.surface
        if (surface == null || !surface.isValid) {
            lastError = "Surface 还没就绪，解不了码"
            AppLog.eThrottle(TAG, lastError, null, 5_000)
            return
        }
        try {
            val w = if (videoW > 0) videoW else 720
            val h = if (videoH > 0) videoH else 1280
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(fmt, surface, null, 0)
            dec.start()
            decoder = dec
            decoderUp = true
            csd.forEach { feedNal(dec, it, config = true) }
            AppLog.i(TAG, "解码器已起：${dec.name} ${w}x${h}，csd=${csd.size}")
            status = "画面来了 ${w}×${h}，点屏幕可反控"
            statusText = status
        } catch (e: Exception) {
            lastError = "解码器起不来：${e.message ?: e.javaClass.name}"
            AppLog.e(TAG, lastError, e)
            toast(lastError)
        }
    }

    private fun restartDecoder() {
        releaseDecoder()
        decoder = null
    }

    private fun feedNal(dec: MediaCodec?, nal: ByteArray, config: Boolean = false) {
        if (dec == null) return
        try {
            val idx = dec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val b = dec.getInputBuffer(idx)!!
                b.clear(); b.put(nal)
                val flag = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                dec.queueInputBuffer(idx, 0, nal.size, System.nanoTime() / 1000, flag)
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = dec.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                dec.releaseOutputBuffer(outIdx, true)
                outIdx = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            // 每帧都可能报错，节流记，否则一秒刷 30 条
            feedErrors++
            AppLog.eThrottle(
                TAG,
                "喂帧失败（累计 $feedErrors 次）：${e.message ?: e.javaClass.simpleName}",
                e,
                10_000,
                key = "feedNal"
            )
        }
    }

    private fun stopLoop() {
        wantConnect = false; loopJob?.cancel(); disconnectUI()
    }

    /** 左 Dock 发回手机切应用（CarPlay 式），走 8889 控制通道；返回键走 KEY */
    private fun sendApp(cmd: String) {
        scope.launch {
            try {
                touchOut?.println(if (cmd == "back") "KEY back" else "APP $cmd")
                touchOut?.flush()
                AppLog.i(TAG, "Dock命令：$cmd")
            } catch (e: Exception) {
                AppLog.wThrottle(TAG, "Dock命令发不出去：${e.message}", 5_000)
            }
        }
    }
    private fun releaseDecoder() {
        try { decoder?.stop(); decoder?.release() } catch (e: Exception) {
            AppLog.wThrottle(TAG, "释放解码器报错：${e.message}", 5_000)
        }
        decoder = null
        decoderUp = false
    }
    private fun disconnectUI() {
        connected = false; status = "未连接"; statusText = "未连接"
    }
    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) {
        status = s
        statusText = s
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}
    override fun onDestroy() { stopLoop(); scope.cancel(); super.onDestroy() }
    private fun toast(s: String) {
        lastError = s
        AppLog.w(TAG, s)
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_IP = "phone_ip"
        const val EXTRA_AUTO = "auto_connect"
        const val EXTRA_APP_CMD = "app_cmd"
        private const val EXPECTED_FPS = 30
        private const val TAG = "CarWithYou"

        // 以下给诊断日志当「现场」（LiteApp.stateText 读这里），所以放静态
        @Volatile var statusText: String = "未连接"
        @Volatile var targetIp: String = ""
        @Volatile var lastW: Int = 0
        @Volatile var lastH: Int = 0
        @Volatile var decoderUp: Boolean = false
        @Volatile var lastError: String = ""
    }
}
