package com.carwithyou.lite.ui

import android.view.SurfaceView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StreamScreen(
    ip: String,
    status: String,
    onIpChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSurface: (SurfaceView) -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    modifier = Modifier.height(56.dp),
                    minHeight = 56.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "断开",
                    onClick = onDisconnect,
                    modifier = Modifier.height(56.dp),
                    minHeight = 56.dp
                )
            }
            Text(
                status,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also(onSurface) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Text(
                "声音走手机蓝牙连车。默认看的是手机独立虚拟屏（高德+音乐），不是手机主屏。可点屏幕反控。",
                modifier = Modifier.padding(12.dp),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 12.sp
            )
        }
    }
}
