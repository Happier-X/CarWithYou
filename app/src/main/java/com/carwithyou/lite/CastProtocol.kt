package com.carwithyou.lite

/** 车机端协议常量 + 触摸映射 + 运行时统计。改值前先改 docs/PROTOCOL.md */
object CastProtocol {
    const val VIDEO_PORT = 8888
    const val CONTROL_PORT = 8889
    const val HB_INTERVAL_MS = 5_000L
    const val HB_TIMEOUT_MS = 15_000L
    private val BACKOFF = longArrayOf(1000, 2000, 4000, 8000, 15000)
    fun backoff(attempt: Int): Long = BACKOFF[attempt.coerceIn(0, BACKOFF.lastIndex)]

    // 车机端 PING→PONG 实时 RTT（手机读不到，只在 STATS 里发）
    @Volatile var lastRtt = 0L

    fun mapToVideo(
        x: Float, y: Float, viewW: Float, viewH: Float, vw: Float, vh: Float
    ): Pair<Float, Float>? {
        if (vw <= 0 || vh <= 0 || viewW <= 0 || viewH <= 0) return null
        val s = minOf(viewW / vw, viewH / vh)
        val cw = vw * s; val ch = vh * s
        val ox = (viewW - cw) / 2f; val oy = (viewH - ch) / 2f
        if (x < ox || x > ox + cw || y < oy || y > oy + ch) return null
        return ((x - ox) / cw).coerceIn(0f, 1f) to ((y - oy) / ch).coerceIn(0f, 1f)
    }
}
