package com.carwithyou.lite

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * H.264 → Surface 的解码器。
 *
 * 从 [StreamReceiverActivity] 里抽出来的，两条链路（手机直连的 8888/8889 与
 * ADB 链路的 shell server）共用同一份解码逻辑 —— 之前那段逻辑在真机上是验过的，
 * 抽的时候刻意不改语义，只把「解码器 + CSD 处理」独立出来。
 *
 * CSD 处理（这是踩过坑的地方，别乱改）：
 *  - 还没起解码器时，先把 SPS(7)/PPS(8) 攒起来，攒齐一对才 configure+start，
 *    并把这对当作 CODEC_CONFIG 喂进去；
 *  - 解码器已经在跑时又收到一对新的 SPS/PPS（手机侧补发真 PPS），
 *    就用真货重启解码器自愈（此前用保底 PPS 可能偏绿）。
 *
 * 线程模型：所有解码器操作都在同一把锁里，谁调都行，但 [feed] 串行。
 */
class H264SurfacePlayer(
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null

    private var width = 0
    private var height = 0

    /** 攒着等凑齐一对的 CSD 字节。 */
    private val csd = mutableListOf<ByteArray>()

    /** 解码器起来时回调（宽, 高, 解码器名）。 */
    var onDecoderUp: ((Int, Int, String) -> Unit)? = null

    /** 出错时回调（人类可读的原因）。 */
    var onError: ((String) -> Unit)? = null

    @Volatile
    var framesFed = 0
        private set

    @Volatile
    var decodeFailures = 0
        private set

    @Volatile
    var lastError = ""
        private set

    val isRunning: Boolean get() = decoder != null

    val decoderName: String get() = decoder?.name ?: ""

    /** Surface 换/没了的时候调。Surface 没了要顺手把解码器收掉。 */
    fun setSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (surface === newSurface) return
            surface = newSurface
            if (newSurface == null) {
                releaseLocked()
            } else {
                // 换 Surface 必须重建解码器，旧的绑在旧 Surface 上
                releaseLocked()
                log("Surface 已就绪，等下一帧 CSD 重建解码器")
            }
        }
    }

    /** 视频头里拿到的尺寸；解码器还没起时才用得上。 */
    fun setSize(w: Int, h: Int) {
        synchronized(lock) {
            if (width == w && height == h) return
            width = w
            height = h
            if (decoder != null) {
                log("视频尺寸变成 ${w}x$h，重建解码器")
                releaseLocked()
            }
        }
    }

    /**
     * 喂一帧。
     *
     * @param payload H.264 数据（Annex-B / 裸 NAL / AVCC 都吃，靠 [nalType] 判）
     * @param config  true = 发送方明确标了这是参数集（ADB 链路的 FRAME_FLAG_CONFIG）
     */
    fun feed(payload: ByteArray, config: Boolean = false) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            // 一个缓冲区里可能夹着多个 NALU：MediaCodec 的 AVC 编码器经常把
            // SPS+PPS 拼在**同一个** CODEC_CONFIG 缓冲区里（server 侧实测 csd-0 19B
            // + csd-1 10B 合成一个 29B 帧）。不拆开就只能看到第一个 NAL，
            // hasPps 永远为 false → 解码器永远起不来 → 每帧被静默丢掉（踩过）。
            val nals = splitNals(payload)
            val hasSps = nals.any { nalType(it) == NAL_SPS }
            val hasPps = nals.any { nalType(it) == NAL_PPS }

            var dec = decoder
            if (dec == null) {
                // 还没起解码器：先把参数集攒齐。有的发送方不标 config，靠 NAL 类型兜底。
                if (config || hasSps || hasPps) absorbCsdLocked(nals)
                if (!hasCompleteCsdLocked()) return
                startLocked(csd.toList())
                dec = decoder ?: return
            } else if (config && hasSps && hasPps) {
                // 运行中收到**显式标注**的新参数集（如手机侧补发真 PPS）：重启自愈。
                // 只在 config 标记时才重启 —— 关键帧里内联的 SPS/PPS 不该触发，
                // 否则每个关键帧都要重启一次解码器。
                absorbCsdLocked(nals)
                log("收到新参数集，重启解码器自愈")
                releaseLocked()
                startLocked(csd.toList())
                dec = decoder ?: return
            }
            feedLocked(dec, payload, config = false)
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
            csd.clear()
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 把参数集（SPS/PPS）逐个收进 csd；同类型的保留最新那个。 */
    private fun absorbCsdLocked(nals: List<ByteArray>) {
        for (nal in nals) {
            val t = nalType(nal)
            if (t == NAL_SPS || t == NAL_PPS) {
                csd.removeAll { nalType(it) == t }
                csd += nal
                if (csd.size > 8) csd.removeAt(0)
            }
        }
    }

    private fun hasCompleteCsdLocked(): Boolean {
        var sps = false
        var pps = false
        for (n in csd) {
            when (nalType(n)) {
                NAL_SPS -> sps = true
                NAL_PPS -> pps = true
            }
        }
        return sps && pps
    }

    /**
     * 按 Annex-B 起始码把缓冲区拆成单个 NALU；每段都带上自己的起始码
     * （MediaCodec 直接吃 Annex-B）。没有起始码就原样返回一段。
     */
    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>(4)
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)
        val out = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (to > from) out += data.copyOfRange(from, to)
        }
        return if (out.isEmpty()) listOf(data) else out
    }

    private fun startLocked(initial: List<ByteArray>) {
        val s = surface
        if (s == null || !s.isValid) {
            lastError = "Surface 还没就绪，解不了码"
            log(lastError)
            return
        }
        try {
            val w = if (width > 0) width else 720
            val h = if (height > 0) height else 1280
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, s, null, 0)
            dec.start()
            decoder = dec
            csd.clear()
            initial.forEach { feedLocked(dec, it, config = true) }
            log("解码器已起：${dec.name} ${w}x${h}，csd=${initial.size}")
            onDecoderUp?.invoke(w, h, dec.name)
        } catch (e: Exception) {
            lastError = "解码器起不来：${e.message ?: e.javaClass.name}"
            log(lastError)
            onError?.invoke(lastError)
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
        }
    }

    private fun releaseLocked() {
        val dec = decoder ?: return
        decoder = null
        try {
            dec.stop()
        } catch (e: Exception) {
            log("解码器 stop 报错：${e.message}")
        }
        try {
            dec.release()
        } catch (e: Exception) {
            log("解码器 release 报错：${e.message}")
        }
    }

    private fun feedLocked(dec: MediaCodec, payload: ByteArray, config: Boolean) {
        try {
            val index = dec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = dec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(payload)
                val flag = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                dec.queueInputBuffer(index, 0, payload.size, System.nanoTime() / 1000, flag)
            }
            val info = MediaCodec.BufferInfo()
            var outIndex = dec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                dec.releaseOutputBuffer(outIndex, true)
                outIndex = dec.dequeueOutputBuffer(info, 0)
            }
            framesFed++
        } catch (e: Exception) {
            decodeFailures++
            if (decodeFailures == 1 || decodeFailures % 30 == 0) {
                log("喂帧失败（累计 $decodeFailures 次）：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 取 NAL 类型，兼容三种封装（不同手机编码器输出不一样，跟机型无关都要认）：
     * Annex-B（00 00 00 01 xx / 00 00 01 xx）、裸 NAL（首字节即头）、AVCC（4 字节长度前缀）。
     * 只认首字节的话，Annex-B 流的 0x00 会被当成 type 0，解码器永远起不来。
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
            val len = ((nal[0].toInt() and 0xFF) shl 24) or
                ((nal[1].toInt() and 0xFF) shl 16) or
                ((nal[2].toInt() and 0xFF) shl 8) or
                (nal[3].toInt() and 0xFF)
            if (len == nal.size - 4) off = 4
        }
        if (off >= nal.size) return 0
        return nal[off].toInt() and 0x1F
    }

    companion object {
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
    }
}
