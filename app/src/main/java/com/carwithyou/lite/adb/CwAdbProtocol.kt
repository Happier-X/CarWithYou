package com.carwithyou.lite.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ADB 链路（shell server）协议 v0.1 —— 车机端常量与字节序工具。
 *
 * 事实来源是 `server/.../protocol/Protocol.java` 和 `docs/PROTOCOL.md`；
 * 改协议先改那两处，再同步这里。
 *
 * 注意与 ADB 自身区分：**ADB 帧是小端，本协议全部大端**。
 */
object CwAdbProtocol {

    /** server 监听的 abstract socket 名。 */
    const val SOCKET_NAME = "carwithyou"

    /** 视频流开头魔数 'CWYV'。 */
    const val VIDEO_MAGIC = 0x43575956

    /** codec 四字符标识。 */
    const val CODEC_AVC = 0x61766331 // 'avc1'
    const val CODEC_HEVC = 0x68766331 // 'hvc1'

    /** 帧标志位。 */
    const val FRAME_FLAG_KEY = 0x01
    const val FRAME_FLAG_CONFIG = 0x02

    /** server jar 推到手机上的位置。 */
    const val REMOTE_JAR = "/data/local/tmp/carwithyou_server.jar"

    /** server 入口类。 */
    const val SERVER_MAIN_CLASS = "com.carwithyou.server.Server"

    /** server 就绪标记文件（由 server 自己写/删）。 */
    const val READY_FILE = "/data/local/tmp/cwy.ready"

    /** server 日志文件（app_process 的 stdout 不可靠，只作辅助）。 */
    const val SERVER_LOG_FILE = "/data/local/tmp/cwy.log"

    /** 控制通道：车机 → 手机。 */
    object Control {
        const val INJECT_TOUCH = 0x00
        const val INJECT_KEY = 0x01
        const val INJECT_SCROLL = 0x02
        const val INJECT_TEXT = 0x03
        const val LAUNCH_APP = 0x04
        const val MOVE_TASK = 0x05
        const val SET_SCREEN_POWER = 0x06
        const val SET_CLIPBOARD = 0x07
        const val GET_CLIPBOARD = 0x08
        const val ROTATE = 0x09
        const val PING = 0x0A
        const val REQUEST_SYNC_FRAME = 0x0B
    }

    /** 控制通道：手机 → 车机。 */
    object Device {
        const val DISPLAY_READY = 0x80
        const val CLIPBOARD = 0x81
        const val ERROR = 0x82
        const val PONG = 0x83
        const val LOG = 0x84
    }

    /** 触摸动作，和 MotionEvent 的 ACTION_* 语义对齐。 */
    object TouchAction {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
    }

    /** 按键动作。 */
    object KeyAction {
        const val DOWN = 0
        const val UP = 1
    }

    // ---------------------------------------------------------------- 写

    fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    fun writeByte(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
    }

    fun writeFloat(out: OutputStream, value: Float) {
        writeInt(out, java.lang.Float.floatToIntBits(value))
    }

    fun writeBytesWithLength(out: OutputStream, bytes: ByteArray) {
        writeInt(out, bytes.size)
        out.write(bytes)
    }

    // ---------------------------------------------------------------- 读

    fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("读到流末尾")
        return value
    }

    fun readInt(input: InputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if ((b1 or b2 or b3 or b4) < 0) throw EOFException("读到流末尾")
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    fun readFloat(input: InputStream): Float = java.lang.Float.intBitsToFloat(readInt(input))

    fun readFully(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = input.read(buffer, filled, length - filled)
            if (read < 0) throw EOFException("读到流末尾（还差 ${length - filled} 字节）")
            filled += read
        }
        return buffer
    }

    /** 读 `int 长度` + 内容；长度异常时抛，避免被畸形包带偏。 */
    fun readBytesWithLength(input: InputStream, maxLength: Int): ByteArray {
        val length = readInt(input)
        if (length < 0 || length > maxLength) {
            throw IOException("长度字段异常：$length")
        }
        return readFully(input, length)
    }
}
