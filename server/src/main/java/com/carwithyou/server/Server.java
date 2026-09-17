package com.carwithyou.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.carwithyou.server.control.Controller;
import com.carwithyou.server.protocol.Protocol;
import com.carwithyou.server.util.IO;
import com.carwithyou.server.util.Ln;
import com.carwithyou.server.video.ScreenCapture;
import com.carwithyou.server.video.VideoEncoder;
import com.carwithyou.server.wrappers.DisplayInfo;
import com.carwithyou.server.wrappers.DisplayManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * server 入口。
 *
 * 生命周期（对应 docs/PROTOCOL.md）：
 *  1. 建虚拟屏（新建独立屏，失败回落镜像）
 *  2. 监听 abstract socket {@link Protocol#SOCKET_NAME}
 *  3. 收**第一条**连接作控制通道、**第二条**作视频通道
 *  4. 先写视频头（魔数+codec+宽高），再写控制通道的 DISPLAY_READY
 *  5. 主线程跑视频编码循环，daemon 线程跑控制消息循环
 *  6. 任一通道断开 → 整体退出，release 所有资源
 */
public final class Server {

    /** 控制通道 accept 超时，防止车机只连了一条就跑。 */
    private static final int ACCEPT_TIMEOUT_MS = 30_000;

    /**
     * 就绪标记文件：监听起来之后写、退出之前删。
     *
     * 车机端就靠它判断“可以连了”——不要用车机去试探性连接来判断，
     * 因为 server 的 accept 有固定顺序（第一条控制、第二条视频），
     * 拿探测连接去问会把顺序搞乱。
     */
    public static final String READY_FILE = "/data/local/tmp/cwy.ready";

    public static void main(String[] args) {
        int status = 0;
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Ln.e("未捕获异常", throwable);
        });
        Ln.i("car-with-you server 启动，参数=" + Arrays.toString(args));
        // 上一次跑完意外残留的就绪文件会骗到车机，先清掉
        deleteReadyFile();

        try {
            Options options = Options.parse(args);
            // 必须在碰 Workarounds / ActivityThread 之前 —— ActivityThread 的构造里会 new Handler，
            // 没有 Looper 会直接抛「Can't create handler inside thread that has not called Looper.prepare()」。
            prepareMainLooper();
            Workarounds.apply();
            com.carwithyou.server.wrappers.ServiceManager.init();
            if (looksLikeProbe(args)) {
                Diagnostics.run();
                return;
            }
            new Server(options, args).run();
        } catch (Throwable t) {
            Ln.e("server 运行失败", t);
            status = 1;
        } finally {
            deleteReadyFile();
            // 进程里可能还有 SDK 起的非守护线程，不显式退会一直挂着。
            Ln.i("server 退出，status=" + status);
            System.exit(status);
        }
    }

    private static void deleteReadyFile() {
        try {
            new java.io.File(READY_FILE).delete();
        } catch (Throwable ignored) {
            // 清理失败不影响主流程
        }
    }

    /**
     * 写就绪文件，内容：`<pid> <displayId> <width> <height> <density>` + 换行 + 原始参数。
     * 车机端比对“原始参数”这一段来决定是复用还是重启 server。
     */
    private void writeReadyFile(int displayId, int width, int height, int density) {
        StringBuilder sb = new StringBuilder();
        sb.append(android.os.Process.myPid()).append(' ')
                .append(displayId).append(' ')
                .append(width).append(' ')
                .append(height).append(' ')
                .append(density).append('\n');
        for (int i = 0; i < rawArgs.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(rawArgs[i]);
        }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(READY_FILE);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            Ln.i("就绪文件已写：" + READY_FILE + " -> " + sb);
        } catch (IOException e) {
            Ln.eShort("写就绪文件失败", e);
        }
    }

    /** `mode=probe` 时只跑诊断，不进正常流程。 */
    private static boolean looksLikeProbe(String[] args) {
        for (String arg : args) {
            if ("mode=probe".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 相当于 Looper.prepareMainLooper()，但允许 quit（系统那个不允许，退出时会报错）。
     */
    private static void prepareMainLooper() {
        android.os.Looper.prepare();
        synchronized (android.os.Looper.class) {
            try {
                java.lang.reflect.Field field = android.os.Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, android.os.Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    private final Options options;
    private final String[] rawArgs;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private Server(Options options, String[] rawArgs) {
        this.options = options;
        this.rawArgs = rawArgs;
    }

    private void run() throws Exception {
        if (!DisplayManager.isReady()) {
            throw new IllegalStateException("显示服务未就绪，无法继续");
        }

        DisplayInfo defaultDisplay = DisplayManager.getDisplayInfo(0);
        if (defaultDisplay == null) {
            throw new IllegalStateException("取主屏信息失败，无法确定采集尺寸");
        }
        Ln.i("主屏信息：" + defaultDisplay);

        final ScreenCapture capture;
        final int videoWidth;
        final int videoHeight;
        final int density;

        // 先把 abstract socket 占下来，再去建屏。
        // 顺序很重要：名字被占（上一轮 server 没清干净）时要在建屏**之前**就报错，
        // 否则会先建出一块虚拟屏再失败 —— 屏泄漏，而且报错信息还会误导人（踩过）。
        final LocalServerSocket serverSocket;
        try {
            serverSocket = new LocalServerSocket(Protocol.SOCKET_NAME);
        } catch (IOException e) {
            throw new IOException("abstract socket '" + Protocol.SOCKET_NAME
                    + "' 被占用（上一轮 server 没退干净？），本轮不建屏直接退出：" + e.getMessage(), e);
        }
        Ln.i("监听 abstract socket: " + Protocol.SOCKET_NAME);

        // 编码器 prepare() 拿到 Surface 后，把虚拟屏接到上面
        encoder = new VideoEncoder(options);
        android.view.Surface encoderSurface = encoder.prepare();

        capture = new ScreenCapture(options);
        try {
            capture.init(encoderSurface, defaultDisplay);
        } catch (Throwable t) {
            safeCloseQuietly(serverSocket);
            throw t;
        }
        videoWidth = options.getWidth();
        videoHeight = options.getHeight();
        density = options.getDensity();

        encoder.start();
        Ln.i("虚拟屏就绪：" + capture.describe() + "，编码器：" + encoder.getCodecName());

        acceptAndRun(serverSocket, capture, videoWidth, videoHeight, density);
    }

    private VideoEncoder encoder;

    private void acceptAndRun(LocalServerSocket serverSocket, ScreenCapture capture,
                              int width, int height, int density) {
        LocalSocket controlSocket = null;
        LocalSocket videoSocket = null;
        try (serverSocket) {
            // 监听已经起来了 —— 到这里才能告诉车机“可以来连了”
            writeReadyFile(capture.getDisplayId(), width, height, density);

            controlSocket = serverSocket.accept();
            controlSocket.setSoTimeout(0);
            Ln.i("控制通道已连接");

            videoSocket = serverSocket.accept();
            videoSocket.setSoTimeout(0);
            Ln.i("视频通道已连接");

            InputStream controlIn = controlSocket.getInputStream();
            OutputStream controlOut = controlSocket.getOutputStream();
            InputStream videoIn = videoSocket.getInputStream(); // 保留引用防 GC 提前回收
            OutputStream videoOut = videoSocket.getOutputStream();

            // 视频头：魔数 + codec + 宽高 —— 车机端拿到才能建解码器
            IO.writeInt(videoOut, Protocol.VIDEO_MAGIC);
            IO.writeInt(videoOut, Protocol.CODEC_AVC);
            IO.writeInt(videoOut, width);
            IO.writeInt(videoOut, height);
            videoOut.flush();

            // 控制通道回 DISPLAY_READY + 一条 LOG
            writeDisplayReady(controlOut, capture.getDisplayId(), width, height, density);
            writeLog(controlOut, "video " + width + "x" + height + " dpi=" + density
                    + " displayId=" + capture.getDisplayId());

            int touchTargetDisplay = capture.getDisplayId();
            Controller controller = new Controller(
                    controlIn, controlOut,
                    width, height,
                    () -> touchTargetDisplay);
            controller.setSyncFrameRequester(encoder::requestSyncFrame);

            Thread controlThread = new Thread(() -> {
                try {
                    while (running.get()) {
                        controller.readAndHandle();
                    }
                } catch (IOException e) {
                    Ln.i("控制通道断开：" + e.getMessage());
                } finally {
                    running.set(false);
                }
            }, "carwithyou-control");
            controlThread.setDaemon(true);
            controlThread.start();

            // 主线程跑视频编码
            Ln.i("开始视频编码");
            encoder.streamTo(videoOut);

            // 视频流结束（对方断开或出错），等控制线程自然退出
            if (running.getAndSet(false)) {
                try {
                    controlThread.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException e) {
            Ln.e("通道异常：" + e.getMessage(), e);
        } finally {
            Ln.i("server 收尾释放资源");
            deleteReadyFile();
            releaseAll(capture);
            safeClose(controlSocket);
            safeClose(videoSocket);
        }
    }

    private static void writeDisplayReady(OutputStream out, int displayId, int width, int height, int density) {
        try {
            IO.writeByte(out, Protocol.Device.DISPLAY_READY);
            IO.writeInt(out, displayId);
            IO.writeInt(out, width);
            IO.writeInt(out, height);
            IO.writeInt(out, density);
            int rotation = DisplayManager.getRotation(0);
            IO.writeInt(out, rotation);
            out.flush();
        } catch (IOException e) {
            Ln.eShort("写 DISPLAY_READY 失败", e);
        }
    }

    private static void writeLog(OutputStream out, String message) {
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            IO.writeByte(out, Protocol.Device.LOG);
            IO.writeBytesWithLength(out, bytes);
            out.flush();
        } catch (IOException e) {
            Ln.eShort("写 LOG 失败", e);
        }
    }

    private void releaseAll(ScreenCapture capture) {
        if (encoder != null) {
            encoder.stop();
        }
        if (capture != null) {
            capture.release();
        }
    }

    private static void safeClose(LocalSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 收尾阶段忽略
            }
        }
    }

    /** 建屏失败时关掉已经建好的监听套接字，避免 abstract socket 名被泄漏占用。 */
    private static void safeCloseQuietly(LocalServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 收尾阶段忽略
            }
        }
    }
}