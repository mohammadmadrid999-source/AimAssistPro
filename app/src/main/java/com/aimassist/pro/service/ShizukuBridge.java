package com.aimassist.pro.service;

import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";
    private static final int PERMISSION_REQUEST = 1001;

    private boolean permissionGranted = false;
    private int displayWidth = 1080;
    private int displayHeight = 2400;

    public interface PermissionListener {
        void onGranted();
        void onDenied();
    }

    private PermissionListener permissionListener;

    public void setPermissionListener(PermissionListener l) { this.permissionListener = l; }

    private final Shizuku.OnRequestPermissionResultListener permListener =
        (requestCode, grantResult) -> {
            if (requestCode != PERMISSION_REQUEST) return;
            permissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "Permission result: " + permissionGranted);
            if (permissionListener != null) {
                if (permissionGranted) permissionListener.onGranted();
                else permissionListener.onDenied();
            }
        };

    public void init() {
        Shizuku.addRequestPermissionResultListener(permListener);
        checkPermission();
    }

    public void checkPermission() {
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                permissionGranted = false;
                return;
            }
            permissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            permissionGranted = false;
        }
    }

    public boolean isAvailable() {
        try { return Shizuku.pingBinder(); }
        catch (Exception e) { return false; }
    }

    public boolean isPermissionGranted() { return permissionGranted; }

    public void requestPermission() {
        try {
            if (Shizuku.isPreV11()) return;
            if (permissionGranted) {
                if (permissionListener != null) permissionListener.onGranted();
                return;
            }
            Shizuku.requestPermission(PERMISSION_REQUEST);
        } catch (Exception e) {
            Log.e(TAG, "requestPermission error", e);
        }
    }

    public void setScreenSize(int w, int h) {
        this.displayWidth = w;
        this.displayHeight = h;
    }

    public void swipeAsync(float x1, float y1, float x2, float y2, int durationMs) {
        if (!permissionGranted) return;
        x1 = clamp(x1, 10, displayWidth - 10);
        y1 = clamp(y1, 10, displayHeight - 10);
        x2 = clamp(x2, 10, displayWidth - 10);
        y2 = clamp(y2, 10, displayHeight - 10);
        String cmd = String.format("input swipe %.0f %.0f %.0f %.0f %d",
                x1, y1, x2, y2, durationMs);
        try {
            String[] c = {"sh", "-c", cmd};
            Shizuku.newProcess(c, null, null);
        } catch (Exception e) {
            Log.e(TAG, "swipeAsync error", e);
        }
    }

    private float clamp(float v, float mn, float mx) {
        return Math.max(mn, Math.min(mx, v));
    }

    public void destroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
    }
}
