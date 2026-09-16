package com.carwithyou.sender

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.json.JSONObject

/**
 * 手机音乐 → 车机：读当前 MediaSession（需通知监听权限），车机发命令回来控制。
 * 不碰音频流本身，声音还是走蓝牙；这里只同步歌名/状态/下发播放控制。
 */
class MusicLink(
    private val ctx: Context,
    private val emit: (JSONObject) -> Unit
) {
    private var lastPush = ""

    fun snapshot(): JSONObject = JSONObject()
        .put("t", "MUSIC")
        .put("app", lastApp)
        .put("title", lastTitle)
        .put("artist", lastArtist)
        .put("playing", lastPlaying)

    @Volatile private var lastApp = ""
    @Volatile private var lastTitle = ""
    @Volatile private var lastArtist = ""
    @Volatile private var lastPlaying = false

    private fun controller(): MediaController? {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val cn = ComponentName(ctx, MediaMonitorService::class.java)
        val sessions = try {
            msm.getActiveSessions(cn)
        } catch (e: SecurityException) {
            AppLog.w("Link", "读音乐状态要先开通知监听（车机歌词监听那个开关）")
            return null
        } catch (e: Exception) {
            AppLog.wThrottle("Link", "读 MediaSession 失败：${e.message}", 10_000)
            return null
        }
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions[0]
    }

    /** 2 秒轮询一次，变了才推，车机不刷屏 */
    fun refresh() {
        val c = controller()
        if (c == null) {
            push("", "", "", false)
            return
        }
        val md = try { c.metadata } catch (_: Exception) { null }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val app = try { c.packageName } catch (_: Exception) { "" }
        val playing = try {
            c.playbackState?.state == PlaybackState.STATE_PLAYING
        } catch (_: Exception) { false }
        push(app, title, artist, playing)
    }

    private fun push(app: String, title: String, artist: String, playing: Boolean) {
        lastApp = app; lastTitle = title; lastArtist = artist; lastPlaying = playing
        val s = "$app|$title|$artist|$playing"
        if (s == lastPush) return
        lastPush = s
        try { emit(snapshot()) } catch (_: Exception) {}
    }

    fun cmd(cmd: String) {
        val c = controller()
        if (c == null) {
            AppLog.w("Link", "车机点了 $cmd，但手机没在放音乐（或没开通知监听）")
            return
        }
        try {
            val tc = c.transportControls
            when (cmd) {
                "play" -> tc.play()
                "pause" -> tc.pause()
                "toggle" -> if (lastPlaying) tc.pause() else tc.play()
                "next" -> tc.skipToNext()
                "prev" -> tc.skipToPrevious()
                else -> return
            }
            AppLog.i("Link", "车机音乐命令：$cmd → $lastApp")
        } catch (e: SecurityException) {
            AppLog.w("Link", "音乐控制被系统拒绝（通知监听没开？）：${e.message}")
        } catch (e: Exception) {
            AppLog.wThrottle("Link", "音乐控制失败：${e.message}", 5_000)
        }
        // 给播放器一点反应时间再读新状态
        try { Thread.sleep(350) } catch (_: Exception) {}
        refresh()
    }
}
