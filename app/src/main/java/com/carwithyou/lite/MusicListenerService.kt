package com.carwithyou.lite

import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.*

/**
 * 监听车机上所有播放器（QQ音乐车机版/网易云/酷狗），拿歌名+歌手，
 * 然后抓歌词，发广播给悬浮窗。
 * 自用：不需要各家音乐SDK，用系统MediaSession就行，换播放器照样 work。
 */
class MusicListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastKey = ""
    private var loggedSong = ""
    private var lastLyricAt = 0L
    private var controllers: List<MediaController> = emptyList()
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = poll()
        override fun onPlaybackStateChanged(state: PlaybackState?) = poll()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i("Lyric", "通知监听已连上")
        attachSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLog.w("Lyric", "通知监听被断开，尝试重连")
        requestRebind(android.content.ComponentName(this, MusicListenerService::class.java))
    }

    private fun attachSessions() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionListener)
            msm.addOnActiveSessionsChangedListener(sessionListener, null)
            onSessions(msm.getActiveSessions(null))
        } catch (e: SecurityException) {
            AppLog.w("Lyric", "拿不到 MediaSession，缺通知监听权限：${e.message}")
        } catch (e: Exception) {
            AppLog.w("Lyric", "监听播放器失败：${e.message}")
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { onSessions(it) }

    private fun onSessions(list: List<MediaController>?) {
        controllers.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        controllers = list ?: emptyList()
        controllers.forEach { try { it.registerCallback(cb) } catch (_: Exception) {} }
        poll()
    }

    private fun poll() {
        // 找正在播放的那个
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return
        val md = playing.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().trim()
        if (title.isBlank()) return
        val key = "$title - $artist"
        if (key != loggedSong) {
            loggedSong = key
            AppLog.i("Lyric", "切歌：$key（来自 ${playing.packageName}）")
        }
        if (key == lastKey && System.currentTimeMillis() - lastLyricAt < 10_000) {
            // 同一首，推进度歌词
            pushProgress(playing, title, artist)
            return
        }
        lastKey = key
        scope.launch {
            val lines = LyricFetcher.getLyricLines(this@MusicListenerService, title, artist)
            LyricFetcher.cacheLines(key, lines)
            lastLyricAt = System.currentTimeMillis()
            if (lines.isEmpty()) {
                AppLog.wThrottle("Lyric", "没拿到歌词：$key（本地 /Music/lyrics 也没这个 lrc）", 20_000)
            }
            pushProgress(playing, title, artist)
        }
    }

    private fun pushProgress(c: MediaController, title: String, artist: String) {
        val pos = try { c.playbackState?.position ?: 0L } catch (_: Exception) { 0L }
        val key = "$title - $artist"
        val (l1, l2) = LyricFetcher.pickByProgress(key, pos)
        val show1 = l1.ifBlank { "$title - $artist" }
        sendBroadcast(Intent(LyricOverlayService.ACTION_LYRIC_UPDATE).apply {
            setPackage(packageName)
            putExtra(LyricOverlayService.EXTRA_LINE1, show1)
            putExtra(LyricOverlayService.EXTRA_LINE2, l2)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
