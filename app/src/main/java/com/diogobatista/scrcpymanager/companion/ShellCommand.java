package com.diogobatista.scrcpymanager.companion;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

/** Small, bounded shell queries made under the Shizuku service's shell/root identity. */
final class ShellCommand {
    static String run(String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = process.getInputStream().read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) return;
                    output.write(buffer, 0, count);
                }
            } catch (Exception ignored) { }
        }, "companion-query-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Shell query timed out");
            reader.join(500);
            if (reader.isAlive() || process.exitValue() != 0) throw new IllegalStateException("Shell query failed");
            return output.toString("UTF-8");
        } finally { process.destroy(); }
    }
}
