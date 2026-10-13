package com.diogobatista.scrcpymanager.companion;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class VibrationStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Setting implements VibrationState.Settings {
        final Map<Integer, String> values = new HashMap<>();
        int user;
        int writes;
        boolean failWrite;
        Runnable beforeWrite = () -> { };

        Setting(String initial) { values.put(0, initial); }
        @Override public int currentUser() { return user; }
        @Override public String read(int user) { return values.getOrDefault(user, "null"); }
        @Override public void write(int user, String value) throws IOException {
            beforeWrite.run();
            if (failWrite) throw new IOException("settings unavailable");
            values.put(user, value);
            writes++;
        }
    }

    private File marker() { return new File(temporary.getRoot(), "haptics"); }
    private VibrationState state(Setting setting) {
        return new VibrationState(setting, new VibrationState.FileStore(marker()));
    }

    @Test public void restoresEnabledSettingWhenPanelReturnsDuringSession() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        setting.beforeWrite = () -> assertTrue("Recovery must be durable before muting", marker().isFile());
        state.update(true, true);
        assertEquals("0", setting.read(0));
        state.update(true, false);
        assertEquals("1", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void restoresMissingSettingAsPlatformDefaultOnSessionExit() throws Exception {
        Setting setting = new Setting("null");
        VibrationState state = state(setting);
        state.update(true, true);
        state.update(false, null);
        assertEquals("null", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void alreadyDisabledSettingStaysDisabled() throws Exception {
        Setting setting = new Setting("0");
        VibrationState state = state(setting);
        state.update(true, true);
        state.update(false, false);
        assertEquals("0", setting.read(0));
        assertEquals(0, setting.writes);
        assertFalse(marker().exists());
    }

    @Test public void repeatedOffSamplesDoNotOverwriteOriginalSettingOrWriteAgain() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        state.update(true, true);
        state.update(true, true);
        state.update(true, true);
        assertEquals(1, setting.writes);
        state.restore();
        assertEquals("1", setting.read(0));
    }

    @Test public void realSleepOrUnwatchedSessionNeverSuppresses() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        state.update(true, false);
        state.update(false, true);
        assertEquals(0, setting.writes);
    }

    @Test public void unknownStateDoesNotMuteOrDiscardRecovery() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        state.update(true, null);
        assertEquals(0, setting.writes);
        state.update(true, true);
        state.update(true, null);
        assertEquals("0", setting.read(0));
        assertTrue(marker().exists());
        state.update(false, null);
        assertEquals("1", setting.read(0));
    }

    @Test public void restartResumesSuppressionWithoutLosingOriginalValue() throws Exception {
        Setting setting = new Setting("null");
        state(setting).update(true, true);
        VibrationState restarted = state(setting);
        restarted.update(true, true);
        restarted.update(true, false);
        assertEquals("null", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void restartWithoutSessionRecoversStaleSuppression() throws Exception {
        Setting setting = new Setting("1");
        state(setting).update(true, true);
        state(setting).update(false, null);
        assertEquals("1", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void userSwitchRestoresOldUserAndSnapshotsNewUser() throws Exception {
        Setting setting = new Setting("1");
        setting.values.put(10, "null");
        VibrationState state = state(setting);
        state.update(true, true);
        setting.user = 10;
        state.update(true, true);
        assertEquals("1", setting.read(0));
        assertEquals("0", setting.read(10));
        state.restore();
        assertEquals("null", setting.read(10));
    }

    @Test public void failedSuppressionRetainsOriginalSnapshotForRetry() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        setting.failWrite = true;
        assertThrows(IOException.class, () -> state.update(true, true));
        assertTrue(marker().exists());
        assertEquals("1", setting.read(0));
        setting.failWrite = false;
        state.update(true, true);
        state.restore();
        assertEquals("1", setting.read(0));
    }

    @Test public void failedRestoreKeepsMarkerForNextDaemon() throws Exception {
        Setting setting = new Setting("null");
        VibrationState state = state(setting);
        state.update(true, true);
        setting.failWrite = true;
        assertThrows(IOException.class, state::restore);
        assertTrue(marker().exists());
        setting.failWrite = false;
        state(setting).restore();
        assertEquals("null", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void cannotMuteWithoutSavingRecovery() throws Exception {
        Setting setting = new Setting("1");
        File impossible = new File(temporary.getRoot(), "missing/marker");
        VibrationState state = new VibrationState(setting, new VibrationState.FileStore(impossible));
        assertThrows(IOException.class, () -> state.update(true, true));
        assertEquals("1", setting.read(0));
        assertEquals(0, setting.writes);
    }

    @Test public void explicitUserChangeIsPreservedDuringRestore() throws Exception {
        Setting setting = new Setting("null");
        VibrationState state = state(setting);
        state.update(true, true);
        setting.values.put(0, "1");
        state.restore();
        assertEquals("1", setting.read(0));
        assertFalse(marker().exists());
    }

    @Test public void eachNewOffIntervalSnapshotsTheCurrentPreference() throws Exception {
        Setting setting = new Setting("1");
        VibrationState state = state(setting);
        state.update(true, true);
        state.update(true, false);
        setting.values.put(0, "0");
        state.update(true, true);
        state.update(true, false);
        assertEquals("0", setting.read(0));
        assertFalse(marker().exists());
    }
}
