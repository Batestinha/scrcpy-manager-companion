package com.diogobatista.scrcpymanager.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Small, bounded shell queries made under the Shizuku service's shell/root identity. */
final class ShellCommand {
    static String run(String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Exception> readError = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new IOException("Shell query output too large");
                    output.write(buffer, 0, count);
                }
            } catch (Exception error) { readError.set(error); }
        }, "companion-query-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Shell query timed out");
            reader.join(500);
            if (reader.isAlive() || process.exitValue() != 0) throw new IllegalStateException("Shell query failed");
            if (readError.get() != null) throw readError.get();
            return output.toString("UTF-8");
        } finally { process.destroy(); }
    }
}
