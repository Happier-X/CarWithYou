# Changelog

## 0.1.6
### Added
- 车机零本地依赖：没装本地导航/音乐时设置页隐藏本地区，桌面 Dock 改手机直达（主屏/导航/分屏：进投屏自动连并下发 `APP`），左大卡+音乐电话原生卡全走手机
- 目的地直达接收（8890 `NAV_POI`）：手机虚拟屏高德按关键词直达，给下版车机搜索框铺路
- 安卓版 CarPlay 主屏：手机虚拟屏改深色大图标桌面（导航/音乐/分屏 + 状态栏时间），由手机渲染、车机只显示
- 车机收流页左 Dock（主屏/导航/音乐/分屏）：经 8889 `APP` 命令回手机切虚拟屏应用
- 手机电量/信号同步（8890 `STATUS`，10 秒一次）：车机桌面状态栏显示“手机85%·充电中”
- 亿连式上车即连（蓝牙）：车机跟手机配对一次并选中，之后蓝牙一连就自动连车联；桌面状态栏显示蓝牙/车联/视频三级状态
- 手机驾驶模式：横屏大按钮（大时钟/当前歌曲/播放下曲/免打扰开关/退出），车机连上弹通知一点即进，设置页也有入口
- 结构化车联 v2（小米 CarWith / HiCar 式）：手机 `LinkService`（TCP 8890 JSON 行协议）只同步数据不传视频；音乐经 MediaSession 同步歌名/状态、车机可切歌/播放暂停；电话同步来电/通话状态、车机可接听/挂断（需电话权限）
- 车机桌面音乐/电话原生卡：歌名/歌手/播放态实时显示，来电弹卡接挂，不再是投屏画面

## 0.1.5
### Added
- 浏览器降级直连（默认关）：手机端可选 MJPEG over HTTP 8080，车机用自带浏览器打开 `http://192.168.43.1:8080/` 即可看画面、点屏反控，车机实在装不了 App 时用；主链路仍是 H264 8888 + 车机 App
- vivo 车联式车机桌面：状态栏时钟日期 + 连接状态圆点 + 手机投屏大卡 + 本地导航/音乐快捷 + 底部 Dock（投屏/导航/音乐/设置），默认横屏大按钮；点设置才进原设置页
- 上车自动连：记住手机热点 IP，开机/点投屏直接进收流页自动连，断线指数退避重连；设置页可开关并手动改 IP
- 两端应用内诊断日志（`AppLog` + `Diagnostics`）：关键事件、被 catch 掉的异常、上次崩溃的栈都攒进环形缓冲，设置页「诊断」里能「查看诊断日志 / 复制诊断日志 / 复制当前状态」，不用接 adb 就能把报错原文发回来；复制内容自带设备型号、Android 版本、APK 版本、当前投屏/收流状态与权限情况
- 崩溃留档：`Application`（手机端 `SenderApp` / 车机端 `LiteApp`）里装 `UncaughtExceptionHandler`，崩溃栈同步落盘，所以上次崩了、这轮重启后复制日志也查得到
- 推流/解码热路径里的报错走节流（`wThrottle` / `eThrottle`：同一条默认 5s 只记一次，剩下的合并成条数补上去），不会把缓冲刷满
- 两端设置页新增「软件更新 → 检查更新」：查 GitHub Release 最新版，有新版直接下载并拉起安装（车机端取 `-car-` APK，手机端取 `-phone-`）；顺带显示当前版本

### Changed
- 投屏协议 v1.4：主链路仍是 H264 8888 + 控制 8889（手机/车机双端 App，对标 vivo 车联），浏览器 8080 为降级；虚拟屏/镜像语义不变
- 车机收流页全屏化：画面铺满全屏，连接控制条悬浮顶部，日志按钮收到右下角，反控手势不受挡
- 本地 debug 构建的 versionName/versionCode 默认跟当前发布版一致（不再写死 `0.1.0-lite` / `0.1.0-sender`），检查更新才不会被自己误报
- 检查更新优先走 `releases/latest` 的 302 跳转取版本号，不吃 GitHub API「未登录 60 次/小时/IP」的限额（手机热点走运营商 NAT 时那点额度常被别人用光）；API 只用来顺带取更新日志，限流了也照样能查出有没有新版，真取不到就按 CI 命名规则拼下载地址
- 更新弹窗正文把 CHANGELOG 原文换成中文小标题（`### Added` → 【新增】等），并去掉跟弹窗标题重复的 `## 版本` 行

### Fixed
- 检查更新失败时如实报原因（GitHub 限流 / 连不上 / 还没发过 Release），之前限流会被误报成「Release 没有版本号」
- `lintRelease` 两端归零（原 7 个 error）：`registerReceiver` 全部改走 `ContextCompat.registerReceiver`，API 33+ 的 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` 由它统一处理，不再需要 `@Suppress`
- 车机端不再申请 `QUERY_ALL_PACKAGES`，改为 `<queries>` 只声明导航/音乐这几个已知包名（首页状态栏的「已装N个包」相应改成「能看见N个包」）
- 两端都不再声明 `android.software.leanback`：不是 TV App，声明了反而要求 `LEANBACK_LAUNCHER`，手机端还连带要求触屏可选
- `local.properties` 的 `sdk.dir` 按 properties 规范转义成 `C\:/...`，本地 lint 不再报 `PropertyEscape`

## 0.1.4
### Added
- 默认独立虚拟屏投车机：导航/音乐在副屏上跑，手机主屏可继续使用
- 车机桌面、悬浮切换栏；触摸回传打到虚拟屏 displayId

### Changed
- 虚拟屏锁定分辨率，只自适应码率，避免重建 Display 杀掉车上的 App
- 镜像模式仍作为 ROM 不支持副屏时的兜底
- UI 迁到 Jetpack Compose + MIUIX 0.9.3（`miuix-ui` / `miuix-preference`）；投屏解码仍用 SurfaceView
- 工具链：Kotlin 2.4.0、AGP 9.2.1、Gradle 9.6.1、Compose BOM 2026.09.00、compileSdk 37、JDK 21

### Fixed
- 触摸回传在 API 34+ 用公开 `setDisplayId`，不再反射私有字段，release lint 可通过
- 换成 Gradle 9 官方 wrapper，CI 多行参数不再被拆掉

## 0.1.3
### Fixed
- GitHub Release 使用独立的 CarWithYou 签名钥，上传正式签名 APK

## 0.1.2
### Fixed
- `SIGNING_KEY` 不是合法 base64 时不再阻断发版，回退 debug 签名并仍上传 APK

## 0.1.1
### Fixed
- GitHub Release 不再因 Android SDK 安装失败而空发布
- Release 正确上传车机 / 手机 APK，缺文件时失败而不是一个空 Release
- CI 不再依赖 `android-actions/setup-android` 去安装已下线的 `tools` 包
- 补齐 Gradle Wrapper，并将 Groovy 脚本迁到 Kotlin DSL

## 0.1.0-lite
- 初始版本
- 车机端：主分屏 + 悬浮歌词 + 手机投屏双开
- 手机端：MediaProjection推流 + 自适应码率/分辨率 + 触摸回传
