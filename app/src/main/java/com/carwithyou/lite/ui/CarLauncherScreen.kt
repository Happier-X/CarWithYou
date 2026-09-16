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
 * 小米 CarWith 式车机桌面：状态栏 + 左导航大卡 + 右音乐/电话原生卡 + 底部 Dock。
 * 音乐/电话是结构化数据（手机 8890 推过来），不是投屏画面，可直接点控。
 * 导航画面仍走视频投屏（点左大卡进入）。
 */
@Composable
fun CarLauncherScreen(
    time: String,
    date: String,
    connected: Boolean,
    connText: String,
    phoneIp: String,
    musicTitle: String,
    musicArtist: String,
    musicPlaying: Boolean,
    callState: String,
    callName: String,
    battText: String,
    onCast: () -> Unit,
    onDirect: (String) -> Unit,
    onSettings: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onAnswer: () -> Unit,
    onHangup: () -> Unit
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
                if (battText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(battText, color = Color(0xFF2E7D32), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            // ── 主卡片区 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 左：导航大卡（视频投屏入口）
                Card(modifier = Modifier.weight(1.4f)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("导航", style = MiuixTheme.textStyles.title2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "手机 $phoneIp · 虚拟屏，手机还能用",
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
                                if (connected) "进入导航" else "连手机",
                                fontSize = 22.sp,
                                color = MiuixTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                // 右：音乐 + 电话原生卡
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("音乐", style = MiuixTheme.textStyles.title3)
                            Text(
                                if (musicTitle.isBlank()) "手机没在放（放首歌自动出）"
                                else "$musicTitle — $musicArtist",
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { SmallButton("上曲", onPrev) }
                                Box(Modifier.weight(1f)) {
                                    SmallButton(if (musicPlaying) "暂停" else "播放", onPlayPause)
                                }
                                Box(Modifier.weight(1f)) { SmallButton("下曲", onNext) }
                            }
                        }
                    }
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("电话", style = MiuixTheme.textStyles.title3)
                            Text(
                                when (callState) {
                                    "ringing" -> "来电：$callName"
                                    "offhook" -> "通话中 $callName"
                                    else -> "无通话（来电自动弹）"
                                },
                                color = if (callState == "idle") MiuixTheme.colorScheme.onBackgroundVariant
                                else Color(0xFF2E7D32),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.weight(1f))
                            if (callState == "ringing") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) { SmallButton("接听", onAnswer) }
                                    Box(Modifier.weight(1f)) { SmallButton("挂断", onHangup) }
                                }
                            } else if (callState == "offhook") {
                                SmallButton("挂断", onHangup)
                            }
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
                    Box(Modifier.weight(1f)) { DockButton("主屏", { onDirect("home") }) }
                    Box(Modifier.weight(1f)) { DockButton("导航", { onDirect("nav") }) }
                    Box(Modifier.weight(1f)) { DockButton("分屏", { onDirect("split") }) }
                    Box(Modifier.weight(1f)) { DockButton("设置", onSettings) }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        minHeight = 52.dp
    ) {
        Text(text, fontSize = 17.sp)
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
