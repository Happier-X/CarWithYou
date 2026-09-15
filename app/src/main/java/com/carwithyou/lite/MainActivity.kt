package com.carwithyou.lite

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.carwithyou.lite.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)
        // 保活常驻 + 电池白名单（终版第4节，系统杀后台 countermeasure）
        try { ReceiverKeepService.start(this) } catch (_: Exception) {}
        try { ReceiverKeepService.requestWhitelist(this) } catch (_: Exception) {}

        refreshSpinners()

        binding.btnNav.setOnClickListener {
            if (!SplitHelper.launch(this, store.navPkg)) toast("没找到导航 ${store.navPkg}，先在车机装高德车机版")
        }
        binding.btnMusic.setOnClickListener {
            if (!SplitHelper.launch(this, store.musicPkg)) toast("没找到音乐 ${store.musicPkg}")
        }
        binding.btnSplit.setOnClickListener {
            SplitHelper.launchSplit(this, store.navPkg, store.musicPkg)
            toast("已发分屏指令：左导航右音乐（部分车机需手动再点一次分屏键）")
        }
        binding.btnLyric.setOnClickListener {
            if (!LyricOverlayService.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                toast("先给悬浮窗权限，再点一次")
                return@setOnClickListener
            }
            startService(Intent(this, LyricOverlayService::class.java))
            toast("悬浮歌词已开，播放音乐自动显示")
        }
        binding.btnLyricOff.setOnClickListener {
            stopService(Intent(this, LyricOverlayService::class.java))
        }
        binding.btnNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("打开 CarWithYou歌词监听 的开关")
        }
        binding.btnSave.setOnClickListener {
            store.navPkg = binding.spNav.selectedItem.toString()
            store.musicPkg = binding.spMusic.selectedItem.toString()
            toast("已保存：${store.navPkg} + ${store.musicPkg}")
        }
        binding.btnCast.setOnClickListener {
            startActivity(Intent(this, StreamReceiverActivity::class.java))
        }
        binding.tvStatus.text = statusText()
    }

    override fun onResume() {
        super.onResume()
        refreshSpinners()
        binding.tvStatus.text = statusText()
    }

    private fun refreshSpinners() {
        val navs = (SplitHelper.installedNavPackages(packageManager) +
            listOf(SplitHelper.AMAP_AUTO, SplitHelper.AMAP_PHONE)).distinct()
        val musics = (SplitHelper.installedMusicPackages(packageManager) +
            listOf(SplitHelper.QQ_MUSIC_CAR, SplitHelper.NETEASE, SplitHelper.KUGOU_CAR)).distinct()
        binding.spNav.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, navs)
        binding.spMusic.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, musics)
        binding.spNav.setSelection(navs.indexOf(store.navPkg).coerceAtLeast(0))
        binding.spMusic.setSelection(musics.indexOf(store.musicPkg).coerceAtLeast(0))
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
