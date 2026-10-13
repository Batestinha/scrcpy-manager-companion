package com.diogobatista.scrcpymanager.companion;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class CompanionUserService extends ICompanionService.Stub {
    private static final String TAG = "ScrcpyMgrCompanion";
    private static final String SOCKET_NAME = "scrcpy_manager_companion_v1";
    private static final File MARKER = new File("/data/local/tmp/scrcpy-manager-companion.armed");
    private static final File FAILURE = new File("/data/local/tmp/scrcpy-manager-companion.failure");
    private static final int SHELL_UID = 2000;
    private static final int LEASE_TIMEOUT_MS = 6000;
    private static final int RESTORE_ATTEMPTS = 20;
    private final Object stateLock = new Object();
    private volatile boolean stopping;
    private volatile LocalServerSocket server;
    private volatile String armedSession;
    private final HapticsGuard haptics = new HapticsGuard(this::ownsDisplaySession);
    private final CableLossGuard cableGuard = new CableLossGuard(haptics);

    public CompanionUserService() { start(); }
    public CompanionUserService(Context ignored) { start(); }

    private void start() {
        Thread daemon = new Thread(this::run, "display-cleanup-socket");
        daemon.setDaemon(false);
        daemon.start();
    }

    private void run() {
        try {
            restoreStaleMarker();
            cableGuard.start();
            haptics.start();
            server = new LocalServerSocket(SOCKET_NAME);
            while (!stopping) {
                LocalSocket socket = server.accept();
                Thread client = new Thread(() -> handle(socket), "display-cleanup-client");
                client.setDaemon(true);
                client.start();
            }
        } catch (Throwable error) {
            if (!stopping) Log.e(TAG, "Companion socket stopped", error);
            restoreOwnedSession("service-failure");
            cableGuard.shutdown();
            haptics.shutdown();
        }
    }

    private void handle(LocalSocket socket) {
        String ownedSession = null;
        try (LocalSocket owned = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(owned.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(owned.getOutputStream(), StandardCharsets.UTF_8))) {
            Credentials peer = owned.getPeerCredentials();
            if (peer == null || (peer.getUid() != Process.ROOT_UID && peer.getUid() != SHELL_UID)) {
                Log.w(TAG, "Rejected socket peer uid=" + (peer == null ? "unknown" : peer.getUid()));
                return;
            }
            boolean hello = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ", 3);
                if (parts.length == 3 && "HELLO".equals(parts[0]) && "1".equals(parts[1]) && validUuid(parts[2])) {
                    hello = true;
                    write(writer, "HELLO 1");
                } else if (hello && parts.length == 1 && "STATUS".equals(parts[0])) {
                    write(writer, "STATUS ready " + BuildConfig.VERSION_NAME + " " + readFailure());
                } else if (hello && line.startsWith("WATCH ")) {
                    String[] watch = line.split(" ");
                    if (watch.length != 6) throw new IllegalArgumentException("Invalid WATCH");
                    cableGuard.register(watch[1], Integer.parseInt(watch[2]), Integer.parseInt(watch[3]), watch[4], watch[5]);
                    write(writer, "WATCHING " + watch[1]);
                } else if (hello && parts.length == 2 && "ARM".equals(parts[0]) && validUuid(parts[1])) {
                    synchronized (stateLock) {
                        if (armedSession != null && !armedSession.equals(parts[1])) restoreLocked("stale-session-replaced");
                        writeMarker(parts[1]); // Durable before ARMED is observable by the host.
                        armedSession = parts[1];
                        ownedSession = parts[1];
                        owned.setSoTimeout(LEASE_TIMEOUT_MS);
                    }
                    write(writer, "ARMED " + parts[1]);
                } else if (hello && parts.length == 2 && "BEAT".equals(parts[0]) && parts[1].equals(ownedSession)) {
                    write(writer, "BEAT " + parts[1]);
                } else if (hello && parts.length == 2 && "RESTORE".equals(parts[0]) && parts[1].equals(ownedSession)) {
                    boolean restored;
                    synchronized (stateLock) {
                        restored = parts[1].equals(armedSession) && restoreLocked("explicit");
                    }
                    write(writer, (restored ? "RESTORED " : "FAILED ") + parts[1]);
                    return;
                } else {
                    throw new IllegalArgumentException("Protocol violation");
                }
            }
        } catch (SocketTimeoutException timeout) {
            Log.w(TAG, "Heartbeat lease expired for " + ownedSession);
        } catch (Throwable error) {
            Log.w(TAG, "Lease ended for " + ownedSession, error);
        } finally {
            if (ownedSession != null) {
                synchronized (stateLock) {
                    if (ownedSession.equals(armedSession)) restoreLocked("lease-ended");
                }
            }
        }
    }

    private void restoreStaleMarker() {
        if (!MARKER.exists()) return;
        try {
            armedSession = new String(Files.readAllBytes(MARKER.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            armedSession = "unknown";
        }
        synchronized (stateLock) { restoreLocked("stale-marker"); }
    }

    private void restoreOwnedSession(String reason) {
        synchronized (stateLock) {
            if (armedSession != null) restoreLocked(reason);
        }
    }

    private boolean restoreLocked(String reason) {
        if (armedSession == null && !MARKER.exists()) return true;
        String session = armedSession;
        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS; attempt++) {
            if (DisplayPower.restoreNormal() && haptics.restore()) {
                if (MARKER.exists() && !MARKER.delete()) Log.w(TAG, "Could not remove armed marker");
                if (FAILURE.exists()) FAILURE.delete();
                armedSession = null;
                Log.i(TAG, "Display power restored: " + reason + ", session=" + session);
                return true;
            }
            if (attempt < RESTORE_ATTEMPTS) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }
        recordFailure(reason, session);
        armedSession = null;
        return false;
    }

    private static void writeMarker(String session) throws Exception {
        File temp = new File(MARKER.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write((session + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Files.move(temp.toPath(), MARKER.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void recordFailure(String reason, String session) {
        try (FileOutputStream output = new FileOutputStream(FAILURE, false)) {
            output.write((System.currentTimeMillis() + " " + reason + " " + session + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Throwable error) {
            Log.e(TAG, "Could not record restoration failure", error);
        }
        Log.e(TAG, "Display restoration failed after " + RESTORE_ATTEMPTS + " attempts: " + reason);
    }

    private static String readFailure() {
        if (!FAILURE.exists()) return "-";
        try {
            return new String(Files.readAllBytes(FAILURE.toPath()), StandardCharsets.UTF_8)
                    .trim().replace('\r', '_').replace('\n', '_');
        } catch (Throwable ignored) {
            return "failure-record-unreadable";
        }
    }

    private static boolean validUuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static void write(BufferedWriter writer, String value) throws Exception {
        writer.write(value);
        writer.newLine();
        writer.flush();
    }

    private boolean ownsDisplaySession() {
        return armedSession != null || cableGuard.isWatching();
    }

    @Override public void destroy() {
        stopping = true;
        cableGuard.shutdown();
        restoreOwnedSession("service-destroyed");
        haptics.shutdown();
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        System.exit(0);
    }
}
