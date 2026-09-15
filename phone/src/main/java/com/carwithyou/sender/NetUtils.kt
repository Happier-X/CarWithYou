package com.carwithyou.sender

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

object NetUtils {
    /** 尽量拿热点下的本机IP，车机连这个 */
    fun hotspotIp(ctx: Context): String {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            }
        } catch (_: Exception) {}
        // 热点模式下wlan0一般是192.168.43.1
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                ni.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(".") == true) {
                        val h = addr.hostAddress ?: ""
                        if (h.startsWith("192.168.43.") || h.startsWith("192.168.49.")) return h
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
