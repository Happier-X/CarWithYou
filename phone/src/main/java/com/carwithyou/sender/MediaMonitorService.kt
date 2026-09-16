package com.carwithyou.sender

import android.service.notification.NotificationListenerService

/**
 * 空的通知监听，只当 MediaSessionManager.getActiveSessions 的凭证用，
 * 让车联能读到手机正在放的歌（歌名/状态/切歌控制）。
 * 有新通知时顺手推一次音乐刷新，车机不用等 2 秒轮询。
 */
class MediaMonitorService : NotificationListenerService() {

    override fun onListenerConnected() {
        AppLog.i(TAG, "音乐同步监听已连上，车机可看歌名/切歌")
        try { LinkService.refreshMusic() } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        AppLog.w(TAG, "音乐同步监听断了（系统杀了或开关被关），车机看不到歌名")
    }

    companion object {
        private const val TAG = "CarWithYou"
    }
}
