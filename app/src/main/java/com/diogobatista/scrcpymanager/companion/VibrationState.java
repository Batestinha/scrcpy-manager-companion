package com.diogobatista.scrcpymanager.companion;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Owns only the temporary change to the main vibration switch, including crash recovery. */
final class VibrationState {
    interface Settings {
        int currentUser() throws Exception;
        String read(int user) throws Exception;
        void write(int user, String value) throws Exception;
    }

    interface Store {
        Snapshot load() throws Exception;
        void save(Snapshot snapshot) throws Exception;
        void clear() throws Exception;
    }

    static final class Snapshot {
        final int user;
        final String value; // "null" means the platform default; restore it by deleting the key.

        Snapshot(int user, String value) {
            if (user < 0 || !("null".equals(value) || value.matches("[0-9]+")))
                throw new IllegalArgumentException("Invalid vibration snapshot");
            this.user = user;
            this.value = value;
        }
    }

    static final class FileStore implements Store {
        private final File file;

        FileStore(File file) { this.file = file; }

        @Override public Snapshot load() throws Exception {
            if (!file.exists()) return null;
            String[] fields = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            if (fields.length != 3 || !"1".equals(fields[0]))
                throw new IllegalArgumentException("Invalid vibration recovery marker");
            return new Snapshot(Integer.parseInt(fields[1]), fields[2]);
        }

        @Override public void save(Snapshot snapshot) throws Exception {
            File temp = new File(file.getPath() + ".tmp");
            try {
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    output.write(("1 " + snapshot.user + " " + snapshot.value + "\n").getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp.toPath());
            }
        }

        @Override public void clear() throws Exception { Files.deleteIfExists(file.toPath()); }
    }

    private final Settings settings;
    private final Store store;
    private Snapshot snapshot;
    private boolean loaded;
    private boolean applied;
    private int appliedUser = -1;

    VibrationState(Settings settings, Store store) {
        this.settings = settings;
        this.store = store;
    }

    synchronized void update(boolean sessionActive, Boolean fauxOff) throws Exception {
        load();
        if (!sessionActive || Boolean.FALSE.equals(fauxOff)) {
            restore();
        } else if (Boolean.TRUE.equals(fauxOff)) {
            int user = settings.currentUser();
            if (applied && appliedUser == user) return;
            if (snapshot != null && snapshot.user != user) restore();
            String current = settings.read(user);
            if (snapshot == null && !"0".equals(current)) {
                Snapshot saved = new Snapshot(user, current);
                store.save(saved); // Durable before changing the setting.
                snapshot = saved;
            }
            if (!"0".equals(current)) settings.write(user, "0");
            applied = true;
            appliedUser = user;
        }
        // Unknown display state never starts a new suppression or discards recovery state.
    }

    synchronized void restore() throws Exception {
        load();
        if (snapshot != null) {
            // Preserve an explicit user change made since we suppressed vibration.
            if ("0".equals(settings.read(snapshot.user))) settings.write(snapshot.user, snapshot.value);
            store.clear();
            snapshot = null;
        }
        applied = false;
        appliedUser = -1;
    }

    private void load() throws Exception {
        if (!loaded) {
            snapshot = store.load();
            loaded = true;
        }
    }
}
