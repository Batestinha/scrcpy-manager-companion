package com.diogobatista.scrcpymanager.companion;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;

public final class CompanionApplication extends Application {
    private static final String TAG = "ScrcpyMgrCompanion";
    // Independent of the APK version. Bump when the daemon implementation must be replaced in place.
    private static final int SERVICE_VERSION = 113;
    private static volatile boolean binding;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            Log.i(TAG, "Display cleanup daemon connected");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            Log.w(TAG, "Display cleanup daemon disconnected");
        }
    };

    private final Shizuku.OnBinderReceivedListener binderListener = CompanionApplication::startDaemonIfAuthorized;

    @Override public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(binderListener);
    }

    static synchronized void startDaemonIfAuthorized() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED || binding) return;
            binding = true;
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName("com.diogobatista.scrcpymanager.companion", CompanionUserService.class.getName()))
                    .daemon(true)
                    .tag("display-cleanup-v1")
                    .version(SERVICE_VERSION)
                    .processNameSuffix("display_cleanup");
            Shizuku.bindUserService(args, CONNECTION);
        } catch (Throwable error) {
            binding = false;
            Log.e(TAG, "Cannot start display cleanup daemon", error);
        }
    }
}
