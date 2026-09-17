package com.carwithyou.server;

import com.carwithyou.server.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 给 shell 进程伪造一个 ActivityThread 环境。
 *
 * 这个进程是 `app_process` 起的，没有 ActivityThread。但很多系统 API（尤其是
 * DisplayManagerGlobal.getDisplayInfo，以及 AudioRecord 那套）会走
 * `ActivityThread.currentActivityThread().getConfiguration()`，没有就 NPE。
 * 所以这里造一个假的 ActivityThread 顶上 —— 这是 scrcpy 踩出来的必要 workaround。
 *
 * 每个改动都很脆，任何一步失败都不能当作错误抛出来，静默降级。
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
public final class Workarounds {

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    static {
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            constructor.setAccessible(true);
            ACTIVITY_THREAD = constructor.newInstance();

            // ActivityThread.sCurrentActivityThread = activityThread
            Field currentField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            currentField.setAccessible(true);
            currentField.set(null, ACTIVITY_THREAD);

            // activityThread.mSystemThread = true
            Field systemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
            systemThreadField.setAccessible(true);
            systemThreadField.setBoolean(ACTIVITY_THREAD, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Workarounds() {
    }

    public static void apply() {
        // 三星等机型 getDisplayInfoLocked 会要非空的 ConfigurationController（仅 12+ 需要）
        if (android.os.Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            fillConfigurationController();
        }
        fillAppInfo();
        fillAppContext();
        Ln.i("ActivityThread workaround 已就位");
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> constructor = appBindDataClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object appBindData = constructor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = FakeContext.PACKAGE_NAME;

            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, applicationInfo);

            Field boundField = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            boundField.set(ACTIVITY_THREAD, appBindData);
        } catch (Throwable t) {
            Ln.d("fillAppInfo 失败（不致命）：" + t.getMessage());
        }
    }

    private static void fillAppContext() {
        try {
            android.app.Application app = android.app.Instrumentation.newApplication(
                    android.app.Application.class, FakeContext.get());

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, app);
        } catch (Throwable t) {
            Ln.d("fillAppContext 失败（不致命）：" + t.getMessage());
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> configClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> constructor = configClass.getDeclaredConstructor(internalClass);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(ACTIVITY_THREAD);

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable t) {
            Ln.d("fillConfigurationController 失败（不致命）：" + t.getMessage());
        }
    }

    static Context getSystemContext() {
        try {
            Method method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(ACTIVITY_THREAD);
        } catch (Throwable t) {
            Ln.d("getSystemContext 失败：" + t.getMessage());
            return null;
        }
    }
}
