package com.carwithyou.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.carwithyou.sender.databinding.ActivitySenderBinding

class SenderActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySenderBinding
    private val handler = Handler(Looper.getMainLooper())

    private val castPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            CastConfig.adaptiveEnabled = binding.cbAdaptive.isChecked
            val i = Intent(this, ScreenCastService::class.java).apply {
                putExtra(ScreenCastService.EXTRA_RESULT_CODE, r.resultCode)
                putExtra(ScreenCastService.EXTRA_DATA, r.data!!)
                putExtra(ScreenCastService.EXTRA_DEBUG_SAVE, binding.cbDebug.isChecked)
            }
            startForegroundService(i)
            binding.tvStatus.text = "投屏中…车机连 ${NetUtils.hotspotIp(this)}:8888"
            startStatsPoll()
        } else {
            toast("要给录屏权限才能投")
        }
    }

    // 定时刷新自适应状态到 UI
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!ScreenCastService.running) {
                binding.tvStats.text = "未投屏"
                return
            }
            val br = CastConfig.currentBitrate / 1_000_000f
            val drop = CastConfig.lastDropRatio * 100f
            val dec = CastConfig.lastDecodeMs
            val lat = CastConfig.lastLatencyMs
            val tier = CastConfig.currentResShort
            val brn = CastConfig.bitrateRange(tier)
            val minB = brn[0] / 1_000_000f; val maxB = brn[1] / 1_000_000f
            binding.tvStats.text =
                "自适应：${tier}p | %.1fM (范围%.1f–%.1fM) | 丢帧%.1f%% | 解码%.1fms | RTT%dms".format(
                    br, minB, maxB, drop, dec, lat
                )
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStatsPoll() { handler.removeCallbacks(statsRunnable); handler.post(statsRunnable) }
    private fun stopStatsPoll() { handler.removeCallbacks(statsRunnable) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySenderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvIp.text = "本机IP：${NetUtils.hotspotIp(this)}（车机连热点后填这个）"

        binding.btnStart.setOnClickListener {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            castPerm.launch(mpm.createScreenCaptureIntent())
        }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            binding.tvStatus.text = "已停止"
            stopStatsPoll()
            binding.tvStats.text = "未投屏"
        }
        binding.btnSplitHelp.setOnClickListener {
            try {
                val pm = packageManager
                val nav = pm.getLaunchIntentForPackage("com.autonavi.minimap")
                val mus = pm.getLaunchIntentForPackage("com.tencent.qqmusic")
                    ?: pm.getLaunchIntentForPackage("com.netease.cloudmusic")
                if (nav != null) startActivity(nav)
                mus?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(this)
                }
                toast("已尝试分屏，不行就手动：最近任务点App图标选分屏")
            } catch (e: Exception) { toast("分屏失败，手动分屏即可") }
        }
        binding.btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("打开 Car投屏-手机端 的无障碍开关，车机才能反控")
        }
        binding.btnBatt.setOnClickListener {
            try {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
                } else toast("已在电池白名单")
            } catch (_: Exception) { toast("请手动：设置→电池→无限制") }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.tvIp.text = "本机IP：${NetUtils.hotspotIp(this)}（车机连热点后填这个）"
        if (ScreenCastService.running) startStatsPoll()
    }

    override fun onPause() {
        super.onPause()
        stopStatsPoll()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
