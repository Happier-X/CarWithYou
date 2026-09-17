package com.carwithyou.server.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import com.carwithyou.server.Options;
import com.carwithyou.server.protocol.Protocol;
import com.carwithyou.server.util.IO;
import com.carwithyou.server.util.Ln;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * 用 MediaCodec 的「输入 Surface」模式把虚拟屏画面编成 H.264。
 *
 * 为什么用 Surface 模式而不是 ByteBuffer 模式：画面本来就在 GPU 上，
 * Surface 模式全程零拷贝，也不用手写 YUV 格式转换。
 *
 * 生命周期有个顺序要求（和 scrcpy 一致，别调换）：
 *   prepare()  →  configure + createInputSurface，拿到 Surface
 *   （调用方拿这个 Surface 去建虚拟屏）
 *   start()    →  codec.start()
 *   streamTo() →  阻塞式把码流写出去
 */
public final class VideoEncoder {

    /** 静态画面时按这个间隔重复上一帧，既让首帧能出来，也避免画面糊住不刷新。 */
    private static final long REPEAT_FRAME_DELAY_US = 100_000L;

    /** 私有 key，Android 10 之前才叫这个名字，但一直有效。 */
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private final Options options;

    private MediaCodec codec;
    private boolean started;

    public VideoEncoder(Options options) {
        this.options = options;
    }

    public String getCodecName() {
        return codec == null ? "?" : codec.getName();
    }

    /** 配置编码器并返回输入 Surface；此时还没开始编码。 */
    public Surface prepare() throws IOException {
        MediaFormat format = createFormat(
                "video/avc",
                options.getBitrate(),
                options.getMaxFps(),
                options.getIFrameIntervalSeconds());

        codec = MediaCodec.createEncoderByType("video/avc");
        Ln.i("使用编码器：" + codec.getName() + " " + options.getWidth() + "x" + options.getHeight());

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return codec.createInputSurface();
    }

    /** 虚拟屏建好后再调用。 */
    public void start() {
        codec.start();
        started = true;
    }

    /**
     * 阻塞式把编码输出写到 out。
     *
     * 帧格式：`int32 长度 | byte 标志位 | 负载`，长度为 1 + 负载长度。
     * 这里不加锁 —— 只有视频线程会碰 codec。
     */
    public void streamTo(OutputStream out) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int produced = 0;
        int tryAgain = 0;
        long lastBeat = System.currentTimeMillis();

        while (!Thread.currentThread().isInterrupted()) {
            // 不用 -1 死等：给一个超时才能打心跳，否则卡住时整条链路一点声响都没有
            int index = codec.dequeueOutputBuffer(info, 200_000);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                tryAgain++;
                long now = System.currentTimeMillis();
                if (now - lastBeat >= 5000) {
                    Ln.w("等编码输出超时：已出 " + produced + " 帧，tryAgain=" + tryAgain);
                    lastBeat = now;
                }
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Ln.i("编码器输出格式变化：" + codec.getOutputFormat());
                continue;
            }
            if (index < 0) {
                // INFO_OUTPUT_BUFFERS_CHANGED：老 API 用得到，现在不用管
                Ln.d("dequeueOutputBuffer -> " + index);
                continue;
            }
            try {
                if (info.size <= 0) {
                    continue;
                }
                boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                produced++;
                if (produced <= 5) {
                    Ln.i("编码输出 #" + produced + " size=" + info.size
                            + " flags=0x" + Integer.toHexString(info.flags));
                }
                IO.writeInt(out, info.size + 1);
                int flags = 0;
                if (keyFrame) {
                    flags |= Protocol.FRAME_FLAG_KEY;
                }
                if (config) {
                    flags |= Protocol.FRAME_FLAG_CONFIG;
                }
                IO.writeByte(out, flags);

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer == null) {
                    continue;
                }
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);

                // 逐块写，避免把整个 ByteBuffer 拷成一个大数组
                byte[] chunk = new byte[Math.min(info.size, 64 * 1024)];
                int written = 0;
                while (buffer.hasRemaining()) {
                    int n = Math.min(buffer.remaining(), chunk.length);
                    buffer.get(chunk, 0, n);
                    out.write(chunk, 0, n);
                    written += n;
                }
                out.flush();
                if (produced <= 5) {
                    Ln.i("第 " + produced + " 帧已写出 " + written + " 字节");
                }
                lastBeat = System.currentTimeMillis();

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Ln.i("编码器到达流末尾");
                    break;
                }
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }
    }

    /**
     * 让下一个编码出来的帧是 IDR。
     *
     * 注意：这**不会**凭空造出一帧 —— 虚拟屏没变化时编码器依然不吐帧。
     * 它的用处是客户端重连/重建解码器后，让下一帧一定是能从零解起的关键帧。
     */
    public void requestSyncFrame() {
        MediaCodec c = codec;
        if (c == null || !started) {
            return;
        }
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(params);
        } catch (Throwable t) {
            Ln.d("请求关键帧失败（忽略）：" + t.getMessage());
        }
    }

    public void stop() {
        if (codec == null) {
            return;
        }
        try {
            if (started) {
                codec.stop();
            }
        } catch (IllegalStateException e) {
            Ln.d("停止编码器时状态异常（忽略）：" + e.getMessage());
        } finally {
            codec.release();
            codec = null;
            started = false;
        }
    }

    private MediaFormat createFormat(String mime, int bitrate, int maxFps, int iFrameSeconds) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mime);
        format.setInteger(MediaFormat.KEY_WIDTH, options.getWidth());
        format.setInteger(MediaFormat.KEY_HEIGHT, options.getHeight());
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        // 这个值是必须的，但真实帧率是变的（有画面才编码）
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameSeconds);
        // 首帧能立刻出来；画面不动时也能靠重复上一帧保活
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);
        // 实时优先级
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // 有 1 帧就出 1 帧，别攒
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }
        return format;
    }
}