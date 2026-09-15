package com.carwithyou.sender

/** 手机端共享常量 + 自适应分级。改值前先改 docs/PROTOCOL.md */
object CastConfig {
    const val VIDEO_PORT = 8888
    const val CONTROL_PORT = 8889
    const val FPS = 30
    const val IFRAME_INTERVAL = 1 // 秒
    const val HB_INTERVAL_MS = 5_000L

    // ── 分辨率档位（短边） ───────────────────────────
    val RES_TIERS = intArrayOf(480, 720, 1080)

    // ── 每档码率上下限 ───────────────────────────────
    // {minBitrate, maxBitrate}
    val BITRATE_RANGE_480  = intArrayOf(800_000,  2_000_000)
    val BITRATE_RANGE_720  = intArrayOf(1_500_000, 4_000_000)
    val BITRATE_RANGE_1080 = intArrayOf(3_000_000, 8_000_000)

    fun bitrateRange(resShort: Int): IntArray = when {
        resShort >= 1080 -> BITRATE_RANGE_1080
        resShort >= 720  -> BITRATE_RANGE_720
        else             -> BITRATE_RANGE_480
    }

    // ── 运行时状态（线程安全，主推流线程+控制线程共享） ──
    @Volatile var adaptiveEnabled = true   // UI 开关
    @Volatile var videoW = 0               // 当前编码宽
    @Volatile var videoH = 0               // 当前编码高
    @Volatile var screenW = 0              // 手机物理屏宽
    @Volatile var screenH = 0              // 手机物理屏高
    @Volatile var currentBitrate = 2_000_000
    @Volatile var currentResShort = 720

    // 车机回报的最近一次统计（手机据此决策）
    @Volatile var lastDropRatio = 0f       // 0~1，丢帧率
    @Volatile var lastDecodeMs = 0f        // 平均解码耗时(ms)
    @Volatile var lastBitrate = 0L         // 车机实测码率(bytes/s)
    @Volatile var lastLatencyMs = 0L       // PING→PONG RTT

    // ── 自适应决策（每收到 STATS 调一次） ─────────────
    /** 返回新码率，0 表示不变，-1 表示需要降分辨率，1 表示升分辨率 */
    fun decide(): Int {
        if (!adaptiveEnabled) return 0
        val drop = lastDropRatio
        val decMs = lastDecodeMs
        val range = bitrateRange(currentResShort)
        val minB = range[0]; val maxB = range[1]

        return when {
            // 丢帧 > 10% 或解码 > 30fps 一帧时间的 1.5 倍 → 降档
            drop > 0.10f || decMs > (1000f / FPS * 1.5f) -> {
                if (currentResShort > RES_TIERS.first()) -1 else {
                    // 已经最低档了，只能降码率
                    val newB = (currentBitrate * 0.7f).toInt().coerceAtLeast(minB)
                    if (newB < currentBitrate) { currentBitrate = newB; 0 } else -1
                }
            }
            // 丢帧 < 2% 且有余量 → 尝试升码率/分辨率
            drop < 0.02f && lastLatencyMs < 80 -> {
                if (currentBitrate < maxB) {
                    currentBitrate = (currentBitrate * 1.2f).toInt().coerceAtMost(maxB)
                    0 // 码率变了，返回0=只调码率
                } else if (currentResShort < RES_TIERS.last()) {
                    1 // 码率已满档，升分辨率
                } else 0
            }
            // 丢帧 2%~10% → 维持
            else -> 0
        }
    }
}
