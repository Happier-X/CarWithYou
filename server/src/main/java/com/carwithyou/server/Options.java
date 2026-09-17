package com.carwithyou.server;

import com.carwithyou.server.util.Ln;

/**
 * 命令行参数解析。车机端通过 `app_process ... <mainClass> key=value ...` 传进来。
 *
 * 用 `key=value` 而不是位置参数，原因是以后加参数不用同步改两边顺序，
 * 车机端也能只传它关心的那几项。
 */
public final class Options {

    /** 归一化到 8 的倍数，编码器对非 8 对齐的尺寸往往直接拒掉。 */
    private static int align8(int value) {
        return (value + 7) & ~7;
    }

    private int width;
    private int height;
    private int density;
    private int maxFps = 60;
    private int bitrate = 8_000_000;
    private int iFrameIntervalSeconds = 1;
    /** true = 镜像已存在的屏；false = 新建一块独立空屏（本项目默认走这条）。 */
    private boolean mirror = false;
    /** 镜像模式的源屏；独立屏模式下忽略。 */
    private int displayIdToMirror = 0;
    /** 独立屏 flags 覆盖；0 = 用 ScreenCapture 的推荐值。调试 ROM 兼容性时用得上。 */
    private int displayFlags = 0;

    private Options() {
    }

    public static Options parse(String... args) {
        Options options = new Options();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                Ln.w("忽略无法解析的参数：" + arg);
                continue;
            }
            String key = arg.substring(0, eq);
            String value = arg.substring(eq + 1);
            try {
                switch (key) {
                    case "width":
                        options.width = align8(Integer.parseInt(value));
                        break;
                    case "height":
                        options.height = align8(Integer.parseInt(value));
                        break;
                    case "density":
                        options.density = Integer.parseInt(value);
                        break;
                    case "maxFps":
                        options.maxFps = Integer.parseInt(value);
                        break;
                    case "bitrate":
                        options.bitrate = Integer.parseInt(value);
                        break;
                    case "iFrameInterval":
                        options.iFrameIntervalSeconds = Integer.parseInt(value);
                        break;
                    case "mirror":
                        options.mirror = Integer.parseInt(value) != 0;
                        break;
                    case "displayId":
                        options.displayIdToMirror = Integer.parseInt(value);
                        break;
                    case "flags":
                        options.displayFlags = Integer.parseInt(value);
                        break;
                    case "mode":
                        // 运行模式（probe / 空=正常），由 Server 自行判断
                        break;
                    default:
                        Ln.w("未知参数：" + key);
                        break;
                }
            } catch (NumberFormatException e) {
                Ln.eShort("参数 " + key + " 的值不是数字：" + value, e);
            }
        }
        return options;
    }

    /** 拿默认屏的尺寸/密度补齐没传的项。 */
    public void fillFromDisplay(int displayWidth, int displayHeight, int displayDensity) {
        if (width <= 0) {
            width = align8(displayWidth);
        }
        if (height <= 0) {
            height = align8(displayHeight);
        }
        if (density <= 0) {
            density = displayDensity;
        }
        if (maxFps <= 0) {
            maxFps = 60;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDensity() {
        return density;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public int getBitrate() {
        return bitrate;
    }

    public int getIFrameIntervalSeconds() {
        return iFrameIntervalSeconds;
    }

    public boolean isMirror() {
        return mirror;
    }

    public int getDisplayIdToMirror() {
        return displayIdToMirror;
    }

    public int getDisplayFlags() {
        return displayFlags;
    }

    @Override
    public String toString() {
        return "Options{" + width + "x" + height + " @" + density + "dpi"
                + ", fps=" + maxFps + ", bitrate=" + bitrate
                + ", iFrame=" + iFrameIntervalSeconds + "s"
                + ", mirror=" + mirror + ", srcDisplay=" + displayIdToMirror
                + ", flags=" + (displayFlags == 0 ? "默认" : ("0x" + Integer.toHexString(displayFlags))) + "}";
    }
}