package com.carwithyou.server.control;

import com.carwithyou.server.util.Command;
import com.carwithyou.server.util.Ln;

import java.util.Locale;

/**
 * 把应用放到指定屏上跑。
 *
 * 我们整个进程本来就是 shell 身份，所以直接 exec `am` 命令就行：
 *  - `am start --display <id> -n pkg/act`：冷启动一个应用进目标屏
 *  - `am display move-stack <task> <id>` / 新版 `am task move`：把已有任务挪到目标屏
 *
 * 这两个是 Easycontrol 在 shell 身份下稳定可用的做法。
 */
public final class AppLauncher {

    private AppLauncher() {
    }

    /** 启动应用组件到指定屏，返回是否成功下发。 */
    public static boolean launch(String component, int displayId) {
        try {
            String output = Command.execReadOutput(
                    "am", "start", "--user", "0", "--display", String.valueOf(displayId),
                    "-n", component);
            if (output == null) {
                return false;
            }
            if (output.toLowerCase(Locale.ROOT).contains("error")) {
                Ln.w("am start 报错：" + output.trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            Ln.eShort("启动应用异常", e);
            return false;
        }
    }

    /** 把已有任务挪到目标屏，返回是否成功。 */
    public static boolean moveTaskToDisplay(int taskId, int displayId) {
        // Android 12 之后 am 的 move-stack 被重构成 am task move
        boolean ok = tryExec("am ", "display", "move-stack",
                String.valueOf(taskId), String.valueOf(displayId));
        if (ok) {
            return true;
        }
        return tryExec("am ", "task", "move",
                String.valueOf(taskId), String.valueOf(displayId));
    }

    private static boolean tryExec(String tag, String... command) {
        try {
            String output = Command.execReadOutput(command);
            if (output == null) {
                return false;
            }
            String lower = output.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("exception")) {
                Ln.w(tag + "失败：" + output.trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            Ln.d(tag + "不可用（不致命）：" + e.getMessage());
            return false;
        }
    }
}