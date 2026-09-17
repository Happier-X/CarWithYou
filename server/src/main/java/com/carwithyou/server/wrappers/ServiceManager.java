package com.carwithyou.server.wrappers;

import android.os.IBinder;
import android.os.IInterface;

import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * 系统 service 的获取。所有要用到的服务在这里一次性初始化，后面直接取单例。
 *
 * service 绑定方式：`ServiceManager.getService("window")` 返回 IBinder，
 * 再用 `<type>.Stub.asInterface(binder)` 转成 IInterface 才能调它的方法。
 *
 * 现阶段只需要「显示管理」「输入注入」和「旋转/剪切板」；其余按需再加。
 */
public final class ServiceManager {

    private static Method getServiceMethod;

    private ServiceManager() {
    }

    public static void init() {
        for (int i = 0; i < 5; i++) {
            try {
                switch (i) {
                    case 0:
                        getServiceMethod = Class.forName("android.os.ServiceManager")
                                .getDeclaredMethod("getService", String.class);
                        break;
                    case 1:
                        DisplayManager.init(getDisplayManagerGlobal());
                        break;
                    case 2:
                        InputManager.init(getInputManager());
                        break;
                    case 3:
                        // window 服务：旋转用
                        WindowManager.init((IBinder) getServiceMethod.invoke(null, "window"));
                        break;
                    case 4:
                        // clipboard 服务：剪切板同步用
                        ClipboardManager.init((IBinder) getServiceMethod.invoke(null, "clipboard"));
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                // 单个服务失败不致命，逐个继续 —— 后面的能力会自己降级。
                Ln.eShort("ServiceManager 初始化第 " + i + " 步失败", t);
            }
        }
        Ln.i("系统服务初始化完成，显示=" + DisplayManager.isReady()
                + " 输入=" + InputManager.isReady()
                + " 旋转=" + WindowManager.isReady()
                + " 剪切板=" + ClipboardManager.isReady());
    }

    private static Object getDisplayManagerGlobal() throws ReflectiveOperationException {
        Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
        return clazz.getDeclaredMethod("getInstance").invoke(null);
    }

    private static Object getInputManager() throws ReflectiveOperationException, ClassNotFoundException {
        Class<?> clazz;
        try {
            // Android 12 起改名成 InputManagerGlobal
            clazz = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException e) {
            // 老版本在 android.hardware.input.InputManager 里
            clazz = android.hardware.input.InputManager.class;
        }
        return clazz.getDeclaredMethod("getInstance").invoke(null);
    }
}