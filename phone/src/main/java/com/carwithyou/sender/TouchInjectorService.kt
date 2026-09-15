package com.carwithyou.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * 车机触摸 -> 手机注入（免root，靠无障碍手势）。
 * 协议（8889端口，文本行）：
 *   TAP x y        x,y是0~1（相对手机屏）
 *   SWIPE x0 y0 x1 y1 durMs
 */
class TouchInjectorService : AccessibilityService() {

    companion object {
        const val TOUCH_PORT = 8889
        @Volatile var instance: TouchInjectorService? = null
    }

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        instance = this
        startServer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        job?.cancel()
        return super.onUnbind(intent)
    }

    private fun startServer() {
        job?.cancel()
        job = scope.launch {
            try {
                ServerSocket(TOUCH_PORT).use { server ->
                    while (isActive) {
                        val sock = try { server.accept() } catch (_: Exception) { break }
                        launch {
                            try {
                                sock.use {
                                    val out = java.io.PrintWriter(it.getOutputStream(), true)
                                    // 新连接先发配置（视频/屏幕尺寸），车机据此做黑边映射
                                    try {
                                        out.println("CONFIG ${CastConfig.videoW} ${CastConfig.videoH} ${CastConfig.screenW} ${CastConfig.screenH}")
                                    } catch (_: Exception) {}
                                    val br = BufferedReader(InputStreamReader(it.getInputStream()))
                                    while (true) {
                                        val line = br.readLine() ?: break
                                        handleLine(line.trim(), out)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleLine(line: String, out: java.io.PrintWriter) {
        val p = line.split(" ")
        try {
            when (p.getOrNull(0)) {
                "PING" -> try { out.println("PONG ${p.getOrNull(1) ?: ""}") } catch (_: Exception) {}
                "TAP" -> {
                    val x = p[1].toFloat(); val y = p[2].toFloat()
                    tap(x, y)
                }
                "SWIPE" -> {
                    swipe(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toLong())
                }
            }
        } catch (_: Exception) {}
    }

    private fun tap(nx: Float, ny: Float) {
        val svc = this
        val dm = svc.resources.displayMetrics
        val x = nx * dm.widthPixels
        val y = ny * dm.heightPixels
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        svc.dispatchGesture(g, null, null)
    }

    private fun swipe(nx0: Float, ny0: Float, nx1: Float, ny1: Float, dur: Long) {
        val svc = this
        val dm = svc.resources.displayMetrics
        val path = Path().apply {
            moveTo(nx0 * dm.widthPixels, ny0 * dm.heightPixels)
            lineTo(nx1 * dm.widthPixels, ny1 * dm.heightPixels)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceIn(50, 1000)))
            .build()
        svc.dispatchGesture(g, null, null)
    }
}
