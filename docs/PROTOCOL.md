# CarWithYou 投屏协议 v1.4（双端 + 浏览器降级）

> 主链路（对标 vivo 车联 / CarPlus）：手机建一块独立虚拟屏，导航/音乐在那块屏上跑，H264 经 8888 推给车机App。手机主屏空出来。
> 降级：打开「浏览器直连」则 MJPEG 经 HTTP 8080 推给车机自带浏览器，车机可不装 App（约15fps）。
> 关掉「独立虚拟屏」则整屏镜像（要占手机分屏）。
> 本文件是两端唯一的协议事实来源，改协议先改这里。

## 0. 浏览器降级（默认关，车机装不了 App 时开）

- 手机开热点（网关 `192.168.43.1` 固定），车机连热点后用自带浏览器打开 `http://192.168.43.1:8080/`。
- `GET /` → 全屏播放页（`<img src=/video>` + 点/滑回传 JS，含黑边扣除）。
- `GET /video` → `multipart/x-mixed-replace` MJPEG，约 15fps，JPEG q70，任何浏览器原生可播。
- `GET /config` → `{"w":1280,"h":720}`。
- `GET /tap?x=0~1&y=0~1` → 点击（视频帧归一化坐标，已扣黑边）。
- `GET /swipe?x0=&y0=&x1=&y1=&dur=` → 滑动，同上。
- 触摸最终走同一 `TouchInjectorService` 注入到 `CastConfig.displayId`（需开无障碍）。
- 实现：`BrowserCastServer`（无三方依赖，ServerSocket）+ `ScreenCastService` 内 `ImageReader → Bitmap → JPEG → offerFrame`。

## 1. 拓扑（为什么是手机当 Server）

- 手机开热点（网关 `192.168.43.1` 固定），车机作为 station 连入。
- 手机 IP 稳定、车机 IP 是 DHCP 分配的，所以 **手机当 TCP Server、车机当 Client 主动连**最稳，断线重连逻辑只写在车机一端。

## 2. 端口与通道

| 端口 | 方向 | 内容 | 格式 |
|---|---|---|---|
| 8888 视频 | 手机 → 车机 | H.264 NAL | `[4字节大端长度][NAL]`，新客户端连上先收 SPS/PPS（outputFormat/嗅探/通用保底三级取）再收 live 流 |
| 8889 控制 | 双向文本行 `\n` | 配置 / 触摸 / 心跳 / 统计 | 下表 |

控制行（全部 UTF-8 文本行）：

| 行 | 发送方 | 含义 |
|---|---|---|
| `CONFIG vw vh sw sh` | 手机 → 车机 | 视频编码宽高 + 触摸坐标系宽高。虚拟屏模式下 `sw,sh` = 虚拟屏像素（通常等于 `vw,vh`）；镜像模式 = 手机物理屏。**分辨率切换时手机会重发** |
| `STATS dropRatio decodeMs bitrate latencyMs` | 车机 → 手机 | 丢帧率(0~1)、平均解码耗时(ms)、实测码率(bps)、RTT(ms)。**每2秒发一次** |
| `PING <ts>` | 任一 → 对方 | 心跳，对方必须回 `PONG <ts>` |
| `PONG <ts>` | 任一 → 对方 | 心跳回包 |
| `TAP nx ny` | 车机 → 手机 | 点击，`nx,ny∈[0,1]`，**视频帧归一化坐标**（已扣黑边） |
| `SWIPE x0 y0 x1 y1 durMs` | 车机 → 手机 | 滑动，同上 |
| `APP home\|nav\|music\|split` | 车机 → 手机 | 左 Dock 切应用：虚拟屏正常时切副屏；已降级/镜像时直接切手机主屏（镜像同步跟过去） |
| `KEY back` | 车机 → 手机 | 左 Dock 返回键，经无障碍全局返回（需无障碍，虚拟/镜像通用） |

## 3. 编码参数

- H.264 Baseline（无 B 帧，天然低延迟），CBR，30fps，GOP 1s，`KEY_LATENCY=1`（API30+）。
- **自适应码率**：`PARAMETER_KEY_VIDEO_BITRATE` 热切换，不重建编码器。
- **自适应分辨率**：仅镜像模式会重建 VirtualDisplay + MediaCodec（300ms 切换窗口）。**虚拟屏锁定分辨率**，只热切码率，避免重建 Display 把车上的 App 杀掉。

分辨率档位与码率范围（`CastConfig`，可调）：

| 档位（短边） | 码率范围 | 适用场景 |
|---|---|---|
| 480p | 0.8–2 Mbps | 网络差/手机热点信号弱 |
| 720p | 1.5–4 Mbps | 默认，大多数车机够用 |
| 1080p | 3–8 Mbps | 网络好，10寸以上大屏 |

### 自适应决策逻辑（手机端 `CastConfig.decide()`）

收到车机 STATS 后每 2 秒决策一次：

| 条件 | 动作 |
|---|---|
| 丢帧 > 10% **或** 解码 > 20ms/帧（1.5倍预算） | **降档**：先降码率；镜像模式码率已最低则降分辨率；虚拟屏只降码率 |
| 丢帧 < 2% **且** RTT < 80ms **且** 码率未满档 | **升码率**（×1.2，不重建编码器） |
| 丢帧 < 2% **且** RTT < 80ms **且** 码率已满档 | **升分辨率**（仅镜像模式；虚拟屏维持） |
| 丢帧 2%~10% | 维持不变 |

降分辨率时目标码率取新档的中值；升分辨率时也取中值，避免跳变。

## 4. 触摸映射（含黑边）

车机 view 为 `W×H`，视频帧 `vw×vh`，fit-center 显示：

```
s  = min(W/vw, H/vh)
cw = vw*s, ch = vh*s, ox = (W-cw)/2, oy = (H-ch)/2
黑边点击直接丢弃；内部：nx=(x-ox)/cw, ny=(y-oy)/ch
```

手机收到后：`sx = nx*sw, sy = ny*sh`，经无障碍 `dispatchGesture` 注入到 `CastConfig.displayId`（虚拟屏优先；Android 14+ `GestureDescription.setDisplayId`）。

**分辨率切换时**：手机先发新 CONFIG（含新 vw/vh），车机收到后重建解码器 + 重算映射参数。

## 5. 自动无感连接

- 车机：`BootReceiver` → `ReceiverKeepService`（前台 dataSync）+ 指数退避重连（1/2/4/8/15s）+ 心跳 5s/超时 15s。
- 手机：热点固定 SSID，车机记住自动连。
- 系统底线：**第一次建虚拟屏仍要点一次「允许录屏」**（用来建独立 Display，不是录手机主屏）。之后不重启可复用。
- 开发期用 `scripts/adb-preauth.ps1` 做电池白名单 + 权限预授权。

## 8. 结构化车联 v2（小米 CarWith / HiCar 式，音乐/电话原生同步）

> 不传整屏视频，只同步数据。手机 `LinkService` 当 TCP Server（端口 **8890**），车机 `LinkClient` 主动连。UTF-8 JSON 行（`\n` 结尾）。声音仍走蓝牙；导航画面这版仍走 8888 视频（车机“投屏”入口），音乐/电话走本链路原生显示和控制。

手机 → 车机：

| 行 | 含义 |
|---|---|
| `{"t":"HELLO","ver":2}` | 握手，建连后先发 + 附带当前快照 |
| `{"t":"MUSIC","app","title","artist","playing"}` | 当前播放（经 MediaSession，需通知监听；变了才推，另有 2s 轮询） |
| `{"t":"PHONE","state","number","name"}` | `state` = `idle`/`ringing`/`offhook`，name 经通讯录查（需 READ_CONTACTS） |
| `{"t":"STATUS","batt","charging","sig"}` | 手机电量（0~100，-1 未知）/是否充电/信号格 0~4（-1 未知），10 秒推一次，车机桌面状态栏显示 |
| `{"t":"PONG","ts"}` | 心跳回包 |

车机 → 手机：

| 行 | 含义 |
|---|---|
| `{"t":"PING","ts"}` | 心跳 |
| `{"t":"MUSIC_CMD","cmd"}` | `play`/`pause`/`toggle`/`next`/`prev`，经 MediaSession transportControls 执行 |
| `{"t":"PHONE_CMD","cmd"}` | `answer`/`hangup`，经 TelecomManager（需 ANSWER_PHONE_CALLS 等电话权限，被拒则只显示状态） |

预留：`{"t":"NAV_POI","keyword"}`（车机搜目的地 → 手机虚拟屏高德直达，车机零本地地图）。

## 6. 音频

V1 只走手机蓝牙到车（导航+音乐都响）。内录（AudioPlaybackCapture）+ AudioTrack 是 V2。

---

# ADB 链路（shell server）协议 v0.1

> **另一套独立的协议**，对应技术路线 A：车机自研 ADB 客户端 → 把 `server`（APK 改名 `carwithyou_server.jar`）
> sync 推进手机 `/data/local/tmp` → `app_process` 以 **shell 身份**拉起 → 手机建一块独立虚拟屏，
> 导航/音乐在那块屏上跑。**手机零安装**。区别于上文 8888/8889 的 MediaProjection 主链路，
> 这两套互不依赖、可并存（A 路线作为新增链路，原方案保留作降级）。
>
> 事实来源是 `server/src/main/java/com/carwithyou/server/protocol/Protocol.java`；
> 改协议先改那里，再同步本文件与车机端实现。
> 本段已在 **Android 15（Xiaomi 23127PN0CC）** 上端到端验证：
> 独立虚拟屏、H.264 avc1、DSPLAY_READY、PING/PONG、LAUNCH_APP 投放应用、断线干净收尾均通。

## A0. 生命周期

1. 车机 ADB 连通手机后：`pushBytes` 推 `carwithyou_server.jar` 到 `/data/local/tmp/`
2. 启动：\`app_process -Djava.class.path=/data/local/tmp/carwithyou_server.jar / com.carwithyou.server.Server key=value ...\`
3. server 建独立虚拟屏（`mirror=0` 默认）或镜像（`mirror=1`），监听 abstract socket `carwithyou`
4. 车机经 `localabstract:carwithyou` 连**两条**连接：**第一条=控制，第二条=视频**（顺序固定，勿反）
5. 视频通道先收视频头；控制通道随后收 DISPLAY_READY
6. 任一通道断开 → server 整体退出并释放资源

## A1. 启动参数（`key=value`）

| key | 默认 | 含义 |
|---|---|---|
| `width` / `height` | 源屏尺寸 | 视频/虚拟屏像素（自动 8 对齐） |
| `density` | 源屏 dpi | 虚拟屏密度 |
| `maxFps` | 60 | 编码上限（`max-fps-to-encoder`） |
| `bitrate` | 8000000 | 编码码率 bps |
| `iFrameInterval` | 1 | 关键帧间隔（秒） |
| `mirror` | 0 | 1=镜像已有屏；0=新建独立空屏（默认，本路线目标） |
| `displayId` | 0 | 镜像的目标屏 id |
| `mode` | 空 | `probe` 时只跑能力探针（Diagnostics）后退出 |

屏幕能力差异（探针实测）：

- **Android 12** 无静态镜像 `createVirtualDisplay`；独立屏实例方法和 `SurfaceControl.createDisplay` 可用。
- **Android 13+** 有静态镜像法（产出注册的 displayId）；`SurfaceControl.createDisplay` 被隐藏 API 拦截不可用。

server 据此自适应：独立屏优先，失败回落镜像；镜像优先静态法、失败用 SurfaceControl。

## A2. 视频通道（手机 → 车机，**大端**）

固定 16 字节头：

```
u32 magic  'CWYV'
u32 codec  'avc1' | 'hvc1'
u32 width
u32 height
```

之后每帧：

```
u32 length          // = 1 + payload 长度
u8  flags           // bit0=关键帧  bit1=codec config(SPS/PPS)
payload             // H.264 Annex-B 帧（含起始码）
```

`codec config` 帧先于首个关键帧到达；Android 编码器默认把 SPS/PPS 拼到 IDR 前。

## A3. 控制通道（双向，**大端**）

### 车机 → 手机：`u8 type` 开头

| type | 名称 | 载荷 |
|---|---|---|
| 0x00 | INJECT_TOUCH | `u8 action`(0=按下 1=抬起 2=移动) `u8 pointerId` `f32 x` `f32 y`（0~1 归一化，相对视频帧） |
| 0x01 | INJECT_KEY | `u8 action`(0=按下 1=抬起 2=按下抬起) `i32 keyCode` `i32 repeat` |
| 0x02 | INJECT_SCROLL | `f32 x` `f32 y` `f32 hScroll` `f32 vScroll` |
| 0x03 | INJECT_TEXT | `u32 len` utf8`文本` —— 用 `KeyCharacterMap` 拆成按键事件逐个注入（真人打字路径，只支持英数；中文返回 ERROR 提示改用粘贴） |
| 0x04 | LAUNCH_APP | `u32 len` utf8`组件` `i32 displayId`（0=主屏） |
| 0x05 | MOVE_TASK | `i32 taskId` `i32 displayId` |
| 0x06 | SET_SCREEN_POWER | `u8 mode`（Display.STATE_*） |
| 0x07 | SET_CLIPBOARD | `u32 len` utf8`文本` —— 写入手机剪切板（反射 `IClipboard`），写成后回一条 `0x81 CLIPBOARD` |
| 0x08 | GET_CLIPBOARD | 无参数；手机回 `0x81 CLIPBOARD`。拿不到或空剪切板回空串 |
| 0x09 | ROTATE | `u8 rotation`（0/1/2/3 指定方向；`0xFF` = -1 表示翻到另一个方向 `0↔1`）。用 `IWindowManager.freezeDisplayRotation` 冻结虚拟屏 |
| 0x0A | PING | 无（对方回 PONG） |
| 0x0B | REQUEST_SYNC_FRAME | 无；让下一个编码帧是 IDR。注意它**不会凭空造帧** —— 虚拟屏无变化时依然不吐帧 |

### 文本 / 剪切板 / 旋转 的实现要点（踩过的坑）

**文本输入（0x03）**：用 `KeyCharacterMap.getEvents()` 把字符转成 `KeyEvent` 序列，再走 `InputManager.injectInputEvent()`。
选它而不是剪切板粘贴，是因为很多输入框（密码框、搜索框）没有粘贴入口，按键序列跟真人打字同一条路径。
**只能英数/符号** —— 中文、emoji 在当前键盘布局下产生不了按键事件，会跳过并回 ERROR 提示改用粘贴。

**剪切板（0x07/0x08）**：反射调 `IClipboard$Stub.asInterface()`，不走 `Context.getSystemService`（app_process 下没有正常 Context）。两个坑：

1. `getPrimaryClip()` 的**无参版本是接口 Default 实现，调了永远返回 null**。必须选参数最多的真实 AIDL 签名：
   `getPrimaryClip(String callingPackage, String attributionTag, int userId, int deviceId)`。
2. `callingPackage` 必须与**调用进程真实 UID 对应**（系统会比对，对不上就当冒充 → 返回 null）。
   我们按 `Process.myUid()` 反推：uid 0 → `"root"`；uid 2000 → `"com.android.shell"`。

另外 Android 10+ 限制：**只有前台应用、或有 `READ_CLIPBOARD_IN_BACKGROUND` 的应用能读**。
shell 自带该权限，所以以**真机的 shell 身份（uid 2000）**运行可读；被 root 提权后会报：
`ClipboardService: Denying clipboard access to root, application is not in focus...`
（**写不受此限制**，`setPrimaryClip` 一直可用。）

**旋转（0x09）**：用 `IWindowManager.freezeDisplayRotation(displayId, rotation, caller)` + `thawDisplayRotation`。

- Android 15 把方法名从 `freezeRotation` 改成了 `freezeDisplayRotation`，且参数从 1 个变 3 个（`displayId, rotation, caller`）。
- 这些成员是 **`hiddenapi: BLOCKED`**，`getMethod()` 会**直接把它们过滤掉**（抛 NoSuchMethodException，不报权限错）。
  必须用 `getDeclaredMethod()` + `setAccessible(true)` 才能拿到。
- `rotation = -1`（字节 `0xFF`）表示「翻到另一个方向」。注意**不要**用 `getRotation()` 判断当前朝向——
  它读的是**默认屏**，虚拟屏冻结后仍是旧值，会导致每次都翻到同一方向。server 自己记 `lastRotation`。

### 视频「静默」是正常的（重要）

虚拟屏画面**静止时编码器一帧都不吐**（SurfaceFlinger 没有新图层要合成，`KEY_REPEAT_PREVIOUS_FRAME_AFTER` 在实测环境里也没救回来）。所以：

- **不能**把「视频流读超时」当成断链 —— 客户端应无限等帧，把最后一帧留在屏上；
- 链路存活只靠**控制通道** PING/PONG 判定；
- 客户端重建解码器后发 `REQUEST_SYNC_FRAME`，保证下一帧能从零解起。

### 空虚拟屏 = 黑屏（重要）

新建的独立虚拟屏**没有任何图层**，因此不会有任何帧。建完屏必须**往里面投一个 App**
（`LAUNCH_APP`）才会有画面 —— 这也是 A 路线的产品形态本身：把手机导航/音乐投到车机屏。

### 手机 → 车机：`u8 type` 开头

| type | 名称 | 载荷 |
|---|---|---|
| 0x80 | DISPLAY_READY | `i32 displayId` `i32 width` `i32 height` `i32 density` `i32 rotation` |
| 0x82 | ERROR | `u32 len` utf8`文本` |
| 0x83 | PONG | 无 |
| 0x84 | LOG | `u32 len` utf8`文本`（server 关键事件带到车机日志） |

## A4. 多点触控约定

- 每个 `pointerId` 一根手指；按下前先 MOVE 会被忽略。
- 第一根按下走 `ACTION_DOWN`，后续 `ACTION_POINTER_DOWN`（带 index）；抬起同理末根 `ACTION_UP`。
- 注入目标：独立屏 = 虚拟屏自身 displayId；镜像 = 虚拟屏 displayId。坐标换算由 server 完成（归一化→像素）。

## A5. 输入注入方式（A 路线免无障碍的核心）

- shell 身份直接 `InputManager.injectInputEvent`，**无需无障碍服务**，不会被杀、无「正在控制你的设备」提示。
- 按键/触摸注入目标屏通过 `InputManager.setDisplayId(event, displayId)` 反射设置。
- 需要把应用放上虚拟屏：`am start --display <id> -n pkg/act`（ActivityOptions 设置 launchDisplayId 更稳，V2）。
