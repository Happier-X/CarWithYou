package com.carwithyou.server;

import android.os.Build;

/** 各 API 级别的具名常量，避免代码里散落魔法数字。 */
public final class AndroidVersions {
    public static final int API_29_ANDROID_10 = 29;
    public static final int API_30_ANDROID_11 = 30;
    public static final int API_31_ANDROID_12 = 31;
    public static final int API_33_ANDROID_13 = 33;
    public static final int API_34_ANDROID_14 = 34;
    public static final int API_35_ANDROID_15 = 35;

    private AndroidVersions() {
    }

    /** Android 12（含 12 的预览版，此时 SDK_INT 还是 R 而 CODENAME 是 S）。 */
    public static boolean isAndroid12OrNewer() {
        return Build.VERSION.SDK_INT > API_30_ANDROID_11
                || (Build.VERSION.SDK_INT == API_30_ANDROID_11 && "S".equals(Build.VERSION.CODENAME));
    }
}
