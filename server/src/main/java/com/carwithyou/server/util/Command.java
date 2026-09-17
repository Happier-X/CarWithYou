package com.carwithyou.server.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 跑 shell 命令。
 *
 * 我们整个进程本来就是 shell 身份，所以这里不需要 adb，直接 exec 就行
 * —— 这也是 `am start --display`、`am display move-stack` 这些能用的原因。
 */
public final class Command {

    private Command() {
    }

    /** 跑命令并等它结束，返回 stdout+stderr。 */
    public static String execReadOutput(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (InputStream in = process.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        process.waitFor();
        return output.toString();
    }

    /** 跑命令，不在意输出，也不等太久。 */
    public static void exec(String... command) {
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            Ln.eShort("exec 失败：" + String.join(" ", command), e);
        }
    }

    /** 带超时的执行，超时就杀掉，返回 null。用于不能让它吊死进程的场景（如 dumpsys）。 */
    public static String execReadOutputWithTimeout(long timeoutMs, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Ln.w("命令超时：" + String.join(" ", command));
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            Ln.eShort("执行失败：" + String.join(" ", command), e);
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}