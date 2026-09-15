package com.carwithyou.lite

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.carwithyou.lite.databinding.ActivityStreamBinding
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * 车机收流（协议 v1.2，见 docs/PROTOCOL.md）：
 * 连手机 8888 看画面，8889 走控制（CONFIG/触摸/心跳/STATS）。
 * 每 2 秒上报 STATS（丢帧率/解码耗时/实测码率/RTT），手机据此自适应。
 */
class StreamReceiverActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityStreamBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var decoder: MediaCodec? = null
    private var touchOut: PrintWriter? = null

    @Volatile private var wantConnect = false
    @Volatile private var connected = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var lastPong = 0L

    private var downNx = 0f; private var downNy = 0f; private var downT = 0L; private var downValid = false

    // ── 自适应统计（2秒窗口） ────────────────────────
    @Volatile private var nalFed = 0            // 2秒内成功 feedNal 的次数（≈帧数）
    @Volatile private var nalExpected = 0       // 2秒内期望帧数（=fps*2）
    @Volatile private var totalDecodeNs = 0L    // 解码总耗时(ns)
    @Volatile private var totalBytes = 0L       // 收到的字节数（含4字节长度头）
    private var lastPingTs = 0L       // 最近一次 PING 发送时间

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.etIp.setText("192.168.43.1")
        binding.surface.holder.addCallback(this)
        ReceiverKeepService.start(this)

        binding.surface.setOnTouchListener { v, e ->
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
                    scope.launch { try { touchOut?.println(line); touchOut?.flush() } catch (_: Exception) {} }
                }
            }
            true
        }
        binding.btnConnect.setOnClickListener { startLoop() }
        binding.btnDisconnect.setOnClickListener { stopLoop() }
    }

    private fun startLoop() {
        if (wantConnect) return
        wantConnect = true
        val ip = binding.etIp.text.toString().trim()
        loopJob?.cancel()
        var attempt = 0
        loopJob = scope.launch {
            while (wantConnect && isActive) {
                try {
                    setStatus("连接 $ip …（第${attempt + 1}次）")
                    runSession(ip)
                    attempt = 0
                } catch (e: Exception) {
                    if (!wantConnect) break
                    val wait = CastProtocol.backoff(attempt++)
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
                    setStatus("已连接，等画面…")

                    // 心跳（5s）
                    val hb = scope.launch {
                        while (isActive && wantConnect && connected) {
                            delay(CastProtocol.HB_INTERVAL_MS)
                            lastPingTs = System.currentTimeMillis()
                            try {
                                touchOut?.println("PING $lastPingTs")
                                touchOut?.flush()
                            } catch (_: Exception) { break }
                            if (System.currentTimeMillis() - lastPong > CastProtocol.HB_TIMEOUT_MS) {
                                try { vs.close() } catch (_: Exception) {}
                                try { cs.close() } catch (_: Exception) {}
                                break
                            }
                        }
                    }
                    // 控制行读取
                    val rd = scope.launch {
                        while (isActive && wantConnect) {
                            val line = try { reader.readLine() } catch (_: Exception) { break } ?: break
                            onControlLine(line.trim())
                        }
                    }
                    // STATS 上报（每 2 秒）
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
        if (wantConnect) throw RuntimeException("连接中断")
    }

    // ── 统计 ──────────────────────────────────────
    private fun resetStats() {
        nalFed = 0; nalExpected = EXPECTED_FPS * 2; totalDecodeNs = 0L; totalBytes = 0L
    }

    private fun sendStats() {
        val dropRatio = if (nalExpected > 0) {
            (1f - nalFed.toFloat() / nalExpected).coerceIn(0f, 1f)
        } else 0f
        val avgDecodeMs = if (nalFed > 0) totalDecodeNs.toFloat() / nalFed / 1_000_000f else 0f
        val bitrate = if (totalBytes > 0) totalBytes * 8 / 2 else 0L  // bits/s (2秒窗口)
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
                // 分辨率变了 → 重建解码器
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
        while (scope.isActive && wantConnect && connected) {
            val len = try { `in`.readInt() } catch (_: Exception) { break }
            if (len <= 0 || len > 2_000_000) break
            val nal = ByteArray(len)
            `in`.readFully(nal)
            totalBytes += len + 4

            var dec = decoder
            if (dec == null) {
                // 等 SPS/PPS 齐了再起解码器
                val type = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else 0
                if (type == 7 || type == 8) csd0 += nal
                if (csd0.size >= 2) {
                    withContext(Dispatchers.Main) { startDecoder(csd0) }
                    var waits = 0
                    while (decoder == null && waits++ < 50) delay(100)
                    dec = decoder
                }
                continue
            }
            // 有解码器了，喂 NAL 并计时
            val t0 = System.nanoTime()
            feedNal(dec, nal)
            totalDecodeNs += System.nanoTime() - t0
            nalFed++
        }
    }

    private fun startDecoder(csd: List<ByteArray>) {
        try {
            val w = if (videoW > 0) videoW else 720
            val h = if (videoH > 0) videoH else 1280
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(fmt, binding.surface.holder.surface, null, 0)
            dec.start()
            decoder = dec
            csd.forEach { feedNal(dec, it, config = true) }
            runOnUiThread { binding.tvStatus.text = "画面来了 ${w}×${h}，点屏幕可反控" }
        } catch (e: Exception) {
            toast("解码器起不来：${e.message}")
        }
    }

    private fun restartDecoder() {
        releaseDecoder()
        decoder = null
        // 下次 pumpVideo 会通过 csd0 缓存自动重建
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
        } catch (_: Exception) {}
    }

    private fun stopLoop() {
        wantConnect = false; loopJob?.cancel(); disconnectUI()
    }
    private fun releaseDecoder() {
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
    }
    private fun disconnectUI() {
        connected = false; binding.tvStatus.text = "未连接"
    }
    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) {
        binding.tvStatus.text = s
    }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}
    override fun onDestroy() { stopLoop(); scope.cancel(); super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXPECTED_FPS = 30
    }
}
