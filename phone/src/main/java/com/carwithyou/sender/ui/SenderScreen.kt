package com.carwithyou.sender.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SenderScreen(
    ip: String,
    status: String,
    stats: String,
    modeHint: String,
    navLabels: List<String>,
    musicLabels: List<String>,
    navIndex: Int,
    musicIndex: Int,
    virtualMode: Boolean,
    keepScreen: Boolean,
    adaptive: Boolean,
    debugSave: Boolean,
    onNavIndex: (Int) -> Unit,
    onMusicIndex: (Int) -> Unit,
    onVirtual: (Boolean) -> Unit,
    onKeepScreen: (Boolean) -> Unit,
    onAdaptive: (Boolean) -> Unit,
    onDebug: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRelaunch: () -> Unit,
    onOverlay: () -> Unit,
    onAccess: () -> Unit,
    onBattery: () -> Unit
) {
    Scaffold(
        topBar = { SmallTopAppBar(title = "手机端 · 独立虚拟屏投车机") }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "1.开热点  2.车机连热点  3.选导航/音乐  4.开始投屏（点一次允许）  5.车机填这个IP。声音走蓝牙，画面走WiFi。默认不占手机主屏。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(12.dp))
            Card {
                Text(
                    ip,
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
                Text(
                    status,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    stats,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
            SmallTitle("车上打开")
            Card {
                WindowDropdownPreference(
                    items = navLabels,
                    selectedIndex = navIndex.coerceIn(0, (navLabels.size - 1).coerceAtLeast(0)),
                    title = "导航",
                    onSelectedIndexChange = onNavIndex
                )
                WindowDropdownPreference(
                    items = musicLabels,
                    selectedIndex = musicIndex.coerceIn(0, (musicLabels.size - 1).coerceAtLeast(0)),
                    title = "音乐",
                    onSelectedIndexChange = onMusicIndex
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = "开始投屏",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                minHeight = 64.dp,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "停止",
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                minHeight = 52.dp
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "重新把导航/音乐丢到车机屏",
                onClick = onRelaunch,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                minHeight = 52.dp
            )
            SmallTitle("投屏选项")
            Card {
                SwitchPreference(
                    checked = virtualMode,
                    onCheckedChange = onVirtual,
                    title = "独立虚拟屏（推荐，不占手机）",
                    summary = modeHint
                )
                SwitchPreference(
                    checked = keepScreen,
                    onCheckedChange = onKeepScreen,
                    title = "保持手机亮屏",
                    summary = "部分 ROM 灭屏会停虚拟屏"
                )
                SwitchPreference(
                    checked = adaptive,
                    onCheckedChange = onAdaptive,
                    title = "自适应码率",
                    summary = "虚拟屏不切分辨率，避免车上 App 被杀"
                )
                SwitchPreference(
                    checked = debugSave,
                    onCheckedChange = onDebug,
                    title = "同时保存 H264",
                    summary = "写到 /Movies/carwithyou"
                )
            }
            SmallTitle("权限")
            Card {
                ArrowPreference(
                    title = "开悬浮窗（车机屏切换栏）",
                    onClick = onOverlay
                )
                ArrowPreference(
                    title = "开触摸回传（无障碍）",
                    onClick = onAccess
                )
                ArrowPreference(
                    title = "电池白名单（防杀后台）",
                    onClick = onBattery
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "原理：默认建一块独立虚拟屏，高德/音乐在那块屏上跑，编码后发给车机。手机主屏还能自己用。若 ROM 不让第三方 App 上副屏，再关掉「独立虚拟屏」改镜像。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
