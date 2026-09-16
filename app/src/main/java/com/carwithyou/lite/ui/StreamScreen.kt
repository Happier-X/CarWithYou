package com.carwithyou.lite.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * vivo 车联风格：画面全屏，控制条悬浮在顶部，状态一行小字。
 * 画面区域触摸直接透给 SurfaceView 做反控，控制条不挡手势。
 */
@Composable
fun StreamScreen(
    ip: String,
    status: String,
    onIpChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCopyLog: () -> Unit,
    onSurface: (SurfaceView) -> Unit,
    onAppCmd: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 全屏画面（手机虚拟屏：导航+音乐）
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also(onSurface) },
            modifier = Modifier.fillMaxSize()
        )
        // 顶部悬浮控制条
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = ip,
                    onValueChange = onIpChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = "手机IP",
                    useLabelAsPlaceholder = true
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "连接",
                    onClick = onConnect,
                    modifier = Modifier.height(48.dp),
                    minHeight = 48.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "断开",
                    onClick = onDisconnect,
                    modifier = Modifier.height(48.dp),
                    minHeight = 48.dp
                )
            }
            Text(
                "$status · 声音走手机蓝牙 · 点画面可反控手机虚拟屏",
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // 左侧 Dock（CarPlay 式）：发 APP_CMD 回手机切虚拟屏应用，不进画面触摸
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .background(Color(0xAA000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DockKey("主屏") { onAppCmd("home") }
            DockKey("导航") { onAppCmd("nav") }
            DockKey("音乐") { onAppCmd("music") }
            DockKey("分屏") { onAppCmd("split") }
        }
        // 右下角小按钮：复制日志（连不上/黑屏时用）
        TextButton(
            text = "日志",
            onClick = onCopyLog,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .height(40.dp),
            minHeight = 40.dp
        )
    }
}

@Composable
private fun DockKey(text: String, onClick: () -> Unit) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.width(64.dp).height(52.dp),
        minHeight = 52.dp
    )
}
