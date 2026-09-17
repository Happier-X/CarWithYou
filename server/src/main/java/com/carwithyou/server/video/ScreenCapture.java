package com.carwithyou.server.video;

import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import com.carwithyou.server.Options;
import com.carwithyou.server.util.Ln;
import com.carwithyou.server.wrappers.DisplayInfo;
import com.carwithyou.server.wrappers.DisplayManager;
import com.carwithyou.server.wrappers.SurfaceControl;

/**
 * 建一块虚拟屏，把它的画面接到编码器的输入 Surface 上。
 *
 * 两条路径，真机上实测来的结论（见 Diagnostics 探针输出）：
 *
 *  A. **新建独立空屏**（首选，也是本项目的产品目标）
 *     `DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags)`（实例方法）。
 *     它建出来的是一块**在 DisplayManager 里注册过的**真屏：有 displayId，
 *     可以用 `am start --display <id>` / `ActivityOptions.setLaunchDisplayId`
 *     把应用放进去，也可以把输入注入进去。车机看到的是「手机的另一块桌面」。
 *     实测 Android 12 上这个方法已存在，Android 14 起转为公开 API。
 *
 *  B. **镜像已有屏**（兜底 / 显式指定）
 *     注意：**Android 12 上没有** `DisplayManager.createVirtualDisplay(name,w,h,mirrorId,surface)`
 *     这个静态隐藏方法（探针确认），所以镜像不能靠它。改用更底层的做法：
 *     `SurfaceControl.createDisplay` 建一个裸显示，把它的 layerStack 设成源屏的
 *     layerStack，于是它渲染的是同一份图层内容，也就是镜像。
 *     代价：这种屏没有 displayId，**不能承载应用、也不能作为输入目标**，纯镜像用。
 *
 * flags 里不能带 SECURE：shell 身份建 secure 屏会被 SurfaceFlinger 直接拒掉。
 */
public final class ScreenCapture {

    private final Options options;

    private DisplayInfo sourceInfo;
    private int displayId = -1;
    private boolean mirrored;

    /** 独立屏路径持有它，用于 release。 */
    private android.hardware.display.VirtualDisplay virtualDisplay;
    /** 镜像路径持有裸显示 token，用于 destroyDisplay。 */
    private IBinder mirrorToken;

    public ScreenCapture(Options options) {
        this.options = options;
    }

    public int getDisplayId() {
        return displayId;
    }

    public DisplayInfo getSourceInfo() {
        return sourceInfo;
    }

    public boolean isMirrored() {
        return mirrored;
    }

    /**
     * 建屏。调用方需保证此时编码器已 prepare() 出 Surface 且**尚未** start()。
     */
    public void init(Surface encoderSurface, DisplayInfo defaultDisplayInfo) throws Exception {
        this.sourceInfo = defaultDisplayInfo;
        options.fillFromDisplay(defaultDisplayInfo.width, defaultDisplayInfo.height, defaultDisplayInfo.density);
        Ln.i("采集参数：" + options);

        if (options.isMirror()) {
            createMirrorDisplay(options.getDisplayIdToMirror(), encoderSurface);
            return;
        }

        try {
            createNewDisplay(encoderSurface);
        } catch (Throwable t) {
            Ln.eShort("新建独立屏失败，回落到镜像 displayId=" + options.getDisplayIdToMirror(), t);
            createMirrorDisplay(options.getDisplayIdToMirror(), encoderSurface);
        }
    }

    // ------------------------------------------------------------ A：独立屏

    private void createNewDisplay(Surface surface) throws Exception {
        int flags = options.getDisplayFlags();
        if (flags != 0) {
            virtualDisplay = DisplayManager.createNewDisplay(
                    "carwithyou", options.getWidth(), options.getHeight(), options.getDensity(), surface, flags);
            Ln.i("使用指定 flags=0x" + Integer.toHexString(flags) + " 建屏");
        } else {
            virtualDisplay = DisplayManager.createNewDisplay(
                    "carwithyou", options.getWidth(), options.getHeight(), options.getDensity(), surface);
        }
        displayId = virtualDisplay.getDisplay().getDisplayId();
        mirrored = false;
        Ln.i("已新建独立虚拟屏 displayId=" + displayId
                + " " + options.getWidth() + "x" + options.getHeight() + " @" + options.getDensity() + "dpi");
    }

    // ------------------------------------------------------------ B：镜像

    private void createMirrorDisplay(int mirrorDisplayId, Surface surface) throws Exception {
        DisplayInfo source = DisplayManager.getDisplayInfo(mirrorDisplayId);
        if (source == null) {
            throw new IllegalStateException("取不到源屏信息，无法镜像 displayId=" + mirrorDisplayId);
        }

        // 先试试某些 Android 13 ROM 上存在的静态镜像方法；没有就走 SurfaceControl
        if (tryDisplayManagerMirror(mirrorDisplayId, surface)) {
            return;
        }

        IBinder token = SurfaceControl.createDisplay("carwithyou", false);
        if (token == null) {
            throw new IllegalStateException("SurfaceControl.createDisplay 返回 null");
        }
        mirrorToken = token;

        // 源屏图层栈的自然尺寸（未旋转），用于建立投影
        int stackWidth = source.rotation % 2 == 0 ? source.width : source.height;
        int stackHeight = source.rotation % 2 == 0 ? source.height : source.width;

        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(token, surface);
            SurfaceControl.setDisplayProjection(
                    token,
                    source.rotation,
                    new Rect(0, 0, stackWidth, stackHeight),
                    new Rect(0, 0, options.getWidth(), options.getHeight()));
            // 关键一步：把裸显示的图层栈指向源屏的图层栈，于是渲染同一份内容 = 镜像
            SurfaceControl.setDisplayLayerStack(token, source.layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }

        displayId = -1;
        mirrored = true;
        Ln.i("已用 SurfaceControl 镜像 displayId=" + mirrorDisplayId
                + "（layerStack=" + source.layerStack + " rotation=" + source.rotation + "）"
                + " → " + options.getWidth() + "x" + options.getHeight());
    }

    /** 部分 ROM（Android 13）有静态镜像方法，有就用它，因为它会注册 displayId。 */
    private boolean tryDisplayManagerMirror(int mirrorDisplayId, Surface surface) {
        try {
            virtualDisplay = DisplayManager.createVirtualDisplay(
                    "carwithyou", options.getWidth(), options.getHeight(), mirrorDisplayId, surface);
            displayId = virtualDisplay.getDisplay().getDisplayId();
            mirrored = true;
            Ln.i("已用 DisplayManager 静态方法镜像 displayId=" + mirrorDisplayId
                    + " → 虚拟屏 displayId=" + displayId);
            return true;
        } catch (NoSuchMethodException e) {
            Ln.d("本 ROM 没有 DisplayManager 静态镜像方法，改用 SurfaceControl");
            return false;
        } catch (Throwable t) {
            Ln.eShort("DisplayManager 静态镜像失败，改用 SurfaceControl", t);
            return false;
        }
    }

    // ------------------------------------------------------------ 释放

    public void release() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                Ln.d("释放 VirtualDisplay 失败（忽略）：" + t.getMessage());
            }
            virtualDisplay = null;
        }
        if (mirrorToken != null) {
            try {
                SurfaceControl.destroyDisplay(mirrorToken);
            } catch (Throwable t) {
                Ln.d("销毁镜像显示失败（忽略）：" + t.getMessage());
            }
            mirrorToken = null;
        }
        // surface 归编码器持有，这里不能 release，否则编码器会拿到野句柄
    }

    /** 供日志用的一行描述。 */
    public String describe() {
        return "displayId=" + displayId
                + " size=" + options.getWidth() + "x" + options.getHeight()
                + " dpi=" + options.getDensity()
                + (mirrored ? " (镜像)" : " (独立屏)");
    }
}
