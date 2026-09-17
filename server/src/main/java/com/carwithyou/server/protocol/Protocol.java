package com.carwithyou.server.protocol;

/**
 * car-with-you ADB 链路协议常量。
 *
 * 事实来源就是这里；改协议先改本文件，再同步 docs/PROTOCOL.md 与车机端实现。
 *
 * 拓扑：车机端通过 ADB 让手机以 shell 身份跑起本 server，server 监听
 * abstract socket {@link #SOCKET_NAME}，车机连**两次**：
 *   第 1 条 = 控制通道，第 2 条 = 视频通道。
 * 顺序固定，先控制后视频，两边都不要猜。
 *
 * 所有多字节整数一律**大端**（和 ADB 本身的小端相反，注意区分）。
 */
public final class Protocol {

    /** server 监听的 abstract socket 名。 */
    public static final String SOCKET_NAME = "carwithyou";

    /** 视频流开头的魔数 'CWYV'。 */
    public static final int VIDEO_MAGIC = 0x43575956;

    /** 视频流 codec 标识，四字符 ASCII。 */
    public static final int CODEC_AVC = 0x61766331; // 'avc1'
    public static final int CODEC_HEVC = 0x68766331; // 'hvc1'

    /** 帧标志位。 */
    public static final int FRAME_FLAG_KEY = 0x01;
    public static final int FRAME_FLAG_CONFIG = 0x02;

    private Protocol() {
    }

    /** 控制通道：车机 → 手机。 */
    public static final class Control {
        /** byte action, byte pointerId, float x, float y（x,y 是 0~1 归一化，相对视频帧）。 */
        public static final int INJECT_TOUCH = 0x00;
        /** byte action(0=按下 1=抬起 2=按下并抬起), int keyCode, int metaState。 */
        public static final int INJECT_KEY = 0x01;
        /** float x, float y, float hScroll, float vScroll。 */
        public static final int INJECT_SCROLL = 0x02;
        /** int length, utf8 文本。 */
        public static final int INJECT_TEXT = 0x03;
        /** int length, utf8 "包名/Activity"，int displayId（0 表示主屏）。 */
        public static final int LAUNCH_APP = 0x04;
        /** int taskId, int displayId。把已存在的任务挪到目标屏。 */
        public static final int MOVE_TASK = 0x05;
        /** byte mode，取值见 android.view.Display.STATE_*。 */
        public static final int SET_SCREEN_POWER = 0x06;
        /** int length, utf8 文本。 */
        public static final int SET_CLIPBOARD = 0x07;
        public static final int GET_CLIPBOARD = 0x08;
        /** 请求旋转（仅请求，实际是否转看被控端当前应用）。 */
        public static final int ROTATE = 0x09;
        /** 心跳，server 回 PONG。 */
        public static final int PING = 0x0A;

        /** 请求一个关键帧。虚拟屏画面静止时编码器不吐帧，重连的客户端会一直等不到 IDR。 */
        public static final int REQUEST_SYNC_FRAME = 0x0B;

        private Control() {
        }
    }

    /** 控制通道：手机 → 车机。 */
    public static final class Device {
        /** int displayId, int width, int height, int density, int rotation。 */
        public static final int DISPLAY_READY = 0x80;
        /** int length, utf8 文本。 */
        public static final int CLIPBOARD = 0x81;
        /** int length, utf8 错误文本。 */
        public static final int ERROR = 0x82;
        /** 心跳回包。 */
        public static final int PONG = 0x83;
        /** int length, utf8 文本（用于把 server 侧关键事件带到车机日志）。 */
        public static final int LOG = 0x84;

        private Device() {
        }
    }

    /** 触摸动作，和 android.view.MotionEvent 的 ACTION_* 对齐。 */
    public static final class TouchAction {
        public static final int DOWN = 0;
        public static final int UP = 1;
        public static final int MOVE = 2;

        private TouchAction() {
        }
    }
}