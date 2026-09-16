package com.carwithyou.lite

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carwithyou.lite.ui.HomeScreen
import com.carwithyou.lite.ui.theme.CarWithYouTheme

class MainActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private var navPkgs by mutableStateOf(emptyList<String>())
    private var musicPkgs by mutableStateOf(emptyList<String>())
    private var navIndex by mutableIntStateOf(0)
    private var musicIndex by mutableIntStateOf(0)
    private var status by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        try { ReceiverKeepService.start(this) } catch (_: Exception) {}
        try { ReceiverKeepService.requestWhitelist(this) } catch (_: Exception) {}
        refreshApps()
        status = statusText()

        setContent {
            CarWithYouTheme {
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
                        if (!SplitHelper.launch(this, pkg)) toast("没找到导航 $pkg，先在车机装高德车机版")
                    },
                    onOpenMusic = {
                        val pkg = selectedMusic()
                        if (!SplitHelper.launch(this, pkg)) toast("没找到音乐 $pkg")
                    },
                    onSplit = {
                        SplitHelper.launchSplit(this, selectedNav(), selectedMusic())
                        toast("已发分屏指令：左导航右音乐（部分车机需手动再点一次分屏键）")
                    },
                    onSave = {
                        store.navPkg = selectedNav()
                        store.musicPkg = selectedMusic()
                        toast("已保存：${store.navPkg} + ${store.musicPkg}")
                    },
                    onCast = { startActivity(Intent(this, StreamReceiverActivity::class.java)) },
                    onLyricOn = {
                        if (!LyricOverlayService.canDrawOverlays(this)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            toast("先给悬浮窗权限，再点一次")
                        } else {
                            startService(Intent(this, LyricOverlayService::class.java))
                            toast("悬浮歌词已开，播放音乐自动显示")
                        }
                    },
                    onLyricOff = { stopService(Intent(this, LyricOverlayService::class.java)) },
                    onNotif = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        toast("打开 CarWithYou歌词监听 的开关")
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        status = statusText()
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
        val all = packageManager.getInstalledPackages(0).size
        return "Android ${Build.VERSION.RELEASE} (SDK${Build.VERSION.SDK_INT}) · $overlay · $notif · 已装$all 个包"
    }

    private fun isNotifEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(this, MusicListenerService::class.java).flattenToString())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
