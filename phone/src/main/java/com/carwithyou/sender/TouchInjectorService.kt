package com.carwithyou.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * 车机触摸 -> 注入到目标 Display（虚拟屏优先，否则手机主屏）。
 * 不占用 8889：控制通道统一由 ScreenCastService 收，再回调这里。
 *
 * TAP/SWIPE 坐标是 0~1，相对 CONFIG 里的 sw/sh（虚拟屏模式下等于虚拟屏像素）。
 * Android 14+ 用 GestureDescription.setDisplayId；更早版本反射 mDisplayId。
 */
class TouchInjectorService : AccessibilityService() {

    companion object {
        private const val TAG = "CarWithYou"
        @Volatile var instance: TouchInjectorService? = null
    }

    override fun onServiceConnected() {
        instance = this
        AppLog.i(TAG, "触摸回传无障碍服务已连上")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AppLog.w(TAG, "触摸回传无障碍服务已断开（车机点了也没反应）")
        return super.onUnbind(intent)
    }

    fun tap(nx: Float, ny: Float) {
        val (w, h) = targetSize()
        val x = (nx * w).coerceIn(0f, w - 1f)
        val y = (ny * h).coerceIn(0f, h - 1f)
        val path = Path().apply { moveTo(x, y) }
        dispatchGestureSafe(buildGesture(path, 80))
    }

    fun swipe(nx0: Float, ny0: Float, nx1: Float, ny1: Float, dur: Long) {
        val (w, h) = targetSize()
        val path = Path().apply {
            moveTo((nx0 * w).coerceIn(0f, w - 1f), (ny0 * h).coerceIn(0f, h - 1f))
            lineTo((nx1 * w).coerceIn(0f, w - 1f), (ny1 * h).coerceIn(0f, h - 1f))
        }
        dispatchGestureSafe(buildGesture(path, dur.coerceIn(50, 1000)))
    }

    private fun targetSize(): Pair<Float, Float> {
        val w = CastConfig.screenW.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = CastConfig.screenH.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        return w.toFloat() to h.toFloat()
    }

    private fun buildGesture(path: Path, dur: Long): GestureDescription {
        val builder = GestureDescription.Builder()
        applyDisplayId(builder)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, dur))
        return builder.build()
    }

    private fun applyDisplayId(builder: GestureDescription.Builder) {
        val displayId = CastConfig.displayId
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return
        // API 34+ has GestureDescription.Builder.setDisplayId. Older phones stay on the
        // default display: reflecting mDisplayId is a blocked private API at targetSdk 35.
        if (Build.VERSION.SDK_INT >= 34) {
            builder.setDisplayId(displayId)
        } else {
            AppLog.wThrottle(TAG, "gesture displayId=$displayId needs API 34+, injecting on default display", 30_000)
        }
    }

    fun dispatchGestureSafe(g: GestureDescription) {
        try {
            val ok = dispatchGesture(g, null, null)
            if (!ok) AppLog.wThrottle(TAG, "dispatchGesture 排队失败（上一个手势还没完）", 5_000)
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "dispatchGesture 报错：${e.message}（displayId=${CastConfig.displayId}）", 5_000)
        }
    }
}
