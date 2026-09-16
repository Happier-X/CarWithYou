package com.carwithyou.lite

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.lite.ui.CarLauncherScreen
import com.carwithyou.lite.ui.HomeScreen
import com.carwithyou.lite.ui.theme.CarWithYouTheme
import top.yukonga.miuix.kmp.basic.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * vivo 车联式车机桌面：默认是桌面（状态栏+大卡+Dock），点设置才进原设置页。
 * 开机 autoConnect 时 BootReceiver 直接进收流页，这里只负责桌面展示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private val handler = Handler(Looper.getMainLooper())

    private var navPkgs by mutableStateOf(emptyList<String>())
    private var musicPkgs by mutableStateOf(emptyList<String>())
    private var navIndex by mutableIntStateOf(0)
    private var musicIndex by mutableIntStateOf(0)
    private var status by mutableStateOf("")
    private var updateHint by mutableStateOf(UpdateChecker.idleHint)
    private var logHint by mutableStateOf(Diagnostics.hint())

    private var showSettings by mutableStateOf(false)
    private var timeText by mutableStateOf("--:--")
    private var dateText by mutableStateOf("")
    private var connected by mutableStateOf(false)
    private var connText by mutableStateOf("未连接手机")
    private var phoneIp by mutableStateOf("192.168.43.1")

    private val tick = object : Runnable {
        override fun run() {
            try {
                val now = Date()
                timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                dateText = SimpleDateFormat("M月d日 E", Locale.CHINA).format(now)
            } catch (_: Exception) {}
            // 收流状态是 StreamReceiverActivity 的静态现场，直接读
            val st = StreamReceiverActivity.statusText
            val up = StreamReceiverActivity.decoderUp
            connected = up
            connText = when {
                up -> "已连接 · $st"
                st.isNotBlank() && st != "未连接" -> st
                else -> "未连接手机"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        AppLog.i("Main", "车机桌面启动")
        try { ReceiverKeepService.start(this) } catch (e: Exception) {
            AppLog.w("Main", "保活服务起不来：${e.message}")
        }
        try { ReceiverKeepService.requestWhitelist(this) } catch (_: Exception) {}
        refreshApps()
        status = statusText()
        phoneIp = store.phoneIp
        logHint = Diagnostics.hint()
        handler.post(tick)

        setContent {
            CarWithYouTheme {
                if (!showSettings) {
                    CarLauncherScreen(
                        time = timeText,
                        date = dateText,
                        connected = connected,
                        connText = connText,
                        phoneIp = phoneIp,
                        onCast = { openCast() },
                        onNav = {
                            val pkg = selectedNav()
                            if (!SplitHelper.launch(this, pkg)) toast("没找到导航 $pkg，先在车机装高德车机版")
                        },
                        onMusic = {
                            val pkg = selectedMusic()
                            if (!SplitHelper.launch(this, pkg)) toast("没找到音乐 $pkg")
                        },
                        onSettings = { showSettings = true }
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TextButton(
                            text = "← 返回桌面",
                            onClick = { showSettings = false },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            minHeight = 52.dp
                        )
                        HomeScreen(
                            status = status,
                            navPkgs = navPkgs,
                            musicPkgs = musicPkgs,
                            navIndex = navIndex,
                            musicIndex = musicIndex,
                            onNavIndex = { navIndex = it },
                            onMusicIndex = { musicIndex = it },
                            onOpenNav = {
                                val pkg = selectedNav()
                                if (!SplitHelper.launch(this@MainActivity, pkg)) toast("没找到导航 $pkg，先在车机装高德车机版")
                            },
                            onOpenMusic = {
                                val pkg = selectedMusic()
                                if (!SplitHelper.launch(this@MainActivity, pkg)) toast("没找到音乐 $pkg")
                            },
                            onSplit = {
                                SplitHelper.launchSplit(this@MainActivity, selectedNav(), selectedMusic())
                                toast("已发分屏指令：左导航右音乐（部分车机需手动再点一次分屏键）")
                            },
                            onSave = {
                                store.navPkg = selectedNav()
                                store.musicPkg = selectedMusic()
                                toast("已保存：${store.navPkg} + ${store.musicPkg}")
                            },
                            onCast = { openCast() },
                            onLyricOn = {
                                if (!LyricOverlayService.canDrawOverlays(this@MainActivity)) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                    toast("先给悬浮窗权限，再点一次")
                                } else {
                                    startService(Intent(this@MainActivity, LyricOverlayService::class.java))
                                    toast("悬浮歌词已开，播放音乐自动显示")
                                }
                            },
                            onLyricOff = {
                                stopService(Intent(this@MainActivity, LyricOverlayService::class.java))
                                AppLog.i("Main", "关闭悬浮歌词")
                            },
                            onNotif = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                toast("打开 CarWithYou歌词监听 的开关")
                            },
                            autoConnect = store.autoConnect,
                            onAutoConnect = { store.autoConnect = it },
                            phoneIpText = store.phoneIp,
                            onPhoneIp = { store.phoneIp = it.trim(); phoneIp = store.phoneIp },
                            updateHint = updateHint,
                            onUpdate = { UpdateChecker.checkFrom(this@MainActivity) { updateHint = it } },
                            logHint = logHint,
                            onShowLog = { Diagnostics.show(this@MainActivity) { logHint = Diagnostics.hint() } },
                            onCopyLog = {
                                Diagnostics.copy(this@MainActivity)
                                logHint = Diagnostics.hint()
                            },
                            onCopyStatus = {
                                Diagnostics.copyText(
                                    this@MainActivity,
                                    "CarWithYou 车机状态",
                                    "$status\n收流：${StreamReceiverActivity.statusText}\n错误：${StreamReceiverActivity.lastError.ifBlank { "无" }}"
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    private fun openCast() {
        AppLog.i("Main", "进投屏页连 ${store.phoneIp}（自动连=${store.autoConnect}）")
        startActivity(Intent(this, StreamReceiverActivity::class.java).apply {
            putExtra(StreamReceiverActivity.EXTRA_IP, store.phoneIp)
            putExtra(StreamReceiverActivity.EXTRA_AUTO, true)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        status = statusText()
        phoneIp = store.phoneIp
        logHint = Diagnostics.hint()
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

    private fun selectedNav() = navPkgs.getOrNull(navIndex).orEmpty()
    private fun selectedMusic() = musicPkgs.getOrNull(musicIndex).orEmpty()

    private fun refreshApps() {
        navPkgs = (SplitHelper.installedNavPackages(packageManager) +
            listOf(SplitHelper.AMAP_AUTO, SplitHelper.AMAP_PHONE)).distinct()
        musicPkgs = (SplitHelper.installedMusicPackages(packageManager) +
            listOf(SplitHelper.QQ_MUSIC_CAR, SplitHelper.NETEASE, SplitHelper.KUGOU_CAR)).distinct()
        navIndex = navPkgs.indexOf(store.navPkg).coerceAtLeast(0)
        musicIndex = musicPkgs.indexOf(store.musicPkg).coerceAtLeast(0)
    }

    private fun statusText(): String {
        val overlay = if (LyricOverlayService.canDrawOverlays(this)) "悬浮窗OK" else "缺悬浮窗权限"
        val notif = if (isNotifEnabled()) "监听OK" else "缺通知监听权限"
        val visible = packageManager.getInstalledPackages(0).size
        return "Android ${Build.VERSION.RELEASE} (SDK${Build.VERSION.SDK_INT}) · $overlay · $notif · 能看见 $visible 个包"
    }

    private fun isNotifEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(this, MusicListenerService::class.java).flattenToString())
    }

    private fun toast(s: String) {
        AppLog.i("UI", s)
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
