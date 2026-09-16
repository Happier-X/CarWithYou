package com.carwithyou.lite

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 诊断日志的取用处：一键复制到剪贴板，或者弹个框先看一眼再复制。
 * 复制出来的内容 = AppLog.dump()（设备/版本 + 当前收流状态 + 上次崩溃 + 日志）。
 */
object Diagnostics {

    /** 设置项上那行小字 */
    fun hint(): String {
        val n = AppLog.size()
        val crash = if (AppLog.hasCrash()) " · 上次异常退出过" else ""
        return if (n == 0) "暂无日志$crash" else "共 $n 条$crash · 可复制发送"
    }

    /** 直接复制，返回复制了多少行（0 = 剪贴板写不进去） */
    fun copy(context: Context): Int {
        val text = AppLog.dump()
        val ok = copyText(context, "CarWithYou 诊断日志", text, toast = false)
        val lines = text.lineSequence().count()
        Toast.makeText(
            context,
            if (ok) "已复制诊断日志（$lines 行），直接粘贴发出去就行"
            else "系统不让写剪贴板，可以在「诊断日志」里长按选中",
            Toast.LENGTH_LONG
        ).show()
        AppLog.i("App", "已复制诊断日志（$lines 行，clipboard=$ok）")
        return if (ok) lines else 0
    }

    /** 拷任意一段文本（当前状态/报错原文），toast 可关 */
    fun copyText(context: Context, label: String, text: String, toast: Boolean = true): Boolean {
        val ok = runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
        if (toast) {
            Toast.makeText(
                context,
                if (ok) "已复制到剪贴板" else "系统不让写剪贴板，可以长按文字选中再复制",
                Toast.LENGTH_SHORT
            ).show()
        }
        return ok
    }

    /** 弹窗先看内容，里面带「复制全文」「清空」 */
    fun show(activity: Activity, onChanged: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics
        ).toInt()
        val body = TextView(activity).apply {
            text = AppLog.dump()
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(pad, pad, pad, pad)
            // 主题里 textColor 不一定给到，直接写死深底白字
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(18, 22, 26))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("诊断日志 · ${AppLog.size()} 条")
                .setView(scroll)
                .setPositiveButton("复制全文") { _, _ -> copy(activity) }
                .setNeutralButton("清空") { _, _ ->
                    AppLog.clear()
                    Toast.makeText(activity, "日志已清空", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                .setNegativeButton("关闭", null)
                .show()
        }.onFailure { AppLog.w("App", "诊断日志弹窗失败：${it.message}") }
    }
}
