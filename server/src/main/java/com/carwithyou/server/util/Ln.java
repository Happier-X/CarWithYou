package com.carwithyou.server.util;

import android.util.Log;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * server 的日志。
 *
 * 同时发两个地方：
 *  1. **logcat**（主通道）——车机端收集诊断日志走这里最稳，`adb logcat -s CwServer` 直接看。
 *  2. **stdout**（辅通道）——app_process 的 stdout 会经 adbd 的 shell 通道回到车机，
 *     但某些 ROM / 调用方式下这条通道可能是空的，所以日志不能只靠它。
 *
 * 注意：控制台输出必须走 UTF-8，否则中文在车机上就是乱码。
 */
public final class Ln {

    /** 0=静默 1=普通 2=详细。启动时按参数决定。 */
    public static volatile int logMode = 1;

    private static final String TAG = "CwServer";

    private static final PrintStream OUT = createStdout();

    private static PrintStream createStdout() {
        try {
            return new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 拿不到 stdout 也不能让日志初始化把进程搞挂
            return null;
        }
    }

    private Ln() {
    }

    private static void log(int priority, String message) {
        String line = TAG + " " + message;
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) {
            // logcat 不可用（罕见）时静默
        }
        if (OUT != null) {
            OUT.println(line);
        }
    }

    public static void d(String message) {
        if (logMode >= 2) {
            log(Log.DEBUG, message);
        }
    }

    public static void i(String message) {
        if (logMode >= 1) {
            log(Log.INFO, message);
        }
    }

    public static void w(String message) {
        if (logMode >= 1) {
            log(Log.WARN, message);
        }
    }

    public static void e(String message) {
        log(Log.ERROR, message);
    }

    public static void e(String message, Throwable throwable) {
        log(Log.ERROR, message + "\n" + stackTrace(throwable));
    }

    /** 只打异常本身，不打堆栈 —— 用于预期内的失败（比如某条 API 路径不可用）。 */
    public static void eShort(String message, Throwable throwable) {
        log(Log.ERROR, message + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    public static String stackTrace(Throwable throwable) {
        if (throwable == null) {
            return "(null)";
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
