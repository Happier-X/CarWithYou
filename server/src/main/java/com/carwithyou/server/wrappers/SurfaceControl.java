package com.carwithyou.server.wrappers;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import com.carwithyou.server.AndroidVersions;
import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * {@code android.view.SurfaceControl} 的隐藏 API 包装。
 *
 * 这是整个 server 里最吃版本兼容的一层，坑集中在两处：
 *  1. {@code getBuiltInDisplay} 在 Android 10 改名成 {@code getInternalDisplayToken}。
 *  2. {@code getPhysicalDisplayIds / getPhysicalDisplayToken} 在 Android 14 起从
 *     SurfaceControl **移到了** {@code com.android.server.display.DisplayControl}，
 *     而后者在 services.jar 里，必须自己造一个能加载它的 ClassLoader。
 *     Android 13 及以前直接从 SurfaceControl 取。
 * 这两条都是逐步降级的，任何一步失败都不能让进程崩。
 */
@SuppressLint("PrivateApi")
public final class SurfaceControl {

    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    private static final Class<?> CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException e) {
            Ln.e("找不到 android.view.SurfaceControl", e);
        }
        CLASS = clazz;
    }

    private static Method getBuiltInDisplayMethod;
    private static Method setDisplayPowerModeMethod;
    private static Method getPhysicalDisplayTokenMethod;
    private static Method getPhysicalDisplayIdsMethod;

    private SurfaceControl() {
    }

    public static boolean isAvailable() {
        return CLASS != null;
    }

    public static void openTransaction() throws ReflectiveOperationException {
        CLASS.getMethod("openTransaction").invoke(null);
    }

    public static void closeTransaction() throws ReflectiveOperationException {
        CLASS.getMethod("closeTransaction").invoke(null);
    }

    public static void setDisplayProjection(IBinder displayToken, int orientation, Rect layerStackRect, Rect displayRect)
            throws ReflectiveOperationException {
        CLASS.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                .invoke(null, displayToken, orientation, layerStackRect, displayRect);
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) throws ReflectiveOperationException {
        CLASS.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, displayToken, layerStack);
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) throws ReflectiveOperationException {
        CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, displayToken, surface);
    }

    public static IBinder createDisplay(String name, boolean secure) throws ReflectiveOperationException {
        return (IBinder) CLASS.getMethod("createDisplay", String.class, boolean.class).invoke(null, name, secure);
    }

    public static void destroyDisplay(IBinder displayToken) throws ReflectiveOperationException {
        CLASS.getMethod("destroyDisplay", IBinder.class).invoke(null, displayToken);
    }

    // ------------------------------------------------------------ 物理屏

    private static Method getGetBuiltInDisplayMethod() throws NoSuchMethodException {
        if (getBuiltInDisplayMethod == null) {
            if (android.os.Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                getBuiltInDisplayMethod = CLASS.getMethod("getBuiltInDisplay", int.class);
            } else {
                getBuiltInDisplayMethod = CLASS.getMethod("getInternalDisplayToken");
            }
        }
        return getBuiltInDisplayMethod;
    }

    public static IBinder getBuiltInDisplay() {
        try {
            Method method = getGetBuiltInDisplayMethod();
            if (android.os.Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                return (IBinder) method.invoke(null, 0);
            }
            return (IBinder) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("取内置屏 token 失败", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayTokenMethod() throws ReflectiveOperationException {
        if (getPhysicalDisplayTokenMethod == null) {
            if (android.os.Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                getPhysicalDisplayTokenMethod = DisplayControl.getMethod("getPhysicalDisplayToken", long.class);
            } else {
                getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
            }
        }
        return getPhysicalDisplayTokenMethod;
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            return (IBinder) getGetPhysicalDisplayTokenMethod().invoke(null, physicalDisplayId);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("取物理屏 token 失败", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayIdsMethod() throws ReflectiveOperationException {
        if (getPhysicalDisplayIdsMethod == null) {
            if (android.os.Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                getPhysicalDisplayIdsMethod = DisplayControl.getMethod("getPhysicalDisplayIds");
            } else {
                getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
            }
        }
        return getPhysicalDisplayIdsMethod;
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            return (long[]) getGetPhysicalDisplayIdsMethod().invoke(null);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("取物理屏 id 列表失败", e);
            return null;
        }
    }

    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) {
        try {
            if (setDisplayPowerModeMethod == null) {
                setDisplayPowerModeMethod = CLASS.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            }
            setDisplayPowerModeMethod.invoke(null, displayToken, mode);
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.eShort("设置屏幕电源模式失败", e);
            return false;
        }
    }
}
