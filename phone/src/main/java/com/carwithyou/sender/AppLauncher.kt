package com.carwithyou.sender

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/** 把第三方 App 启动到指定 Display（独立虚拟屏），失败时绝不回落到手机主屏。 */
object AppLauncher {
    private const val TAG = "CarWithYou"

    fun leftBounds(w: Int, h: Int) = Rect(0, 0, (w * 2) / 3, h)
    fun rightBounds(w: Int, h: Int) = Rect((w * 2) / 3, 0, w, h)

    fun launch(
        ctx: Context,
        pkg: String,
        displayId: Int,
        bounds: Rect? = null,
        adjacent: Boolean = false
    ): Boolean {
        if (pkg.isBlank()) return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Log.w(TAG, "no launch intent for $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (adjacent) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val opts = ActivityOptions.makeBasic()
        if (displayId != Display.INVALID_DISPLAY) {
            opts.launchDisplayId = displayId
        }
        if (bounds != null) opts.launchBounds = bounds
        val startCtx = displayContext(ctx, displayId)
        return try {
            startCtx.startActivity(intent, opts.toBundle())
            Log.i(TAG, "launched $pkg on display=$displayId bounds=$bounds adjacent=$adjacent")
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch $pkg on display $displayId failed: ${e.message}")
            false
        }
    }

    private fun displayContext(ctx: Context, displayId: Int): Context {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return ctx
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId) ?: return ctx
            ctx.createDisplayContext(display)
        } catch (_: Exception) {
            ctx
        }
    }
}
