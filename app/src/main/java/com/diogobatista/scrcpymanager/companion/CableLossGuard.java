package com.diogobatista.scrcpymanager.companion;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** A video-owned watch survives the host socket closing; it never uses a heartbeat. */
final class CableLossGuard {
    private static final String TAG = "ScrcpyMgrCompanion";
    private static final File MARKER = new File("/data/local/tmp/scrcpy-manager-companion.cable-watch");
    private volatile Watch watch;
    private final HapticsGuard haptics;
    private volatile boolean stopping;
    private int disconnectedSamples;

    CableLossGuard(HapticsGuard haptics) { this.haptics = haptics; }

    boolean isWatching() { return watch != null; }

    synchronized void start() {
        try {
            if (MARKER.exists()) watch = Watch.parse(new String(Files.readAllBytes(MARKER.toPath()), StandardCharsets.UTF_8));
        } catch (Exception error) { Log.e(TAG, "Cannot reload cable watch", error); }
        Thread worker = new Thread(this::monitor, "display-cleanup-cable");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void register(String session, int videoPid, int audioPid, String kind, String iface) throws Exception {
        UUID.fromString(session);
        if (!("usb".equals(kind) && "-".equals(iface))
                && !("ethernet".equals(kind) && iface.matches("[a-zA-Z0-9_.-]{1,32}")))
            throw new IllegalArgumentException("Unsupported cable transport");
        String videoStart = serverStart(videoPid, true);
        String audioStart = audioPid == 0 ? "-" : serverStart(audioPid, false);
        if (videoStart == null || audioStart == null) throw new IllegalArgumentException("Not a live scrcpy server");
        Boolean connected = cable(kind, iface);
        if (!Boolean.TRUE.equals(connected)) throw new IllegalStateException("Selected cable is not connected or its state is unavailable");
        if (watch != null && !watch.session.equals(session))
            throw new IllegalStateException("Previous video watch has not finished cleanup");
        watch = new Watch(session, videoPid, videoStart, audioPid, audioStart, kind, iface);
        persist();
        disconnectedSamples = 0;
        Log.i(TAG, "Watching video " + videoPid + " and audio " + audioPid + " on " + kind + "/" + iface + "; no heartbeat");
    }

    private void monitor() {
        while (!stopping) {
            try {
                synchronized (this) {
                    if (watch != null) {
                        if (!matches(watch.videoPid, watch.videoStart, true)) finish("video-ended", false);
                        else {
                            Boolean connected = cable(watch.kind, watch.iface);
                            // Unknown queries do not count as a cable loss, nor preserve a partial loss sample.
                            disconnectedSamples = Boolean.FALSE.equals(connected) ? disconnectedSamples + 1 : 0;
                            if (disconnectedSamples >= 2) finish("selected-cable-disconnected", true);
                        }
                    }
                }
            } catch (Throwable error) { Log.w(TAG, "Cable watch query failed; retaining video", error); }
            try { Thread.sleep(1000); }
            catch (InterruptedException ignored) { return; }
        }
    }

    private void finish(String reason, boolean stopVideo) throws Exception {
        Watch old = watch;
        stop(old.audioPid, old.audioStart, false);
        if (stopVideo) stop(old.videoPid, old.videoStart, true);
        // Killing only the verified server lets scrcpy's own child restore settings and power.
        Thread.sleep(750);
        if (matches(old.videoPid, old.videoStart, true)) return;
        boolean restored = false;
        for (int attempt = 0; attempt < 20 && !restored; attempt++) {
            restored = DisplayPower.restoreNormal() && haptics.restore();
            if (!restored) Thread.sleep(1000);
        }
        if (!restored) throw new IllegalStateException("Cable-loss display restoration failed");
        Files.deleteIfExists(MARKER.toPath());
        watch = null;
        disconnectedSamples = 0;
        Log.i(TAG, "Cable watch restored display: " + reason + ", video=" + old.videoPid);
    }

    private static void stop(int pid, String start, boolean video) throws Exception {
        if (pid > 0 && matches(pid, start, video)) Os.kill(pid, OsConstants.SIGTERM);
    }

    private static boolean matches(int pid, String start, boolean video) throws Exception {
        return start.equals(serverStart(pid, video));
    }

    private static String serverStart(int pid, boolean video) throws Exception {
        if (pid <= 0) return null;
        File directory = new File("/proc/" + pid);
        try {
            String command = new String(Files.readAllBytes(new File(directory, "cmdline").toPath()), StandardCharsets.UTF_8).replace('\0', ' ');
            if (!command.startsWith("app_process / com.genymobile.scrcpy.Server ")) return null;
            if (video ? !command.contains(" audio=false ") || command.contains(" video=false ") : !command.contains(" video=false ")) return null;
            String stat = new String(Files.readAllBytes(new File(directory, "stat").toPath()), StandardCharsets.UTF_8);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            return fields[19]; // Linux stat field 22: process start time; prevents PID reuse.
        } catch (java.nio.file.NoSuchFileException gone) { return null; }
    }

    private static Boolean cable(String kind, String iface) throws Exception {
        String dump = ShellCommand.run("/system/bin/dumpsys", "usb".equals(kind) ? "usb" : "ethernet");
        return "usb".equals(kind) ? CableState.usb(dump) : CableState.ethernet(dump, iface);
    }

    private void persist() throws Exception {
        File temp = new File(MARKER.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(watch.encode().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Files.move(temp.toPath(), MARKER.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void shutdown() { stopping = true; /* Persisted watch is resumed by the replacement daemon. */ }

    private static final class Watch {
        final String session, videoStart, audioStart, kind, iface;
        final int videoPid, audioPid;
        Watch(String session, int videoPid, String videoStart, int audioPid, String audioStart, String kind, String iface) {
            this.session = session; this.videoPid = videoPid; this.videoStart = videoStart;
            this.audioPid = audioPid; this.audioStart = audioStart; this.kind = kind; this.iface = iface;
        }
        String encode() { return session + " " + videoPid + " " + videoStart + " " + audioPid + " " + audioStart + " " + kind + " " + iface + "\n"; }
        static Watch parse(String text) {
            String[] fields = text.trim().split(" ");
            if (fields.length != 7) throw new IllegalArgumentException("Invalid watch marker");
            UUID.fromString(fields[0]);
            return new Watch(fields[0], Integer.parseInt(fields[1]), fields[2], Integer.parseInt(fields[3]), fields[4], fields[5], fields[6]);
        }
    }
}
