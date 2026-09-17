package com.carwithyou.server;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

import com.carwithyou.server.util.Ln;

/**
 * 伪造一个 Context 给 shell 进程用。
 *
 * 公开 API 的 `DisplayManager.createVirtualDisplay(...)` 是个实例方法，必须要 Context。
 * 我们拿 system context 当底座，然后把包名伪装成 `com.android.shell`
 * —— 这样系统在鉴权时认的就是 shell 身份（本来就该是），
 * 而不是一个不存在的包名。
 */
public final class FakeContext extends ContextWrapper {

    /** shell 的包名，系统鉴权看这个。 */
    public static final String PACKAGE_NAME = "com.android.shell";

    private static final FakeContext INSTANCE = new FakeContext();

    public static FakeContext get() {
        return INSTANCE;
    }

    private FakeContext() {
        super(Workarounds.getSystemContext());
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    @Override
    public AttributionSource getAttributionSource() {
        AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
        builder.setPackageName(PACKAGE_NAME);
        return builder.build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @SuppressLint("SoonBlockedPrivateApi")
    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service == null) {
            return null;
        }
        // DisplayManager / WindowManager 这些 service 内部持有创建它的 Context，
        // 得把它替换成我们自己，否则它会用真实系统 Context 的包名去鉴权。
        if (Context.DISPLAY_SERVICE.equals(name) || Context.WINDOW_SERVICE.equals(name)) {
            try {
                java.lang.reflect.Field field = service.getClass().getDeclaredField("mContext");
                field.setAccessible(true);
                field.set(service, this);
            } catch (ReflectiveOperationException e) {
                Ln.d("替换 " + name + " 的 mContext 失败：" + e.getMessage());
            }
        }
        return service;
    }
}