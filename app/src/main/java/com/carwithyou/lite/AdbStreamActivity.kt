package com.carwithyou.lite

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.lite.adb.AdbCastSession
import com.carwithyou.lite.adb.CwAdbProtocol
import com.carwithyou.lite.ui.StreamScreen
import com.carwithyou.lite.ui.theme.CarWithYouTheme
import kotlinx.coroutines.*
import java.io.File

/**
 * A 路线收流页：车机自己当 ADB 客户端，让手机以 **shell 身份**跑起 server。
 *
 * 与 [StreamReceiverActivity]（手机直连 8888/8889）的区别：
 *  - 链路是 ADB，手机侧**零安装**（server jar 由车机推过去、app_process 拉起）
 *  - 手机侧建的是**独立虚拟屏**，App 放进去后手机还能自由使用
 *  - 输入走 `injectInputEvent`，**不需要手机开无障碍**
 *
 * 协议见 docs/PROTOCOL.md 的「ADB 链路（shell server）协议」；解码复用 [H264SurfacePlayer]。
 */
class AdbStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            // 协程里的未捕获异常默认会直接弄死整个进程 —— 这里是安全网：
            // 链路出错只该断链重连，不该把 App 干掉。
            AppLog.e(TAG, "协程未捕获异常（已拦截，不崩溃）：${t::class.simpleName}: ${t.message}", t)
            lastError = "内部异常：${t.message ?: t::class.simpleName}"
        }
    )
    private var loopJob: Job? = null
    private var player: H264SurfacePlayer? = null
    private var surfaceView: SurfaceView? = null

    @Volatile private var session: AdbCastSession? = null
    @Volatile private var wantConnect = false
    @Volatile private var connected = false

    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var targetDisplayId = -1
    @Volatile private var lastPong = 0L
    @Volatile private var lastPingTs = 0L
    @Volatile private var frames = 0L
    @Volatile private var bytes = 0L

    private var target by mutableStateOf("")
    private var status by mutableStateOf("未连接")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = SettingsStore(this)
        target = intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }
            ?: store.adbTarget.ifBlank { "" }
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        // 自动投放的包：可以临时用 intent extra 覆盖（自动化测试/临时换 App 用）
        autoPkgOverride = intent.getStringExtra(EXTRA_AUTO_PKG).orEmpty()
        pendingAppCmd = intent.getStringExtra(EXTRA_APP_CMD).orEmpty()
        AppLog.i(
            TAG,
            "进 ADB 投屏页：target=${target.ifBlank { "(未配置)" }} auto=$auto cmd=$pendingAppCmd" +
                (if (autoPkgOverride.isNotBlank()) " autoPkg=$autoPkgOverride" else "")
        )

        setContent {
            CarWithYouTheme {
                StreamScreen(
                    ip = target,
                    status = status,
                    onIpChange = { target = it },
                    onConnect = { startLoop() },
                    onDisconnect = { stopLoop() },
                    onCopyLog = { Diagnostics.copy(this@AdbStreamActivity) },
                    onSurface = { attachSurface(it) },
                    onAppCmd = { sendApp(it) },
                    ipLabel = "手机ADB地址 host:port",
                    hint = "ADB 链路（手机零安装）· 独立虚拟屏 · 点画面可反控"
                )
            }
        }
        if (auto && !wantConnect) {
            window.decorView.postDelayed({ startLoop() }, 300)
        }
    }

    // ---------------------------------------------------------------- Surface / 触摸

    private fun attachSurface(view: SurfaceView) {
        if (surfaceView === view) return
        surfaceView?.holder?.removeCallback(this)
        surfaceView = view
        view.holder.addCallback(this)
        view.setOnTouchListener { v, e -> handleTouch(v, e) }
    }

    /**
     * 触摸直接映射成 ADB 链路的 INJECT_TOUCH（真 DOWN/MOVE/UP + pointerId），
     * 不走手机直连那条链路的 TAP/SWIPE 合成。
     */
    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        val s = session
        if (!connected || s == null || targetDisplayId < 0) return false
        try {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    sendTouch(s, CwAdbProtocol.TouchAction.DOWN, v, e, 0)

                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    sendTouch(s, CwAdbProtocol.TouchAction.DOWN, v, e, i)
                }

                MotionEvent.ACTION_MOVE -> {
                    // 每根手指都要发：server 侧按下标维护状态，漏一个就会黏住
                    for (i in 0 until e.pointerCount) {
                        sendTouch(s, CwAdbProtocol.TouchAction.MOVE, v, e, i)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = if (e.actionMasked == MotionEvent.ACTION_UP) 0 else e.actionIndex
                    sendTouch(s, CwAdbProtocol.TouchAction.UP, v, e, i)
                }

                MotionEvent.ACTION_CANCEL -> {
                    for (i in 0 until e.pointerCount) {
                        sendTouch(s, CwAdbProtocol.TouchAction.UP, v, e, i)
                    }
                }

                MotionEvent.ACTION_SCROLL -> {
                    val (nx, ny) = norm(v, e.x, e.y)
                    s.sendScroll(nx, ny, e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL))
                }
            }
        } catch (ex: Exception) {
            // 把栈打出来："触摸发不出去：null" 这种信息量太低了（NPE 的 message 就是 null）
            AppLog.eThrottle(TAG, "触摸发不出去：${ex.javaClass.simpleName}: ${ex.message}", ex, 5_000, "adbTouch")
        }
        return true
    }

    private fun sendTouch(s: AdbCastSession, action: Int, v: View, e: MotionEvent, index: Int) {
        val (nx, ny) = norm(v, e.getX(index), e.getY(index))
        s.sendTouch(action, e.getPointerId(index), nx, ny)
    }

    /**
     * 视图坐标 → 视频帧内 0~1 归一化。
     *
     * 与 [CastProtocol.mapToVideo] 的差别：这里对画面外的点**夹紧**而不是丢弃。
     * 触摸必须一一对应地发出去，丢掉一个 MOVE/UP 会让手机侧的指针状态黏住。
     */
    private fun norm(v: View, x: Float, y: Float): Pair<Float, Float> {
        val vw = videoW.toFloat()
        val vh = videoH.toFloat()
        val viewW = v.width.toFloat().coerceAtLeast(1f)
        val viewH = v.height.toFloat().coerceAtLeast(1f)
        if (vw <= 0 || vh <= 0) {
            return (x / viewW).coerceIn(0f, 1f) to (y / viewH).coerceIn(0f, 1f)
        }
        val s = minOf(viewW / vw, viewH / vh)
        val cw = vw * s
        val ch = vh * s
        val ox = (viewW - cw) / 2f
        val oy = (viewH - ch) / 2f
        return ((x - ox) / cw).coerceIn(0f, 1f) to ((y - oy) / ch).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- 会话

    private fun startLoop() {
        if (wantConnect) return
        if (target.isBlank()) {
            toast("先填手机 ADB 地址，例如 192.168.1.20:5555")
            return
        }
        wantConnect = true
        SettingsStore(this).adbTarget = target
        player = H264SurfacePlayer { AppLog.i(TAG, it) }.also { p ->
            p.onDecoderUp = { w, h, name ->
                decoderUp = true
                lastW = w
                lastH = h
                AppLog.i(TAG, "解码器已起：$name ${w}x$h")
            }
            p.onError = { msg -> lastError = msg }
        }
        surfaceView?.holder?.surface?.let { player?.setSurface(it) }

        loopJob?.cancel()
        var attempt = 0
        loopJob = scope.launch {
            while (wantConnect && isActive) {
                try {
                    updateStatus("连手机 ADB $target …")
                    runSession(target.trim())
                    attempt = 0
                } catch (e: Exception) {
                    if (!wantConnect) break
                    val wait = CastProtocol.backoff(attempt++)
                    lastError = "ADB 链路失败：${e.message ?: e.javaClass.name}"
                    AppLog.wThrottle(TAG, "$lastError（${wait / 1000}s 后重连）", 3_000, key = "adbSession")
                    updateStatus("失败/${e.message}，${wait / 1000}s后重连…")
                    delay(wait)
                } finally {
                    cleanupSession()
                }
            }
            withContext(Dispatchers.Main) { updateStatus("未连接") }
        }
    }

    private suspend fun runSession(target: String) {
        val host = target.substringBeforeLast(':', target)
        val port = target.substringAfterLast(':').toIntOrNull() ?: 5555

        val s = AdbCastSession(host, port, File(filesDir, "adb")) { line ->
            AppLog.i("AdbLink", line)
        }
        session = s
        try {
            s.connect()
            val info = s.deviceInfo()
            updateStatus("ADB 已连${if (info.isNotBlank()) "：$info" else ""}")

            val plan = buildServerArgs(s)
            val jar = loadServerJar()
            s.ensureServer(jar, plan, onProgress = { updateStatus(it) })
            updateStatus("server 已就绪，连通道…")

            s.openChannels()
            val header = s.readVideoHeader()
            videoW = header.width
            videoH = header.height
            AppLog.i(TAG, "视频头：$header")
            player?.setSize(header.width, header.height)
            // 编解码器是新建的，要一个能从零解起的 IDR
            s.requestSyncFrame()

            // 控制通道读取线程：DISPLAY_READY / PONG / ERROR / LOG 都从这里来
            val ctrlJob = scope.launch { controlLoop(s) }
            val hb = scope.launch { heartbeatLoop(s) }
            connected = true
            updateStatus("已连，等画面 ${header.width}×${header.height} …")
            try {
                pumpVideo(s)
            } finally {
                connected = false
                ctrlJob.cancel()
                hb.cancel()
            }
        } finally {
            connected = false
            session = null
            try { s.close() } catch (e: Exception) { AppLog.w(TAG, "关会话报错：${e.message}") }
        }
        if (wantConnect) throw RuntimeException("ADB 链路中断")
    }

    /** server 参数规划：尺寸/密度能自动就自动（取手机 wm size / wm density）。 */
    private fun buildServerArgs(s: AdbCastSession): List<String> {
        val store = SettingsStore(this)
        var w = store.adbWidth
        var h = store.adbHeight
        var d = store.adbDensity
        if (w <= 0 || h <= 0 || d <= 0) {
            val auto = queryPhoneDisplay(s)
            if (w <= 0) w = auto.first
            if (h <= 0) h = auto.second
            if (d <= 0) d = auto.third
        }
        val args = mutableListOf(
            "width=$w",
            "height=$h",
            "density=$d",
            "maxFps=${store.adbFps}",
            "bitrate=${store.adbBitrate}",
            "iFrameInterval=1",
        )
        if (store.adbMirror) {
            args += "mirror=1"
        } else {
            args += "mirror=0"
        }
        AppLog.i(TAG, "server 参数：${args.joinToString(" ")}")
        return args
    }

    /** 取手机主屏尺寸/密度：`wm size` / `wm density`；失败给 1280x720@240。 */
    private fun queryPhoneDisplay(s: AdbCastSession): Triple<Int, Int, Int> {
        var w = 0
        var h = 0
        var d = 0
        try {
            val sizeOut = s.runShellQuiet("wm size")
            Regex("(\\d+)x(\\d+)").findAll(sizeOut).lastOrNull()?.let {
                w = it.groupValues[1].toIntOrNull() ?: 0
                h = it.groupValues[2].toIntOrNull() ?: 0
            }
            val densityOut = s.runShellQuiet("wm density")
            Regex("(\\d+)").findAll(densityOut).lastOrNull()?.let {
                d = it.groupValues[1].toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "取手机屏参失败：${e.message}")
        }
        if (w <= 0 || h <= 0) {
            w = 1280
            h = 720
        }
        if (d <= 0) d = 240
        AppLog.i(TAG, "手机屏参：${w}x$h @${d}dpi")
        return Triple(w, h, d)
    }

    private fun loadServerJar(): ByteArray =
        resources.openRawResource(R.raw.carwithyou_server).use { it.readBytes() }

    private fun pumpVideo(s: AdbCastSession) {
        var lastStats = System.currentTimeMillis()
        var firstFrameAt = 0L
        var idleLogged = false
        while (scope.isActive && wantConnect && connected) {
            // 无限等：虚拟屏画面静止时编码器**根本不吐帧**（这是正常的，不是断链）。
            // 链路存活靠控制通道心跳判定；这里超时返回只会在断链关流时发生。
            val frame = try {
                s.readVideoFrame(0)
            } catch (e: java.net.SocketTimeoutException) {
                // 理论上不会走到（0=不限超时），万一走到也算正常静默
                continue
            } catch (e: Exception) {
                AppLog.i(TAG, "读视频帧结束：${e::class.simpleName}: ${e.message}")
                return
            }
            if (firstFrameAt == 0L) {
                firstFrameAt = System.currentTimeMillis()
                AppLog.i(TAG, "首帧到达：flags=0x${frame.flags.toString(16)} ${frame.payload.size} 字节")
            }
            player?.feed(frame.payload, config = frame.isConfig)
            frames++
            bytes += frame.payload.size
            val now = System.currentTimeMillis()
            if (now - lastStats >= 2000) {
                if (frames > 0) {
                    val mbps = bytes * 8 / 2000.0 / 1000.0
                    AppLog.i(
                        TAG,
                        "画面 ${videoW}x$videoH 帧累计=$frames 码率=%.2fMbps 解码失败=%d".format(
                            mbps, player?.decodeFailures ?: 0
                        )
                    )
                    idleLogged = false
                } else if (!idleLogged) {
                    // 画面静止：没帧是正常的，说一声免得以为是卡死
                    AppLog.i(TAG, "画面静止中（虚拟屏无变化，编码器不吐帧，这是正常的）")
                    idleLogged = true
                }
                frames = 0
                bytes = 0
                lastStats = now
            }
        }
    }

    private suspend fun controlLoop(s: AdbCastSession) {
        while (scope.isActive && wantConnect) {
            val msg = try {
                s.readDeviceMessage()
            } catch (e: Exception) {
                // 流断开/关闭是正常收尾路径，不该往上抛（未捕获协程异常会把进程带走）
                AppLog.i(TAG, "控制通道读取结束：${e::class.simpleName}: ${e.message}")
                return
            }
            when (msg) {
                is AdbCastSession.DeviceMessage.DisplayReady -> {
                    targetDisplayId = msg.displayId
                    AppLog.i(
                        TAG,
                        "DISPLAY_READY displayId=${msg.displayId} ${msg.width}x${msg.height}@${msg.density} rot=${msg.rotation}"
                    )
                    updateStatus("画面来了 ${msg.width}×${msg.height} · 虚拟屏 ${msg.displayId} · 点屏幕可反控")
                    // CarPlay 式直达：进页面就带命令的话，连上直接投对应 App。
                    // 没带命令时必须自己投一个 —— 新建的独立屏是**空屏**，
                    // 空屏没有任何图层，SurfaceFlinger 不会合成，编码器一帧都拿不到（踩过）。
                    val pending = pendingAppCmd.ifBlank {
                        val s2 = SettingsStore(this@AdbStreamActivity)
                        if (s2.adbAutoLaunch) {
                            autoPkgOverride.ifBlank { s2.adbNavPkg }
                        } else ""
                    }
                    if (pending.isNotBlank()) {
                        pendingAppCmd = ""
                        delay(800)
                        launchOnDisplay(s, resolvePkgFor(pending), "首屏")
                    }
                }
                is AdbCastSession.DeviceMessage.Pong -> {
                    lastPong = System.currentTimeMillis()
                    if (lastPingTs > 0) CastProtocol.lastRtt = System.currentTimeMillis() - lastPingTs
                }
                is AdbCastSession.DeviceMessage.Error ->
                    AppLog.w(TAG, "手机侧报错：${msg.message}")
                is AdbCastSession.DeviceMessage.Log ->
                    AppLog.i(TAG, "手机侧：${msg.text}")
                is AdbCastSession.DeviceMessage.Clipboard -> {
                    AppLog.i(TAG, "手机侧剪贴板：${msg.text.take(60)}")
                    // 顺手同步到车机剪切板，这样车机上别的 App 也能粘到手机复制的内容
                    if (msg.text.isNotEmpty()) {
                        try {
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("CarWithYou", msg.text)
                            )
                        } catch (e: Exception) {
                            AppLog.w(TAG, "写车机剪切板失败：${e.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun heartbeatLoop(s: AdbCastSession) {
        // 第一条心跳晚一点发：server 刚建完屏，先让它把首帧吐出来
        delay(1500)
        lastPong = System.currentTimeMillis()
        while (scope.isActive && wantConnect && connected) {
            lastPingTs = System.currentTimeMillis()
            try {
                s.sendPing()
            } catch (e: Exception) {
                AppLog.w(TAG, "心跳发不出去：${e.message}")
                return
            }
            delay(CastProtocol.HB_INTERVAL_MS)
            if (System.currentTimeMillis() - lastPong > CastProtocol.HB_TIMEOUT_MS) {
                AppLog.w(TAG, "心跳超时（${CastProtocol.HB_TIMEOUT_MS}ms 没 PONG），主动断开重连")
                return
            }
        }
    }

    /** 左 Dock：主屏/导航/音乐/分屏/返回 → 映射到 ADB 链路的实际动作。 */
    private fun sendApp(cmd: String) {
        val s = session
        if (s == null) {
            pendingAppCmd = cmd
            AppLog.i(TAG, "还没连上，先记下 Dock 命令：$cmd")
            return
        }
        val store = SettingsStore(this)
        scope.launch {
            try {
                when (cmd) {
                    "home" -> s.sendKey(CwAdbProtocol.KeyAction.DOWN, KeyEvent.KEYCODE_HOME)
                        .also { s.sendKey(CwAdbProtocol.KeyAction.UP, KeyEvent.KEYCODE_HOME) }
                    "back" -> s.sendKey(CwAdbProtocol.KeyAction.DOWN, KeyEvent.KEYCODE_BACK)
                        .also { s.sendKey(CwAdbProtocol.KeyAction.UP, KeyEvent.KEYCODE_BACK) }
                    "nav" -> launchOnDisplay(s, store.adbNavPkg, "导航")
                    "music" -> launchOnDisplay(s, store.adbMusicPkg, "音乐")
                    "split" -> withContext(Dispatchers.Main) {
                        toast("ADB 链路暂不支持分屏（独立虚拟屏里只有一个 App 在前台）")
                    }
                    "rotate" -> {
                        // -1 = 翻到另一个方向，车机上一个按钮两用
                        s.sendRotate(-1)
                        AppLog.i(TAG, "已请求旋转虚拟屏")
                    }
                    "paste" -> pasteFromCar(s)
                    else -> AppLog.w(TAG, "未知 Dock 命令：$cmd")
                }
                AppLog.i(TAG, "Dock 命令已发：$cmd")
            } catch (e: Exception) {
                AppLog.w(TAG, "Dock 命令发不出去（$cmd）：${e.message}")
            }
        }
    }

    /**
     * 把车机剪切板内容同步到手机，并触发粘贴。
     *
     * 为什么要绕一圈：手机侧的文字注入是拆成按键事件打的，只能英数；
     * 中文/emoji 在当前键盘布局下根本产生不了按键事件。所以中文只能走剪切板：
     * 先推到手机剪切板，再在手机上按一下粘贴快捷键。
     */
    private suspend fun pasteFromCar(s: AdbCastSession) {
        val text = try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            withContext(Dispatchers.Main) { toast("车机剪切板是空的，先复制点什么") }
            return
        }
        s.sendSetClipboard(text)
        // 等手机侧写完再触发粘贴，不然容易贴出旧内容
        delay(300)
        s.sendKey(CwAdbProtocol.KeyAction.DOWN, KeyEvent.KEYCODE_PASTE)
        s.sendKey(CwAdbProtocol.KeyAction.UP, KeyEvent.KEYCODE_PASTE)
        AppLog.i(TAG, "已推送剪切板并触发粘贴，长度 ${text.length}")
        withContext(Dispatchers.Main) {
            toast(if (text.length > 12) "已粘贴 ${text.take(12)}…" else "已粘贴 $text")
        }
    }

    /** Dock 命令名（nav/music）或包名 → 包名。 */
    private fun resolvePkgFor(cmdOrPkg: String): String {        val store = SettingsStore(this)
        return when (cmdOrPkg) {
            "nav" -> store.adbNavPkg
            "music" -> store.adbMusicPkg
            else -> cmdOrPkg
        }
    }

    private suspend fun launchOnDisplay(s: AdbCastSession, pkg: String, label: String) {
        if (pkg.isBlank()) {
            withContext(Dispatchers.Main) { toast("先填手机侧$label 包名") }
            return
        }
        val display = if (targetDisplayId >= 0) targetDisplayId else 0
        AppLog.i(TAG, "拉起手机$label：$pkg → display $display")
        if (s.launchApp(pkg, display)) {
            withContext(Dispatchers.Main) { toast("已把 $pkg 投到虚拟屏") }
        } else {
            withContext(Dispatchers.Main) { toast("拉起 $pkg 失败，包名对吗？") }
        }
    }

    private fun cleanupSession() {
        connected = false
        targetDisplayId = -1
        videoW = 0
        videoH = 0
        decoderUp = false
        player?.release()
    }

    private fun stopLoop() {
        wantConnect = false
        loopJob?.cancel()
        loopJob = null
        scope.launch {
            session?.let { try { it.close() } catch (_: Exception) {} }
        }
        session = null
        cleanupSession()
        updateStatus("未连接")
    }

    private fun updateStatus(s: String) {
        statusText = s
        runOnUiThread { status = s }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        AppLog.i(TAG, "Surface 创建")
        player?.setSurface(h.surface)
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        AppLog.i(TAG, "Surface 销毁，停解码器")
        player?.setSurface(null)
    }

    override fun onDestroy() {
        stopLoop()
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(s: String) {
        lastError = s
        AppLog.w(TAG, s)
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_TARGET = "adb_target"
        const val EXTRA_AUTO = "adb_auto"
        const val EXTRA_APP_CMD = "adb_app_cmd"
        const val EXTRA_AUTO_PKG = "adb_auto_pkg"
        private const val TAG = "CarWithYou"

        @Volatile private var pendingAppCmd = ""
    /** intent 里临时指定的自动投放包；空=用设置里的导航包 */
    @Volatile private var autoPkgOverride = ""

        // 诊断日志的「现场」（LiteApp.stateText 读这几个静态）
        @Volatile var statusText: String = "未连接"
        @Volatile var lastError: String = ""
        @Volatile var decoderUp: Boolean = false
        @Volatile var lastW: Int = 0
        @Volatile var lastH: Int = 0
    }
}
