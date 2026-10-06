package com.aimassist.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aimassist.pro.gyro.GyroController;
import com.aimassist.pro.util.Prefs;

public class AssistService extends AccessibilityService implements GyroController.Listener {

    private static final String TAG = "AssistService";
    public static AssistService instance;
    private static ShizukuBridge shizuku;

    private GyroController gyro;
    private WindowManager wm;
    private LinearLayout panel;
    private TextView statusView;
    private Button gyroBtn;
    private WindowManager.LayoutParams panelParams;
    private boolean panelVisible = false;
    private boolean gyroEnabled = false;
    private String targetPackage = "";
    private float aimXPercent = 0.5f;
    private float aimYPercent = 0.5f;
    private int screenW, screenH;

    private long lastSwipeTime = 0;
    private static final long MIN_GAP = 30;
    private static final long SWIPE_DUR = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Prefs prefs = new Prefs();

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) stopAll("الشاشة مغلقة");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs.init(this);

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);

        gyro = new GyroController(this);
        gyro.setListener(this);
        loadSettings();
        updateScreenSize();
        showPanel();
    }

    private void loadSettings() {
        targetPackage = prefs.getTargetPackage();
        gyro.setSensitivity(prefs.getGyroSensitivity());
        gyro.setSmoothing(prefs.getGyroSmoothing());
        gyro.setDeadzone(prefs.getGyroDeadzone());
        aimXPercent = prefs.getAimX();
        aimYPercent = prefs.getAimY();
    }

    private void updateScreenSize() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        screenW = m.widthPixels;
        screenH = m.heightPixels;
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateScreenSize();
        if (gyro != null) gyro.setRotation(wm.getDefaultDisplay().getRotation());
    }

    @Override public void onDestroy() {
        hidePanel();
        stopAll("الخدمة متوقفة");
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (gyro != null) gyro.stop();
        handler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    @Override public void onInterrupt() { stopAll("الخدمة قوطعت"); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (gyroEnabled && !isTargetForeground()) stopAll("خرجت من اللعبة");
    }

    public static void setShizukuBridge(ShizukuBridge b) { shizuku = b; }

    private boolean isTargetForeground() {
        if (targetPackage.isEmpty()) return false;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (!w.isActive() && !w.isFocused()) continue;
            android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            CharSequence p = r.getPackageName();
            r.recycle();
            if (p != null && targetPackage.contentEquals(p)) return true;
        }
        return false;
    }

    @Override public void onAimMove(float dx, float dy) {
        if (!gyroEnabled || !isTargetForeground()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSwipeTime < MIN_GAP) return;
        lastSwipeTime = now;
        if (shizuku == null || !shizuku.isPermissionGranted()) return;

        float cx = screenW * aimXPercent, cy = screenH * aimYPercent;
        float s = 80f;
        cx = clamp(cx, s, screenW - s); cy = clamp(cy, s, screenH - s);
        float ex = clamp(cx + dx, s, screenW - s);
        float ey = clamp(cy + dy, s, screenH - s);
        if (Math.hypot(ex - cx, ey - cy) < 2f) return;
        shizuku.swipeAsync(cx, cy, ex, ey, (int) SWIPE_DUR);
    }

    @Override public void onCalibrationProgress(int s, int t) {
        if (s % 30 == 0) message("معايرة: " + s + "/" + t);
    }
    @Override public void onCalibrationComplete() { message("اكتملت المعايرة - حرك الجهاز"); }
    @Override public void onError(String m) {
        message("خطأ: " + m);
        gyroEnabled = false;
        updateGyroBtn();
    }

    private void showPanel() {
        if (panelVisible) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC0F1F2E);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView title = new TextView(this);
        title.setText("AimAssist Pro");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setPadding(dp(4), dp(4), dp(4), dp(6));
        panel.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF72EDC5);
        statusView.setTextSize(11);
        statusView.setText("جاهز");
        statusView.setPadding(dp(4), 0, dp(4), dp(6));
        panel.addView(statusView);

        gyroBtn = mkBtn("جيروسكوب: إيقاف");
        gyroBtn.setOnClickListener(v -> toggleGyro());
        panel.addView(gyroBtn);

        Button closeBtn = mkBtn("إخفاء");
        closeBtn.setOnClickListener(v -> hidePanel());
        panel.addView(closeBtn);

        panelParams = new WindowManager.LayoutParams(
            dp(200), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(12);
        panelParams.y = dp(60);

        title.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int px, py;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        px = panelParams.x; py = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = (int)(px + e.getRawX() - sx);
                        panelParams.y = (int)(py + e.getRawY() - sy);
                        wm.updateViewLayout(panel, panelParams);
                        return true;
                }
                return false;
            }
        });

        try { wm.addView(panel, panelParams); panelVisible = true; }
        catch (Exception e) { Log.e(TAG, "panel error", e); }
    }

    private void hidePanel() {
        if (!panelVisible || panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) {}
        panel = null; statusView = null; panelVisible = false;
    }

    private Button mkBtn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(11); b.setAllCaps(false);
        b.setMinHeight(0); b.setMinimumHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lp.setMargins(0, dp(2), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void message(String s) {
        if (statusView != null) statusView.setText(s);
        Log.d(TAG, s);
    }

    private void toggleGyro() {
        if (gyroEnabled) {
            gyro.stop(); gyroEnabled = false; message("جيروسكوب: إيقاف");
        } else {
            if (shizuku == null || !shizuku.isPermissionGranted()) {
                message("Shizuku غير مفعل"); return;
            }
            if (targetPackage.isEmpty()) { message("اختر لعبة اولا"); return; }
            if (!gyro.isAvailable()) { message("لا يوجد جيروسكوب"); return; }
            gyroEnabled = gyro.start();
            message(gyroEnabled ? "معايرة... ثبت الجهاز" : "فشل التشغيل");
        }
        updateGyroBtn();
    }

    private void updateGyroBtn() {
        if (gyroBtn != null) gyroBtn.setText(gyroEnabled ? "جيروسكوب: تشغيل" : "جيروسكوب: إيقاف");
    }

    private void stopAll(String r) {
        if (gyroEnabled) { gyro.stop(); gyroEnabled = false; updateGyroBtn(); }
        message(r);
    }

    private float clamp(float v, float mn, float mx) { return Math.max(mn, Math.min(mx, v)); }

    public void refreshSettings() {
        loadSettings();
        if (gyro != null) {
            gyro.setSensitivity(prefs.getGyroSensitivity());
            gyro.setSmoothing(prefs.getGyroSmoothing());
            gyro.setDeadzone(prefs.getGyroDeadzone());
        }
        message("تم تحديث الاعدادات");
    }

    public void showPanelAgain() { if (!panelVisible) showPanel(); }
}
