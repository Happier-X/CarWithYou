package com.carwithyou.adb

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条到手机 adbd 的连接。
 *
 * 车机端从这里拿到 shell（跑 app_process 起 server）、localabstract（连 server 的 socket）、
 * sync（推 server jar 到 /data/local/tmp）三件事，剩下的都是在这之上的业务。
 */
class Adb(
    host: String,
    port: Int,
    private val keyPair: AdbKeyPair? = null,
    private val log: (String) -> Unit = {},
) : Closeable, StreamSender {

    private val channel: AdbChannel = TcpChannel(host, port)
    private val localIds = AtomicInteger(1)

    /** 已经 OPEN 成功的通道。 */
    private val streams = ConcurrentHashMap<Int, AdbStream>()

    /** 已经发出 OPEN、还在等 OKAY 的通道。 */
    private val pendingOpen = ConcurrentHashMap<Int, AdbStream>()

    private val sendLock = Any()
    private var authRound = 0
    private var reader: Thread? = null

    @Volatile
    private var closed = false

    /** adbd 回的 `device::ro.product.model=...` 那一串。 */
    @Volatile
    var banner: String = ""
        private set

    /** adbd 在 CNXN 里协商的单包上限，必须按它切片，不能用我们自己声明的值。 */
    @Volatile
    var maxData: Int = AdbProtocol.CONNECT_MAXDATA
        private set

    init {
        handshake()
        reader = Thread(::readLoop, "adb-reader").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------- 握手

    private fun handshake() {
        log("-> CNXN host:: 声明 maxdata=${AdbProtocol.CONNECT_MAXDATA}")
        send(
            AdbProtocol.encode(
                AdbProtocol.CMD_CNXN,
                AdbProtocol.A_VERSION,
                AdbProtocol.CONNECT_MAXDATA,
                "host::".toAdbPayload(),
            )
        )
        while (true) {
            val message = AdbProtocol.read(channel) ?: throw ProtocolException("握手阶段 adbd 断开连接")
            when (message.command) {
                AdbProtocol.CMD_CNXN -> {
                    maxData = message.arg1
                    banner = message.payloadString
                    log("<- CNXN 协商 maxdata=$maxData banner=${banner.take(48)}")
                    return
                }
                AdbProtocol.CMD_AUTH -> handleAuth(message)
                else -> throw ProtocolException("握手阶段收到非预期包：$message")
            }
        }
    }

    private fun handleAuth(message: AdbMessage) {
        val keys = keyPair ?: throw ProtocolException("adbd 要求 RSA 认证，但没有可用密钥对")
        if (message.arg0 != AdbProtocol.AUTH_TOKEN) {
            throw ProtocolException("AUTH 收到非预期 arg0=${message.arg0}")
        }
        // 第一轮用私钥签 token；adbd 不认（手机没授权过这个公钥）就第二轮上报公钥，
        // 手机上会弹「是否允许 USB 调试」，用户允许后 adbd 才回 CNXN。
        val firstRound = authRound == 0
        authRound++
        val type: Int
        val payload: ByteArray
        if (firstRound) {
            type = AdbProtocol.AUTH_SIGNATURE
            payload = keys.sign(message.payload)
            log("-> AUTH SIGNATURE (签 ${message.payload.size} 字节 token)")
        } else {
            type = AdbProtocol.AUTH_RSA_PUBLIC
            payload = keys.adbPublicKeyBytes()
            log("-> AUTH RSA_PUBLIC（手机上应弹出授权框，请勾「一律允许」）")
        }
        send(AdbProtocol.encode(AdbProtocol.CMD_AUTH, type, 0, payload))
    }

    // ------------------------------------------------------------ 收包循环

    private fun readLoop() {
        try {
            while (!closed) {
                val message = AdbProtocol.read(channel) ?: break
                dispatch(message)
            }
        } catch (e: Exception) {
            if (!closed) log("收包线程结束：$e")
        } finally {
            teardown()
        }
    }

    private fun dispatch(message: AdbMessage) {
        when (message.command) {
            AdbProtocol.CMD_OKAY -> {
                val localId = message.arg1
                val pending = pendingOpen.remove(localId)
                if (pending != null) {
                    // OPEN 的 OKAY：arg0 是对端的 channel id，同时就是写许可。
                    pending.remoteId = message.arg0
                    pending.markWritable()
                    streams[localId] = pending
                    pending.opened.countDown()
                } else {
                    streams[localId]?.markWritable()
                }
            }

            AdbProtocol.CMD_WRTE -> {
                val localId = message.arg1
                streams[localId]?.push(message.payload)
                // 每收一片都回 OKAY，否则对端不会再发下一片（ADB 是乒乓流控）。
                send(AdbProtocol.encode(AdbProtocol.CMD_OKAY, localId, message.arg0))
            }

            AdbProtocol.CMD_CLSE -> {
                val localId = message.arg1
                val stream = pendingOpen.remove(localId) ?: streams.remove(localId)
                if (stream != null) {
                    stream.markClosed()
                    // OPEN 被拒时也要放行 open() 的等待，别让它一直挂到超时。
                    stream.opened.countDown()
                }
            }

            AdbProtocol.CMD_OPEN -> {
                // adbd 主动开通道（reverse forward 用）。本阶段不支持，直接拒绝。
                log("<- OPEN 来自 adbd，暂不支持，已拒绝：${message.payloadString}")
                runCatching { send(AdbProtocol.encode(AdbProtocol.CMD_CLSE, 0, message.arg0)) }
            }

            else -> log("<- 忽略 ${AdbProtocol.name(message.command)}")
        }
    }

    // ------------------------------------------------------------ 通道开关

    fun open(destination: String, multipleSend: Boolean = false, timeoutMs: Long = 10_000): AdbStream {
        check(!closed) { "ADB 连接已关闭" }
        val localId = localIds.getAndIncrement()
        val stream = AdbStream(localId, this, { maxData }, multipleSend)
        pendingOpen[localId] = stream
        log("-> OPEN $destination (local=$localId)")
        send(AdbProtocol.encode(AdbProtocol.CMD_OPEN, localId, 0, destination.toAdbPayload()))
        if (!stream.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pendingOpen.remove(localId)
            throw SocketTimeoutException("OPEN $destination 超时（${timeoutMs}ms）")
        }
        if (stream.remoteId == 0) throw IOException("OPEN $destination 被 adbd 拒绝")
        stream.markWritable()
        return stream
    }

    override fun sendWrite(stream: AdbStream, payload: ByteArray) {
        send(AdbProtocol.encode(AdbProtocol.CMD_WRTE, stream.localId, stream.remoteId, payload))
    }

    override fun sendClose(stream: AdbStream) {
        streams.remove(stream.localId)
        runCatching { send(AdbProtocol.encode(AdbProtocol.CMD_CLSE, stream.localId, stream.remoteId)) }
    }

    // ------------------------------------------------------------ 常用服务

    /** 跑一条 shell 命令，返回 stdout+stderr。adbd 在命令结束时关通道。 */
    fun runShell(command: String, timeoutMs: Long = 60_000): String {
        val stream = open("shell:$command", multipleSend = true)
        val bytes = try {
            stream.readAll(timeoutMs)
        } finally {
            stream.close()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 开一条常驻 shell，用来跑 `app_process` 起 server 并持续读它的日志。 */
    fun openShell(): AdbStream = open("shell:", multipleSend = true)

    /** 连设备上的 abstract socket。server 端监听的就是这个。 */
    fun localSocketForward(socketName: String): AdbStream =
        open("localabstract:$socketName", multipleSend = true)

    /** 让 adbd 去连设备本机的某个 TCP 端口。 */
    fun tcpForward(port: Int): AdbStream = open("tcp:$port", multipleSend = true)

    // ------------------------------------------------------------ 推文件

    /**
     * 用 sync 服务推一个文件到设备上。
     *
     * 经典 sync 协议：`SEND` + 长度，随后 payload 是 `"<路径>,<十进制 mode>"`；
     * 然后若干 `DATA` + 长度 + 内容；最后 `DONE` + mtime，adbd 回 `OKAY` 或 `FAIL`，
     * 再 `QUIT` 关通道。
     */
    fun pushFile(source: File, remotePath: String, timeoutMs: Long = 120_000) {
        pushBytes(source.readBytes(), remotePath, timeoutMs = timeoutMs)
    }

    fun pushBytes(
        bytes: ByteArray,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE,
        mtimeSeconds: Int = (System.currentTimeMillis() / 1000).toInt(),
        timeoutMs: Long = 120_000,
    ) {
        val stream = open("sync:", multipleSend = true)
        try {
            val spec = "$remotePath,$mode"
            log("-> sync SEND $spec (${bytes.size} 字节)")
            stream.write(syncHeader(SYNC_SEND, spec.length))
            stream.write(spec.toByteArray(Charsets.UTF_8))

            var offset = 0
            while (offset < bytes.size) {
                val take = minOf(SYNC_CHUNK, bytes.size - offset)
                stream.write(syncHeader(SYNC_DATA, take))
                stream.write(bytes.copyOfRange(offset, offset + take))
                offset += take
            }

            stream.write(syncHeader(SYNC_DONE, mtimeSeconds))

            // adbd 用 8 字节头回 OKAY / FAIL。
            val reply = stream.readFully(8, timeoutMs)
            val replyId = String(reply, 0, 4, Charsets.UTF_8)
            val replyArg = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (replyId == SYNC_FAIL) {
                val message = if (replyArg > 0) String(stream.readFully(replyArg, timeoutMs), Charsets.UTF_8) else ""
                throw IOException("sync 写 $remotePath 失败：$message")
            }
            if (replyId != SYNC_OKAY) {
                throw IOException("sync 收到非预期回包：$replyId")
            }
            stream.write(syncHeader(SYNC_QUIT, 0))
        } finally {
            stream.close()
        }
    }

    // ---------------------------------------------------------------- 收尾

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
        reader?.interrupt()
        teardown()
    }

    private fun teardown() {
        for (stream in pendingOpen.values) {
            stream.markClosed()
            stream.opened.countDown()
        }
        for (stream in streams.values) stream.markClosed()
        pendingOpen.clear()
        streams.clear()
    }

    private fun send(packet: ByteArray) {
        synchronized(sendLock) {
            channel.write(packet)
            channel.flush()
        }
    }

    private fun syncHeader(id: String, arg: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.UTF_8))
            .putInt(arg)
            .array()

    companion object {
        /** 0o100666：普通文件、rw-rw-rw-。手机侧 /data/local/tmp 里够用。 */
        const val DEFAULT_FILE_MODE = 33206

        private const val SYNC_CHUNK = 64 * 1024
        private const val SYNC_SEND = "SEND"
        private const val SYNC_DATA = "DATA"
        private const val SYNC_DONE = "DONE"
        private const val SYNC_QUIT = "QUIT"
        private const val SYNC_OKAY = "OKAY"
        private const val SYNC_FAIL = "FAIL"
    }
}
