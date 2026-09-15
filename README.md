# CarWithYou — 科鲁泽投屏终版（手机分屏 + 车机当显示器）

> 终版结论：不做车机分屏，不做互联认证。分屏在手机系统里做（高德+音乐任意摆），车机只是一个普通 App：收流、解码、显示、回传触摸。
> 自研替代 CarPlus 的价值：免费无 VIP、歌词布局自己定、按自己车机定制。

## 1. 架构（2个APK，协议见 docs/PROTOCOL.md）

```
手机端 phone/  com.carwithyou.sender（热点网关，TCP Server）
├── SenderActivity       # 分屏帮助 → 录屏授权 → 推流开关 + 自适应开关 + 实时统计
├── ScreenCastService    # MediaProjection → H264基线/CBR → 8888推流
│                         自适应：码率热切换(PER_KEY_VIDEO_BITRATE) + 分辨率重建
└── TouchInjectorService # 8889：收 TAP/SWIPE 注入 + PING/PONG 心跳

车机端 app/  com.carwithyou.lite（热点客户端，TCP Client）
├── StreamReceiverActivity # 8888解码显示 + 8889控制，每2秒回报 STATS
├── ReceiverKeepService    # 前台保活，开机自启，电池白名单引导
└── 本机模式（MainActivity/悬浮歌词） # 车机有流量时用，不耗手机，最清晰
```

## 2. 自适应（核心卖点）

手机端一键开启，默认开启。车机每 2 秒回报 `STATS`（丢帧率/解码耗时/码率/RTT），手机据此：

| 条件 | 动作 |
|---|---|
| 丢帧 > 10% 或解码卡 | 降码率 → 降分辨率 |
| 丢帧 < 2% 且 RTT < 80ms | 升码率 → 升分辨率 |

分辨率三档：480p ↔ 720p ↔ 1080p，码率跟着档位自动调。

手机 UI 实时显示：`720p | 2.5M (1.5–4M) | 丢帧0.3% | 解码8.2ms | RTT23ms`

## 3. 上车流程（30秒，接近无感）

1. 手机开固定热点 + 蓝牙连车；车机记住热点自动连。
2. 手机手动分屏摆好任意两个 App。
3. 手机 `Car投屏` → 勾自适应 → 开始投屏（重启后第一次要点一次允许）。
4. 车机 `CarWithYou` → `手机投屏双开` → 自动重连，连上即看。
5. 别锁手机屏；两端都加电池白名单并锁后台。

## 4. 开发顺序（终版第5节 → 代码映射）

1. 环境确认 → `scripts/check-car.ps1`
2. 手机采集编码 Demo → 手机端勾"保存H264" → `/Movies/carwithyou/cast.h264`
3. 车机解码 Demo → `StreamReceiverActivity`
4. 视频单向传输 → 已通（核心 70%）
5. 触摸回传 → 已通（CONFIG+黑边映射）
6. 自动连接保活 → 已做（保活+退避+心跳+STATS）
7. 自适应 → **已实现**（`CastConfig.decide()` + STATS 回报）
8. 音频优化 → 当前蓝牙方案；内录是 V2

## 5. 构建安装

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew :app:assembleDebug :phone:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk      # 车机
adb install -r phone/build/outputs/apk/debug/phone-debug.apk  # 手机
```

GitHub Release 不是跑 `assembleDebug`，而是推 `v*` 标签触发 `release.yml`。成功后会上传：

- `CarWithYou-car-vX.Y.Z.apk`（车机）
- `CarWithYou-phone-vX.Y.Z.apk`（手机）

`v0.1.0` 没有 APK，是因为当时 CI 在安装 Android SDK 时就失败了，Release 都没建成。

先跑 `.\scripts\check-car.ps1` 把输出贴我，我帮你定分辨率和码率。

## 6. 参考

- Scrcpy：H.264/CBR/双Socket/低延迟标杆，自适应逻辑仿它
- ScreenOnAuto / LibAuto：投到 Android Auto 的完整实现
- Android 官方 MediaProjection + MediaCodec 示例
