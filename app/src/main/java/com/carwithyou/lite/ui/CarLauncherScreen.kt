package com.carwithyou.lite.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * vivo 车联风格车机桌面：状态栏 + 大卡片 + 底部 Dock。
 * 默认横屏，按钮够大，开车盲点可及。
 */
@Composable
fun CarLauncherScreen(
    time: String,
    date: String,
    connected: Boolean,
    connText: String,
    phoneIp: String,
    onCast: () -> Unit,
    onNav: () -> Unit,
    onMusic: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ── 状态栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(time, style = MiuixTheme.textStyles.title1)
                Spacer(Modifier.width(12.dp))
                Text(
                    date,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (connected) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    connText,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            // ── 主卡片区 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 左：手机投屏大卡
                Card(modifier = Modifier.weight(1.4f)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("手机投屏", style = MiuixTheme.textStyles.title2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "手机 $phoneIp · 虚拟屏（导航+音乐），手机还能用",
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            connText,
                            color = if (connected) Color(0xFF2E7D32) else MiuixTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onCast,
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            minHeight = 72.dp,
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(
                                if (connected) "进入投屏" else "连手机",
                                fontSize = 22.sp,
                                color = MiuixTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                // 右：本地应用
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("本地导航", style = MiuixTheme.textStyles.title3)
                            Text(
                                "车机版高德（不断手机时用）",
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.weight(1f))
                            DockButton("打开导航", onNav)
                        }
                    }
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("本地音乐", style = MiuixTheme.textStyles.title3)
                            Text(
                                "车机版音乐 + 悬浮歌词",
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.weight(1f))
                            DockButton("打开音乐", onMusic)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // ── 底部 Dock ──
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.weight(1f)) { DockButton("投屏", onCast, primary = true) }
                    Box(Modifier.weight(1f)) { DockButton("导航", onNav) }
                    Box(Modifier.weight(1f)) { DockButton("音乐", onMusic) }
                    Box(Modifier.weight(1f)) { DockButton("设置", onSettings) }
                }
            }
        }
    }
}

@Composable
private fun DockButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            minHeight = 60.dp,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(
                text,
                fontSize = 19.sp,
                color = MiuixTheme.colorScheme.onPrimary
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            minHeight = 60.dp
        ) {
            Text(text, fontSize = 19.sp)
        }
    }
}
