package com.carwithyou.sender

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件更新：查 GitHub Release 的最新版，比本机新就提示下载安装。
 * 手机端只认 `-phone-` 那个 APK，车机端只认 `-car-` 的（同一个 Release 里两个都在）。
 */
object UpdateChecker {

    private const val REPO = "Happier-X/CarWithYou"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 这个包要捡哪个 APK：手机 phone / 车机 car */
    private const val ARTIFACT = "phone"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    val idleHint: String get() = "当前 v$currentVersion · 点击检查更新"

    sealed class Result {
        /** 有新版 */
        class Newer(
            val version: String,
            val notes: String,
            val apkUrl: String?,
            val pageUrl: String
        ) : Result()

        /** 已是最新 */
        class Latest(val version: String) : Result()

        /** 查不动：没网 / GitHub 不通 / 还没发过 Release */
        class Fail(val message: String) : Result()
    }

    /** 点一下检查更新：后台查，查完弹框或在设置项上回一行状态 */
    fun checkFrom(activity: Activity, onStatus: (String) -> Unit) {
        onStatus("正在检查更新…")
        Thread {
            val r = check()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (r) {
                    is Result.Newer -> {
                        onStatus("发现新版本 v${r.version}（当前 v$currentVersion）")
                        dialog(activity, r)
                    }
                    is Result.Latest -> {
                        onStatus("已是最新版 v${r.version}")
                        toast(activity, "已是最新版 v${r.version}")
                    }
                    is Result.Fail -> {
                        onStatus("检查失败：${r.message}")
                        toast(activity, "检查更新失败：${r.message}")
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 阻塞式网络请求，必须在子线程调用 */
    fun check(): Result = try {
        val code = intArrayOf(0)
        val body = get(API, code)
        if (body.isNullOrBlank()) return Result.Fail(httpHint(code[0]))
        val json = JSONObject(body)
        val tag = json.optString("tag_name").trim()
        if (tag.isBlank()) return Result.Fail("Release 没有版本号")
        val latest = tag.trimStart('v', 'V')
        val pageUrl = json.optString("html_url").ifBlank { "https://github.com/$REPO/releases" }
        if (newerThan(latest, currentVersion)) {
            Result.Newer(latest, json.optString("body").trim(), apkUrl(json), pageUrl)
        } else {
            Result.Latest(latest)
        }
    } catch (e: Exception) {
        Result.Fail(e.message ?: e.javaClass.simpleName)
    }

    /** 下载 APK，下载完直接拉安装界面 */
    fun downloadAndInstall(context: Context, apkUrl: String, version: String) {
        val app = context.applicationContext
        try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = "CarWithYou-$ARTIFACT-v$version.apk"
            val id = dm.enqueue(
                DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("CarWithYou v$version")
                    .setDescription("下载完自动弹安装")
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                    runCatching { app.unregisterReceiver(this) }
                    val uri = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull()
                    if (uri == null) {
                        toast(app, "下载失败，可以手动去 GitHub 下载")
                        return
                    }
                    install(app, uri)
                }
            }
            // 系统广播（DownloadManager 发的），要收就显式 EXPORTED
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            toast(app, "开始下载 v$version，完成后自动弹安装")
        } catch (e: Exception) {
            toast(app, "下载不了：${e.message ?: e.javaClass.simpleName}")
            openPage(app, "https://github.com/$REPO/releases")
        }
    }

    fun openPage(context: Context, url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            toast(context, "打不开浏览器，地址：$url")
        }
    }

    private fun install(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            toast(context, "装不了：先允许「安装未知应用」，或手动装 下载 目录里的 APK")
        }
    }

    private fun dialog(activity: Activity, r: Result.Newer) {
        val notes = r.notes.lines().filter { it.isNotBlank() }.take(16).joinToString("\n")
            .ifBlank { "这个版本没写更新说明" }
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("发现新版本 v${r.version}（当前 v$currentVersion）")
                .setMessage(notes)
                .setPositiveButton("下载并安装") { _, _ ->
                    val url = r.apkUrl
                    if (url.isNullOrBlank()) openPage(activity, r.pageUrl)
                    else downloadAndInstall(activity, url, r.version)
                }
                .setNeutralButton("去 GitHub") { _, _ -> openPage(activity, r.pageUrl) }
                .setNegativeButton("稍后") { _, _ -> }
                .show()
        }
    }

    private fun apkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val hit = names.firstOrNull {
            val n = it.optString("name")
            n.contains("-$ARTIFACT-") && n.endsWith(".apk", ignoreCase = true)
        } ?: names.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        return hit?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
    }

    /** 1.2.10 > 1.2.9，比 BuildConfig.VERSION_NAME 大才算新版 */
    private fun newerThan(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until 3) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String) = Regex("\\d+").findAll(v).map { it.value.toInt() }.take(3).toList()

    private fun get(url: String, outCode: IntArray): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarWithYou-$ARTIFACT-update")
        }
        return try {
            outCode[0] = conn.responseCode
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpHint(code: Int): String = when (code) {
        0 -> "连不上 GitHub（车机没网？热点里手机不给外网？）"
        403, 429 -> "GitHub 限流了，过会儿再试"
        404 -> "还没发过 Release"
        else -> "GitHub 返回 $code"
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}