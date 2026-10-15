package cn.wordwatch.app.gesture.core;

import java.util.List;

/**
 * Fixed-size feature extraction shared by training and runtime. Only the active part of a segment
 * (relative activity envelope, so pre-roll and quiet tail are dropped) is resampled to 32 points;
 * the shape is then scaled to unit RMS so light and forceful versions of one gesture match. The
 * last slot keeps ln(shape RMS) as a weak amplitude cue.
 */
final class GestureFeatures {
    static final long MAX_FRAME_GAP_NANOS = GestureDetector.MAX_SENSOR_GAP_NANOS;
    static final int POINTS = 32;
    static final int RAW_DIMENSIONS = 6;
    static final int DIMENSIONS = RAW_DIMENSIONS * 2;
    static final int SHAPE_SIZE = POINTS * DIMENSIONS;
    static final int LOG_ENERGY_INDEX = SHAPE_SIZE;
    static final int VECTOR_SIZE = SHAPE_SIZE + 1;
    private static final double ACCEL_SCALE = 4.0;
    private static final double GYRO_SCALE = 5.0;
    private static final long HIGH_PASS_TAU_NANOS = 30_000_000L;
    private static final long ENVELOPE_TAU_NANOS = 20_000_000L;
    private static final double ACTIVE_FRACTION = 0.20;
    private static final long ACTIVE_PAD_NANOS = 40_000_000L;
    private static final double ENERGY_FLOOR = 0.002;
    private static final double LOG_ENERGY_WEIGHT = 0.05;

    private GestureFeatures() {}

    static double[] extract(List<SensorFrame> frames) {
        if (frames == null || frames.size() < GestureSample.MIN_FRAMES || frames.size() > GestureSample.MAX_FRAMES) return null;
        if (!validTimes(frames)) return null;
        if (frames.get(frames.size() - 1).timestampNanos() - frames.get(0).timestampNanos()
                < GestureSample.MIN_DURATION_NANOS) return null;
        double[] out = resample(activeWindow(frames));
        double sum = 0.0;
        for (int i = 0; i < SHAPE_SIZE; i++) sum += out[i] * out[i];
        double energy = Math.max(ENERGY_FLOOR, Math.sqrt(sum / SHAPE_SIZE));
        for (int i = 0; i < SHAPE_SIZE; i++) out[i] /= energy;
        out[LOG_ENERGY_INDEX] = Math.log(energy);
        return out;
    }

    /** Frames between the first and last point where the activity envelope reaches 20% of its peak. */
    static List<SensorFrame> activeWindow(List<SensorFrame> frames) {
        int count = frames.size();
        double[] envelope = activityEnvelope(frames);
        double peak = 0.0;
        for (double value : envelope) peak = Math.max(peak, value);
        if (peak <= 0.0) return frames;
        double threshold = peak * ACTIVE_FRACTION;
        int first = 0;
        while (first < count - 1 && envelope[first] < threshold) first++;
        int last = count - 1;
        while (last > first && envelope[last] < threshold) last--;
        long start = frames.get(first).timestampNanos() - ACTIVE_PAD_NANOS;
        long end = frames.get(last).timestampNanos() + ACTIVE_PAD_NANOS;
        int from = first;
        while (from > 0 && frames.get(from - 1).timestampNanos() >= start) from--;
        int to = last;
        while (to < count - 1 && frames.get(to + 1).timestampNanos() <= end) to++;
        if (to - from + 1 < GestureSample.MIN_FRAMES
                || frames.get(to).timestampNanos() - frames.get(from).timestampNanos() < GestureSample.MIN_DURATION_NANOS) {
            return frames;
        }
        return frames.subList(from, to + 1);
    }

    /** Six-axis high-pass activity (30 ms) smoothed by a 20 ms envelope, one value per frame. */
    static double[] activityEnvelope(List<SensorFrame> frames) {
        double[] out = new double[frames.size()];
        double[] smooth = new double[RAW_DIMENSIONS];
        for (int dim = 0; dim < RAW_DIMENSIONS; dim++) smooth[dim] = channel(frames.get(0), dim);
        double envelope = 0.0;
        for (int i = 1; i < frames.size(); i++) {
            long dt = frames.get(i).timestampNanos() - frames.get(i - 1).timestampNanos();
            double alpha = -Math.expm1(-dt / (double) HIGH_PASS_TAU_NANOS);
            double sum = 0.0;
            for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
                double raw = channel(frames.get(i), dim);
                smooth[dim] += alpha * (raw - smooth[dim]);
                double value = (raw - smooth[dim]) / (dim < 3 ? ACCEL_SCALE : GYRO_SCALE);
                sum += value * value;
            }
            envelope += -Math.expm1(-dt / (double) ENVELOPE_TAU_NANOS) * (Math.sqrt(sum / RAW_DIMENSIONS) - envelope);
            out[i] = envelope;
        }
        return out;
    }

    private static double[] resample(List<SensorFrame> frames) {
        double[] out = new double[VECTOR_SIZE];
        SensorFrame first = frames.get(0);
        long startTime = first.timestampNanos();
        long endTime = frames.get(frames.size() - 1).timestampNanos();
        long span = endTime - startTime;
        if (span < GestureSample.MIN_DURATION_NANOS) return null;
        double[] binStartValues = new double[RAW_DIMENSIONS];
        double[] binEndValues = new double[RAW_DIMENSIONS];
        int cursor = 0;
        for (int point = 0; point < POINTS; point++) {
            long binStart = startTime + span * point / POINTS;
            long binEnd = startTime + span * (point + 1L) / POINTS;
            if (binEnd <= binStart) binEnd = Math.min(endTime, binStart + 1L);
            int leftIndex = segmentAt(frames, binStart, cursor);
            int endIndex = segmentAt(frames, binEnd, leftIndex);
            for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
                binStartValues[dim] = valueAt(frames, binStart, dim, leftIndex);
                binEndValues[dim] = valueAt(frames, binEnd, dim, endIndex);
            }

            double[] meanIntegral = new double[RAW_DIMENSIONS];
            double[] residualIntegral = new double[RAW_DIMENSIONS];
            double totalTime = 0.0;
            for (int i = leftIndex; i < frames.size() - 1 && frames.get(i).timestampNanos() < binEnd; i++) {
                SensorFrame aFrame = frames.get(i);
                SensorFrame bFrame = frames.get(i + 1);
                long aTime = Math.max(binStart, aFrame.timestampNanos());
                long bTime = Math.min(binEnd, bFrame.timestampNanos());
                if (bTime <= aTime) continue;
                double dt = bTime - aTime;
                totalTime += dt;
                double binDuration = Math.max(1.0, binEnd - binStart);
                double startFraction = (aTime - binStart) / binDuration;
                double endFraction = (bTime - binStart) / binDuration;
                for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
                    double valueA = interpolate(aFrame, bFrame, aTime, dim);
                    double valueB = interpolate(aFrame, bFrame, bTime, dim);
                    meanIntegral[dim] += (valueA + valueB) * 0.5 * dt;
                    double baselineA = binStartValues[dim] + (binEndValues[dim] - binStartValues[dim]) * startFraction;
                    double baselineB = binStartValues[dim] + (binEndValues[dim] - binStartValues[dim]) * endFraction;
                    double residualA = valueA - baselineA;
                    double residualB = valueB - baselineB;
                    residualIntegral[dim] += (residualA * residualA + residualA * residualB
                            + residualB * residualB) * dt / 3.0;
                }
            }
            if (totalTime <= 0.0) totalTime = Math.max(1.0, binEnd - binStart);
            for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
                double scale = dim < 3 ? ACCEL_SCALE : GYRO_SCALE;
                double mean = meanIntegral[dim] / totalTime;
                double rms = Math.sqrt(Math.max(0.0, residualIntegral[dim] / totalTime));
                out[point * DIMENSIONS + dim] = (mean - channel(first, dim)) / scale;
                out[point * DIMENSIONS + RAW_DIMENSIONS + dim] = rms / scale;
            }
            cursor = Math.max(leftIndex, endIndex - 1);
        }
        return out;
    }

    static double peak(List<SensorFrame> frames) {
        SensorFrame first = frames.get(0);
        double peak = 0.0;
        for (SensorFrame frame : frames) {
            double sum = 0.0;
            for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
                double scale = dim < 3 ? ACCEL_SCALE : GYRO_SCALE;
                double value = (channel(frame, dim) - channel(first, dim)) / scale;
                sum += value * value;
            }
            peak = Math.max(peak, Math.sqrt(sum / RAW_DIMENSIONS));
        }
        return peak;
    }

    static double change(SensorFrame previous, SensorFrame current) {
        double sum = 0.0;
        double elapsedSeconds = (current.timestampNanos() - previous.timestampNanos()) / 1_000_000_000.0;
        if (elapsedSeconds <= 0.0 || elapsedSeconds > 0.25) return 0.0;
        for (int dim = 0; dim < RAW_DIMENSIONS; dim++) {
            double scale = dim < 3 ? ACCEL_SCALE : GYRO_SCALE;
            double delta = (channel(current, dim) - channel(previous, dim)) / scale / elapsedSeconds;
            sum += delta * delta;
        }
        return Math.sqrt(sum / RAW_DIMENSIONS);
    }

    static double distance(double[] a, double[] b,
                           double[] previousCost, double[] currentCost,
                           int[] previousLength, int[] currentLength) {
        final int points = POINTS;
        java.util.Arrays.fill(previousCost, 0, points + 1, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(previousLength, 0, points + 1, Integer.MAX_VALUE);
        previousCost[0] = 0.0;
        previousLength[0] = 0;
        for (int i = 1; i <= points; i++) {
            currentCost[0] = Double.POSITIVE_INFINITY;
            currentLength[0] = Integer.MAX_VALUE;
            for (int j = 1; j <= points; j++) {
                double local = localCost(a, i - 1, b, j - 1);
                double best = previousCost[j - 1];
                int length = previousLength[j - 1];
                if (previousCost[j] < best) { best = previousCost[j]; length = previousLength[j]; }
                if (currentCost[j - 1] < best) { best = currentCost[j - 1]; length = currentLength[j - 1]; }
                currentCost[j] = best + local;
                currentLength[j] = length + 1;
            }
            double[] costSwap = previousCost; previousCost = currentCost; currentCost = costSwap;
            int[] lengthSwap = previousLength; previousLength = currentLength; currentLength = lengthSwap;
        }
        int length = previousLength[points];
        if (length <= 0 || !Double.isFinite(previousCost[points])) return Double.POSITIVE_INFINITY;
        return previousCost[points] / length
                + LOG_ENERGY_WEIGHT * Math.abs(a[LOG_ENERGY_INDEX] - b[LOG_ENERGY_INDEX]);
    }

    static boolean validTimes(List<SensorFrame> frames) {
        long last = -1;
        for (SensorFrame frame : frames) {
            if (frame == null || frame.timestampNanos() <= last) return false;
            if (last >= 0 && frame.timestampNanos() - last > MAX_FRAME_GAP_NANOS) return false;
            last = frame.timestampNanos();
        }
        return true;
    }

    private static double localCost(double[] a, int pointA, double[] b, int pointB) {
        int offsetA = pointA * DIMENSIONS;
        int offsetB = pointB * DIMENSIONS;
        double sum = 0.0;
        for (int dim = 0; dim < DIMENSIONS; dim++) {
            double delta = a[offsetA + dim] - b[offsetB + dim];
            sum += delta * delta;
        }
        return Math.sqrt(sum / DIMENSIONS);
    }

    private static int segmentAt(List<SensorFrame> frames, long timestamp, int hint) {
        int index = Math.min(Math.max(0, hint), frames.size() - 2);
        while (index > 0 && frames.get(index).timestampNanos() > timestamp) index--;
        while (index < frames.size() - 2 && frames.get(index + 1).timestampNanos() < timestamp) index++;
        return index;
    }

    private static double valueAt(List<SensorFrame> frames, long timestamp, int dim, int segment) {
        SensorFrame a = frames.get(segment);
        SensorFrame b = frames.get(segment + 1);
        return interpolate(a, b, timestamp, dim);
    }

    private static double interpolate(SensorFrame a, SensorFrame b, long timestamp, int dim) {
        long duration = b.timestampNanos() - a.timestampNanos();
        double fraction = duration <= 0 ? 0.0 : (timestamp - a.timestampNanos()) / (double) duration;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        double start = channel(a, dim);
        return start + (channel(b, dim) - start) * fraction;
    }

    static double channel(SensorFrame frame, int dim) {
        switch (dim) {
            case 0: return frame.accelX();
            case 1: return frame.accelY();
            case 2: return frame.accelZ();
            case 3: return frame.gyroX();
            case 4: return frame.gyroY();
            default: return frame.gyroZ();
        }
    }
}
