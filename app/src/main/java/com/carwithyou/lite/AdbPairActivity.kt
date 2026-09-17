package com.carwithyou.lite

import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.carwithyou.lite.adb.AdbPairManager
import com.carwithyou.lite.ui.theme.CarWithYouTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Android 11+ 无线调试配对。
 *
 * 配对用的 TLS 客户端证书是**用我们自己的 ADB 密钥自签的**，所以配对授权的那把
 * 密钥就是后续明文连接用的那把，手机上只会有这一次配对动作，不会再弹「允许 USB 调试」。
 *
 * 配对成功后顺手发 `tcpip:5555`，把经典明文端口开出来；之后投屏链路完全走自研的
 * `:adb-core`，不需要再碰 SPAKE2/TLS。
 */
class AdbPairActivity : AppCompatActivity() {

    internal var manager: AdbPairManager? by mutableStateOf(null)
    internal var managerError: String? by mutableStateOf(null)
    private var nsd: NsdManager? = null
    private var scanJob: Job? = null

    // UI 状态（挂在 Activity 上，保证切后台/重进不丢）
    internal var statusText by mutableStateOf("")
    internal var busy by mutableStateOf(false)
    internal var scanning by mutableStateOf(false)
    internal var foundCount by mutableStateOf(0)
    internal var hostText by mutableStateOf("")
    internal var pairPortText by mutableStateOf("")
    internal var connectPortText by mutableStateOf("")
    internal var codeText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = SettingsStore(this)
        // 已经配过的地址里把 IP 抠出来当默认值，省得用户再敲一遍
        val defaultHost = store.adbTarget.substringBefore(':').takeIf { it.isNotBlank() } ?: ""
        if (hostText.isBlank()) hostText = defaultHost

        // intent 预填（自动化测试/脚本回归用）
        intent?.let { it0 ->
            it0.getStringExtra(EXTRA_HOST)?.takeIf { s -> s.isNotBlank() }?.let { hostText = it }
            it0.getStringExtra(EXTRA_CODE)?.takeIf { s -> s.isNotBlank() }?.let { codeText = it }
            val pp = it0.getIntExtra(EXTRA_PAIR_PORT, 0)
            if (pp > 0) pairPortText = pp.toString()
            val cp = it0.getIntExtra(EXTRA_CONNECT_PORT, 0)
            if (cp > 0) connectPortText = cp.toString()
        }

        // 证书生成有一次 RSA 2048 的运算，别放主线程；失败也不让页面崩
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { AdbPairManager(applicationContext) }
            }
            r.onSuccess { manager = it }
                .onFailure {
                    managerError = "${it.javaClass.simpleName}: ${it.message}"
                    AppLog.e(TAG, "配对管理器初始化失败：$managerError", it)
                }
        }

        setContent {
            CarWithYouTheme {
                PairScreen(
                    activity = this,
                    onBack = { finish() },
                    onScan = { startScan() },
                    onStopScan = { stopScan() },
                    onPairAndOpen = { host, pairPort, code, connectPort ->
                        runTask("配对并开端口") {
                            val mgr = manager ?: error("配对管理器还没就绪")
                            mgr.pairWithCode(host, pairPort, code).getOrThrow()
                            // 配对成功了，开端口即使失败也告知用户核心步骤已成
                            val openResult = runCatching { mgr.openTcpip5555(host, connectPort).getOrThrow() }
                            if (openResult.isFailure) {
                                error(
                                    "配对成功！但自动开启 5555 端口失败（${openResult.exceptionOrNull()?.message}）。" +
                                        "可以在手机无线调试里点开启，或用 USB 补执行一次 `adb tcpip 5555`。"
                                )
                            }
                            store.adbTarget = "$host:5555"
                            "配对成功，已开启明文 5555 端口，可以直接投屏了！"
                        }
                    },
                    onPairOnly = { host, pairPort, code ->
                        runTask("配对") {
                            val mgr = manager ?: error("配对管理器还没就绪")
                            mgr.pairWithCode(host, pairPort, code).getOrThrow()
                            "配对成功！手机已信任车机。还差一步：点「只开 5555 端口」或一键按钮把明文链路开出来。"
                        }
                    },
                    onOpenOnly = { host, connectPort ->
                        runTask("开 5555 端口") {
                            val mgr = manager ?: error("配对管理器还没就绪")
                            mgr.openTcpip5555(host, connectPort).getOrThrow()
                            store.adbTarget = "$host:5555"
                            "已开启明文 5555 端口，目标地址已更新为 $host:5555，可以直接投屏了！"
                        }
                    },
                    onGoCast = {
                        val target = store.adbTarget.ifBlank { "${hostText.trim()}:5555" }
                        startActivity(
                            Intent(this, AdbStreamActivity::class.java).apply {
                                putExtra(AdbStreamActivity.EXTRA_TARGET, target)
                                putExtra(AdbStreamActivity.EXTRA_AUTO, true)
                            }
                        )
                    }
                )
            }
        }

        // 自动跑（脚本回归用）：等证书就绪后按 mode 执行一次
        if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            val mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { "both" }
            lifecycleScope.launch {
                // 等 manager（证书生成）就绪，最多 10s
                var waited = 0
                while (manager == null && managerError == null && waited < 100) {
                    delay(100); waited++
                }
                val h = hostText.trim()
                val pp = pairPortText.trim().toIntOrNull() ?: 0
                val cp = connectPortText.trim().toIntOrNull() ?: 0
                val c = codeText.trim()
                AppLog.i(TAG, "自动 $mode：host=$h pairPort=$pp connectPort=$cp")
                when (mode) {
                    "pair" -> runTask("配对") {
                        manager?.pairWithCode(h, pp, c)?.getOrThrow() ?: error("管理器未就绪")
                        "配对成功"
                    }
                    "open" -> runTask("开 5555 端口") {
                        manager?.openTcpip5555(h, cp)?.getOrThrow() ?: error("管理器未就绪")
                        store.adbTarget = "$h:5555"
                        "已开启 5555 端口"
                    }
                    else -> runTask("配对并开端口") {
                        val mgr = manager ?: error("管理器未就绪")
                        mgr.pairWithCode(h, pp, c).getOrThrow()
                        val r = runCatching { mgr.openTcpip5555(h, cp).getOrThrow() }
                        if (r.isFailure) error("配对成功但开端口失败：${r.exceptionOrNull()?.message}")
                        store.adbTarget = "$h:5555"
                        "配对成功并已开启 5555 端口"
                    }
                }
            }
        }
    }

    /** 跑一个耗时任务，把结果文本推到状态栏（所有异常都在这里被兜住，不崩 App）。 */
    private fun runTask(
        label: String,
        block: suspend () -> String,
    ) {
        if (busy) {
            toast("上个任务还在进行，请稍等")
            return
        }
        busy = true
        statusText = "正在$label…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            r.onSuccess { msg ->
                statusText = "✅ $msg"
                toast(msg)
                AppLog.i(TAG, "$label 成功：$msg")
            }.onFailure { ex ->
                val err = readableError(ex)
                statusText = "❌ $err"
                toast("$label 失败：$err")
                AppLog.e(TAG, "$label 失败：$err", ex)
            }
            busy = false
        }
    }

    /**
     * 把异常文本变成用户看得懂的话。
     *
     * 底层异常经常只有一个类名（如 `java.io.IOException` 裹着 `ECONNREFUSED`），
     * 直接展示给用户等于没说，所以沿着 cause 链找到真正有信息量那一层，
     * 再按常见场景换成可操作的建议。
     */
    private fun readableError(ex: Throwable): String {
        // 沿 cause 链找第一条 message 非空且不是纯类名的
        var cur: Throwable? = ex
        var best: String? = null
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            val m = cur.message?.trim().orEmpty()
            if (m.isNotEmpty() && m != cur.javaClass.name) {
                best = m
                // ECONNREFUSED / timeout 这类底层信息最有用，优先用它
                if (m.contains("ECONNREFUSED") || m.contains("Connection refused") ||
                    m.contains("timed out") || m.contains("ETIMEDOUT") ||
                    m.contains("Unable to resolve") || m.contains("Network is unreachable")
                ) break
            }
            cur = cur.cause
        }
        val raw = best ?: ex.javaClass.simpleName
        return when {
            raw.contains("ECONNREFUSED") || raw.contains("Connection refused") ->
                "端口没人监听。请确认手机上「无线调试」是打开状态，且填的端口号是页面上显示的那个（不是配对弹窗那个）"
            raw.contains("timed out") || raw.contains("ETIMEDOUT") ->
                "连接超时。确认手机和车机在同一个 Wi-Fi，且 IP 填对了"
            raw.contains("Unable to resolve") || raw.contains("UnknownHost") ->
                "解析不了这个 IP。检查是否填成了域名或写错了"
            raw.contains("Network is unreachable") ->
                "网络不通。车机可能没连上手机热点"
            raw.contains("SSL") || raw.contains("TLS") || raw.contains("handshake") ->
                "TLS 握手失败。手机侧可能已退出配对模式，重新打开「无线调试」再试"
            raw.contains("已经配过") || raw.contains("already") -> raw
            else -> raw
        }
    }

    // ------------------------------------------------------------ mDNS 自动发现

    private fun startScan() {
        if (scanning) return
        val svc = getSystemService(NsdManager::class.java)
        if (svc == null) {
            statusText = "这台车机不支持 NSD，只能手动填"
            return
        }
        nsd = svc
        scanning = true
        statusText = "扫描中…请先在手机上打开「使用配对码配对设备」弹窗（扫描 15 秒）"
        repeat(2) { which ->
            val type = if (which == 0) SERVICE_PAIRING else SERVICE_CONNECT
            runCatching {
                svc.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener(type))
            }.onFailure { AppLog.w(TAG, "NSD 发现 $type 失败：${it.message}") }
        }
        scanJob = lifecycleScope.launch {
            delay(15_000)
            finishScan()
        }
    }

    private fun finishScan() {
        if (!scanning) return
        scanning = false
        scanJob?.cancel()
        scanJob = null
        statusText = if (foundCount > 0) {
            "扫描结束，已填好端口（如果填的不对就手动改）"
        } else {
            "没扫到手机（车机网络多为 AP 隔离，正常）。手动填下面几项即可"
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanJob?.cancel()
        scanJob = null
        statusText = "已停止扫描"
    }

    private var resolving = false

    private fun discoveryListener(type: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            AppLog.i(TAG, "开始发现 $serviceType")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            AppLog.i(TAG, "发现 $type 服务：${info.serviceName}")
            if (resolving) return
            resolving = true
            runCatching {
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        AppLog.w(TAG, "解析 ${failed.serviceName} 失败，code=$errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        resolving = false
                        val host = resolved.host?.hostAddress ?: return
                        val port = resolved.port
                        AppLog.i(TAG, "解析到 $host:$port（$type）")
                        runOnUiThread {
                            foundCount++
                            if (hostText.isBlank() || !host.startsWith("127.")) hostText = host
                            if (type == SERVICE_PAIRING) {
                                if (pairPortText.isBlank()) pairPortText = port.toString()
                            } else {
                                if (connectPortText.isBlank()) connectPortText = port.toString()
                            }
                            statusText = "已扫到手机 $host，填好端口后输入配对码即可"
                        }
                    }
                })
            }.onFailure { resolving = false }
        }

        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLog.w(TAG, "发现 $serviceType 启动失败，code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val TAG = "AdbLink"
        private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"
        private const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

        // 自动化/调试入口：am start 带这几个 extra 就能不开 UI 直接跑（方便脚本回归）
        const val EXTRA_HOST = "pair_host"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_CODE = "pair_code"
        const val EXTRA_CONNECT_PORT = "pair_connect_port"

        /** pair=只配对，open=只开 5555 端口，both=一键（默认） */
        const val EXTRA_MODE = "pair_mode"
        const val EXTRA_AUTO = "pair_auto"
    }
}

@Composable
private fun PairScreen(
    activity: AdbPairActivity,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairAndOpen: (String, Int, String, Int) -> Unit,
    onPairOnly: (String, Int, String) -> Unit,
    onOpenOnly: (String, Int) -> Unit,
    onGoCast: () -> Unit,
) {
    val host = activity.hostText
    val pairPort = activity.pairPortText
    val connectPort = activity.connectPortText
    val code = activity.codeText
    val status = activity.statusText
    val busy = activity.busy
    val scanning = activity.scanning
    val manager = activity.manager
    val managerErr = activity.managerError

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "← 返回",
                    onClick = onBack
                )
                Text(
                    "Android 11+ 无线调试配对",
                    style = MiuixTheme.textStyles.title2,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "手机上：设置 → 开发者选项 → 无线调试 → 「使用配对码配对设备」，" +
                    "把弹窗里的端口和 6 位配对码填到下面。只需配对这一次，" +
                    "配完会把 5555 明文端口开出来，以后进车自动连。",
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))

            if (managerErr != null) {
                Card {
                    Text(
                        "证书初始化失败，配对不可用：$managerErr",
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SmallTitle("① 先扫一下（可选，省得手敲端口）")
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { if (scanning) onStopScan() else onScan() },
                        modifier = Modifier.height(52.dp),
                        minHeight = 52.dp
                    ) {
                        Text(if (scanning) "停止扫描" else "扫描附近手机")
                    }
                }
                Text(
                    "扫描需要车机和手机在同一个能跑组播的网段；车机 Wi-Fi 常是 AP 隔离，" +
                        "扫不到就直接手动填，不影响使用。",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))

            SmallTitle("② 手机 IP 和配对信息")
            Card {
                Field(host, "手机 IP（如 192.168.1.20）") { activity.hostText = it }
                Field(pairPort, "配对端口（弹窗里的端口，如 37251）", number = true) {
                    activity.pairPortText = it
                }
                Field(code, "6 位配对码（弹窗里那个码）", number = true) {
                    activity.codeText = it
                }
                Field(
                    connectPort,
                    "无线调试端口（返回上一层页面上的端口，如 41234）",
                    number = true
                ) { activity.connectPortText = it }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        onPairAndOpen(
                            host.trim(),
                            pairPort.trim().toIntOrNull() ?: 0,
                            code.trim(),
                            connectPort.trim().toIntOrNull() ?: 0
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp),
                    minHeight = 64.dp
                ) {
                    Text("一键配对并开启 5555 端口")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = "只配对",
                        onClick = {
                            onPairOnly(
                                host.trim(),
                                pairPort.trim().toIntOrNull() ?: 0,
                                code.trim()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "只开 5555 端口",
                        onClick = {
                            onOpenOnly(
                                host.trim(),
                                connectPort.trim().toIntOrNull() ?: 0
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            SmallTitle("本机身份（手机上会显示这个名字）")
            Card {
                Text(
                    buildString {
                        append("设备名：")
                        append(manager?.keyDisplayName ?: "证书还没就绪…")
                        append("\n指纹：")
                        append(manager?.fingerprint ?: "—")
                        append("\n配对后手机上「已配对的设备」里会看到这个设备名。")
                        append("如果之前配过但被手机删了，删掉 App 数据重装会换一把新密钥。")
                    },
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Text(
                        if (busy) "⏳ $status" else status,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onGoCast,
                    modifier = Modifier.weight(1f).height(64.dp),
                    minHeight = 64.dp
                ) {
                    Text("去投屏")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    number: Boolean = false,
    onValue: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = { s -> onValue(if (number) s.filter { it.isDigit() } else s) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = label,
        useLabelAsPlaceholder = true
    )
}
