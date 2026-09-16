package com.carwithyou.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun tap(nx: Float, ny: Float) {
        val (w, h) = targetSize()
        val x = (nx * w).coerceIn(0f, w - 1f)
        val y = (ny * h).coerceIn(0f, h - 1f)
        val path = Path().apply { moveTo(x, y) }
        dispatch(buildGesture(path, 80))
    }

    fun swipe(nx0: Float, ny0: Float, nx1: Float, ny1: Float, dur: Long) {
        val (w, h) = targetSize()
        val path = Path().apply {
            moveTo((nx0 * w).coerceIn(0f, w - 1f), (ny0 * h).coerceIn(0f, h - 1f))
            lineTo((nx1 * w).coerceIn(0f, w - 1f), (ny1 * h).coerceIn(0f, h - 1f))
        }
        dispatch(buildGesture(path, dur.coerceIn(50, 1000)))
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
        if (Build.VERSION.SDK_INT >= 34) {
            builder.setDisplayId(displayId)
            return
        }
        try {
            val f = GestureDescription.Builder::class.java.getDeclaredField("mDisplayId")
            f.isAccessible = true
            f.setInt(builder, displayId)
        } catch (e: Exception) {
            Log.w(TAG, "cannot set gesture displayId=$displayId: ${e.message}")
        }
    }

    private fun dispatch(g: GestureDescription) {
        try {
            dispatchGesture(g, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed: ${e.message}")
        }
    }
}
