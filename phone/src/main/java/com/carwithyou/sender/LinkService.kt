package com.carwithyou.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米 CarWith / HiCar 式结构化车联服务（独立于视频投屏）。
 *
 * 不传整屏视频，只同步数据：TCP 8890，JSON 行协议（见 docs/PROTOCOL.md §8）。
 * - 手机 → 车机：HELLO / MUSIC（歌名/歌手/播放状态）/ PHONE（来电/通话）
 * - 车机 → 手机：MUSIC_CMD（播放/暂停/上下曲）/ PHONE_CMD（接听/挂断）/ PING
 *
 * 声音还是走蓝牙；导航画面这版仍走视频投屏（车机“手机投屏”卡），
 * 音乐/电话走这条数据链路原生显示。
 */
class LinkService : Service() {

    companion object {
        const val PORT = 8890
        private const val TAG = "CarWithYou"

        @Volatile var running = false
        @Volatile private var instance: LinkService? = null
        @Volatile var clientCount = 0
            private set
        @Volatile var lastMusicTitle = ""
            private set
        @Volatile var lastMusicArtist = ""
            private set
        @Volatile var lastMusicPlaying = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, LinkService::class.java)) } catch (_: Exception) {}
        }

        /** 通知栏切歌后让车机立刻刷新，不用等 2 秒轮询 */
        fun refreshMusic() {
            instance?.music?.refresh()
        }

        /** 驾驶模式/通知按钮调：后台线程里执行切歌 */
        fun musicCmd(cmd: String) {
            val svc = instance ?: return
            Thread {
                try { svc.music.cmd(cmd) } catch (_: Exception) {}
            }.start()
        }
    }

    private val broadcastLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val writers = ConcurrentHashMap<Socket, PrintWriter>()
    private lateinit var music: MusicLink
    private lateinit var phone: PhoneLink

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        startFg()
        music = MusicLink(this) { o ->
            if (o.optString("t") == "MUSIC") {
                lastMusicTitle = o.optString("title")
                lastMusicArtist = o.optString("artist")
                lastMusicPlaying = o.optBoolean("playing")
            }
            broadcast(o)
        }
        phone = PhoneLink(this) { broadcast(it) }
        phone.start()
        scope.launch { acceptLoop() }
        // 轮询必须跑在 IO 线程：之前用主线程 Handler，广播一写 socket 就
        // NetworkOnMainThreadException，直接把车机掐掉（连上几秒必断）。
        scope.launch {
            var n = 0
            while (isActive && running) {
                try { music.refresh() } catch (_: Exception) {}
                if (++n % 5 == 0) {
                    try { broadcast(statusSnapshot()) } catch (_: Exception) {}
                }
                try { delay(2000) } catch (_: Exception) { break }
            }
        }
        AppLog.i(TAG, "车联服务已起（:$PORT），音乐/电话走结构化同步")
    }

    private fun startFg() {
        val chId = "link"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(chId, "车联服务", NotificationManager.IMPORTANCE_MIN))
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId).setContentTitle("车联服务运行中（音乐/电话同步）")
                .setSmallIcon(android.R.drawable.ic_media_play).build()
        else @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("车联服务运行中")
                .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                2002, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(2002, n)
        }
    }

    private fun acceptLoop() {
        try {
            server = ServerSocket(PORT)
            AppLog.i(TAG, "车联端口已监听 $PORT，等车机连")
            while (scope.isActive && running) {
                val sock = try {
                    server?.accept() ?: break
                } catch (e: Exception) {
                    if (running) AppLog.w(TAG, "车联 accept 退出：${e.message}")
                    break
                }
                scope.launch { serve(sock) }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "车联端口 $PORT 起不来：${e.message}", e)
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = PrintWriter(s.getOutputStream(), true)
                writers[s] = out
                clientCount++
                if (clientCount == 1) notifyCar()
                AppLog.i(TAG, "车机连上车联端口 ${s.inetAddress}:${s.port}")
                // 握手 + 当前快照，车机桌面秒出内容
                send(out, JSONObject().put("t", "HELLO").put("ver", 2))
                send(out, music.snapshot())
                send(out, phone.snapshot())
                var endCause = "unknown"
                while (scope.isActive && running) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        endCause = "readEx:${e.message}"
                        break
                    } ?: run { endCause = "readNull(FIN)"; break }
                    handle(line.trim())
                }
                AppLog.i(TAG, "车联会话结束 ${s.inetAddress}:${s.port}：$endCause")
            }
        } catch (e: Exception) {
            AppLog.wThrottle(TAG, "车联会话异常：${e.message}", 5_000)
        } finally {
            writers.remove(sock)
            if (clientCount > 0) clientCount--
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun handle(line: String) {
        val o = try { JSONObject(line) } catch (_: Exception) { return }
        when (o.optString("t")) {
            "PING" -> broadcast(JSONObject().put("t", "PONG").put("ts", o.optLong("ts")))
            "MUSIC_CMD" -> scope.launch { music.cmd(o.optString("cmd")) }
            "PHONE_CMD" -> scope.launch { phone.cmd(o.optString("cmd")) }
            "NAV_POI" -> handleNavPoi(o.optString("keyword"))
        }
    }

    /** 车机搜目的地 → 手机虚拟屏高德直达（车机零本地地图） */
    private fun handleNavPoi(keyword: String) {
        if (keyword.isBlank()) return
        if (!ScreenCastService.running || !ScreenCastService.virtualMode) {
            AppLog.w(TAG, "收到目的地 $keyword，但虚拟屏投屏没开，打不开")
            return
        }
        val id = CastConfig.displayId
        if (id == Display.INVALID_DISPLAY || id == Display.DEFAULT_DISPLAY) {
            AppLog.w(TAG, "收到目的地 $keyword，但虚拟屏还没建好")
            return
        }
        scope.launch {
            try {
                val uri = android.net.Uri.parse(
                    "androidamap://poi?sourceApplication=carwithyou&keywords=" +
                        java.net.URLEncoder.encode(keyword, "UTF-8")
                )
                // 先拿手机设置的导航包（SenderPrefs），拿不到再用高德手机版
                val prefs = SenderPrefs(this@LinkService)
                val nav = prefs.navPkg.ifBlank { KnownApps.AMAP }
                AppLauncher.launchUri(this@LinkService, nav, uri, id)
                AppLog.i(TAG, "目的地直达：$keyword → 虚拟屏高德")
            } catch (e: Exception) {
                AppLog.w(TAG, "目的地直达失败：${e.message}")
            }
        }
    }

    /** CarPlay 式状态同步：车机桌面显示手机电量/充电/信号 */
    private fun statusSnapshot(): JSONObject {
        var batt = -1
        var charging = false
        var sig = -1
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batt = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {}
        try {
            val bi = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val st = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                st == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {}
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("MissingPermission")
            sig = tm.signalStrength?.level ?: -1
        } catch (_: Exception) {
            sig = -1
        }
        return JSONObject()
            .put("t", "STATUS")
            .put("batt", batt)
            .put("charging", charging)
            .put("sig", sig)
    }

    /** 车机上线弹通知，点一下进驾驶模式（后台不允许直接拉 Activity） */
    private fun notifyCar() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(this, CarModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, "link")
                    .setContentTitle("已连车机，点我进驾驶模式")
                    .setContentText("大按钮 + 免打扰，开车盲操作")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi).setAutoCancel(true).build()
            else @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("已连车机，点我进驾驶模式")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(2003, n)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "通知没权限，弹不了驾驶模式入口：${e.message}")
        } catch (e: Exception) {
            AppLog.w(TAG, "驾驶模式通知失败：${e.message}")
        }
    }

    private fun send(out: PrintWriter, o: JSONObject) {
        try {
            out.println(o.toString())
            out.flush()
        } catch (_: Exception) {}
    }

    private fun broadcast(o: JSONObject) {
        synchronized(broadcastLock) {
        val s = o.toString()
        val dead = mutableListOf<Socket>()
        for ((sock, out) in writers) {
            try {
                out.println(s)
                out.flush()
                if (out.checkError()) {
                    AppLog.w(TAG, "广播 ${o.optString("t")} 到 ${sock.inetAddress}:${sock.port} 失败(checkError)，移除")
                    dead += sock
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "广播失败", e)
                dead += sock
            }
        }
        for (d in dead) {
            writers.remove(d)
            try { d.close() } catch (_: Exception) {}
        }
        }
    }

    override fun onDestroy() {
        AppLog.i(TAG, "车联服务退出")
        running = false
        instance = null
        try { phone.stop() } catch (_: Exception) {}
        for ((s, _) in writers) { try { s.close() } catch (_: Exception) {} }
        writers.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
        scope.cancel()
        super.onDestroy()
    }
}
