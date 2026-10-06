package com.aimassist.pro.gyro;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

public class GyroController implements SensorEventListener {
    public interface Listener {
        void onAimMove(float dx, float dy);
        void onCalibrationProgress(int samples, int total);
        void onCalibrationComplete();
        void onError(String message);
    }

    private final SensorManager sm;
    private final Sensor gyro;
    private final Handler handler;
    private Listener listener;
    private final KalmanFilter1D kfYaw = new KalmanFilter1D(0.005f, 0.08f);
    private final KalmanFilter1D kfPitch = new KalmanFilter1D(0.005f, 0.08f);
    private static final int CAL_N = 150;
    private int calCount = 0;
    private float calSumY = 0, calSumP = 0;
    private float biasY = 0, biasP = 0;
    private boolean calibrated = false;
    private boolean running = false;
    private long lastT = 0;
    private float accX = 0, accY = 0;
    private float sensitivity = 1.5f;
    private float smoothing = 0.15f;
    private float deadzone = 0.02f;
    private int rotation = 0;

    public GyroController(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        handler = new Handler(Looper.getMainLooper());
    }
    public boolean isAvailable() { return gyro != null; }
    public void setListener(Listener l) { listener = l; }
    public void setSensitivity(float s) { sensitivity = s; }
    public void setSmoothing(float s) { smoothing = s; }
    public void setDeadzone(float d) { deadzone = d; }
    public void setRotation(int r) { rotation = r; }
    public boolean isRunning() { return running; }

    public boolean start() {
        if (gyro == null) { if (listener != null) listener.onError("لا يوجد جيروسكوب"); return false; }
        if (running) return true;
        calCount = 0; calSumY = 0; calSumP = 0; calibrated = false;
        kfYaw.reset(); kfPitch.reset();
        accX = 0; accY = 0; lastT = 0;
        running = sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, handler);
        return running;
    }
    public void stop() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false; accX = 0; accY = 0; lastT = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        long now = SystemClock.elapsedRealtimeNanos();
        if (lastT == 0) { lastT = now; return; }
        float dt = (now - lastT) / 1_000_000_000f;
        lastT = now;
        if (dt <= 0 || dt > 0.1f) return;

        float rp = e.values[0], ry = e.values[2];
        float y, p;
        switch (rotation) {
            case Surface.ROTATION_90: y = rp; p = -ry; break;
            case Surface.ROTATION_180: y = -ry; p = -rp; break;
            case Surface.ROTATION_270: y = -rp; p = ry; break;
            default: y = ry; p = rp; break;
        }

        if (!calibrated) {
            calSumY += y; calSumP += p; calCount++;
            if (listener != null) listener.onCalibrationProgress(calCount, CAL_N);
            if (calCount >= CAL_N) {
                biasY = calSumY / CAL_N;
                biasP = calSumP / CAL_N;
                calibrated = true;
                if (listener != null) listener.onCalibrationComplete();
            }
            return;
        }

        float cy = y - biasY, cp = p - biasP;
        cy = dz(cy); cp = dz(cp);
        float fy = kfYaw.update(cy);
        float fp = kfPitch.update(cp);
        float scale = sensitivity * 1000f;
        float dx = -fy * dt * scale;
        float dy = -fp * dt * scale;
        dx *= (1f - smoothing); dy *= (1f - smoothing);
        accX += dx; accY += dy;
        if ((float) Math.hypot(accX, accY) >= 3f) {
            float sx = clamp(accX, -30, 30);
            float sy = clamp(accY, -30, 30);
            if (listener != null) listener.onAimMove(sx, sy);
            accX -= sx; accY -= sy;
        }
    }

    private float dz(float v) {
        if (Math.abs(v) < deadzone) return 0f;
        return Math.signum(v) * (Math.abs(v) - deadzone);
    }
    private float clamp(float v, float mn, float mx) { return Math.max(mn, Math.min(mx, v)); }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
