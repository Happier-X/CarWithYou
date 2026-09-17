package com.carwithyou.adb

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * 桌面自检：对着一个**真实 adbd** 把 ADB 协议栈完整跑一遍。
 *
 *   ./gradlew :adb-core:probe -Ptarget=127.0.0.1:5555
 *
 * 不需要真机和车机 —— MuMu 这类模拟器的 5555 就是现成的 adbd，
 * 协议实现有问题在这一步就暴露了，不用等上车。
 */
fun main(args: Array<String>) {
    // Windows 控制台默认 GBK，不换 UTF-8 会把中文输出打成乱码。
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))

    val target = args.firstOrNull() ?: "127.0.0.1:5555"
    var passed = 0
    var failed = 0

    fun check(name: String, block: () -> String) {
        try {
            println("  [PASS] $name — ${block()}")
            passed++
        } catch (t: Throwable) {
            println("  [FAIL] $name — ${t::class.simpleName}: ${t.message}")
            failed++
        }
    }

    println("== 1. 密钥对自检（不连设备） ==")
    check("n0inv 编码自洽") {
        val keys = AdbKeyPair.generate()
        check(keys.verifyEncoding()) { "n0inv * n[0] mod 2^32 != -1" }
        "通过"
    }
    check("公钥是 ADB 格式且 NUL 结尾") {
        val keys = AdbKeyPair.generate()
        val text = String(keys.adbPublicKeyBytes(), Charsets.UTF_8)
        check(text.endsWith("\u0000")) { "没有 NUL 结尾" }
        check(text.contains(' ')) { "base64 和名字之间没有空格" }
        "${text.length} 字符，名字=${keys.name}"
    }
    check("签名长度等于模长 256 字节") {
        val size = AdbKeyPair.generate().sign("token".toByteArray()).size
        check(size == 256) { "实际 $size" }
        "256 字节"
    }
    check("密钥对可存可复用") {
        val dir = File(System.getProperty("java.io.tmpdir"), "carwithyou-adbkey-selftest")
        try {
            dir.deleteRecursively()
            val first = AdbKeyPair.loadOrCreate(dir)
            val second = AdbKeyPair.loadOrCreate(dir)
            check(first.publicKey == second.publicKey) { "两次读出的公钥不一致" }
            "复用成功（${first.name}）"
        } finally {
            dir.deleteRecursively()
        }
    }

    println("== 2. 连 adbd（$target） ==")
    val host = target.substringBeforeLast(':', target)
    val port = target.substringAfterLast(':').toIntOrNull() ?: 5555
    val keyDir = File(System.getProperty("java.io.tmpdir"), "carwithyou-adbkey-probe")

    val adb = try {
        Adb(host, port, AdbKeyPair.loadOrCreate(keyDir), log = { println("       $it") })
    } catch (t: Throwable) {
        println("  [FAIL] 连接/握手 — ${t::class.simpleName}: ${t.message}")
        println()
        println("结果：$passed 通过 / ${failed + 1} 失败（连不上，后面的用例跳过）")
        return
    }

    try {
        check("CNXN 握手拿到协商 maxdata") {
            check(adb.maxData > 0) { "maxdata=${adb.maxData}" }
            "maxdata=${adb.maxData}，banner=${adb.banner.take(40)}"
        }
        check("shell: 跑命令") {
            val out = adb.runShell("getprop ro.product.model").trim()
            check(out.isNotEmpty()) { "输出为空" }
            out
        }
        check("shell: 常驻通道可读写（双向流）") {
            val shell = adb.openShell()
            try {
                shell.write("echo CWMARK-OK\n".toByteArray())
                val deadline = System.currentTimeMillis() + 5_000
                val sb = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    sb.append(String(shell.readAvailable(), Charsets.UTF_8))
                    if (sb.contains("CWMARK-OK")) break
                    Thread.sleep(50)
                }
                check(sb.contains("CWMARK-OK")) { "没等到回显，收到=${sb.toString().trim().take(80)}" }
                "回显正常"
            } finally {
                shell.close()
            }
        }
        check("sync 推文件并可读回") {
            val marker = "carwithyou-probe-${System.currentTimeMillis()}"
            val remote = "/data/local/tmp/carwithyou_probe.txt"
            adb.pushBytes("$marker\n".toByteArray(), remote)
            val back = adb.runShell("cat $remote").trim()
            adb.runShell("rm -f $remote")
            check(back == marker) { "读回内容=「$back」，期望=「$marker」" }
            "${"$marker".length} 字节往返一致"
        }
        check("localabstract 连已有 socket（tcp:5555 自连）") {
            val stream = adb.tcpForward(5555)
            stream.close()
            "OKAY 正常，通道已关"
        }
        check("localabstract 连不存在的 socket 会失败而不是挂死") {
            try {
                adb.localSocketForward("carwithyou_definitely_missing_socket")
                error("不该成功")
            } catch (e: Exception) {
                if (e is IllegalStateException) throw e
                "按预期失败：${e.message}"
            }
        }
    } finally {
        adb.close()
        keyDir.deleteRecursively()
    }

    println()
    println("结果：$passed 通过 / $failed 失败")
    if (failed > 0) kotlin.system.exitProcess(1)
}
