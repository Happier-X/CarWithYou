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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    status: String,
    hasLocalApps: Boolean,
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
    onNotif: () -> Unit,
    autoConnect: Boolean,
    onAutoConnect: (Boolean) -> Unit,
    phoneIpText: String,
    onPhoneIp: (String) -> Unit,
    btNames: List<String>,
    btIndex: Int,
    onBtIndex: (Int) -> Unit,
    btAuto: Boolean,
    onBtAuto: (Boolean) -> Unit,
    updateHint: String,
    onUpdate: () -> Unit,
    logHint: String,
    onShowLog: () -> Unit,
    onCopyLog: () -> Unit,
    onCopyStatus: () -> Unit,
    adbTarget: String = "",
    onAdbTarget: (String) -> Unit = {},
    adbMirror: Boolean = false,
    onAdbMirror: (Boolean) -> Unit = {},
    adbNavPkg: String = "",
    onAdbNavPkg: (String) -> Unit = {},
    adbMusicPkg: String = "",
    onAdbMusicPkg: (String) -> Unit = {},
    adbAutoLaunch: Boolean = true,
    onAdbAutoLaunch: (Boolean) -> Unit = {},
    onAdbCast: () -> Unit = {},
    onAdbPair: () -> Unit = {}
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
            if (hasLocalApps) {
                LocalAppsBlock(
                    navPkgs = navPkgs,
                    musicPkgs = musicPkgs,
                    navIndex = navIndex,
                    musicIndex = musicIndex,
                    onNavIndex = onNavIndex,
                    onMusicIndex = onMusicIndex,
                    onOpenNav = onOpenNav,
                    onOpenMusic = onOpenMusic,
                    onSplit = onSplit,
                    onSave = onSave
                )
            } else {
                Card {
                    Text(
                        "车机没装本地导航/音乐，导航音乐全走手机投屏：回桌面点投屏或左 Dock 直达。",
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            SmallTitle("上车即连（蓝牙）")
            Card {
                SwitchPreference(
                    checked = btAuto,
                    onCheckedChange = onBtAuto,
                    title = "蓝牙连上手机自动连车联",
                    summary = "跟手机蓝牙配对一次，以后上车蓝牙一连就自动连车联，不用手点"
                )
                WindowDropdownPreference(
                    items = btNames,
                    selectedIndex = btIndex.coerceIn(0, (btNames.size - 1).coerceAtLeast(0)),
                    title = "我的手机（已配对蓝牙里选）",
                    onSelectedIndexChange = onBtIndex
                )
            }
            SmallTitle("自动连接（上车即连）")
            Card {
                SwitchPreference(
                    checked = autoConnect,
                    onCheckedChange = onAutoConnect,
                    title = "记住手机自动连",
                    summary = if (autoConnect) "开机/进桌面自动连下面这个手机IP，断线自动重连" else "关了就只手动点连接"
                )
                TextField(
                    value = phoneIpText,
                    onValueChange = onPhoneIp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = "手机IP（连上一次自动记）",
                    useLabelAsPlaceholder = true
                )
            }
            Spacer(Modifier.height(8.dp))
            BigButton("连手机虚拟屏（导航+音乐上车，手机还能用）", onCast)
            Spacer(Modifier.height(20.dp))
            SmallTitle("A 路线：ADB 链路（手机零安装）")
            Card {
                TextField(
                    value = adbTarget,
                    onValueChange = onAdbTarget,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = "手机 ADB 地址 host:port（如 192.168.1.20:5555）",
                    useLabelAsPlaceholder = true
                )
                SwitchPreference(
                    checked = adbMirror,
                    onCheckedChange = onAdbMirror,
                    title = "镜像手机主屏",
                    summary = if (adbMirror) {
                        "车机看手机上那一个屏（手机不能干别的）"
                    } else {
                        "默认：在手机上新建独立虚拟屏，投进去的 App 跟手机互不干扰"
                    }
                )
                TextField(
                    value = adbNavPkg,
                    onValueChange = onAdbNavPkg,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = "手机侧导航包名（默认 com.autonavi.minimap）",
                    useLabelAsPlaceholder = true
                )
                TextField(
                    value = adbMusicPkg,
                    onValueChange = onAdbMusicPkg,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = "手机侧音乐包名（默认 com.tencent.qqmusic）",
                    useLabelAsPlaceholder = true
                )
                SwitchPreference(
                    checked = adbAutoLaunch,
                    onCheckedChange = onAdbAutoLaunch,
                    title = "连上就自动投导航",
                    summary = if (adbAutoLaunch) {
                        "进投屏页直接把导航 App 投到虚拟屏（必开：空屏没有任何画面）"
                    } else {
                        "关掉后需要手动点 Dock 上的导航/音乐才会出画面"
                    }
                )
                Text(
                    "手机要先开无线调试（或 USB 插车机做过转发），并在手机上同意一次调试授权。" +
                        "第一次用先点下面的「无线配对手机」，输入手机上的配对码即可，之后就不用再管了。" +
                        "投屏时手机的调试通知不能关。",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAdbPair,
                    modifier = Modifier.weight(1f).height(64.dp),
                    minHeight = 64.dp
                ) {
                    Text("无线配对手机")
                }
                Button(
                    onClick = onAdbCast,
                    modifier = Modifier.weight(1.4f).height(64.dp),
                    minHeight = 64.dp
                ) {
                    Text("ADB 投屏（独立虚拟屏）")
                }
            }
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
            SmallTitle("软件更新")
            Card {
                ArrowPreference(
                    title = "检查更新",
                    summary = updateHint,
                    onClick = onUpdate
                )
            }
            SmallTitle("诊断")
            Card {
                ArrowPreference(
                    title = "诊断日志",
                    summary = logHint,
                    onClick = onShowLog
                )
                ArrowPreference(
                    title = "复制诊断日志",
                    summary = "出问题时点一下，把整段粘贴发回给开发者（含上次崩溃现场）",
                    onClick = onCopyLog
                )
                ArrowPreference(
                    title = "复制当前状态",
                    summary = "只要系统版本/权限/最近一次收流状态这几行",
                    onClick = onCopyStatus
                )
            }
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
private fun LocalAppsBlock(
    navPkgs: List<String>,
    musicPkgs: List<String>,
    navIndex: Int,
    musicIndex: Int,
    onNavIndex: (Int) -> Unit,
    onMusicIndex: (Int) -> Unit,
    onOpenNav: () -> Unit,
    onOpenMusic: () -> Unit,
    onSplit: () -> Unit,
    onSave: () -> Unit
) {
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
