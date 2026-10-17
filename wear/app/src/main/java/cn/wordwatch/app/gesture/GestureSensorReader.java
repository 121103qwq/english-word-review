package cn.wordwatch.app.gesture;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import cn.wordwatch.app.gesture.core.SensorFrame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/** Fuses actual accelerometer and gyroscope callbacks on a private worker thread. */
public final class GestureSensorReader implements SensorEventListener, AutoCloseable {
    public interface Listener {
        void onFrame(SensorFrame frame, RateSnapshot rates);
        void onReaderStatus(String message, SensorInfo sensors);
        default void onRates(RateSnapshot rates, SensorInfo sensors) { }
    }

    public static final class SensorInfo {
        public final Sensor accelerometer;
        public final Sensor gyroscope;
        public final String identity;
        public final String description;

        SensorInfo(Sensor accelerometer, Sensor gyroscope) {
            this.accelerometer = accelerometer;
            this.gyroscope = gyroscope;
            this.description = describe(accelerometer) + "\n" + describe(gyroscope);
            this.identity = digest(Build.MANUFACTURER + "|" + Build.MODEL + "|" + description);
        }

        public boolean usable() { return accelerometer != null && gyroscope != null; }

        private static String describe(Sensor sensor) {
            if (sensor == null) return "缺少必需传感器";
            return sensor.getName() + " · " + sensor.getVendor() + " · v" + sensor.getVersion()
                    + " · " + sensor.getResolution();
        }
    }

    public static final class RateSnapshot {
        public final double accelerometerHz;
        public final double gyroscopeHz;
        public final double observedHz;
        public final long maxGapMillis;
        public final long accelerometerMaxGapMillis;
        public final long gyroscopeMaxGapMillis;

        RateSnapshot(double accelerometerHz, double gyroscopeHz, double observedHz,
                     long maxGapMillis, long accelerometerMaxGapMillis, long gyroscopeMaxGapMillis) {
            this.accelerometerHz = accelerometerHz;
            this.gyroscopeHz = gyroscopeHz;
            this.observedHz = observedHz;
            this.maxGapMillis = maxGapMillis;
            this.accelerometerMaxGapMillis = accelerometerMaxGapMillis;
            this.gyroscopeMaxGapMillis = gyroscopeMaxGapMillis;
        }

        public static RateSnapshot empty() { return new RateSnapshot(0, 0, 0, 0, 0, 0); }
    }

    private static final long MAX_FUSION_SKEW_NS = 60_000_000L;
    private final SensorManager manager;
    private final SensorInfo sensors;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private boolean registered;
    private final SensorTimestampPairer pairer = new SensorTimestampPairer(MAX_FUSION_SKEW_NS);
    private long accelTimestamp = -1, gyroTimestamp = -1, lastFrameTimestamp = -1;
    private long accelFirst = -1, accelLast = -1, gyroFirst = -1, gyroLast = -1;
    private long accelMaxGapNs, gyroMaxGapNs;
    private long frameFirst = -1, frameLast = -1, frameMaxGapNs;
    private int accelCount, gyroCount, frameCount;
    private long lastRatesCallbackNs;

    public GestureSensorReader(Context context, Listener listener, String threadName) {
        this.manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        this.sensors = inspect(context);
        this.thread = new HandlerThread(threadName);
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public SensorInfo sensorInfo() { return sensors; }

    public static SensorInfo inspect(Context context) {
        SensorManager service = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = service == null ? null : service.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscope = service == null ? null : service.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        return new SensorInfo(accelerometer, gyroscope);
    }

    public void start() {
        handler.post(() -> {
            if (registered) return;
            resetStatistics();
            pairer.reset();
            if (!sensors.usable() || manager == null) {
                listener.onReaderStatus("需要真实加速度计和陀螺仪；本设备目前不能进行校准或识别。", sensors);
                return;
            }
            boolean accel = registerPair(SensorManager.SENSOR_DELAY_FASTEST);
            if (!accel) accel = registerPair(5_000);
            if (!accel) accel = registerPair(20_000);
            registered = accel;
            if (!registered) {
                listener.onReaderStatus("系统未能同时启动两种传感器。", sensors);
                return;
            }
            listener.onReaderStatus("传感器已启动（请求 FASTEST，权限或设备不支持时依次回退到 5 ms、20 ms）；正在测量实际送达频率。", sensors);
        });
    }

    private boolean registerPair(int periodUs) {
        manager.unregisterListener(this);
        try {
            boolean accel = manager.registerListener(this, sensors.accelerometer, periodUs, handler);
            boolean gyro = manager.registerListener(this, sensors.gyroscope, periodUs, handler);
            if (accel && gyro) return true;
        } catch (SecurityException denied) {
            // A requested high sampling rate can be unavailable on a protected device; try a slower ordinary rate.
        }
        manager.unregisterListener(this);
        return false;
    }

    public void stop() {
        if (handler == null) return;
        handler.post(() -> {
            if (manager != null) manager.unregisterListener(this);
            registered = false;
            accelTimestamp = gyroTimestamp = lastFrameTimestamp = -1;
            pairer.reset();
        });
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!registered || event == null || event.values == null || event.values.length < 3) return;
        long timestamp = event.timestamp;
        List<SensorFrame> frames;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (timestamp <= accelTimestamp) return;
            if (accelLast > 0) {
                long gap = timestamp - accelLast;
                accelMaxGapNs = Math.max(accelMaxGapNs, gap);
            }
            if (accelFirst < 0) accelFirst = timestamp;
            accelLast = timestamp; accelTimestamp = timestamp; accelCount++;
            frames = pairer.addAccelerometer(timestamp, event.values[0], event.values[1], event.values[2]);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (timestamp <= gyroTimestamp) return;
            if (gyroLast > 0) {
                long gap = timestamp - gyroLast;
                gyroMaxGapNs = Math.max(gyroMaxGapNs, gap);
            }
            if (gyroFirst < 0) gyroFirst = timestamp;
            gyroLast = timestamp; gyroTimestamp = timestamp; gyroCount++;
            frames = pairer.addGyroscope(timestamp, event.values[0], event.values[1], event.values[2]);
        } else return;

        for (SensorFrame frame : frames) {
            long frameTimestamp = frame.timestampNanos();
            if (frameTimestamp <= lastFrameTimestamp) continue;
            if (frameLast > 0) frameMaxGapNs = Math.max(frameMaxGapNs, frameTimestamp - frameLast);
            if (frameFirst < 0) frameFirst = frameTimestamp;
            frameLast = lastFrameTimestamp = frameTimestamp; frameCount++;
            listener.onFrame(frame, rateSnapshot());
        }
        notifyRatesIfDue();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    public RateSnapshot rateSnapshot() {
        return new RateSnapshot(hz(accelCount, accelFirst, accelLast), hz(gyroCount, gyroFirst, gyroLast),
                hz(frameCount, frameFirst, frameLast), ceilMillis(Math.max(frameMaxGapNs, Math.max(accelMaxGapNs, gyroMaxGapNs))),
                ceilMillis(accelMaxGapNs), ceilMillis(gyroMaxGapNs));
    }

    private void resetStatistics() {
        accelFirst = accelLast = gyroFirst = gyroLast = frameFirst = frameLast = -1;
        accelMaxGapNs = gyroMaxGapNs = frameMaxGapNs = 0;
        accelCount = gyroCount = frameCount = 0;
        lastRatesCallbackNs = -1;
    }

    private void notifyRatesIfDue() {
        long observedThroughNs = Math.max(accelTimestamp, gyroTimestamp);
        if (lastRatesCallbackNs >= 0 && observedThroughNs - lastRatesCallbackNs < 1_000_000_000L) return;
        lastRatesCallbackNs = observedThroughNs;
        listener.onRates(rateSnapshot(), sensors);
    }

    private static double hz(int count, long first, long last) {
        return count < 2 || first < 0 || last <= first ? 0.0 : (count - 1) * 1_000_000_000.0 / (last - first);
    }

    private static long ceilMillis(long nanos) { return nanos <= 0 ? 0 : (nanos + 999_999L) / 1_000_000L; }
    private static String digest(String value) {
        try {
            byte[] data = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : data) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    @Override public void close() {
        stop();
        thread.quitSafely();
    }
}
