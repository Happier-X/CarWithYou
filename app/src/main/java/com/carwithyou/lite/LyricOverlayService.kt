package com.carwithyou.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 悬浮歌词：TYPE_APPLICATION_OVERLAY，全局浮在高德上面，可拖动。
 * 歌词来源：MusicListenerService 发广播 ACTION_LYRIC_UPDATE
 */
class LyricOverlayService : Service() {

    companion object {
        const val ACTION_LYRIC_UPDATE = "com.carwithyou.lite.LYRIC_UPDATE"
        const val EXTRA_LINE1 = "line1"   // 当前句
        const val EXTRA_LINE2 = "line2"   // 下一句
        const val ACTION_TOGGLE = "com.carwithyou.lite.TOGGLE_LYRIC"

        fun canDrawOverlays(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx)
            else true
    }

    private var wm: WindowManager? = null
    private var lyricView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LYRIC_UPDATE) {
                val l1 = intent.getStringExtra(EXTRA_LINE1) ?: ""
                val l2 = intent.getStringExtra(EXTRA_LINE2) ?: ""
                updateText(l1, l2)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_LYRIC_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showOverlay()
    }

    override fun onDestroy() {
        AppLog.i("Lyric", "悬浮歌词服务退出")
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        lyricView?.let { wm?.removeView(it) }
        super.onDestroy()
    }

    private fun startFg() {
        val chId = "lyric"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "悬浮歌词", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val noti = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, chId)
                .setContentTitle("CarWithYou 歌词运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CarWithYou 歌词运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
        startForeground(1001, noti)
    }

    @Suppress("ClickableViewAccessibility")
    private fun showOverlay() {
        if (lyricView != null) return
        if (!canDrawOverlays(this)) {
            AppLog.w("Lyric", "没给悬浮窗权限，加不了歌词窗口")
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val store = SettingsStore(this)

        val tv = TextView(this).apply {
            text = "CarWithYou 歌词就绪 · 播放音乐自动显示"
            textSize = store.lyricTextSize
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#80000000"))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120  // 导航条上方一点，科鲁泽横屏刚好
        }

        // 按住拖动
        var lastY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { lastY = e.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - lastY).toInt()
                    if (kotlin.math.abs(dy) > 8) {
                        params?.y = (params?.y ?: 0) - dy
                        wm?.updateViewLayout(v, params)
                        lastY = e.rawY
                        true
                    } else false
                }
                else -> false
            }
        }

        wm?.addView(tv, params)
        lyricView = tv
        AppLog.i("Lyric", "悬浮歌词窗口已加到屏幕")
    }

    private fun updateText(l1: String, l2: String) {
        lyricView?.text = if (l2.isBlank()) l1 else "$l1\n$l2"
    }
}
