package com.carwithyou.sender

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carwithyou.sender.ui.theme.CarSenderTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 亿连式手机驾驶模式：横屏大按钮，开车盲操作。
 * 大时钟 + 当前歌曲 + 播放/下曲 + 免打扰开关 + 退出。
 * 平时不用，是车机连上（通知）或设置页点“进入驾驶模式”才进。
 */
class CarModeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var time by mutableStateOf("--:--")
    private var title by mutableStateOf("")
    private var artist by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var dnd by mutableStateOf(false)
    private var linkUp by mutableStateOf(false)

    private val tick = object : Runnable {
        override fun run() {
            try {
                time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            } catch (_: Exception) {}
            title = LinkService.lastMusicTitle.take(24)
            artist = LinkService.lastMusicArtist.take(24)
            playing = LinkService.lastMusicPlaying
            linkUp = LinkService.running && LinkService.clientCount > 0
            dnd = isDndOn()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.i(TAG, "进驾驶模式")
        handler.post(tick)
        setContent {
            CarSenderTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(time, style = MiuixTheme.textStyles.title1)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (linkUp) "车联已连" else "车联未连（先连车机）",
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (dnd) "免打扰开" else "免打扰关",
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Column(Modifier.padding(20.dp)) {
                                Text("正在播放", style = MiuixTheme.textStyles.title2)
                                Text(
                                    if (title.isBlank()) "手机没在放" else "$title — $artist",
                                    style = MiuixTheme.textStyles.title3,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                Row {
                                    BigBtn(
                                        if (playing) "暂停" else "播放",
                                        Modifier.weight(1f)
                                    ) { LinkService.musicCmd(if (playing) "pause" else "play") }
                                    Spacer(Modifier.width(12.dp))
                                    BigBtn("下曲", Modifier.weight(1f)) { LinkService.musicCmd("next") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            BigBtn("导航", Modifier.weight(1f)) { openApp(navPkg(), "高德装了吗") }
                            Spacer(Modifier.width(12.dp))
                            BigBtn("音乐", Modifier.weight(1f)) { openApp(musicPkg(), "音乐装了吗") }
                            Spacer(Modifier.width(12.dp))
                            BigBtn("电话", Modifier.weight(1f)) { openDialer() }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            BigBtn(
                                if (dnd) "关免打扰" else "开免打扰",
                                Modifier.weight(1f)
                            ) { toggleDnd() }
                            Spacer(Modifier.width(12.dp))
                            BigBtn("退出驾驶", Modifier.weight(1f), primary = false) { finish() }
                        }
                    }
                }
            }
        }
    }

    private fun navPkg(): String {
        return try { SenderPrefs(this).navPkg } catch (_: Exception) { KnownApps.AMAP }
    }

    private fun musicPkg(): String {
        return try { SenderPrefs(this).musicPkg } catch (_: Exception) { KnownApps.QQ_MUSIC }
    }

    /** 普通启动（主屏，零特殊权限），镜像画面同步跟过去 */
    private fun openApp(pkg: String, missingHint: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                Toast.makeText(this, missingHint, Toast.LENGTH_LONG).show()
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "驾驶舱打开 $pkg 失败：${e.message}")
        }
    }

    private fun openDialer() {
        val cands = listOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.huawei.contacts",
            "com.miui.contacts"
        )
        for (pkg in cands) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "拨号盘打不开", Toast.LENGTH_LONG).show()
        }
    }

    private fun isDndOn(): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) { false }
    }

    private fun toggleDnd() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                Toast.makeText(this, "先给“勿扰权限”，再点一次", Toast.LENGTH_LONG).show()
                return
            }
            if (isDndOn()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                AppLog.i(TAG, "驾驶模式：免打扰已关")
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                AppLog.i(TAG, "驾驶模式：免打扰已开（只留重要来电）")
            }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "免打扰切换被拒：${e.message}")
            Toast.makeText(this, "切不了免打扰：${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.w(TAG, "免打扰切换失败：${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarWithYou"
    }
}

@Composable
private fun BigBtn(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    onClick: () -> Unit
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(72.dp),
            minHeight = 72.dp,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(text, fontSize = 22.sp, color = MiuixTheme.colorScheme.onPrimary)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(72.dp),
            minHeight = 72.dp
        ) {
            Text(text, fontSize = 22.sp)
        }
    }
}
