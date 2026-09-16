package com.carwithyou.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/**
 * 安卓版 CarPlay 主屏：跑在手机虚拟屏上，由手机渲染、车机只显示。
 * 深色 + 横屏大图标 + 状态栏时间。点图标在虚拟屏上切应用，
 * 车机左 Dock 发 APP_CMD 回来也是切这里（见 ScreenCastService.switchVirtualApp）。
 */
@Composable
fun DesktopScreen(
    time: String,
    onNav: () -> Unit,
    onMusic: () -> Unit,
    onSplit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 状态栏：左标题，右时间（电量/信号由车机桌面经 8890 STATUS 显示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("CarWithYou", color = Color.White, fontSize = 26.sp)
                    Text("手机主屏可继续用", color = Color(0xFF888888), fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(time, color = Color.White, fontSize = 44.sp)
            }
            Spacer(Modifier.height(16.dp))
            // 图标区
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HomeIcon("导航", Modifier.weight(2f), accent = true, onClick = onNav)
                HomeIcon("音乐", Modifier.weight(1f), accent = false, onClick = onMusic)
                HomeIcon("分屏", Modifier.weight(1f), accent = false, onClick = onSplit)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "车机点屏可直接反控 · 左 Dock 也可切应用",
                color = Color(0xFF666666),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun HomeIcon(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(if (accent) Color(0xFF1E88E5) else Color(0xFF1C1E21))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 40.sp)
    }
}
