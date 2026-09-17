package com.carwithyou.adb

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 二进制协议编解码。
 *
 * 包结构（全部小端）：
 *   command(4) arg0(4) arg1(4) dataLength(4) dataChecksum(4) magic(4) + payload
 *   magic = command xor 0xFFFFFFFF
 *
 * 这个文件是 ADB 直连链路的协议事实来源，改协议先改这里。
 */
object AdbProtocol {
    const val CMD_SYNC = 0x434E5953
    const val CMD_CNXN = 0x4E584E43
    const val CMD_AUTH = 0x48545541
    const val CMD_OPEN = 0x4E45504F
    const val CMD_OKAY = 0x59414B4F
    const val CMD_CLSE = 0x45534C43
    const val CMD_WRTE = 0x45545257

    /** 我们声明的 ADB 协议版本。 */
    const val A_VERSION = 0x01000001

    /** AUTH 的 arg0：adbd 给的挑战 token。 */
    const val AUTH_TOKEN = 1

    /** AUTH 的 arg0：我们用私钥对 token 的签名。 */
    const val AUTH_SIGNATURE = 2

    /** AUTH 的 arg0：我们的 RSA 公钥（adbd 拿去弹授权框）。 */
    const val AUTH_RSA_PUBLIC = 3

    /** 我们单包 payload 的保守上限；adbd 会在 CNXN 的 arg1 里回协商值。 */
    const val CONNECT_MAXDATA = 256 * 1024

    const val HEADER_LENGTH = 24

    /** 保留给 adbd 包头开销的余量，别把包塞满。 */
    const val WRITE_MARGIN = 128

    fun name(command: Int): String = when (command) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_CLSE -> "CLSE"
        CMD_WRTE -> "WRTE"
        CMD_SYNC -> "SYNC"
        else -> "0x%08x".format(command)
    }

    fun checksum(payload: ByteArray): Int {
        var sum = 0L
        for (b in payload) sum += (b.toInt() and 0xFF)
        return sum.toInt()
    }

    fun encode(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LENGTH + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command xor 0xFFFFFFFF.toInt())
        buf.put(payload)
        return buf.array()
    }

    /** 从通道读一个完整包；对端正常关闭返回 null。 */
    fun read(channel: AdbChannel): AdbMessage? {
        val header = channel.readFully(HEADER_LENGTH) ?: return null
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        val checksum = buf.int
        val magic = buf.int
        if (magic != command xor 0xFFFFFFFF.toInt()) {
            throw ProtocolException("bad magic for ${name(command)}: 0x%08x".format(magic))
        }
        if (length < 0 || length > CONNECT_MAXDATA) {
            throw ProtocolException("bad payload length $length for ${name(command)}")
        }
        val payload = if (length > 0) {
            channel.readFully(length) ?: throw EOFException("truncated ${name(command)} payload")
        } else {
            ByteArray(0)
        }
        return AdbMessage(command, arg0, arg1, payload)
    }
}

/** ADB 的字符串 payload 以 NUL 结尾。顶层扩展函数。 */
fun String.toAdbPayload(): ByteArray = (this + "\u0000").toByteArray(Charsets.UTF_8)

class ProtocolException(message: String) : Exception(message)

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
) {
    val payloadString: String get() = String(payload, Charsets.UTF_8).trimEnd('\u0000')

    override fun toString(): String =
        "${AdbProtocol.name(command)} arg0=$arg0 arg1=$arg1 len=${payload.size}"
}

/** ADB 的字节流传输层。TCP 是主路径，USB 走同一接口。 */
interface AdbChannel : Closeable {
    /** 读满 length 字节；对端在读到任何字节前就关闭则返回 null。 */
    fun readFully(length: Int): ByteArray?
    fun write(data: ByteArray)
    fun flush()
}

class TcpChannel(
    host: String,
    port: Int,
    connectTimeoutMs: Int = 8_000,
) : AdbChannel {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream

    init {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun readFully(length: Int): ByteArray? {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = input.read(out, filled, length - filled)
            if (read < 0) return if (filled == 0) null else throw EOFException("channel closed mid-packet")
            filled += read
        }
        return out
    }

    override fun write(data: ByteArray) = output.write(data)

    override fun flush() = output.flush()

    override fun close() {
        runCatching { socket.close() }
    }
}
