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
    private var linkIp by mutableStateOf("")

    // 结构化车联快照（8890 数据链，原生卡片用）
    private var musicTitle by mutableStateOf("")
    private var musicArtist by mutableStateOf("")
    private var musicPlaying by mutableStateOf(false)
    private var callState by mutableStateOf("idle")
    private var callName by mutableStateOf("")
    private var phoneBatt by mutableIntStateOf(-1)
    private var phoneCharging by mutableStateOf(false)

    // 蓝牙上车即连
    private var btNames by mutableStateOf(listOf("先给蓝牙权限"))
    private var btMacs by mutableStateOf(listOf(""))
    private var btIndex by mutableIntStateOf(0)
    private var btConnected by mutableStateOf(false)
    private var btRegistered = false

    private val btPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.i("Main", "蓝牙权限已给，读配对列表")
            refreshBt()
        } else {
            AppLog.w("Main", "蓝牙权限没给，上车即连不可用，只能手动连")
            toast("不给蓝牙权限就用不了上车即连，只能手动点连接")
        }
    }

    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            val dev = intent?.let { BtHelper.deviceOf(it) } ?: return
            val mac = try { dev.address } catch (_: Exception) { return }
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (store.btAuto && mac == store.phoneBtMac && store.phoneBtMac.isNotBlank()) {
                        btConnected = true
                        AppLog.i("Main", "蓝牙连上手机，上车即连：自动连车联 ${store.phoneIp}")
                        toast("蓝牙连上手机，自动连车联")
                        linkIp = store.phoneIp
                        LinkClient.connect(store.phoneIp)
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (mac == store.phoneBtMac) {
                        btConnected = false
                        AppLog.i("Main", "手机蓝牙断了")
                    }
                }
            }
        }
    }

    private val linkListener = object : LinkClient.Listener {
        override fun onConn(ok: Boolean) {}
        override fun onMusic(title: String, artist: String, playing: Boolean) = runOnUiThread {
            musicTitle = title.take(24); musicArtist = artist.take(24); musicPlaying = playing
        }
        override fun onPhone(state: String, number: String, name: String) = runOnUiThread {
            callState = state
            callName = (name.ifBlank { number }.ifBlank { "未知号码" }).take(24)
            if (state == "ringing") AppLog.i("Main", "车机来电卡：$callName")
        }
        override fun onStatus(batt: Int, charging: Boolean, sig: Int) = runOnUiThread {
            phoneBatt = batt
            phoneCharging = charging
        }
    }

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
                LinkClient.connected -> "车联已连（音乐/电话同步）"
                btConnected -> "蓝牙已连，等 WiFi 车联…"
                st.isNotBlank() && st != "未连接" -> st
                else -> "未连接手机"
            }
            // 车联链路跟手机 IP 走，IP 变了重连
            if (linkIp != phoneIp) {
                linkIp = phoneIp
                LinkClient.connect(phoneIp)
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
        LinkClient.listener = linkListener
        linkIp = store.phoneIp
        LinkClient.connect(store.phoneIp)
        ensureBt()
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
                        musicTitle = musicTitle,
                        musicArtist = musicArtist,
                        musicPlaying = musicPlaying,
                        callState = callState,
                        callName = callName,
                        battText = if (LinkClient.connected && phoneBatt >= 0)
                            "手机$phoneBatt%" + if (phoneCharging) "·充电中" else ""
                        else "",
                        onCast = { openCast() },
                        onDirect = { openDirect(it) },
                        onSettings = { showSettings = true },
                        onPlayPause = { LinkClient.musicCmd(if (musicPlaying) "pause" else "play") },
                        onNext = { LinkClient.musicCmd("next") },
                        onPrev = { LinkClient.musicCmd("prev") },
                        onAnswer = { LinkClient.phoneCmd("answer") },
                        onHangup = { LinkClient.phoneCmd("hangup") }
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
                            hasLocalApps = hasLocalApps(),
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
                            btNames = btNames,
                            btIndex = btIndex,
                            onBtIndex = {
                                btIndex = it
                                store.phoneBtMac = btMacs.getOrNull(it).orEmpty()
                                AppLog.i("Main", "记住手机蓝牙：${btNames.getOrNull(it)} ${store.phoneBtMac}")
                                toast("已记住 ${btNames.getOrNull(it)}，下次蓝牙一连就自动连车联")
                            },
                            btAuto = store.btAuto,
                            onBtAuto = { store.btAuto = it },
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

    private fun ensureBt() {
        // Android 12+ 要 BLUETOOTH_CONNECT 才能读配对列表和收 ACL
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                btPerm.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        refreshBt()
    }

    private fun refreshBt() {
        val list = BtHelper.bonded(this)
        if (list.isEmpty()) {
            btNames = listOf("没找到配对设备（先跟手机配对）")
            btMacs = listOf("")
            btIndex = 0
            return
        }
        btNames = list.map { it.first }
        btMacs = list.map { it.second }
        btIndex = btMacs.indexOf(store.phoneBtMac).coerceAtLeast(0)
        if (btRegistered) return
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, btReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
            btRegistered = true
        } catch (e: Exception) {
            AppLog.w("Main", "蓝牙广播注册失败：${e.message}")
        }
    }

    private fun openCast() {
        openDirect("")
    }

    /** CarPlay 式直达：进投屏页自动连，连上就发 APP_CMD（home/nav/split，空=只看主屏） */
    private fun openDirect(cmd: String) {
        AppLog.i("Main", "直达投屏 ${store.phoneIp} cmd=$cmd")
        startActivity(Intent(this, StreamReceiverActivity::class.java).apply {
            putExtra(StreamReceiverActivity.EXTRA_IP, store.phoneIp)
            putExtra(StreamReceiverActivity.EXTRA_AUTO, true)
            if (cmd.isNotBlank()) putExtra(StreamReceiverActivity.EXTRA_APP_CMD, cmd)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        status = statusText()
        phoneIp = store.phoneIp
        logHint = Diagnostics.hint()
        refreshBt()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        LinkClient.disconnect()
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

    /** 车机没装本地导航/音乐时，桌面只走手机（本机就是这种情况） */
    private fun hasLocalApps(): Boolean {
        return navPkgs.any { SplitHelper.isInstalled(packageManager, it) } ||
            musicPkgs.any { SplitHelper.isInstalled(packageManager, it) }
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
