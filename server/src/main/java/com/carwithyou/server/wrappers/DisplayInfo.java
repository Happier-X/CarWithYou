package com.carwithyou.server.wrappers;

/**
 * 一块屏的静态信息快照。
 *
 * 注意 width/height 已经把旋转算进去了（DisplayManagerGlobal 的 logical 尺寸语义）。
 */
public final class DisplayInfo {

    /** 该屏允许跑 DRM/受保护内容；没有这个 flag 时镜像可能受限。 */
    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 << 8;

    public final int displayId;
    public final int width;
    public final int height;
    public final int density;
    public final int rotation;
    public final int layerStack;
    public final int flags;

    public DisplayInfo(int displayId, int width, int height, int density, int rotation, int layerStack, int flags) {
        this.displayId = displayId;
        this.width = width;
        this.height = height;
        this.density = density;
        this.rotation = rotation;
        this.layerStack = layerStack;
        this.flags = flags;
    }

    public boolean supportsProtectedBuffers() {
        return (flags & FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0;
    }

    @Override
    public String toString() {
        return "DisplayInfo{id=" + displayId + ", " + width + "x" + height
                + ", dpi=" + density + ", rotation=" + rotation
                + ", layerStack=" + layerStack + ", flags=0x" + Integer.toHexString(flags) + "}";
    }
}