package com.carwithyou.server.wrappers;

import android.view.InputEvent;

import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * 输入事件注入。
 *
 * 这是 ADB 方案相比 MediaProjection + 无障碍的最大优势：
 * shell 身份可以直接 `injectInputEvent`，不需要任何无障碍服务，也不会被杀。
 */
public final class InputManager {

    /** 异步注入，不阻塞我们自己的线程。 */
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private static Object manager;
    private static Class<?> managerClass;
    private static Method injectInputEventMethod;
    private static Method setDisplayIdMethod;

    private InputManager() {
    }

    public static void init(Object inputManagerGlobal) {
        manager = inputManagerGlobal;
        managerClass = inputManagerGlobal == null ? null : inputManagerGlobal.getClass();
    }

    public static boolean isReady() {
        return manager != null;
    }

    /** 把事件定向到某块屏；不调用则默认进主屏。 */
    public static void setDisplayId(InputEvent event, int displayId) throws ReflectiveOperationException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        setDisplayIdMethod.invoke(event, displayId);
    }

    public static void injectInputEvent(InputEvent event, int mode) throws ReflectiveOperationException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        injectInputEventMethod.invoke(manager, event, mode);
    }

    /**
     * 注入一个事件到指定屏。
     *
     * 主屏不要调 setDisplayId —— 默认就是主屏，显式设 0 反而在部分机型上被忽略。
     */
    public static void inject(InputEvent event, int targetDisplayId) {
        try {
            if (targetDisplayId != android.view.Display.DEFAULT_DISPLAY) {
                setDisplayId(event, targetDisplayId);
            }
            injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            Ln.eShort("注入输入事件失败", e);
        }
    }
}