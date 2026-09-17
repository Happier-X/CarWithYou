package com.carwithyou.server.wrappers;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;

import com.carwithyou.server.AndroidVersions;
import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * {@code com.android.server.display.DisplayControl}（services.jar 里的隐藏服务类）包装。
 *
 * 从 Android 14 起，{@code SurfaceControl.getPhysicalDisplayIds / getPhysicalDisplayToken}
 * 移到了这个类。它不在 boot classpath，普通 Class.forName 找不到，只能拿
 * {@code ClassLoaderFactory} 显式加载 system_server 的 classpath（SYSTEMSERVERCLASSPATH）
 * 再 loadClass，顺便还要把 native 库 android_servers 加载上。
 *
 * 这套是 scrcpy 的成熟做法，这里只是把方法解析做成 lazy，避免在拿不到时拖崩进程。
 */
@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
@TargetApi(AndroidVersions.API_34_ANDROID_14)
public final class DisplayControl {

    private static Class<?> clazz;

    private DisplayControl() {
    }

    private static Class<?> resolveClass() {
        if (clazz != null) {
            return clazz;
        }
        clazz = loadClass();
        return clazz;
    }

    @SuppressLint("DiscouragedPrivateApi")
    private static Class<?> loadClass() {
        try {
            Class<?> factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createClassLoaderMethod = factoryClass.getDeclaredMethod(
                    "createClassLoader", String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);

            String systemServerClasspath = android.system.Os.getenv("SYSTEMSERVERCLASSPATH");
            ClassLoader loader = (ClassLoader) createClassLoaderMethod.invoke(
                    null, systemServerClasspath, null, null,
                    DisplayControl.class.getClassLoader(), 0, true, null);

            Class<?> displayControlClass = loader.loadClass("com.android.server.display.DisplayControl");

            Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadMethod.setAccessible(true);
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");

            Ln.d("DisplayControl 已从 services.jar 加载");
            return displayControlClass;
        } catch (Throwable t) {
            // 拿不到就返回 null，调用侧（surfaceControl 那层逐步降级）会自己兜。
            Ln.eShort("无法加载 DisplayControl 类", t);
            return null;
        }
    }

    /** SurfaceControl 借它解析方法；返回 null 表示拿不到。 */
    public static Method getMethod(String name, Class<?>... parameterTypes) throws ReflectiveOperationException {
        Class<?> klass = resolveClass();
        if (klass == null) {
            throw new NoSuchMethodException("DisplayControl 未加载");
        }
        return klass.getMethod(name, parameterTypes);
    }
}
