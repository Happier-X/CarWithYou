package com.carwithyou.server.wrappers;

import android.os.IBinder;

import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * window 服务的最小包装，只为旋转虚拟屏用。
 *
 * {@code IWindowManager} 是 @hide 的 AIDL 接口，不能直接 import，
 * 所以按照 scrcpy/参考实现的老办法：反射调 {@code IWindowManager$Stub.asInterface(binder)}。
 *
 * 用 {@code freezeRotation/thawRotation}：这是 shell 身份能直接调的公开接口，
 * 比改 Settings 可靠，也比 {@code SurfaceControl.setDisplayRotation} 兼容性好。
 */
public final class WindowManager {

    /** 传给 AIDL 的调用方包名。用 shell 的进程名，与 app_process 身份一致。 */
    private static final String CALLER = "com.android.shell";

    private static Object manager;   // IWindowManager$Stub$Proxy
    private static Method freezeRotationMethod;
    private static Method thawRotationMethod;
    private static Method isRotationFrozenMethod;
    private static Method getRotationMethod;
    private static boolean ready;

    private WindowManager() {}

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    public static void init(IBinder binder) {
        if (binder == null) {
            Ln.w("window 服务拿不到，旋转功能不可用");
            return;
        }
        try {
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            manager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (manager == null) {
                Ln.w("IWindowManager.asInterface 返回 null");
                return;
            }
            Ln.i("window 代理类：" + manager.getClass().getName());

            // 签名实情（从设备 dex 核实，Android 15）：
            //   freezeDisplayRotation(int displayId, int rotation, String caller)
            //   thawDisplayRotation(int displayId, String caller)
            // 旧名 freezeRotation/thawRotation 已废弃，部分 ROM 上不存在，所以两个候选都试。
            freezeRotationMethod = firstNonNull(
                    findMethod("freezeDisplayRotation", int.class, int.class, String.class),
                    findMethod("freezeDisplayRotation", int.class),
                    findMethod("freezeRotation", int.class),
                    findMethod("freezeRotation", int.class, int.class));
            thawRotationMethod = firstNonNull(
                    findMethod("thawDisplayRotation", int.class, String.class),
                    findMethod("thawDisplayRotation"),
                    findMethod("thawRotation"));
            isRotationFrozenMethod = firstNonNull(
                    findMethod("isRotationFrozen"),
                    findMethod("isDisplayRotationFrozen"));
            getRotationMethod = findMethod("getRotation");

            ready = freezeRotationMethod != null || thawRotationMethod != null;
            if (ready) {
                Ln.i("WindowManager 已就绪（旋转可用）：freeze=" + freezeRotationMethod
                        + " thaw=" + thawRotationMethod);
            } else {
                // 把实际存在的方法名打出来，方便定位是名字不对还是被隐藏 API 拦了
                StringBuilder names = new StringBuilder();
                for (Method m : manager.getClass().getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("rotat")) {
                        names.append(n).append('/').append(m.getParameterCount()).append(' ');
                    }
                }
                Ln.w("WindowManager 里没有可用的旋转方法，旋转功能不可用。"
                        + "代理里含 rotat 的方法：[" + names + "]");
            }
        } catch (Throwable t) {
            Ln.e("初始化 WindowManager 失败", t);
        }
    }

    public static boolean isReady() {
        return ready;
    }

    /**
     * 找方法。
     *
     * 优先 `getMethod`（公开方法）；找不到时回落 `getDeclaredMethod` + `setAccessible`。
     *
     * 为什么要这个回落：Android 的隐藏 API 拦截会把 BLOCKED 成员从
     * `getMethod`/`getMethods` 结果里**直接过滤掉**（返回 NoSuchMethodException），
     * 而 `IWindowManager` 的旋转接口正是 BLOCKED。app_process（shell 身份）不在
     * 任何 App 的 hiddenapi 豁免名单里，所以必须绕一下。
     */
    private static Method findMethod(String name, Class<?>... params) {
        try {
            return manager.getClass().getMethod(name, params);
        } catch (NoSuchMethodException e) {
            // 继续试 declared
        }
        try {
            Method m = manager.getClass().getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /** 找同名无参方法（用于兼容只有老签名的 ROM）。 */
    private static Method findAny(String name) {
        // 先按名字 + 参数个数扫一遍 declared，避免隐藏 API 过滤
        for (Method m : manager.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // 拿不到就跳过，下面还有 getMethods 兜底
                }
                return m;
            }
        }
        for (Method m : manager.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
        }
        return null;
    }

    /**
     * 冻结旋转到指定 rotation（0/1/2/3）。
     *
     * @param displayId 目标屏；传 -1 用默认屏
     */
    public static boolean freezeRotation(int rotation) {
        return freezeRotation(android.view.Display.DEFAULT_DISPLAY, rotation);
    }

    /**
     * 冻结指定屏的旋转。
     *
     * 各版本签名不同，按参数个数把能凑齐的实参填进去。
     */
    public static boolean freezeRotation(int displayId, int rotation) {
        Method m = freezeRotationMethod;
        if (m == null) m = findAny("freezeDisplayRotation");
        if (m == null) m = findAny("freezeRotation");
        if (m == null) {
            Ln.w("没有 freezeRotation 方法");
            return false;
        }
        try {
            Class<?>[] p = m.getParameterTypes();
            // findAny 可能返回任意重载，这里按参数个数才敢填实参
            switch (p.length) {
                case 0:
                    m.invoke(manager);
                    break;
                case 1:
                    m.invoke(manager, rotation);
                    break;
                case 2:
                    // (displayId, rotation) 或 (rotation, caller)
                    if (p[1] == String.class) m.invoke(manager, rotation, CALLER);
                    else m.invoke(manager, displayId, rotation);
                    break;
                default:
                    // 真实签名 (int displayId, int rotation, String caller)
                    m.invoke(manager, displayId, rotation, CALLER);
                    break;
            }
            return true;
        } catch (Throwable t) {
            Ln.e("freezeRotation(display=" + displayId + ", rot=" + rotation + ") 失败", t);
            return false;
        }
    }

    /** 解除旋转冻结，交还给重力感应。 */
    public static boolean thawRotation() {
        return thawRotation(android.view.Display.DEFAULT_DISPLAY);
    }

    /** 解除指定屏的旋转冻结。 */
    public static boolean thawRotation(int displayId) {
        Method m = thawRotationMethod;
        if (m == null) m = findAny("thawDisplayRotation");
        if (m == null) m = findAny("thawRotation");
        if (m == null) {
            Ln.w("没有 thawRotation 方法");
            return false;
        }
        try {
            Class<?>[] p = m.getParameterTypes();
            switch (p.length) {
                case 0:
                    m.invoke(manager);
                    break;
                case 1:
                    m.invoke(manager, p[0] == String.class ? CALLER : displayId);
                    break;
                default:
                    // (displayId, caller)
                    m.invoke(manager, displayId, CALLER);
                    break;
            }
            return true;
        } catch (Throwable t) {
            // 本来就没冻结时会抛 IllegalStateException，不算错误
            Ln.d("thawRotation 失败（可能本来就没冻结）：" + t.getMessage());
            return false;
        }
    }

    /** 当前是否处于「旋转被冻结」状态。判断失败时返回 false，让调用方按「会跟随重力」处理。 */
    public static boolean isRotationFrozen() {
        Method m = isRotationFrozenMethod;
        if (m == null) m = findAny("isRotationFrozen");
        if (m == null) return false;
        try {
            Object r = m.invoke(manager);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主屏当前 rotation。拿不到返回 -1。 */
    public static int getRotation() {
        Method m = getRotationMethod;
        if (m == null) m = findAny("getRotation");
        if (m == null) return -1;
        try {
            Object r = m.invoke(manager);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
