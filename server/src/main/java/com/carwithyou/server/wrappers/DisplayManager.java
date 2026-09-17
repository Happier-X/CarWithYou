package com.carwithyou.server.wrappers;

import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;

import com.carwithyou.server.FakeContext;
import com.carwithyou.server.util.Command;
import com.carwithyou.server.util.Ln;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显示信息与虚拟屏创建。
 *
 * 两条创建路径（这是本文件的核心）：
 *  1. `createVirtualDisplay` —— 反射 {@code android.hardware.display.DisplayManager}
 *     的隐藏静态方法，直接拿一个「镜像某块屏」的 VirtualDisplay。**优先走这条**。
 *  2. `createNewDisplay` + SurfaceControl —— 建一块真正独立的空屏。
 *
 * 注意：DisplayManagerGlobal.getDisplayInfo 在部分机型（如三星）会走
 * ActivityThread.getConfiguration()，所以进程里必须先跑过 Workarounds，
 * 否则这里会 NPE —— 这是 scrcpy 踩出来的坑。
 */
public final class DisplayManager {

    private static Object manager;
    private static Method getDisplayInfoMethod;
    private static Method getDisplayIdsMethod;
    private static Method createVirtualDisplayMethod;

    private DisplayManager() {
    }

    public static void init(Object displayManagerGlobal) {
        manager = displayManagerGlobal;
    }

    public static boolean isReady() {
        return manager != null;
    }

    // ------------------------------------------------------------ 显示信息

    public static int[] getDisplayIds() {
        try {
            if (getDisplayIdsMethod == null) {
                getDisplayIdsMethod = manager.getClass().getMethod("getDisplayIds");
            }
            return (int[]) getDisplayIdsMethod.invoke(manager);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("getDisplayIds 失败", e);
            return new int[0];
        }
    }

    public static DisplayInfo getDisplayInfo(int displayId) {
        try {
            if (getDisplayInfoMethod == null) {
                getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
            }
            Object info = getDisplayInfoMethod.invoke(manager, displayId);
            if (info == null) {
                return fromDumpsys(displayId);
            }
            Class<?> cls = info.getClass();
            // 宽高已经把旋转算进去了
            int width = cls.getDeclaredField("logicalWidth").getInt(info);
            int height = cls.getDeclaredField("logicalHeight").getInt(info);
            int rotation = cls.getDeclaredField("rotation").getInt(info);
            int density = cls.getDeclaredField("logicalDensityDpi").getInt(info);
            int layerStack = cls.getDeclaredField("layerStack").getInt(info);
            int flags = cls.getDeclaredField("flags").getInt(info);
            return new DisplayInfo(displayId, width, height, density, rotation, layerStack, flags);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("getDisplayInfo(" + displayId + ") 反射失败，回落 dumpsys", e);
            return fromDumpsys(displayId);
        }
    }

    private static DisplayInfo fromDumpsys(int displayId) {
        try {
            String output = Command.execReadOutput("dumpsys", "display");
            Pattern regex = Pattern.compile(
                    "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId
                            + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                            + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                    Pattern.MULTILINE);
            Matcher m = regex.matcher(output);
            if (!m.find()) {
                return null;
            }
            return new DisplayInfo(
                    displayId,
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(6)),
                    parseDisplayFlags(m.group(1)));
        } catch (Exception e) {
            Ln.eShort("dumpsys display 兜底也失败", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String text) {
        if (text == null) {
            return 0;
        }
        int flags = 0;
        Matcher m = Pattern.compile("FLAG_[A-Z_]+").matcher(text);
        while (m.find()) {
            try {
                Field field = Display.class.getDeclaredField(m.group());
                flags |= field.getInt(null);
            } catch (ReflectiveOperationException ignored) {
                // dumpsys 里有些 flag 是 @TestApi，取不到是正常的
            }
        }
        return flags;
    }

    // ------------------------------------------------------------ 虚拟屏

    /**
     * 镜像某块屏，产出可直接喂给编码器的 surface。隐藏 API，走反射。
     */
    public static VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface)
            throws ReflectiveOperationException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        }
        return (VirtualDisplay) createVirtualDisplayMethod.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    /**
     * 建一块真正独立的空屏（不镜像任何屏），应用可以被 `am start --display` 放进来。
     *
     * 走的是 Android 14 才公开的实例方法，所以需要一个 Context；server 里用 FakeContext 造一个。
     * 旧系统上会 NoSuchMethodError，调用方自己降级。
     */
    public static VirtualDisplay createNewDisplay(String name, int width, int height, int density, Surface surface)
            throws ReflectiveOperationException {
        // SUPPORTS_TOUCH 是隐藏 API，必须写字面量 —— 但别写错位：
        //   1<<5 = CAN_SHOW_WITH_INSECURE_KEYGUARD（带上它建公开屏会被拒：
        //          "Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE"）
        //   1<<6 = VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH  ← 这个才是要的
        int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 6);
        return createNewDisplay(name, width, height, density, surface, flags);
    }

    public static VirtualDisplay createNewDisplay(String name, int width, int height, int density, Surface surface,
                                                  int flags)
            throws ReflectiveOperationException {
        Constructor<android.hardware.display.DisplayManager> constructor =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        android.hardware.display.DisplayManager displayManager =
                constructor.newInstance(FakeContext.get());
        return displayManager.createVirtualDisplay(name, width, height, density, surface, flags);
    }

    /** 取某块屏的旋转，拿不到就返回 0。 */
    public static int getRotation(int displayId) {
        DisplayInfo info = getDisplayInfo(displayId);
        return info == null ? 0 : info.rotation;
    }
}