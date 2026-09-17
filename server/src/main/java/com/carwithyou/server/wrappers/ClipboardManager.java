package com.carwithyou.server.wrappers;

import android.content.ClipData;
import android.os.IBinder;

import com.carwithyou.server.util.Ln;

import java.lang.reflect.Method;

/**
 * clipboard 服务的最小包装，用于车机↔手机的剪切板同步。
 *
 * {@code IClipboard} 是 @hide 的 AIDL 接口，不能直接 import，
 * 所以反射调 {@code IClipboard$Stub.asInterface(binder)}。
 * 走它而不是 {@code Context.getSystemService}：app_process 下没有正常 Context，
 * 而且 shell 身份直接调 IClipboard 不需要额外权限。
 *
 * 注意：{@code getPrimaryClip}/{@code setPrimaryClip} 的签名在各 Android 版本上变过很多次
 * （attributionSource / userId / callingPackage 的个数不同），所以这里运行时逐个试。
 */
public final class ClipboardManager {

    private static Object service;   // IClipboard$Stub$Proxy

    private static Method getPrimaryClipMethod;   // 签名不确定，运行时匹配
    private static Method setPrimaryClipMethod;

    private ClipboardManager() {}

    public static void init(IBinder binder) {
        if (binder == null) {
            Ln.w("clipboard 服务拿不到，剪切板同步不可用");
            return;
        }
        try {
            Class<?> stub = Class.forName("android.content.IClipboard$Stub");
            service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            getPrimaryClipMethod = pick("getPrimaryClip");
            setPrimaryClipMethod = pick("setPrimaryClip");
            if (getPrimaryClipMethod == null && setPrimaryClipMethod == null) {
                Ln.w("IClipboard 里找不到可用的读写方法");
            } else {
                Ln.i("ClipboardManager 已就绪（读=" + (getPrimaryClipMethod != null)
                        + " 写=" + (setPrimaryClipMethod != null) + "）");
            }
        } catch (Throwable t) {
            Ln.e("初始化 ClipboardManager 失败", t);
        }
    }

    public static boolean isReady() {
        return service != null && (getPrimaryClipMethod != null || setPrimaryClipMethod != null);
    }

    /**
     * 在候选签名里挑一个能用的。
     *
     * 各版本签名（挑常见的几个）：
     * - getPrimaryClip(String callingPackage)
     * - getPrimaryClip(String callingPackage, int userId)
     * - getPrimaryClip(String callingPackage, String attributionTag, int userId)
     * - getPrimaryClip(String callingPackage, String attributionTag, int userId, int deviceId)
     * - getPrimaryClip()——接口的 Default 实现，调了返回 null，必须排除
     *
     * 对 setPrimaryClip 同理，第一个参数固定是 ClipData。
     *
     * 选参数**最多**的那个：参数最多的是真正的 AIDL 事务方法，
     * 参数少的是 Default 兼容层（内部会直接返回 null/空实现），调了等于没调。
     */
    private static Method pick(String name) {
        Method best = null;
        for (Method m : service.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (name.equals("setPrimaryClip") && (p.length == 0 || p[0] != ClipData.class)) continue;
            // getPrimaryClip() 无参版本是 Default 实现，排除
            if (name.equals("getPrimaryClip") && (p.length == 0 || p[0] != String.class)) continue;
            if (best == null || p.length > best.getParameterTypes().length) best = m;
        }
        return best;
    }

    /** 读手机当前剪切板文本。拿不到（空/非文本/异常）返回 null。 */
    public static String getText() {
        if (service == null || getPrimaryClipMethod == null) return null;
        try {
            Class<?>[] params = getPrimaryClipMethod.getParameterTypes();
            String[] names = paramNames(getPrimaryClipMethod);
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = defaultValue(params[i], names[i]);
            }
            Object clip = getPrimaryClipMethod.invoke(service, args);
            if (!(clip instanceof ClipData)) {
                Ln.w("getPrimaryClip 返回了非 ClipData："
                        + (clip == null ? "null" : clip.getClass().getName())
                        + "（签名 " + getPrimaryClipMethod + "）");
                return null;
            }
            ClipData data = (ClipData) clip;
            if (data.getItemCount() <= 0) {
                Ln.w("getPrimaryClip 返回的 ClipData 没有条目");
                return null;
            }
            CharSequence text = data.getItemAt(0).getText();
            return text == null ? null : text.toString();
        } catch (Throwable t) {
            Ln.w("读剪切板失败：" + t);
            return null;
        }
    }

    /** 把文本写进手机剪切板。 */
    public static boolean setText(String text) {
        if (service == null || setPrimaryClipMethod == null) return false;
        if (text == null) return false;
        try {
            ClipData clip = ClipData.newPlainText("CarWithYou", text);
            Class<?>[] params = setPrimaryClipMethod.getParameterTypes();
            String[] names = paramNames(setPrimaryClipMethod);
            Object[] args = new Object[params.length];
            args[0] = clip;
            for (int i = 1; i < params.length; i++) {
                args[i] = defaultValue(params[i], names[i]);
            }
            setPrimaryClipMethod.invoke(service, args);
            return true;
        } catch (Throwable t) {
            Ln.w("写剪切板失败：" + t);
            return false;
        }
    }

    /**
     * 尝试拿到参数名。
     *
     * 反射默认拿不到参数名（需要编译时 -parameters），拿不到就返回全是 null 的数组，
     * 调用方按类型兜默认值——对本用例已经够了。
     */
    private static String[] paramNames(Method m) {
        int n = m.getParameterTypes().length;
        String[] names = new String[n];
        try {
            java.lang.reflect.Parameter[] ps = m.getParameters();
            for (int i = 0; i < n; i++) names[i] = ps[i].getName();
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return names;
    }

    /**
     * 给不确定的参数类型兜一个能用的默认值。
     *
     * 关键是 userId —— 传 0 在某些 ROM 上会被当成“查不到当前用户”而返回 null，
     * 所以这里用当前用户（拿不到再用 0 兜底）。
     */
    private static Object defaultValue(Class<?> type, String name) {
        if (type == String.class) {
            // attributionTag 传 null 是合法的，传说空串反而可能被拒
            if ("attributionTag".equals(name)) return null;
            // 必须用**当前进程真实对应的包名**：
            // 系统会拿 callingPackage 去比对调用方 UID，对不上就当成冒充，直接返回 null。
            // 我们以 app_process 跑，包名要从 Process.myUid() 反推。
            return callingPackage();
        }
        if (type == int.class || type == Integer.class) {
            String n = name == null ? "" : name;
            if (n.contains("user")) return currentUserId();
            if (n.contains("device")) return android.os.Process.myUid() / 100000;
            return 0;
        }
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }

    /** 当前用户 id。拿不到就 0（多数单用户设备就是 0）。 */
    private static int currentUserId() {
        try {
            Class<?> uhu = Class.forName("android.os.UserHandle");
            Object id = uhu.getMethod("myUserId").invoke(null);
            if (id instanceof Integer) return (Integer) id;
        } catch (Throwable ignored) {
            // 反射不到就兜底
        }
        return 0;
    }

    /**
     * 当前进程对应的包名。
     *
     * 剪切板服务会拿 callingPackage 去和调用方 UID 比对，对不上就当冒充 → 返回 null，
     * 所以不能写死 "com.android.shell"。这里按 UID 反推：
     *   uid 0（root）→ "root"
     *   uid 2000（shell）→ "com.android.shell"
     *   其他 → 交给系统按 UID 自己判断（传 null）。
     */
    private static String callingPackage() {
        int uid = android.os.Process.myUid();
        if (uid == 0) return "root";
        if (uid == 2000) return "com.android.shell";
        // 拿不到就传 null：比传一个错的包名强（错的会被当成冒充）
        return null;
    }
}
