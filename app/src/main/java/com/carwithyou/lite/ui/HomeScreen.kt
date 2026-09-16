package com.carwithyou.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    status: String,
    navPkgs: List<String>,
    musicPkgs: List<String>,
    navIndex: Int,
    musicIndex: Int,
    onNavIndex: (Int) -> Unit,
    onMusicIndex: (Int) -> Unit,
    onOpenNav: () -> Unit,
    onOpenMusic: () -> Unit,
    onSplit: () -> Unit,
    onSave: () -> Unit,
    onCast: () -> Unit,
    onLyricOn: () -> Unit,
    onLyricOff: () -> Unit,
    onNotif: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text("CarWithYou · 科鲁泽", style = MiuixTheme.textStyles.title2)
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Card {
                        WindowDropdownPreference(
                            items = navPkgs,
                            selectedIndex = navIndex.coerceIn(0, (navPkgs.size - 1).coerceAtLeast(0)),
                            title = "导航",
                            onSelectedIndexChange = onNavIndex
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BigButton("打开高德", onOpenNav)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Card {
                        WindowDropdownPreference(
                            items = musicPkgs,
                            selectedIndex = musicIndex.coerceIn(0, (musicPkgs.size - 1).coerceAtLeast(0)),
                            title = "音乐",
                            onSelectedIndexChange = onMusicIndex
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BigButton("打开音乐", onOpenMusic)
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("一键分屏：导航 + 音乐", onSplit)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("保存选择", onSave)
            Spacer(Modifier.height(8.dp))
            BigButton("连手机虚拟屏（导航+音乐上车，手机还能用）", onCast)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            SmallTitle(
                "悬浮歌词（浮在导航上）",
                insideMargin = PaddingValues(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onLyricOn,
                    modifier = Modifier.weight(1f).height(64.dp),
                    minHeight = 64.dp,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("开启悬浮歌词", fontSize = 16.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
                Button(
                    onClick = onLyricOff,
                    modifier = Modifier.weight(1f).height(64.dp),
                    minHeight = 64.dp
                ) {
                    Text("关闭", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("给通知监听权限（拿歌名用）", onNotif)
            Spacer(Modifier.height(16.dp))
            Text(
                "自用Tips：本地歌词丢 /Music/lyrics/歌名-歌手.lrc 最准；车机热点用手机的，QQ音乐同账号登录歌单自动同步。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        minHeight = 72.dp,
        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
        Text(text, fontSize = 20.sp, color = MiuixTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        minHeight = 56.dp
    )
}
