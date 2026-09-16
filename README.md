# CarWithYou — 科鲁泽投屏（独立虚拟屏，手机还能用）

> 默认像 CarPlus：手机建一块独立虚拟屏，高德 + 音乐在那块屏上跑，车机只收流显示。手机主屏空出来，可以继续刷微信。
> 不做互联认证。自研替代 CarPlus：免费无 VIP、歌词布局自己定、按自己车机定制。

## 1. 架构（2个APK，协议见 docs/PROTOCOL.md）

```
手机端 phone/  com.carwithyou.sender（热点网关，TCP Server）
├── SenderActivity       # Compose + MIUIX 设置页：选导航/音乐 → 录屏授权 → 推流
├── ScreenCastService    # 独立 VirtualDisplay → H264 → 8888
│                         默认 PRESENTATION|OWN_CONTENT_ONLY，不镜像主屏
│                         自适应：码率热切换；虚拟屏不切分辨率（避免杀车上 App）
├── CarDesktopActivity   # 跑在虚拟屏上的桌面，从这块屏拉起高德/音乐
├── TouchInjectorService # 无障碍：把车机 TAP/SWIPE 打到虚拟屏
└── UpdateChecker        # 设置页「软件更新」：查 GitHub Release，直接下载安装新 APK

车机端 app/  com.carwithyou.lite（热点客户端，TCP Client）
├── StreamReceiverActivity # 8888解码显示 + 8889控制，每2秒回报 STATS
├── ReceiverKeepService    # 前台保活，开机自启，电池白名单引导
├── UpdateChecker          # 设置页「软件更新」：查 GitHub Release，直接下载安装新 APK
└── 本机模式（MainActivity/悬浮歌词） # 车机有流量时用，不耗手机，最清晰
```

关掉「独立虚拟屏」则退回整屏镜像（旧行为，要占手机分屏）。

## 2. 自适应

手机端默认开启。车机每 2 秒回报 `STATS`（丢帧率/解码耗时/码率/RTT），手机据此调码率。

虚拟屏模式**锁分辨率**（默认 1280×720），只调码率，避免重建 Display 把车上的 App 杀掉。镜像模式仍可 480p ↔ 720p ↔ 1080p。

手机 UI 实时显示：`虚拟屏#5 | 720p | 2.5M (1.5–4M) | 丢帧0.3% | 解码8.2ms | RTT23ms`

## 3. 上车流程

1. 手机开固定热点 + 蓝牙连车；车机记住热点自动连。
2. 手机 `Car投屏`：选导航/音乐，勾「独立虚拟屏」，开始投屏（重启后第一次要点一次允许录屏）。
3. 车机 `CarWithYou` → `连手机虚拟屏` → 自动重连，连上即看。
4. 手机主屏可以继续用。别把投屏 App 从后台划掉；建议电池白名单。
5. 车机点屏幕可反控虚拟屏（需打开无障碍）。底部切换栏可切导航/音乐。

若导航/音乐弹到了手机上：点「重新把导航/音乐丢到车机屏」。仍不行就关掉独立虚拟屏，改镜像（要占手机）。

## 4. 开发顺序

1. 环境确认 → `scripts/check-car.ps1`
2. 手机采集编码 Demo → 手机端勾"保存H264" → `/Movies/carwithyou/cast.h264`
3. 车机解码 Demo → `StreamReceiverActivity`
4. 视频单向传输 → 已通
5. 触摸回传 → 已通（CONFIG + 黑边映射 + 虚拟屏 displayId）
6. 自动连接保活 → 已做（保活+退避+心跳+STATS）
7. 自适应码率 → 已实现
8. **独立虚拟屏 → 已实现（默认）**
9. 音频优化 → 当前蓝牙方案；内录是后续
10. **检查更新 → 已做（设置页查 GitHub Release，下载即装）**

## 5. 构建安装

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew :app:assembleDebug :phone:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk      # 车机
adb install -r phone/build/outputs/apk/debug/phone-debug.apk  # 手机
```

GitHub Release 推 `v*` 标签触发 `release.yml`，上传：

- `CarWithYou-car-vX.Y.Z.apk`（车机）
- `CarWithYou-phone-vX.Y.Z.apk`（手机）

先跑 `.\scripts\check-car.ps1` 把输出贴我，我帮你定分辨率和码率。发版前可跑 `./gradlew :app:lintRelease :phone:lintRelease`（目前两端 0 error）。

## 5.1 检查更新

两端设置页都有「软件更新 → 检查更新」（车机端主页往下拉，手机端在最底下）。点一下查 GitHub 最新 Release：

- 有新版：弹窗显示新版本号 + 更新说明 → 「下载并安装」走 DownloadManager 下到 `下载/CarWithYou-<car|phone>-vX.Y.Z.apk`，下完自动弹安装（首次要允许「安装未知应用」）。也能点「去 GitHub」自己下。
- 已是最新 / 检查失败（车机没网、GitHub 限流）都直接写在设置项的说明里。

版本号优先走 `https://github.com/.../releases/latest` 的 302 跳转拿 tag：GitHub API 未登录只有 60 次/小时/IP，手机热点常走运营商 NAT，那点额度很容易被别人用光，所以 API 只用来顺带取更新日志（限流了照样能查出有没有新版）。真要拼下载地址就按 CI 的命名规则来：`releases/download/<tag>/CarWithYou-<car|phone>-vX.Y.Z.apk`。

版本号就是 `BuildConfig.VERSION_NAME`：本地 debug 默认取 `build.gradle.kts` 里的 `0.1.4`，发版时 CI 用 `-PversionName=vX.Y.Z` 覆盖。车机端只认 Release 里的 `-car-` APK，手机端只认 `-phone-` 的。

> 更新包的签名得和已装应用一致（release 包装不上 debug 包，反之亦然），不一致时系统会直接报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，得先卸载再装。

## 6. 参考

- Scrcpy：H.264/CBR/双Socket/低延迟标杆
- CarPlus / DisplayManager Presentation：独立虚拟屏，不占手机
- Android 官方 MediaProjection + MediaCodec 示例
