package com.carwithyou.sender

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * 手机电话 → 车机：来电/通话状态同步，车机可点接听/挂断。
 * 接听/挂断走 TelecomManager（需 ANSWER_PHONE_CALLS 等电话权限，被拒就只显示状态）。
 */
class PhoneLink(
    private val ctx: Context,
    private val emit: (JSONObject) -> Unit
) {
    @Volatile var state = "idle" // idle | ringing | offhook
        private set
    @Volatile var number = ""
        private set
    @Volatile var name = ""
        private set

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(s: Int, incomingNumber: String?) {
            when (s) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    state = "ringing"
                    number = incomingNumber.orEmpty()
                    name = lookupName(number)
                    AppLog.i("Link", "来电：${name.ifBlank { number.ifBlank { "未知号码" } }}")
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    state = "offhook"
                    AppLog.i("Link", "通话中")
                }
                else -> {
                    if (state != "idle") AppLog.i("Link", "通话结束")
                    state = "idle"; number = ""; name = ""
                }
            }
            push()
        }
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("t", "PHONE")
        .put("state", state)
        .put("number", number)
        .put("name", name)

    private fun push() {
        try { emit(snapshot()) } catch (_: Exception) {}
    }

    fun start() {
        if (tm == null) {
            AppLog.w("Link", "没有 TelephonyManager，电话同步不可用")
            return
        }
        try {
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            AppLog.i("Link", "电话状态监听已开")
        } catch (e: SecurityException) {
            AppLog.w("Link", "电话同步要先给电话权限：${e.message}")
        } catch (e: Exception) {
            AppLog.w("Link", "电话监听开不起来：${e.message}")
        }
    }

    fun stop() {
        try {
            @Suppress("DEPRECATION")
            tm?.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}
    }

    private fun lookupName(num: String): String {
        if (num.isBlank()) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
    }

    fun cmd(cmd: String) {
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom == null) {
            AppLog.w("Link", "没有 TelecomManager，接/挂电话不可用")
            return
        }
        try {
            when (cmd) {
                "answer" -> {
                    telecom.acceptRingingCall()
                    AppLog.i("Link", "车机点了接听")
                }
                "hangup" -> {
                    if (Build.VERSION.SDK_INT >= 28) telecom.endCall()
                    AppLog.i("Link", "车机点了挂断")
                }
            }
        } catch (e: SecurityException) {
            AppLog.w("Link", "接/挂电话被系统拒绝，先给电话权限：${e.message}")
        } catch (e: Exception) {
            AppLog.wThrottle("Link", "接/挂电话失败：${e.message}", 5_000)
        }
    }
}
