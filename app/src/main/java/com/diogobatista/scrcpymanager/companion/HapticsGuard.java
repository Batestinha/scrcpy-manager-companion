package com.diogobatista.scrcpymanager.companion;

import android.util.Log;

import java.io.File;
import java.util.function.BooleanSupplier;

/** Observes actual panel power; a WATCH alone does not imply the display is faux off. */
final class HapticsGuard {
    private static final String TAG = "ScrcpyMgrCompanion";
    private final BooleanSupplier sessionActive;
    private final VibrationState vibration = new VibrationState(new SystemSetting(),
            new VibrationState.FileStore(new File("/data/local/tmp/scrcpy-manager-companion.haptics")));
    private volatile boolean stopping;
    private Thread worker;

    HapticsGuard(BooleanSupplier sessionActive) { this.sessionActive = sessionActive; }

    void start() {
        worker = new Thread(this::monitor, "display-haptics");
        worker.setDaemon(true);
        worker.start();
    }

    private void monitor() {
        boolean reportedFailure = false;
        while (!stopping) {
            try {
                boolean active = sessionActive.getAsBoolean();
                Boolean fauxOff = active ? DisplayPower.isFauxOff() : Boolean.FALSE;
                if (active && fauxOff == null) throw new IllegalStateException("Physical display state unavailable");
                synchronized (vibration) {
                    if (!stopping) vibration.update(active && sessionActive.getAsBoolean(), fauxOff);
                }
                reportedFailure = false;
            } catch (Exception error) {
                if (!reportedFailure) Log.w(TAG, "Cannot update vibration; preserving recovery state", error);
                reportedFailure = true;
            }
            try { Thread.sleep(1000); }
            catch (InterruptedException ignored) { return; }
        }
    }

    boolean restore() {
        try {
            vibration.restore();
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Cannot restore vibration; retaining recovery marker", error);
            return false;
        }
    }

    void shutdown() {
        stopping = true;
        if (worker != null) worker.interrupt();
        restore();
    }

    private static final class SystemSetting implements VibrationState.Settings {
        @Override public int currentUser() throws Exception {
            return (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        }

        @Override public String read(int user) throws Exception {
            String value = ShellCommand.run("/system/bin/settings", "--user", Integer.toString(user),
                    "get", "system", "vibrate_on").trim();
            new VibrationState.Snapshot(user, value); // Reject permission errors or malformed output.
            return value;
        }

        @Override public void write(int user, String value) throws Exception {
            if ("null".equals(value)) {
                ShellCommand.run("/system/bin/settings", "--user", Integer.toString(user), "delete", "system", "vibrate_on");
            } else {
                ShellCommand.run("/system/bin/settings", "--user", Integer.toString(user), "put", "system", "vibrate_on", value);
            }
            if (!value.equals(read(user))) throw new IllegalStateException("Vibration setting did not persist");
        }
    }
}
