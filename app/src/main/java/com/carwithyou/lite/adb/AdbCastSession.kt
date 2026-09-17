package com.carwithyou.lite.adb

import com.carwithyou.adb.Adb
import com.carwithyou.adb.AdbKeyPair
import com.carwithyou.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 车机端持有的一条 ADB 投屏会话。
 *
 * 生命周期（对应 docs/PROTOCOL.md 的「ADB 链路」一节）：
 *  1. [connect]        —— 连手机 adbd（RSA 认证，密钥存在 app 私有目录）
 *  2. [ensureServer]   —— 推 jar（size 不同才推）→ 拉起 app_process → 等 ready 文件
 *  3. [openChannels]   —— 连**两次** abstract socket：先控制、后视频（顺序不能反）
 *  4. [readVideoHeader] + [readVideoFrame] 接解码器；[readDeviceMessage] 收回包
 *  5. 控制用 sendXxx 系列
 *
 * 这个类只做管线，不碰 UI，也不碰编解码器。
 */
class AdbCastSession(
    private val host: String,
    private val port: Int,
    private val keyDir: File,
    private val log: (String) -> Unit = {},
) : Closeable {

    private var adb: Adb? = null
    private var control: AdbStream? = null
    private var video: AdbStream? = null

    /** 控制通道的字节流视图（协议解析按字节走），开通道时建一次。 */
    private var controlIn: java.io.InputStream? = null

    /** 控制通道写入串行化 + 入队。触摸回调在**主线程**上，直接写 socket 会被
     *  StrictMode 判 `NetworkOnMainThreadException`（实测：触摸全被丢掉）。
     *  所以所有控制消息都入队，由单独线程按顺序写出。 */
    private val controlLock = Any()
    private val controlQueue = ArrayDeque<ByteArray>()
    private var controlWriter: Thread? = null

    private val closed = AtomicBoolean(false)

    /** server 进程 pid（从 pid 文件或 ready 文件拿），用于存活判断和收尾。 */
    @Volatile
    var serverPid: Int = 0
        private set

    val isConnected: Boolean get() = adb != null && !closed.get()

    // ---------------------------------------------------------------- 1. 连 adbd

    /**
     * 连手机 adbd。首次会触发手机上的「允许调试吗」，用户同意一次之后就一直可用。
     * 认证失败（没同意）会抛异常，消息里带原因。
     */
    fun connect() {
        if (adb != null) return
        val keys = AdbKeyPair.loadOrCreate(keyDir)
        log("连 adbd $host:$port（公钥 ${keys.name}）")
        adb = Adb(host, port, keys) { log("adb: $it") }
        log("adbd 已握手：maxData=${adb!!.maxData} banner=${adb!!.banner.take(60)}")
    }

    /** 手机型号 / 版本，连上后用来在 UI 上显示「已连到哪台手机」。 */
    fun deviceInfo(): String {
        val a = adb ?: return ""
        return try {
            val model = a.runShell("getprop ro.product.model").trim()
            val sdk = a.runShell("getprop ro.build.version.sdk").trim()
            "$model (SDK $sdk)"
        } catch (e: Exception) {
            log("取设备信息失败：${e.message}")
            ""
        }
    }

    // ---------------------------------------------------------------- 2. 起 server

    /** [ensureServer] 的结果。 */
    data class ServerState(
        /** true = 本次是新拉起的；false = 复用了已在跑的。 */
        val launched: Boolean,
        /** 推 jar 花了多久（ms），复用时为 0。 */
        val pushMs: Long,
        /** 从拉起/确认到就绪花了多久（ms）。 */
        val readyMs: Long,
    )

    /**
     * 确保手机侧 server 在跑。
     *
     * 复用判据：ready 文件里的原始参数与本轮一致，且那个 pid 还活着。
     * 不一致（比如车机换了分辨率/开关了镜像）就杀掉重拉。
     *
     * @param jarBytes res/raw 里的 server jar
     * @param args     `key=value` 参数，会被拼进 app_process 命令行
     */
    fun ensureServer(
        jarBytes: ByteArray,
        args: List<String>,
        readyTimeoutMs: Long = 25_000,
        onProgress: (String) -> Unit = {},
    ): ServerState {
        val a = adb ?: throw IllegalStateException("还没连 adbd")
        val signature = args.joinToString(" ")

        // 已经在跑并且参数一致 → 直接复用
        val existing = readReadyFile()
        if (existing != null && existing.signature == signature && isPidAlive(existing.pid)) {
            serverPid = existing.pid
            log("复用已在跑的 server（pid=${existing.pid}，displayId=${existing.displayId}）")
            return ServerState(launched = false, pushMs = 0, readyMs = 0)
        }

        // 清掉旧的：残留的 server 会占着 abstract socket，新 server 绑不上
        if (existing != null) {
            onProgress("清掉旧 server（pid=${existing.pid}）")
            killPid(existing.pid)
        }
        killPidFromPidFile()
        deleteReadyFile(a)

        val pushStart = System.currentTimeMillis()
        // 比大小是不够的！改一个常量（比如 flags 的位）jar 大小一模一样，
        // 结果就是新代码永远推不上去、手机上一直跑旧 server（踩过这个坑）。
        // 所以比 md5；拿不到 diff 就老老实实重推。
        val localMd5 = md5Hex(jarBytes)
        val remoteMd5 = remoteMd5(CwAdbProtocol.REMOTE_JAR)
        if (localMd5 != null && localMd5 == remoteMd5) {
            log("jar 内容一致（md5=${localMd5.take(8)}），跳过推送")
        } else {
            onProgress("推 server jar（${jarBytes.size / 1024} KB）")
            a.pushBytes(jarBytes, CwAdbProtocol.REMOTE_JAR)
            log(
                "jar 已推送到 ${CwAdbProtocol.REMOTE_JAR}（${jarBytes.size} 字节，md5=${localMd5?.take(8)}" +
                    if (remoteMd5 != null) "，旧 md5=${remoteMd5.take(8)}）" else "）"
            )
        }
        val pushMs = System.currentTimeMillis() - pushStart

        // 就绪文件要等 server 自己写，先确认它真的没了（避免拿到上一次的）
        if (readReadyFile() != null) {
            throw IOException("就绪文件清不掉，没法确认新 server 起没起来")
        }

        val cmd = buildLaunchCommand(args)
        var launchAttempt = 0
        val maxLaunches = 2
        var lastErr = ""
        while (launchAttempt < maxLaunches) {
            launchAttempt++
            onProgress(if (launchAttempt == 1) "拉起 server（app_process + shell 身份）" else "server 没起来，重拉一次（$launchAttempt/$maxLaunches）")
            log("启动命令：$cmd")
            val launchedOut = a.runShell(cmd, timeoutMs = 20_000).trim()
            serverPid = launchedOut.toIntOrNull() ?: 0
            log("app_process 已拉起，pid=$serverPid")

            val readyStart = System.currentTimeMillis()
            val waitThisRound = if (launchAttempt < maxLaunches) readyTimeoutMs / 2 else readyTimeoutMs
            var waited = 0L
            var logsLookedAt = false
            while (waited < waitThisRound) {
                val ready = readReadyFile()
                if (ready != null) {
                    serverPid = ready.pid
                    log("server 就绪：pid=${ready.pid} displayId=${ready.displayId} ${ready.width}x${ready.height}@${ready.density}")
                    return ServerState(true, pushMs, System.currentTimeMillis() - readyStart)
                }
                onProgress("等 server 就绪…（${waited / 1000}s）")
                Thread.sleep(400)
                waited += 400
                // 只看一眼 logcat 当参考：**不能**用 /proc 存活判断做 fail-fast ——
                // app_process 冷启动要 1~3s，猜错就会把刚拉起来的 server 杀掉（踩过）。
                if (!logsLookedAt && waited >= 6000) {
                    logsLookedAt = true
                    lastErr = serverLogTail(20)
                }
            }
            log("本轮（${waitThisRound}ms）没等到就绪文件")
            killPid(serverPid)
            Thread.sleep(400)
        }
        throw IOException("等 server 就绪超时（共推 ${maxLaunches} 次）\n$lastErr")
    }

    private var REMOTE_JAR_CHK = Unit
        get() = field

    /** 跑一条 shell，出错不抛（探活/取值用）。 */
    fun runShellQuiet(command: String, timeoutMs: Long = 15_000): String = try {
        adb?.runShell(command, timeoutMs) ?: ""
    } catch (e: Exception) {
        log("shell 失败（$command）：${e.message}")
        ""
    }

    /** `stat -c %s` 拿远端文件大小；拿不到返回 -1（那就走推送）。 */
    private fun statSize(path: String): Long {
        val a = adb ?: return -1
        return try {
            val out = a.runShell("stat -c %s $path 2>/dev/null").trim()
            out.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** 远端 md5（toybox md5sum）；拿不到返回 null，调用方就重推。 */
    private fun remoteMd5(path: String): String? {
        val a = adb ?: return null
        return try {
            val out = a.runShell("md5sum $path 2>/dev/null").trim()
            val first = out.substringBefore(' ').trim()
            if (first.length == 32 && first.all { it in "0123456789abcdefABCDEF" }) first.lowercase() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun md5Hex(bytes: ByteArray): String? = try {
        val d = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        d.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun buildLaunchCommand(args: List<String>): String {
        val quoted = args.joinToString(" ") { arg ->
            if (arg.any { it.isWhitespace() }) "'" + arg.replace("'", "'\\''") + "'" else arg
        }
        // 三个 fd 全部接管：不接管的话 adb shell 退出时会把子进程一起带走，
        // 而且 shell 通道会一直挂着不返回。
        return "nohup app_process -Djava.class.path=${CwAdbProtocol.REMOTE_JAR} / " +
            "${CwAdbProtocol.SERVER_MAIN_CLASS} $quoted " +
            "</dev/null >${CwAdbProtocol.SERVER_LOG_FILE} 2>&1 & " +
            "echo \$! > /data/local/tmp/cwy.pid; cat /data/local/tmp/cwy.pid"
    }

    private class ReadyInfo(
        val pid: Int,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val density: Int,
        val signature: String,
    )

    private fun readReadyFile(): ReadyInfo? {
        val a = adb ?: return null
        val text = try {
            a.runShell("cat ${CwAdbProtocol.READY_FILE} 2>/dev/null")
        } catch (e: Exception) {
            return null
        }
        val lines = text.trim().split('\n')
        if (lines.isEmpty()) return null
        val head = lines[0].trim().split(Regex("\\s+"))
        if (head.size < 5) return null
        val pid = head[0].toIntOrNull() ?: return null
        return ReadyInfo(
            pid = pid,
            displayId = head[1].toIntOrNull() ?: 0,
            width = head[2].toIntOrNull() ?: 0,
            height = head[3].toIntOrNull() ?: 0,
            density = head[4].toIntOrNull() ?: 0,
            signature = lines.drop(1).joinToString(" ").trim(),
        )
    }

    private fun deleteReadyFile(a: Adb) {
        try {
            a.runShell("rm -f ${CwAdbProtocol.READY_FILE}")
        } catch (e: Exception) {
            log("删就绪文件失败：${e.message}")
        }
    }

    private fun isPidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        val a = adb ?: return false
        return try {
            a.runShell("[ -d /proc/$pid ] && echo yes").trim() == "yes"
        } catch (e: Exception) {
            false
        }
    }

    private fun killPid(pid: Int) {
        if (pid <= 0) return
        val a = adb ?: return
        try {
            a.runShell("kill -9 $pid 2>/dev/null")
            Thread.sleep(200)
        } catch (e: Exception) {
            log("杀 pid=$pid 失败：${e.message}")
        }
    }

    private fun killPidFromPidFile() {
        val a = adb ?: return
        try {
            val pid = a.runShell("cat /data/local/tmp/cwy.pid 2>/dev/null").trim().toIntOrNull()
            if (pid != null && pid > 0) killPid(pid)
        } catch (e: Exception) {
            log("读 pid 文件失败：${e.message}")
        }
    }

    /** 拉一段 CwServer 的 logcat 出来 —— app_process 的 stdout 不可靠，它才是主通道。 */
    fun serverLogTail(lines: Int = 40): String {
        val a = adb ?: return ""
        return try {
            val out = a.runShell("logcat -d -t $lines -s CwServer:V", timeoutMs = 10_000).trim()
            if (out.isEmpty()) "（logcat 里没有 CwServer 输出，可能进程根本没起来）" else out
        } catch (e: Exception) {
            "（取 logcat 失败：${e.message}）"
        }
    }

    // ---------------------------------------------------------------- 3. 开通道

    /**
     * 连 abstract socket 拿控制 + 视频两条通道。
     * **顺序固定**：server 先 accept 控制、再 accept 视频，反了会把画面喂到控制通道上。
     */
    fun openChannels() {
        val a = adb ?: throw IllegalStateException("还没连 adbd")
        if (control != null) return
        log("连控制通道 localabstract:${CwAdbProtocol.SOCKET_NAME}")
        val c = a.localSocketForward(CwAdbProtocol.SOCKET_NAME)
        control = c
        controlIn = c.inputStream()
        log("控制通道已连，连视频通道")
        video = a.localSocketForward(CwAdbProtocol.SOCKET_NAME)
        log("视频通道已连")
        startControlWriter()
    }

    // ---------------------------------------------------------------- 4. 读

    /** 视频头：魔数 + codec + 宽高，各 4 字节大端。 */
    class VideoHeader(val magic: Int, val codec: Int, val width: Int, val height: Int) {
        val isAvc: Boolean get() = codec == CwAdbProtocol.CODEC_AVC
        override fun toString(): String = "magic=0x%08X codec=0x%08X %dx%d".format(magic, codec, width, height)
    }

    fun readVideoHeader(timeoutMs: Long = 15_000): VideoHeader {
        val v = video ?: throw IllegalStateException("视频通道没开")
        val bytes = v.readFully(16, timeoutMs)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val header = VideoHeader(buf.int, buf.int, buf.int, buf.int)
        if (header.magic != CwAdbProtocol.VIDEO_MAGIC) {
            throw IOException("视频头魔数不对：${header}（协议不同版本？）")
        }
        return header
    }

    /** 一帧：`u32 长度(=1+负载) | u8 标志位 | 负载`。 */
    class VideoFrame(val flags: Int, val payload: ByteArray) {
        val isKeyFrame: Boolean get() = flags and CwAdbProtocol.FRAME_FLAG_KEY != 0
        val isConfig: Boolean get() = flags and CwAdbProtocol.FRAME_FLAG_CONFIG != 0
    }

    fun readVideoFrame(timeoutMs: Long = 15_000): VideoFrame {
        val v = video ?: throw IllegalStateException("视频通道没开")
        val header = v.readFully(4, timeoutMs)
        val length = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw IOException("视频帧长度异常：$length")
        }
        val body = v.readFully(length, timeoutMs)
        return VideoFrame(body[0].toInt() and 0xFF, body.copyOfRange(1, body.size))
    }

    /** 手机 → 车机的控制消息。 */
    sealed class DeviceMessage {
        class DisplayReady(
            val displayId: Int, val width: Int, val height: Int,
            val density: Int, val rotation: Int,
        ) : DeviceMessage()

        class Clipboard(val text: String) : DeviceMessage()
        class Error(val message: String) : DeviceMessage()
        object Pong : DeviceMessage()
        class Log(val text: String) : DeviceMessage()
    }

    /** 阻塞读一条设备回包；流断开抛 IOException 由调用方收尾。 */
    fun readDeviceMessage(): DeviceMessage {
        val input = controlIn ?: throw IllegalStateException("控制通道没开")
        return when (val type = CwAdbProtocol.readByte(input)) {
            CwAdbProtocol.Device.DISPLAY_READY -> {
                val displayId = CwAdbProtocol.readInt(input)
                val width = CwAdbProtocol.readInt(input)
                val height = CwAdbProtocol.readInt(input)
                val density = CwAdbProtocol.readInt(input)
                val rotation = CwAdbProtocol.readInt(input)
                DeviceMessage.DisplayReady(displayId, width, height, density, rotation)
            }
            CwAdbProtocol.Device.CLIPBOARD ->
                DeviceMessage.Clipboard(String(CwAdbProtocol.readBytesWithLength(input, 64 * 1024), Charsets.UTF_8))
            CwAdbProtocol.Device.ERROR ->
                DeviceMessage.Error(String(CwAdbProtocol.readBytesWithLength(input, 4096), Charsets.UTF_8))
            CwAdbProtocol.Device.PONG -> DeviceMessage.Pong
            CwAdbProtocol.Device.LOG ->
                DeviceMessage.Log(String(CwAdbProtocol.readBytesWithLength(input, 4096), Charsets.UTF_8))
            else -> DeviceMessage.Error("未知回包类型 0x${type.toString(16)}")
        }
    }

    // ---------------------------------------------------------------- 5. 写

    fun sendTouch(action: Int, pointerId: Int, xNorm: Float, yNorm: Float) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.INJECT_TOUCH)
        CwAdbProtocol.writeByte(it, action)
        CwAdbProtocol.writeByte(it, pointerId)
        CwAdbProtocol.writeFloat(it, xNorm)
        CwAdbProtocol.writeFloat(it, yNorm)
    }

    fun sendKey(action: Int, keyCode: Int, repeat: Int = 0) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.INJECT_KEY)
        CwAdbProtocol.writeByte(it, action)
        CwAdbProtocol.writeInt(it, keyCode)
        CwAdbProtocol.writeInt(it, repeat)
    }

    fun sendScroll(xNorm: Float, yNorm: Float, hScroll: Float, vScroll: Float) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.INJECT_SCROLL)
        CwAdbProtocol.writeFloat(it, xNorm)
        CwAdbProtocol.writeFloat(it, yNorm)
        CwAdbProtocol.writeFloat(it, hScroll)
        CwAdbProtocol.writeFloat(it, vScroll)
    }

    /** 把某个 App 投到虚拟屏（displayId=0 表示手机主屏）。 */
    fun sendLaunchApp(component: String, displayId: Int) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.LAUNCH_APP)
        CwAdbProtocol.writeBytesWithLength(it, component.toByteArray(Charsets.UTF_8))
        CwAdbProtocol.writeInt(it, displayId)
    }

    fun sendMoveTask(taskId: Int, displayId: Int) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.MOVE_TASK)
        CwAdbProtocol.writeInt(it, taskId)
        CwAdbProtocol.writeInt(it, displayId)
    }

    /**
     * 注入一段文本。
     *
     * 手机侧会把它拆成按键事件逐个注入（跟真人打字同一条路径），
     * 所以只支持英文/符号；中文要走 [sendSetClipboard] 粘贴。
     */
    fun sendText(text: String) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.INJECT_TEXT)
        CwAdbProtocol.writeBytesWithLength(it, text.toByteArray(Charsets.UTF_8))
    }

    /** 把车机剪切板内容写到手机剪切板（中文只能走这条路）。 */
    fun sendSetClipboard(text: String) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.SET_CLIPBOARD)
        CwAdbProtocol.writeBytesWithLength(it, text.toByteArray(Charsets.UTF_8))
    }

    /** 请求手机当前剪切板内容，结果通过 [readDeviceMessage] 的 Clipboard 回包返回。 */
    fun sendGetClipboard() =
        sendControl { CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.GET_CLIPBOARD) }

    /**
     * 请求旋转虚拟屏。
     *
     * @param rotation 0/1/2/3 指定方向；-1 = 翻到另一个方向（车机上一个按钮两用）。
     */
    fun sendRotate(rotation: Int = -1) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.ROTATE)
        // 协议里 rotation 是有符号字节，-1 → 255
        CwAdbProtocol.writeByte(it, rotation and 0xFF)
    }

    /**
     * 把手机上的某个 App 投到指定屏。
     *
     * 传包名也行、传 `包名/Activity`（或 `包名/.Activity`）也行：包名先 `resolve-activity` 解析成组件，
     * 因为 server 侧走的是 `am start -n <组件>`，光给包名它没法直接启。
     *
     * @return 解析到组件并发出去了 = true；包名解析不到 = false（调用方去提示）
     */
    fun launchApp(pkgOrComponent: String, displayId: Int): Boolean {
        val component = resolveComponent(pkgOrComponent) ?: return false
        log("launchApp 解析：$pkgOrComponent -> $component（display $displayId）")
        sendLaunchApp(component, displayId)
        return true
    }

    private fun resolveComponent(pkgOrComponent: String): String? {
        if (pkgOrComponent.contains('/')) return pkgOrComponent
        val a = adb ?: return null
        val out = try {
            a.runShell("cmd package resolve-activity --brief $pkgOrComponent", 15_000)
        } catch (e: Exception) {
            log("resolve-activity 失败：${e.message}")
            return null
        }
        // 输出可能有前置警告行，取最后一个带 '/' 的行
        return out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && !it.contains(' ') }
    }

    fun sendScreenPower(mode: Int) = sendControl {
        CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.SET_SCREEN_POWER)
        CwAdbProtocol.writeByte(it, mode)
    }

    fun sendPing() = sendControl { CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.PING) }

    /** 请求一个关键帧：重建解码器后靠它拿到能独立解的第一帧。 */
    fun requestSyncFrame() =
        sendControl { CwAdbProtocol.writeByte(it, CwAdbProtocol.Control.REQUEST_SYNC_FRAME) }

    /** 控制通道写入线程：串行写出队列里的控制消息。 */
    private fun startControlWriter() {
        synchronized(controlLock) {
            if (controlWriter != null) return
            val t = Thread({
                while (true) {
                    val msg: ByteArray
                    synchronized(controlLock) {
                        while (controlQueue.isEmpty()) {
                            if (closed.get()) return@Thread
                            try {
                                // 带超时等，避免退出时漏 notify 而卡死
                                (controlLock as Object).wait(500)
                            } catch (e: InterruptedException) {
                                return@Thread
                            }
                        }
                        msg = controlQueue.removeFirst()
                    }
                    val c = control ?: return@Thread
                    try {
                        c.write(msg)
                    } catch (e: Exception) {
                        if (!closed.get()) log("控制消息写出失败：${e.javaClass.simpleName}: ${e.message}")
                        return@Thread
                    }
                }
            }, "cwy-control-writer")
            t.isDaemon = true
            controlWriter = t
            t.start()
        }
    }

    /**
     * 一条控制消息入队（**不在调用线程上做网络 I/O**）。
     *
     * 相邻的触摸 MOVE 会合并成一条：MOVE 是高频事件，画面跟手比“一个不漏”重要，
     * 而 DOWN/UP 永远不会被合并，所以手机侧指针状态不会黏住。
     */
    private fun sendControl(block: (ByteArrayOutputStream) -> Unit) {
        if (control == null) {
            log("控制通道没开，丢弃一条控制消息")
            return
        }
        val buffer = ByteArrayOutputStream(32)
        block(buffer)
        val bytes = buffer.toByteArray()
        synchronized(controlLock) {
            if (controlQueue.size > 512) {
                log("控制队列积压 ${controlQueue.size} 条，丢弃最旧的触摸 MOVE")
                controlQueue.clear()
            }
            if (isTouchMove(bytes) && isTouchMove(controlQueue.lastOrNull())) {
                controlQueue.removeLast()
            }
            controlQueue.addLast(bytes)
            (controlLock as Object).notifyAll()
        }
    }

    private fun isTouchMove(msg: ByteArray?): Boolean =
        msg != null && msg.size >= 2 &&
            msg[0] == CwAdbProtocol.Control.INJECT_TOUCH.toByte() &&
            msg[1] == CwAdbProtocol.TouchAction.MOVE.toByte()

    // ---------------------------------------------------------------- 收尾

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 叫醒写线程（否则它还在 wait 里），再关流让它退出
        synchronized(controlLock) {
            (controlLock as Object).notifyAll()
        }
        controlWriter = null
        try { video?.close() } catch (e: Exception) { log("关视频通道：${e.message}") }
        try { control?.close() } catch (e: Exception) { log("关控制通道：${e.message}") }
        video = null
        control = null
        controlIn = null
        // 车机主动断开时顺手把 server 收掉，别把手机上的进程留着
        try {
            if (serverPid > 0) {
                adb?.runShell("kill $serverPid 2>/dev/null")
                log("已通知 server（pid=$serverPid）退出")
            }
        } catch (e: Exception) {
            log("停 server 失败：${e.message}")
        }
        try { adb?.close() } catch (e: Exception) { log("关 adbd 连接：${e.message}") }
        adb = null
    }

    companion object {
        /** 一帧最大 4MB，超过必定是协议错位，早点断开比乱喂解码器强。 */
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
