package com.carwithyou.server.control;

import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.carwithyou.server.protocol.Protocol;
import com.carwithyou.server.util.IO;
import com.carwithyou.server.util.Ln;
import com.carwithyou.server.wrappers.InputManager;
import com.carwithyou.server.wrappers.SurfaceControl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 解析控制通道上的消息并注入输入事件。
 *
 * 这就是 A 路线（shell 身份免无障碍）的全部意义：直接 `injectInputEvent`，
 * 不需要任何无障碍服务，不会被用户杀，也不会被系统提示「XX正在控制你的设备」。
 *
 * 坐标统一用 0~1 归一化（相对视频帧），在这里换算成虚拟屏的像素再注入。
 */
public final class Controller {

    private final InputStream controlIn;
    private final OutputStream controlOut;
    private final int videoWidth;
    private final int videoHeight;
    private final ControlTarget target;

    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];

    private long lastTouchDown;

    /**
     * 我们最后一次设过的旋转（-1 = 还没设过）。
     *
     * 旋转只有我们在改，所以自己记比每都去问系统可靠——
     * 虚拟屏的 rotation 在 dumpsys 里不一定及时反映，
     * 而 {@code getRotation()} 拿的是默认屏。
     */
    private int lastRotation = -1;

    /** 收到 REQUEST_SYNC_FRAME 时调它。可空 —— 没接编码器也能跑。 */
    private Runnable syncFrameRequester;

    public void setSyncFrameRequester(Runnable requester) {
        this.syncFrameRequester = requester;
    }

    public Controller(InputStream controlIn, OutputStream controlOut,
                      int videoWidth, int videoHeight, ControlTarget target) {
        this.controlIn = controlIn;
        this.controlOut = controlOut;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.target = target;
        for (int i = 0; i < PointersState.MAX_POINTERS; i++) {
            props[i] = new MotionEvent.PointerProperties();
            coords[i] = new MotionEvent.PointerCoords();
        }
    }

    /** 触摸/按键注入要定向到的屏。 */
    public interface ControlTarget {
        /** 返回目标显示 id；{@link android.view.Display#DEFAULT_DISPLAY} 表示主屏。 */
        int getTouchTargetDisplayId();
    }

    /**
     * 读取并处理一条控制消息。阻塞直到读到下一条；流结束抛 IOException 由调用方收尾。
     */
    public boolean readAndHandle() throws IOException {
        int type = IO.readByte(controlIn);
        switch (type) {
            case Protocol.Control.INJECT_TOUCH:
                handleTouch();
                return true;
            case Protocol.Control.INJECT_KEY:
                handleKey();
                return true;
            case Protocol.Control.INJECT_SCROLL:
                handleScroll();
                return true;
            case Protocol.Control.SET_SCREEN_POWER:
                handleSetDisplayPower();
                return true;
            case Protocol.Control.LAUNCH_APP:
                handleLaunchApp();
                return true;
            case Protocol.Control.MOVE_TASK:
                handleMoveTask();
                return true;
            case Protocol.Control.PING:
                sendPong();
                return true;
            case Protocol.Control.REQUEST_SYNC_FRAME:
                if (syncFrameRequester != null) {
                    syncFrameRequester.run();
                    Ln.d("已请求关键帧");
                }
                return true;
            case Protocol.Control.INJECT_TEXT:
                handleInjectText();
                return true;
            case Protocol.Control.SET_CLIPBOARD:
                handleSetClipboard();
                return true;
            case Protocol.Control.GET_CLIPBOARD:
                handleGetClipboard();
                return true;
            case Protocol.Control.ROTATE:
                handleRotate();
                return true;
            default:
                sendError("未知控制类型 0x" + Integer.toHexString(type) + "，跳过");
                return true;
        }
    }

    // ------------------------------------------------------------ 触摸

    private void handleTouch() throws IOException {
        int action = IO.readByte(controlIn);
        int pointerId = IO.readByte(controlIn);
        float xNorm = IO.readFloat(controlIn);
        float yNorm = IO.readFloat(controlIn);

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Ln.w("指针数已达上限，忽略");
            return;
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.x = xNorm * videoWidth;
        pointer.y = yNorm * videoHeight;
        pointer.up = (action == Protocol.TouchAction.UP);

        int pointerCount = pointersState.update(props, coords);
        int resultAction;
        if (pointerCount == 1) {
            if (action == Protocol.TouchAction.DOWN) {
                resultAction = MotionEvent.ACTION_DOWN;
                lastTouchDown = SystemClock.uptimeMillis();
            } else if (action == Protocol.TouchAction.UP) {
                resultAction = MotionEvent.ACTION_UP;
            } else {
                resultAction = MotionEvent.ACTION_MOVE;
            }
        } else {
            if (action == Protocol.TouchAction.UP) {
                resultAction = MotionEvent.ACTION_POINTER_UP
                        | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == Protocol.TouchAction.DOWN) {
                resultAction = MotionEvent.ACTION_POINTER_DOWN
                        | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else {
                resultAction = MotionEvent.ACTION_MOVE;
            }
        }

        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                lastTouchDown, now, resultAction, pointerCount, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        applyDisplayId(event);
        try {
            InputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("注入触摸失败", e);
        } finally {
            event.recycle();
        }
    }

    /**
     * 给注入事件定向到目标屏。
     *
     * 不用 15 参的 obtain(...displayId...) —— 那套在多数机型上是隐藏的；
     * 改为 obtain 之后通过反射调 InputManager.setDisplayId，和 scrcpy 一致。
     * 主屏则不加，因为部分机型显式设 displayId=0 反而会被忽略。
     */
    private void applyDisplayId(MotionEvent event) {
        applyDisplayIdTo((android.view.InputEvent) event);
    }

    /** KeyEvent / MotionEvent 共用的定向逻辑。 */
    private void applyDisplayIdTo(android.view.InputEvent event) {
        try {
            int display = target.getTouchTargetDisplayId();
            if (display != android.view.Display.DEFAULT_DISPLAY) {
                InputManager.setDisplayId(event, display);
            }
        } catch (ReflectiveOperationException e) {
            Ln.eShort("设置事件 displayId 失败", e);
        }
    }

    // ------------------------------------------------------------ 按键

    private void handleKey() throws IOException {
        int action = IO.readByte(controlIn);
        int keyCode = IO.readInt(controlIn);
        int repeat = IO.readInt(controlIn);

        int actionEvent;
        if (action == 0) {
            actionEvent = KeyEvent.ACTION_DOWN;
        } else if (action == 1) {
            actionEvent = KeyEvent.ACTION_UP;
        } else {
            actionEvent = KeyEvent.ACTION_DOWN;
        }

        long now = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(now, now, actionEvent, keyCode, repeat);

        try {
            int display = target.getTouchTargetDisplayId();
            if (display != android.view.Display.DEFAULT_DISPLAY) {
                InputManager.setDisplayId(keyEvent, display);
            }
            InputManager.injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("注入按键失败", e);
        }
    }

    // ------------------------------------------------------------ 滚动

    private void handleScroll() throws IOException {
        float xNorm = IO.readFloat(controlIn);
        float yNorm = IO.readFloat(controlIn);
        float hScroll = IO.readFloat(controlIn);
        float vScroll = IO.readFloat(controlIn);

        long now = SystemClock.uptimeMillis();
        // 滚动量必须设在 PointerCoords 上，MotionEvent 上没有公开的 setAxisValue
        props[0].id = 0;
        coords[0].x = xNorm * videoWidth;
        coords[0].y = yNorm * videoHeight;
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        applyDisplayId(event);
        try {
            InputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("注入滚动失败", e);
        } finally {
            event.recycle();
        }
    }

    // ------------------------------------------------------------ 电源 / 应用

    private void handleSetDisplayPower() throws IOException {
        int mode = IO.readByte(controlIn);
        boolean ok = SurfaceControl.setDisplayPowerMode(SurfaceControl.getBuiltInDisplay(), mode);
        if (!ok) {
            sendError("设置屏幕电源失败");
        }
    }

    private void handleLaunchApp() throws IOException {
        String component = new String(IO.readBytesWithLength(controlIn, 4096), StandardCharsets.UTF_8);
        int displayId = IO.readInt(controlIn);
        boolean ok = AppLauncher.launch(component, displayId);
        if (!ok) {
            sendError("启动应用失败: " + component);
        } else {
            Ln.i("已请求启动 " + component + " → display " + displayId);
        }
    }

    private void handleMoveTask() throws IOException {
        int taskId = IO.readInt(controlIn);
        int displayId = IO.readInt(controlIn);
        boolean ok = AppLauncher.moveTaskToDisplay(taskId, displayId);
        if (!ok) {
            sendError("移动任务失败: " + taskId);
        } else {
            Ln.i("已请求移动任务 " + taskId + " → display " + displayId);
        }
    }

    // ------------------------------------------------------------ 文本 / 剪切板 / 旋转

    /**
     * 文本输入：把字符串用 {@code KeyCharacterMap.getEvents()} 转成按键事件序列逐个注入。
     *
     * 选它而不是剪切板粘贴，是因为很多输入框（尤其是密码框、搜索框）没有粘贴入口，
     * 而按键序列走的是跟真人打字一样的路径，兼容性最好。
     *
     * 注意：只支持 ASCII。中文/emoji 这类字符在当前键盘布局下产生不了按键事件，
     * 会被跳过并回一条提示，让用户改用剪切板。
     */
    private void handleInjectText() throws IOException {
        String text = new String(IO.readBytesWithLength(controlIn, 8192), StandardCharsets.UTF_8);
        if (text.isEmpty()) return;

        int injected = 0;
        int skipped = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int[] keyCodes = charToKeyCodes(c);
            if (keyCodes == null) {
                skipped++;
                continue;
            }
            for (int keyCode : keyCodes) {
                if (injectKeyTap(keyCode)) injected++;
                else skipped++;
            }
        }
        Ln.i("注入文本长度 " + text.length() + "，成功 " + injected + " 个按键，跳过 " + skipped);
        if (skipped > 0 && injected == 0) {
            sendError("这段文字无法用按键注入（只支持英文/符号），请改用「粘贴」");
        }
    }

    /** 把单个字符转成需要的按键序列（可能会带上 SHIFT）。返回 null 表示当前布局打不出这个字符。 */
    private int[] charToKeyCodes(char c) {
        // KeyCharacterMap 只能处理 Latin-1 范围，先挡掉中文/emoji
        if (c > 127) return null;
        try {
            android.view.KeyCharacterMap kcm =
                    android.view.KeyCharacterMap.load(android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent[] events = kcm.getEvents(new char[]{c});
            if (events == null || events.length == 0) return null;
            int[] codes = new int[events.length];
            for (int i = 0; i < events.length; i++) codes[i] = events[i].getKeyCode();
            return codes;
        } catch (Throwable t) {
            Ln.w("字符转按键失败：'" + c + "' " + t.getMessage());
            return null;
        }
    }

    /** 注入一次「按下+抬起」。 */
    private boolean injectKeyTap(int keyCode) {
        try {
            injectKeyEvent(keyCode, KeyEvent.ACTION_DOWN);
            injectKeyEvent(keyCode, KeyEvent.ACTION_UP);
            return true;
        } catch (Throwable t) {
            Ln.w("注入按键 " + keyCode + " 失败：" + t.getMessage());
            return false;
        }
    }

    private void injectKeyEvent(int keyCode, int action) throws ReflectiveOperationException {
        long now = SystemClock.uptimeMillis();
        // deviceId 传 -1 = VIRTUAL_KEYBOARD（KeyCharacterMap.VIRTUAL_KEYBOARD 是 @hide，不能直接引用）
        KeyEvent event = new KeyEvent(now, now, action, keyCode, 0, 0, -1, 0, 0);
        applyDisplayIdTo(event);
        InputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /** 车机 → 手机：把文本写进手机剪切板。 */
    private void handleSetClipboard() throws IOException {
        String text = new String(IO.readBytesWithLength(controlIn, 65536), StandardCharsets.UTF_8);
        if (!com.carwithyou.server.wrappers.ClipboardManager.isReady()) {
            sendError("手机侧剪切板不可用");
            return;
        }
        if (com.carwithyou.server.wrappers.ClipboardManager.setText(text)) {
            Ln.i("已写入手机剪切板，长度 " + text.length());
            // 回一条让车机知道写成功了（车机侧会更新自己的记录，避免又回传一遍）
            sendClipboard(text);
        } else {
            sendError("写入手机剪切板失败");
        }
    }

    /** 车机请求手机当前剪切板内容。 */
    private void handleGetClipboard() throws IOException {
        if (!com.carwithyou.server.wrappers.ClipboardManager.isReady()) {
            sendError("手机侧剪切板不可用");
            return;
        }
        String text = com.carwithyou.server.wrappers.ClipboardManager.getText();
        Ln.i("读手机剪切板：" + (text == null ? "拿不到（null）" : "长度 " + text.length()));
        if (text == null) {
            // 空剪切板不是错误，回空串让车机侧安心
            sendClipboard("");
            return;
        }
        sendClipboard(text);
    }

    private void sendClipboard(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        IO.writeByte(controlOut, Protocol.Device.CLIPBOARD);
        IO.writeBytesWithLength(controlOut, bytes);
    }

    /**
     * 旋转虚拟屏。
     *
     * 传 -1 表示「翻到另一个方向」（0↔1、2↔3），方便车机上一个按钮两用。
     * 用 freezeRotation 而不是直接写 Settings：前者立即生效且不影响用户设置。
     */
    private void handleRotate() throws IOException {
        int requested = IO.readByte(controlIn);
        // 协议里 rotation 用有符号值，-1 会在字节里变成 255
        if (requested == 255) requested = -1;

        if (!com.carwithyou.server.wrappers.WindowManager.isReady()) {
            sendError("手机侧不支持旋转（window 服务不可用）");
            return;
        }
        int targetRotation = requested;
        int displayId = target.getTouchTargetDisplayId();
        if (targetRotation == -1) {
            // “翻到另一个方向”：用我们自己记的值。
            // 不能问 getRotation()：它读的是**默认屏**，虚拟屏冻结后它还是旧值，
            // 会导致每次都翻到同一个方向（踩过）。
            int current = lastRotation >= 0 ? lastRotation : 0;
            targetRotation = (current & 1) ^ 1;   // 0->1, 1->0
        }
        if (com.carwithyou.server.wrappers.WindowManager.freezeRotation(displayId, targetRotation)) {
            lastRotation = targetRotation;
            Ln.i("已旋转 display " + displayId + " 到 " + targetRotation);
        } else {
            sendError("旋转失败");
        }
    }

    // ------------------------------------------------------------ 回包

    private void sendPong() throws IOException {
        IO.writeByte(controlOut, Protocol.Device.PONG);
        controlOut.flush();
    }

    private void sendError(String message) throws IOException {
        Ln.w(message);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        IO.writeByte(controlOut, Protocol.Device.ERROR);
        IO.writeBytesWithLength(controlOut, bytes);
        controlOut.flush();
    }
}