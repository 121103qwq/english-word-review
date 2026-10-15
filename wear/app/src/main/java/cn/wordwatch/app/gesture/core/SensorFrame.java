package cn.wordwatch.app.gesture.core;

/** One immutable, timestamped accelerometer and gyroscope observation. */
public final class SensorFrame {
    private final long timestampNanos;
    private final double accelX;
    private final double accelY;
    private final double accelZ;
    private final double gyroX;
    private final double gyroY;
    private final double gyroZ;

    public SensorFrame(long timestampNanos, double accelX, double accelY, double accelZ,
                       double gyroX, double gyroY, double gyroZ) {
        if (timestampNanos < 0) throw new IllegalArgumentException("timestampNanos must be non-negative");
        requireFinite(accelX, "accelX");
        requireFinite(accelY, "accelY");
        requireFinite(accelZ, "accelZ");
        requireFinite(gyroX, "gyroX");
        requireFinite(gyroY, "gyroY");
        requireFinite(gyroZ, "gyroZ");
        this.timestampNanos = timestampNanos;
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
    }

    public long timestampNanos() { return timestampNanos; }
    public double accelX() { return accelX; }
    public double accelY() { return accelY; }
    public double accelZ() { return accelZ; }
    public double gyroX() { return gyroX; }
    public double gyroY() { return gyroY; }
    public double gyroZ() { return gyroZ; }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        if (Math.abs(value) > 1_000_000.0) throw new IllegalArgumentException(name + " is out of range");
    }
}
