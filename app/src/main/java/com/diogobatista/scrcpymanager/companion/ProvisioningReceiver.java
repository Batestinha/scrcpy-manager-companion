package com.diogobatista.scrcpymanager.companion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/** A shell-only bootstrap and readiness endpoint for the Windows manager. */
public final class ProvisioningReceiver extends BroadcastReceiver {
    public static final int PROTOCOL_VERSION = 1;
    public static final String ACTION_STATUS =
            "com.diogobatista.scrcpymanager.companion.action.STATUS";
    public static final String ACTION_START =
            "com.diogobatista.scrcpymanager.companion.action.START";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_STATUS.equals(action) && !ACTION_START.equals(action)) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"protocolVersion\":1,\"error\":\"unsupported-action\"}");
            return;
        }

        boolean shizukuRunning = false;
        boolean authorized = false;
        String error = null;
        try {
            shizukuRunning = Shizuku.pingBinder() && !Shizuku.isPreV11();
            authorized = shizukuRunning
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            if (ACTION_START.equals(action) && authorized) {
                CompanionApplication.startDaemonIfAuthorized();
            }
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }

        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            JSONObject result = new JSONObject();
            result.put("protocolVersion", PROTOCOL_VERSION);
            result.put("version", packageInfo.versionName);
            result.put("shizukuRunning", shizukuRunning);
            result.put("authorized", authorized);
            result.put("startRequested", ACTION_START.equals(action));
            result.put("readyForProbe", authorized && error == null);
            if (error != null) result.put("error", error);
            setResultCode(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
            setResultData(result.toString());
        } catch (Throwable failure) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"protocolVersion\":1,\"error\":\"status-encoding-failed\"}");
        }
    }
}
