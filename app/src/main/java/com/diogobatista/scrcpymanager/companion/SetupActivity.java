package com.diogobatista.scrcpymanager.companion;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public final class SetupActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 41;
    private TextView status;
    private Button authorize;
    private final Shizuku.OnBinderReceivedListener binderListener = this::refresh;
    private final Shizuku.OnBinderDeadListener deadListener = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED)
            CompanionApplication.startDaemonIfAuthorized();
        refresh();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("scrcpy Manager companion");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, padding, 0, padding);
        authorize = new Button(this);
        authorize.setText("Authorize with Shizuku");
        authorize.setOnClickListener(view -> requestAuthorization());
        layout.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(authorize, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        refresh();
    }

    private void requestAuthorization() {
        if (!Shizuku.pingBinder()) return;
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            CompanionApplication.startDaemonIfAuthorized();
            refresh();
        } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(REQUEST_SHIZUKU);
        }
    }

    private void refresh() {
        runOnUiThread(() -> {
            if (!Shizuku.pingBinder()) {
                status.setText("Shizuku is not running. Start it, then return here.");
                authorize.setEnabled(false);
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                status.setText("Ready. When cable-loss protection is enabled in the Windows manager, this phone watches the selected USB or Ethernet cable and restores the display if it is disconnected. No heartbeat or permanent notification is used.");
                authorize.setEnabled(false);
                CompanionApplication.startDaemonIfAuthorized();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                status.setText("Authorization was denied. Open this screen again when you want to retry.");
                authorize.setEnabled(false);
            } else {
                status.setText("Authorize once with Shizuku to enable phone-side cable-loss protection. The Windows manager must also enable it for this phone.");
                authorize.setEnabled(true);
            }
        });
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }
}
