package cn.wordwatch.app.gesture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.wordwatch.app.gesture.core.SensorFrame;

/** Pairs queued accelerometer observations with the nearest real gyroscope observation. */
final class SensorTimestampPairer {
    private static final int MAX_ACCELEROMETER_BUFFER = 128;
    private static final int MAX_GYROSCOPE_BUFFER = 256;

    private final long maximumSkewNs;
    private final ArrayDeque<VectorSample> accelerometers = new ArrayDeque<>();
    private final ArrayDeque<VectorSample> gyroscopes = new ArrayDeque<>();
    private long lastAccelerometerTimestamp = -1;
    private long lastGyroscopeTimestamp = -1;
    private long lastFrameTimestamp = -1;

    SensorTimestampPairer(long maximumSkewNs) {
        if (maximumSkewNs < 0) throw new IllegalArgumentException("maximumSkewNs must be non-negative");
        this.maximumSkewNs = maximumSkewNs;
    }

    List<SensorFrame> addAccelerometer(long timestampNs, float x, float y, float z) {
        if (timestampNs <= lastAccelerometerTimestamp) return Collections.emptyList();
        lastAccelerometerTimestamp = timestampNs;
        if (accelerometers.size() == MAX_ACCELEROMETER_BUFFER) accelerometers.removeFirst();
        accelerometers.addLast(new VectorSample(timestampNs, x, y, z));
        return drainReadyPairs();
    }

    List<SensorFrame> addGyroscope(long timestampNs, float x, float y, float z) {
        if (timestampNs <= lastGyroscopeTimestamp) return Collections.emptyList();
        lastGyroscopeTimestamp = timestampNs;
        if (gyroscopes.size() == MAX_GYROSCOPE_BUFFER) gyroscopes.removeFirst();
        gyroscopes.addLast(new VectorSample(timestampNs, x, y, z));
        return drainReadyPairs();
    }

    void reset() {
        accelerometers.clear();
        gyroscopes.clear();
        lastAccelerometerTimestamp = lastGyroscopeTimestamp = lastFrameTimestamp = -1;
    }

    private List<SensorFrame> drainReadyPairs() {
        if (accelerometers.isEmpty() || gyroscopes.isEmpty()) return Collections.emptyList();
        ArrayList<SensorFrame> output = null;
        while (!accelerometers.isEmpty()) {
            VectorSample accel = accelerometers.peekFirst();
            VectorSample latestGyro = gyroscopes.peekLast();
            // A later timestamp is needed before a queued accel can be paired. The closest
            // candidate is then either the previous gyro or the first gyro at/after accel.
            if (latestGyro.timestampNs < accel.timestampNs) break;

            VectorSample nearest = nearestGyroscope(accel.timestampNs);
            accelerometers.removeFirst();
            if (nearest == null || distanceNs(nearest.timestampNs, accel.timestampNs) > maximumSkewNs
                    || accel.timestampNs <= lastFrameTimestamp) {
                continue;
            }

            if (output == null) output = new ArrayList<>();
            output.add(new SensorFrame(accel.timestampNs, accel.x, accel.y, accel.z,
                    nearest.x, nearest.y, nearest.z));
            lastFrameTimestamp = accel.timestampNs;
        }
        pruneGyroscopeHistory();
        return output == null ? Collections.emptyList() : output;
    }

    private VectorSample nearestGyroscope(long timestampNs) {
        VectorSample nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (VectorSample candidate : gyroscopes) {
            long distance = distanceNs(candidate.timestampNs, timestampNs);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
            if (candidate.timestampNs > timestampNs && distance > nearestDistance) break;
        }
        return nearest;
    }

    private void pruneGyroscopeHistory() {
        if (gyroscopes.size() < 2) return;
        long referenceTimestamp = accelerometers.isEmpty()
                ? lastGyroscopeTimestamp : accelerometers.peekFirst().timestampNs;
        long cutoff = referenceTimestamp - maximumSkewNs;
        while (gyroscopes.size() > 1) {
            java.util.Iterator<VectorSample> iterator = gyroscopes.iterator();
            iterator.next();
            VectorSample second = iterator.next();
            if (second.timestampNs >= cutoff) break;
            gyroscopes.removeFirst();
        }
    }

    private static long distanceNs(long first, long second) {
        return first >= second ? first - second : second - first;
    }

    private static final class VectorSample {
        final long timestampNs;
        final float x, y, z;

        VectorSample(long timestampNs, float x, float y, float z) {
            this.timestampNs = timestampNs;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
