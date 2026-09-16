package com.carwithyou.sender

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内诊断日志：内存环形缓冲 + 崩溃留档，设置页一键复制/查看。
 *
 * 存在的意义：出问题时光靠 Toast 那句「启动失败：xxx」查不动，接 adb 又不现实。
 * 这里把关键事件、被 catch 掉的异常、上次崩溃的栈都攒起来，用户点一下「复制诊断日志」
 * 就能把原文（含设备/版本/当前投屏状态）发回来。
 *
 * 环形缓冲常驻内存，崩溃时的最后一条会同步落到 SharedPreferences，
 * 所以「崩了 → 重启 → 打开设置复制日志」这条路也查得到。
 */
object AppLog {

    /** logcat 里仍用统一 TAG，方便 adb 对照 */
    private const val LOGCAT_TAG = "CarWithYou"
    private const val MAX_LINES = 500
    private const val MAX_TRACE_LINES = 24
    private const val PREFS = "diagnostics"
    private const val KEY_LINES = "log_lines"
    private const val KEY_CRASH = "last_crash"
    /** 正常情况下的写盘节流；w/e 与崩溃会立刻落盘 */
    private const val PERSIST_INTERVAL_MS = 5_000L

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val windows = HashMap<String, Window>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var sp: SharedPreferences? = null
    private var startedAt = 0L
    private var lastPersistAt = 0L
    private var inited = false
    private var crash: String? = null

    /** dump 时追加的一段运行时状态，由 [SenderApp] 注入（避免本类依赖业务状态） */
    @Volatile var stateProvider: (() -> String)? = null

    private class Window(var until: Long, var merged: Int)

    /** 要在任何 Log 调用之前先跑（Application.onCreate） */
    fun init(app: Context) {
        synchronized(lock) {
            if (inited) return
            inited = true
            val ctx = app.applicationContext
            sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            startedAt = SystemClock.elapsedRealtime()
            sp?.getString(KEY_LINES, null)?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.forEach { lines.addLast(it) }
            crash = sp?.getString(KEY_CRASH, null)
            installCrashHandler()
            record(
                Log.INFO, "App",
                "Car投屏-手机端 v${BuildConfig.VERSION_NAME} 启动 · Android ${Build.VERSION.RELEASE}(SDK${Build.VERSION.SDK_INT}) " +
                    "· ${Build.MANUFACTURER} ${Build.MODEL}"
            )
            if (!crash.isNullOrBlank()) record(Log.WARN, "App", "上次运行异常退出，详情见诊断日志")
        }
    }

    // ── 记录 ─────────────────────────────────────────────

    fun v(tag: String, msg: String) = record(Log.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = record(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = record(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = record(Log.WARN, tag, msg, null)
    fun e(tag: String, msg: String) = record(Log.ERROR, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable?) = record(Log.ERROR, tag, msg, tr)

    /**
     * 热路径专用（推流/解码循环里的 catch）：同一条消息在 windowMs 内只记一次，
     * 被合并掉的条数补到下一条上。消息带变量的（比如累计次数）用 key 固定住，
     * 否则每条都算新消息，既节流不了也会把窗口表撑爆。
     */
    fun wThrottle(tag: String, msg: String, windowMs: Long = 5_000, key: String = msg): Boolean =
        throttled(tag, msg, key, Log.WARN, null, windowMs)

    fun eThrottle(
        tag: String,
        msg: String,
        tr: Throwable? = null,
        windowMs: Long = 5_000,
        key: String = msg
    ): Boolean = throttled(tag, msg, key, Log.ERROR, tr, windowMs)

    private fun throttled(
        tag: String,
        msg: String,
        key: String,
        prio: Int,
        tr: Throwable?,
        windowMs: Long
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        var merged = 0
        val allow = synchronized(lock) {
            if (windows.size > 128) windows.clear()
            val w = windows.getOrPut(key) { Window(0, 0) }
            if (now >= w.until) {
                w.until = now + windowMs
                merged = w.merged
                w.merged = 0
                true
            } else {
                w.merged++
                false
            }
        }
        if (allow) {
            record(prio, tag, if (merged > 0) "$msg（同类另有 $merged 条已合并）" else msg, tr)
        }
        return allow
    }

    private fun record(prio: Int, tag: String, msg: String, tr: Throwable? = null) {
        val trText = tr?.let { trace(it) }
        val sb = StringBuilder()
        sb.append(nowTime()).append(' ')
            .append(LEVELS[prio.coerceIn(2, 6)]).append('/').append(tag).append(": ").append(msg)
        if (trText != null) {
            trText.lineSequence().take(MAX_TRACE_LINES).forEach {
                sb.append('\n').append("    ").append(it)
            }
        }
        val line = sb.toString()
        val important = prio >= Log.WARN
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.println(prio, "$LOGCAT_TAG/$tag", msg)
        if (trText != null) Log.println(prio, "$LOGCAT_TAG/$tag", trText)
        persist(important)
    }

    /** SimpleDateFormat 不线程安全（推流线程/主线程会同时写），所有格式化都走锁 */
    private fun nowTime(): String = synchronized(lock) { timeFmt.format(Date()) }

    private fun nowStamp(): String = synchronized(lock) { stampFmt.format(Date()) }

    // ── 取用 ─────────────────────────────────────────────

    fun size(): Int = synchronized(lock) { lines.size }

    fun hasCrash(): Boolean = synchronized(lock) { !crash.isNullOrBlank() }

    /** 清空内存和落盘的日志/崩溃记录 */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            windows.clear()
            crash = null
        }
        runCatching { sp?.edit()?.clear()?.apply() }
        lastPersistAt = SystemClock.elapsedRealtime()
    }

    /** 复制用的全文：设备信息 + 当前状态 + 上次崩溃 + 日志 */
    fun dump(): String = buildDump()

    private fun buildDump(): String {
        val snapshot = synchronized(lock) { lines.toList() }
        val crashText = synchronized(lock) { crash }
        val sb = StringBuilder()
        sb.append("CarWithYou · 手机端 v").append(BuildConfig.VERSION_NAME)
            .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("时间：").append(nowStamp()).append('\n')
        val up = SystemClock.elapsedRealtime() - startedAt
        if (startedAt > 0) {
            sb.append("本次运行：").append(up / 60_000).append("分").append(up % 60_000 / 1000).append("秒\n")
        }
        runCatching { stateProvider?.invoke()?.takeIf { it.isNotBlank() } }.getOrNull()?.let {
            sb.append("── 当前状态 ──\n").append(it).append('\n')
        }
        if (!crashText.isNullOrBlank()) {
            sb.append("── 上次异常退出 ──\n").append(crashText).append('\n')
        }
        sb.append("── 日志（").append(snapshot.size).append(" 条）──\n")
        snapshot.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    // ── 内部 ─────────────────────────────────────────────

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(Log.ERROR, "Crash", "未捕获异常（线程 ${t.name}）：${e.message ?: e.javaClass.name}", e)
            // 进程马上就没了，这次必须同步落盘，连日志缓冲一起
            synchronized(lock) {
                crash = "${nowStamp()} ${t.name}\n" +
                    trace(e).lineSequence().take(MAX_TRACE_LINES).joinToString("\n")
                runCatching {
                    sp?.edit()
                        ?.putString(KEY_CRASH, crash)
                        ?.putString(KEY_LINES, lines.joinToString("\n"))
                        ?.commit()
                }
            }
            runCatching { prev?.uncaughtException(t, e) }
        }
    }

    /** 平时异步落盘（崩溃路径在自己的 handler 里同步 commit，不走这里） */
    private fun persist(important: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!important && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        val snapshot = synchronized(lock) { lines.joinToString("\n") }
        val edit = sp?.edit()?.putString(KEY_LINES, snapshot) ?: return
        runCatching { edit.apply() }
    }

    private fun trace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private val LEVELS = arrayOf(" ", " ", "V", "D", "I", "W", "E")
}
