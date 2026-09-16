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
| 8888 视频 | 手机 → 车机 | H.264 NAL | `[4字节大端长度][NAL]`，首包必含 SPS(7)/PPS(8) |
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

## 6. 音频

V1 只走手机蓝牙到车（导航+音乐都响）。内录（AudioPlaybackCapture）+ AudioTrack 是 V2。
