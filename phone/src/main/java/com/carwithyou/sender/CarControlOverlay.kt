package com.carwithyou.sender

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper

/**
 * 浮在虚拟屏底部的切换栏（车机上能点到）。
 * 用 display 的 WindowContext 加窗，避免跑到手机主屏。
 */
class CarControlOverlay(private val appCtx: Context) {

    private var view: View? = null
    private var wm: WindowManager? = null

    fun show(
        display: Display,
        onNav: () -> Unit,
        onMusic: () -> Unit,
        onSplit: () -> Unit
    ) {
        hide()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(appCtx)) return
        try {
            val displayCtx = appCtx.createDisplayContext(display)
            val windowCtx = if (Build.VERSION.SDK_INT >= 30) {
                displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                displayCtx
            }
            val themed = ContextThemeWrapper(windowCtx, R.style.Theme_CarSender)
            val bar = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xCC101418.toInt())
                val pad = dp(themed, 8)
                setPadding(pad, pad, pad, pad)
            }
            fun addBtn(text: String, weight: Float, click: () -> Unit) {
                val b = Button(themed).apply {
                    this.text = text
                    textSize = 16f
                    setOnClickListener { click() }
                }
                bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    val m = dp(themed, 4)
                    setMargins(m, 0, m, 0)
                })
            }
            addBtn("导航", 2f, onNav)
            addBtn("音乐", 1f, onMusic)
            addBtn("分屏", 1f, onSplit)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(themed, 12)
            }
            wm = windowCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm?.addView(bar, params)
            view = bar
        } catch (_: Exception) {
            hide()
        }
    }

    fun hide() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        view = null
        wm = null
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
