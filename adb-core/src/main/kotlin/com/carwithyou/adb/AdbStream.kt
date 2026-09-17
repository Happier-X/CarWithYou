package com.carwithyou.adb

import java.io.EOFException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** AdbStream 通过它把出站字节交给 Adb 去发，避免循环引用。 */
interface StreamSender {
    fun sendWrite(stream: AdbStream, payload: ByteArray)
    fun sendClose(stream: AdbStream)
}

/**
 * 一条 ADB 逻辑通道：OPEN 之后的一对 (localId, remoteId)。
 *
 * 入站数据由 Adb 的收包线程 push 进来，读方按需取；出站按 adbd 协商的 maxData 切片成 WRTE。
 * 默认要等对端 OKAY 才能写下一片（ADB 是乒乓流控）；[multipleSend] 打开则连发不等待，
 * 对应 adb 自己的 canMultipleSend 语义，用于 shell: 这种单向大流量。
 */
class AdbStream internal constructor(
    val localId: Int,
    private val sender: StreamSender,
    private val maxData: () -> Int,
    val multipleSend: Boolean,
) {
    @Volatile
    internal var remoteId: Int = 0

    /** OPEN 收到 OKAY 后放行，remoteId 才有意义。 */
    internal val opened = CountDownLatch(1)

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()

    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var buffered = 0

    @Volatile
    private var closed = false

    @Volatile
    private var writable = false

    val size: Int
        get() = lock.withLock { buffered }

    val isClosed: Boolean get() = closed

    internal fun push(data: ByteArray) {
        if (data.isEmpty()) return
        lock.withLock {
            chunks.addLast(data)
            buffered += data.size
            dataAvailable.signalAll()
        }
    }

    internal fun markWritable() {
        writable = true
    }

    internal fun markClosed() {
        closed = true
        lock.withLock { dataAvailable.signalAll() }
    }

    /** 读满 length 字节；流在读满前关闭抛 EOFException，超时抛 SocketTimeoutException。 */
    fun readFully(length: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        if (length < 0) throw IllegalArgumentException("length=$length")
        val out = ByteArray(length)
        var filled = 0
        lock.lock()
        try {
            val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000 else 0L
            while (filled < length) {
                if (buffered == 0) {
                    if (closed) {
                        throw EOFException("流 $localId 已关闭，还差 ${length - filled} 字节")
                    }
                    awaitData(deadline)
                    continue
                }
                filled += takeLocked(out, filled, length - filled)
            }
        } finally {
            lock.unlock()
        }
        return out
    }

    fun readInt(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int =
        ByteBuffer.wrap(readFully(4, timeoutMs)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()

    fun readLong(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Long =
        ByteBuffer.wrap(readFully(8, timeoutMs)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()

    /** 把已缓冲的数据全部取走，取不到就等；流关闭后返回剩余部分。 */
    fun readAvailable(): ByteArray {
        lock.lock()
        try {
            if (buffered == 0) return ByteArray(0)
            val out = ByteArray(buffered)
            var filled = 0
            while (filled < out.size) filled += takeLocked(out, filled, out.size - filled)
            return out
        } finally {
            lock.unlock()
        }
    }

    /** 一直读到流关闭，返回全部内容。shell: 执行一条命令就用这个。 */
    fun readAll(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        lock.lock()
        try {
            val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000 else 0L
            while (true) {
                if (buffered == 0) {
                    if (closed) break
                    awaitData(deadline)
                    continue
                }
                val chunk = ByteArray(buffered)
                var filled = 0
                while (filled < chunk.size) filled += takeLocked(chunk, filled, chunk.size - filled)
                out.write(chunk, 0, filled)
            }
        } finally {
            lock.unlock()
        }
        return out.toByteArray()
    }

    /**
     * 把这个流适配成 [java.io.InputStream]，给“按字节解析协议”的代码用（流关闭时返回 -1）。
     *
     * 用无限等（timeoutMs=0）：超时策略交给调用方，
     * 免得 InputStream.read() 里冒出 SocketTimeoutException 这种不合契约的异常。
     */
    fun inputStream(): java.io.InputStream = object : java.io.InputStream() {
        override fun read(): Int = try {
            readFully(1, 0)[0].toInt() and 0xFF
        } catch (e: EOFException) {
            -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return try {
                val bytes = readFully(len, 0)
                System.arraycopy(bytes, 0, b, off, len)
                len
            } catch (e: EOFException) {
                -1
            }
        }

        override fun available(): Int = size
    }

    /** 阻塞直到这一片可以写。 */
    fun awaitWritable(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (writable || multipleSend) return
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!writable && !closed) {
            if (System.nanoTime() > deadline) throw SocketTimeoutException("流 $localId 等 OKAY 超时")
            Thread.sleep(1)
        }
    }

    fun write(data: ByteArray) {
        val max = maxData()
        var offset = 0
        while (offset < data.size) {
            val take = minOf(max - AdbProtocol.WRITE_MARGIN, data.size - offset)
            awaitWritable()
            sender.sendWrite(this, data.copyOfRange(offset, offset + take))
            // 非连发模式下，这一片要等下一个 OKAY 才能继续写。
            if (!multipleSend) writable = false
            offset += take
        }
    }

    fun write(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        write(bytes)
    }

    fun close() {
        if (closed) return
        markClosed()
        if (remoteId != 0) sender.sendClose(this)
    }

    private fun takeLocked(dest: ByteArray, destOffset: Int, want: Int): Int {
        val head = chunks.first()
        val available = head.size - headOffset
        val take = minOf(available, want)
        System.arraycopy(head, headOffset, dest, destOffset, take)
        headOffset += take
        buffered -= take
        if (headOffset == head.size) {
            chunks.removeFirst()
            headOffset = 0
        }
        return take
    }

    private fun awaitData(deadline: Long) {
        if (deadline == 0L) {
            dataAvailable.await()
            return
        }
        val remain = deadline - System.nanoTime()
        if (remain <= 0) throw SocketTimeoutException("流 $localId 读超时")
        if (!dataAvailable.await(remain, java.util.concurrent.TimeUnit.NANOSECONDS)) {
            throw SocketTimeoutException("流 $localId 读超时")
        }
    }

    override fun toString(): String = "AdbStream(local=$localId, remote=$remoteId, closed=$closed)"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
