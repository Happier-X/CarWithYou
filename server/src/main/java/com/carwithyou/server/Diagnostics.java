package com.carwithyou.server;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import com.carwithyou.server.util.Ln;
import com.carwithyou.server.wrappers.DisplayInfo;
import com.carwithyou.server.wrappers.DisplayManager;
import com.carwithyou.server.wrappers.SurfaceControl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 建屏能力探针。
 *
 * 各 ROM 对「怎么建虚拟屏」这条隐藏 API 的裁剪差异很大，与其猜，
 * 不如在真机上把候选路径挨个试一遍，把结果打出来。
 *
 * 用法：`app_process ... com.carwithyou.server.Server mode=probe`
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    public static void run() {
        Ln.i("=== 设备信息 ===");
        Ln.i("MANUFACTURER=" + android.os.Build.MANUFACTURER
                + " BRAND=" + android.os.Build.BRAND
                + " MODEL=" + android.os.Build.MODEL);
        Ln.i("Android " + android.os.Build.VERSION.RELEASE
                + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");

        dumpCreateVirtualDisplayMethods("android.hardware.display.DisplayManager");
        dumpCreateVirtualDisplayMethods("android.hardware.display.DisplayManagerGlobal");
        dumpSurfaceControlCreateDisplay();

        Ln.i("=== 现有屏幕 ===");
        int[] ids = DisplayManager.getDisplayIds();
        StringBuilder builder = new StringBuilder("displayIds=");
        for (int id : ids) {
            builder.append(id).append(' ');
        }
        Ln.i(builder.toString());
        for (int id : ids) {
            DisplayInfo info = DisplayManager.getDisplayInfo(id);
            Ln.i("  display " + id + " -> " + info);
        }

        Ln.i("=== 探针结束 ===");
    }

    private static void dumpCreateVirtualDisplayMethods(String className) {
        Ln.i("--- " + className + " ---");
        try {
            Class<?> clazz = Class.forName(className);
            boolean found = false;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().toLowerCase(java.util.Locale.ROOT).contains("virtualdisplay")
                        && !method.getName().equals("createVirtualDisplay")) {
                    continue;
                }
                found = true;
                Ln.i("  " + describe(method));
            }
            if (!found) {
                Ln.i("  (没有任何 create*VirtualDisplay 方法)");
            }
        } catch (Throwable t) {
            Ln.eShort("  无法枚举 " + className, t);
        }
    }

    private static void dumpSurfaceControlCreateDisplay() {
        Ln.i("--- android.view.SurfaceControl ---");
        try {
            Class<?> clazz = Class.forName("android.view.SurfaceControl");
            for (String name : new String[]{"createDisplay", "destroyDisplay", "getInternalDisplayToken", "getBuiltInDisplay", "getPhysicalDisplayIds", "getPhysicalDisplayToken", "setDisplaySurface", "openTransaction", "closeTransaction", "setDisplayProjection", "setDisplayLayerStack"}) {
                try {
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (method.getName().equals(name)) {
                            Ln.i("  " + describe(method));
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个方法失败不影响其它
                }
            }
            Ln.i("  createDisplay 反射可用性=" + canCreateDisplay());
        } catch (Throwable t) {
            Ln.eShort("  无法枚举 SurfaceControl", t);
        }
    }

    private static boolean canCreateDisplay() {
        try {
            // 只是尝试建立，立刻销毁，验证这条路径通不通
            java.lang.reflect.Method method = Class.forName("android.view.SurfaceControl")
                    .getMethod("createDisplay", String.class, boolean.class);
            return method != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String describe(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(Modifier.isStatic(method.getModifiers()) ? "static " : "       ")
                .append(method.getReturnType().getSimpleName())
                .append(' ')
                .append(method.getName())
                .append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(params[i].getSimpleName());
        }
        builder.append(')');
        return builder.toString();
    }

    /** 让编译器不抱怨未使用的 import。 */
    static void refs(VirtualDisplay display, Surface surface) {
        if (display == null || surface == null) {
            Ln.d("refs");
        }
    }
}
