package com.carwithyou.lite

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 结构化车联客户端：连手机 8890，收 MUSIC / PHONE，快照直接刷桌面原生卡片。
 * 发 MUSIC_CMD / PHONE_CMD 回手机执行（切歌/接挂）。
 * 视频投屏（8888）不动：导航画面仍走视频，音乐/电话走这条数据链。
 */
object LinkClient {

    const val PORT = 8890
    private const val TAG = "CarWithYou"

    interface Listener {
        fun onConn(ok: Boolean)
        fun onMusic(title: String, artist: String, playing: Boolean)
        fun onPhone(state: String, number: String, name: String)
        fun onStatus(batt: Int, charging: Boolean, sig: Int)
    }

    @Volatile var listener: Listener? = null
    @Volatile var connected = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var want = false
    @Volatile private var out: PrintWriter? = null
    private val writeLock = Any()

    fun connect(ip: String) {
        disconnect()
        want = true
        val target = ip.trim().ifBlank { "192.168.43.1" }
        job = scope.launch {
            var attempt = 0
            while (want && isActive) {
                try {
                    runSession(target)
                    attempt = 0
                } catch (e: Exception) {
                    if (!want) break
                    AppLog.wThrottle(
                        "Link", "车联连不上 $target：${e.message}，${CastProtocol.backoff(attempt++) / 1000}s后重连",
                        3_000
                    )
                    delay(CastProtocol.backoff(attempt.coerceAtMost(4)))
                }
            }
        }
    }

    fun disconnect() {
        want = false
        job?.cancel()
        job = null
        synchronized(writeLock) {
            try { out?.close() } catch (_: Exception) {}
            out = null
        }
        if (connected) {
            connected = false
            try { listener?.onConn(false) } catch (_: Exception) {}
        }
    }

    fun musicCmd(cmd: String) = send(JSONObject().put("t", "MUSIC_CMD").put("cmd", cmd))

    fun phoneCmd(cmd: String) = send(JSONObject().put("t", "PHONE_CMD").put("cmd", cmd))

    private fun send(o: JSONObject) {
        synchronized(writeLock) {
            try {
                out?.println(o.toString())
                out?.flush()
            } catch (e: Exception) {
                AppLog.wThrottle("Link", "车联命令发不出去：${e.message}", 5_000)
            }
        }
    }

    private suspend fun runSession(ip: String) {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(ip, PORT), 5000)
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        }
        try {
            sock.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)
                synchronized(writeLock) { out = writer }
                connected = true
                AppLog.i("Link", "车联已连上 $ip:$PORT")
                try { listener?.onConn(true) } catch (_: Exception) {}
                // 链路心跳：部分网络（模拟器 NAT、某些车机热点）会掐 5 秒无流量的空闲 TCP。
                // 每 5 秒 PING 一次，手机回 PONG，双向都有流量就不断。
                val hb = scope.launch {
                    while (isActive && want) {
                        try { delay(5000) } catch (_: Exception) { break }
                        if (!want) break
                        try {
                            send(JSONObject().put("t", "PING").put("ts", System.currentTimeMillis()))
                        } catch (e: Exception) {
                            AppLog.w(TAG, "心跳发送失败：${e.message}")
                            break
                        }
                    }
                }
                try {
                    writer.println(JSONObject().put("t", "PING").put("ts", System.currentTimeMillis()).toString())
                    writer.flush()
                } catch (_: Exception) {}
                var endCause = "unknown"
                while (scope.isActive && want) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        endCause = "readEx:${e.message}"
                        break
                    } ?: run { endCause = "readNull(FIN)"; break }
                    onLine(line.trim())
                }
                try { hb.cancel() } catch (_: Exception) {}
                AppLog.i(TAG, "车联读循环结束：$endCause")
            }
        } finally {
            synchronized(writeLock) { out = null }
            connected = false
            try { listener?.onConn(false) } catch (_: Exception) {}
        }
        if (want) throw RuntimeException("车联中断")
    }

    private fun onLine(line: String) {
        if (line.isBlank()) return
        val o = try { JSONObject(line) } catch (_: Exception) { return }
        try {
            when (o.optString("t")) {
                "MUSIC" -> listener?.onMusic(
                    o.optString("title"), o.optString("artist"), o.optBoolean("playing")
                )
                "PHONE" -> listener?.onPhone(
                    o.optString("state", "idle"), o.optString("number"), o.optString("name")
                )
                "STATUS" -> listener?.onStatus(
                    o.optInt("batt", -1), o.optBoolean("charging"), o.optInt("sig", -1)
                )
            }
        } catch (_: Exception) {}
    }
}
