package com.carwithyou.lite

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * 亿连式上车即连：记住手机蓝牙 MAC，蓝牙连上就自动连车联，不用手点。
 * 纯 App 层：只读已配对列表 + 收 ACL 广播，不碰系统蓝牙协议栈。
 */
object BtHelper {

    /** 已配对设备（名字，MAC），没权限/没开蓝牙就空列表 */
    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<Pair<String, String>> {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices
                ?.map { d -> (d.name ?: d.address) to d.address }
                ?.sortedBy { it.first }
                .orEmpty()
        } catch (e: SecurityException) {
            AppLog.w("BT", "读配对列表要先给蓝牙权限：${e.message}")
            emptyList()
        } catch (e: Exception) {
            AppLog.w("BT", "读配对列表失败：${e.message}")
            emptyList()
        }
    }

    fun label(ctx: Context, mac: String): String {
        if (mac.isBlank()) return "还没选"
        return bonded(ctx).firstOrNull { it.second == mac }?.first ?: mac
    }

    /** 广播里拿设备，拿不到返回 null */
    fun deviceOf(intent: android.content.Intent): BluetoothDevice? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        } catch (_: Exception) { null }
    }
}
