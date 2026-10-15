package cn.wordwatch.app.gesture.core;

import java.util.List;

/** Signal gates applied before a six-axis template is allowed to name a gesture. */
public final class GestureSignalQuality {
    private static final long MAX_SAMPLE_GAP_NANOS = 120_000_000L;
    private static final long FILTER_TAU_NANOS = 30_000_000L;
    private static final long ENVELOPE_TAU_NANOS = 20_000_000L;
    private static final long RING_MERGE_GAP_NANOS = 90_000_000L;
    private static final long MAX_DOUBLE_PULSE_GAP_NANOS = 400_000_000L;
    private static final long RELATIVE_PEAK_REFRACTORY_NANOS = 40_000_000L;
    private static final long MIN_RELATIVE_VALLEY_NANOS = 15_000_000L;
    private static final double MIN_ACTIVITY = 0.035;
    private static final double SNR_GATE = 4.5;
    private static final double PULSE_ON_RATIO = 4.5;
    private static final double PULSE_OFF_RATIO = 2.0;
    private static final double RELATIVE_PEAK_FRACTION = 0.15;
    private static final double RELATIVE_VALLEY_FRACTION = 0.55;

    private GestureSignalQuality() {}

    /**
     * Measures six-axis motion above this watch's personal still noise. Pulse count is based on a
     * hysteretic high-pass energy envelope; short gaps inside one impact's ringing are merged.
     */
    public static Result evaluate(GestureKind kind, List<SensorFrame> frames,
                                  GestureNoiseProfile profile) {
        if (kind == null || kind == GestureKind.UNKNOWN) return rejected("需要有效的手势类型", 0, 0.0);
        if (profile == null) return rejected("需要先完成静止噪声校准", 0, 0.0);
        if (frames == null || frames.size() < 8 || frames.size() > GestureSample.MAX_FRAMES) {
            return rejected("候选数据帧数不符合要求", 0, 0.0);
        }

        SensorFrame first = frames.get(0);
        if (first == null) return rejected("候选数据包含空帧", 0, 0.0);
        long startNanos = first.timestampNanos();
        long previousNanos = startNanos;
        long spanNanos;
        double[] smooth = new double[GestureNoiseProfile.AXES];
        double[] axisEnergy = new double[GestureNoiseProfile.AXES];
        double[] axisPeak = new double[GestureNoiseProfile.AXES];
        for (int axis = 0; axis < smooth.length; axis++) smooth[axis] = GestureFeatures.channel(first, axis);

        ArrayListEnvelope envelope = new ArrayListEnvelope(frames.size());
        double[][] highPassSeries = new double[GestureNoiseProfile.AXES][frames.size()];
        long[] times = new long[frames.size()];
        double maxEnvelope = 0.0;
        double energySum = 0.0;
        int validIntervals = 0;
        for (int i = 1; i < frames.size(); i++) {
            SensorFrame frame = frames.get(i);
            if (frame == null) return rejected("候选数据包含空帧", 0, 0.0);
            long time = frame.timestampNanos();
            long dtNanos = time - previousNanos;
            if (dtNanos <= 0L) return rejected("候选数据时间戳必须递增", 0, 0.0);
            if (dtNanos > MAX_SAMPLE_GAP_NANOS) return rejected("候选数据存在传感器采样间断", 0, 0.0);
            double alpha = -Math.expm1(-dtNanos / (double) FILTER_TAU_NANOS);
            double sumSquares = 0.0;
            for (int axis = 0; axis < smooth.length; axis++) {
                double raw = GestureFeatures.channel(frame, axis);
                double prior = smooth[axis];
                double filtered = prior + alpha * (raw - prior);
                double highPass = raw - filtered;
                smooth[axis] = filtered;
                double scale = axis < 3 ? 4.0 : 5.0;
                double normalized = highPass / scale;
                sumSquares += normalized * normalized;
                axisEnergy[axis] += normalized * normalized * dtNanos;
                axisPeak[axis] = Math.max(axisPeak[axis], Math.abs(normalized));
                highPassSeries[axis][i] = normalized;
            }
            double activity = Math.sqrt(sumSquares / smooth.length);
            double envelopeAlpha = -Math.expm1(-dtNanos / (double) ENVELOPE_TAU_NANOS);
            double filteredActivity = envelope.value + envelopeAlpha * (activity - envelope.value);
            envelope.value = filteredActivity;
            envelope.samples[i] = filteredActivity;
            times[i] = time;
            maxEnvelope = Math.max(maxEnvelope, filteredActivity);
            energySum += activity * activity * dtNanos;
            validIntervals++;
            previousNanos = time;
        }
        spanNanos = previousNanos - startNanos;
        if (spanNanos < GestureSample.MIN_DURATION_NANOS || spanNanos > GestureSample.MAX_DURATION_NANOS) {
            return rejected("候选动作时长不符合要求", 0, 0.0);
        }
        double noise = Math.max(0.002, profile.normalizedNoiseRms());
        double signalToNoise = maxEnvelope / noise;
        if (maxEnvelope < Math.max(MIN_ACTIVITY, noise * SNR_GATE) || signalToNoise < SNR_GATE) {
            return new Result(false, "动作信号没有明显高于个人静止噪声", 0, signalToNoise);
        }

        double on = Math.max(MIN_ACTIVITY, noise * PULSE_ON_RATIO);
        double off = Math.max(MIN_ACTIVITY * 0.45, noise * PULSE_OFF_RATIO);
        PulseSummary pulses = countPulses(envelope.samples, times, frames.size(), on, off);
        int activeAxes = 0;
        for (int axis = 0; axis < axisEnergy.length; axis++) {
            double rms = Math.sqrt(axisEnergy[axis] / Math.max(1.0, spanNanos));
            double axisNoise = profile.noiseSigma(axis) / (axis < 3 ? 4.0 : 5.0);
            if (rms >= Math.max(0.006, axisNoise * 2.5) && axisPeak[axis] >= Math.max(0.012, axisNoise * 4.0)) activeAxes++;
        }
        int dominantAxis = 0;
        for (int axis = 1; axis < axisEnergy.length; axis++) {
            if (axisEnergy[axis] > axisEnergy[dominantAxis]) dominantAxis = axis;
        }
        int reversals = countPersistentReversals(highPassSeries[dominantAxis], times, frames.size(),
                dominantAxis, profile);
        long activeSpan = pulses.count == 0 ? 0L : pulses.lastEndNanos - pulses.firstStartNanos;
        long activeDuration = pulses.totalActiveNanos;
        // The end-point samples contribute no interval energy; a small floor protects division
        // while preserving the measured RMS as the actual candidate duration grows.
        if (validIntervals <= 0 || !Double.isFinite(energySum)) {
            return rejected("候选数据中没有可测量的动作", pulses.count, signalToNoise);
        }

        if (kind == GestureKind.DOUBLE_PINCH_MIDDLE) {
            boolean separatedPulses = pulses.count == 2
                    && pulses.starts[1] - pulses.starts[0] >= 180_000_000L
                    && pulses.starts[1] - pulses.starts[0] <= 900_000_000L
                    && pulses.durations[0] >= 25_000_000L && pulses.durations[0] <= 450_000_000L
                    && pulses.durations[1] >= 25_000_000L && pulses.durations[1] <= 450_000_000L
                    && pulses.gaps[0] >= 45_000_000L && pulses.gaps[0] <= MAX_DOUBLE_PULSE_GAP_NANOS;
            boolean relativeDouble = pulses.count == 1
                    && hasRelativeDoublePulse(envelope.samples, times, frames.size(), on, maxEnvelope);
            if (!separatedPulses && !relativeDouble) {
                return new Result(false, "双指捏合需要两个间隔合适的独立动作脉冲", pulses.count, signalToNoise);
            }
            // This only says the trace resembles the required pulse count. Finger identity remains
            // gated by the personalized six-axis DTW model and its negative templates.
            return new Result(true, "检测到两个高于个人噪声的独立动作脉冲",
                    relativeDouble ? 2 : pulses.count, signalToNoise);
        }

        boolean nonPinchTexture = pulses.count != 2
                && activeAxes >= 2
                && activeDuration >= 100_000_000L
                && activeSpan >= 160_000_000L
                && (reversals >= 2 || activeDuration >= 260_000_000L);
        if (!nonPinchTexture) {
            return new Result(false, pulses.count == 2
                    ? "检测到捏合式双脉冲，不符合食指短促摩擦特征"
                    : "食指摩擦需要多个轴上都有高于噪声的动作纹理", pulses.count, signalToNoise);
        }
        return new Result(true, "检测到多个轴上高于个人噪声的摩擦纹理", pulses.count, signalToNoise);
    }

    /**
     * Some watch impacts leave enough residual energy that the absolute OFF threshold never clears
     * between two quick pinches. In that case, accept only two prominent envelope peaks separated
     * by a sustained valley relative to both peaks; a decaying ring has peaks without that valley.
     */
    private static boolean hasRelativeDoublePulse(double[] envelope, long[] times, int count,
                                                  double absoluteOn, double maxEnvelope) {
        double peakFloor = Math.max(absoluteOn * 1.5, maxEnvelope * RELATIVE_PEAK_FRACTION);
        int[] peaks = new int[96]; // 3.5 s maximum duration, with a 40 ms peak refractory interval.
        int peakCount = 0;
        for (int i = 1; i < count - 1; i++) {
            double value = envelope[i];
            if (value < peakFloor || value < envelope[i - 1] || value <= envelope[i + 1]) continue;
            if (peakCount > 0) {
                int previousPeak = peaks[peakCount - 1];
                if (times[i] - times[previousPeak] < RELATIVE_PEAK_REFRACTORY_NANOS) {
                    if (value > envelope[previousPeak]) peaks[peakCount - 1] = i;
                    continue;
                }
            }
            if (peakCount < peaks.length) peaks[peakCount++] = i;
        }

        for (int first = 0; first < peakCount; first++) {
            int firstIndex = peaks[first];
            for (int second = first + 1; second < peakCount; second++) {
                int secondIndex = peaks[second];
                long separation = times[secondIndex] - times[firstIndex];
                if (separation > 900_000_000L) break;
                if (separation < 180_000_000L) continue;

                double valleyLimit = Math.min(envelope[firstIndex], envelope[secondIndex])
                        * RELATIVE_VALLEY_FRACTION;
                int valleyIndex = firstIndex + 1;
                for (int i = firstIndex + 2; i < secondIndex; i++) {
                    if (envelope[i] < envelope[valleyIndex]) valleyIndex = i;
                }
                if (envelope[valleyIndex] > valleyLimit) continue;

                int valleyStart = valleyIndex;
                while (valleyStart > firstIndex && envelope[valleyStart - 1] <= valleyLimit) valleyStart--;
                int valleyEnd = valleyIndex;
                while (valleyEnd < secondIndex && envelope[valleyEnd + 1] <= valleyLimit) valleyEnd++;
                if (times[valleyEnd] - times[valleyStart] >= MIN_RELATIVE_VALLEY_NANOS) return true;
            }
        }
        return false;
    }

    private static PulseSummary countPulses(double[] envelope, long[] times, int count,
                                             double on, double off) {
        PulseSummary result = new PulseSummary();
        boolean active = false;
        long rawStart = 0L;
        long rawEnd = 0L;
        for (int i = 1; i < count; i++) {
            double value = envelope[i];
            if (!active && value >= on) {
                active = true;
                rawStart = times[i];
                rawEnd = times[i];
            } else if (active && value >= off) {
                rawEnd = times[i];
            } else if (active) {
                addPulse(result, rawStart, rawEnd);
                active = false;
            }
        }
        if (active) addPulse(result, rawStart, rawEnd);
        return result;
    }

    private static void addPulse(PulseSummary result, long start, long end) {
        if (result.count > 0 && start - result.lastEndNanos <= RING_MERGE_GAP_NANOS) {
            result.lastEndNanos = Math.max(result.lastEndNanos, end);
            result.durations[result.count - 1] = result.lastEndNanos - result.starts[result.count - 1];
            result.totalActiveNanos += Math.max(0L, end - start);
            return;
        }
        if (result.count == 0) result.firstStartNanos = start;
        if (result.count >= result.starts.length) {
            // The gate only distinguishes two pulses from other counts, so saturate at the
            // fixed storage bound instead of growing an index past the arrays.
            result.lastEndNanos = end;
            result.totalActiveNanos += Math.max(0L, end - start);
            return;
        }
        if (result.count > 0) result.gaps[result.count - 1] = start - result.lastEndNanos;
        result.starts[result.count] = start;
        result.durations[result.count] = Math.max(0L, end - start);
        result.count++;
        result.lastEndNanos = end;
        result.totalActiveNanos += Math.max(0L, end - start);
    }

    private static int countPersistentReversals(double[] series, long[] times, int count,
                                                int axis, GestureNoiseProfile profile) {
        double scale = axis < 3 ? 4.0 : 5.0;
        double threshold = Math.max(0.012, profile.noiseSigma(axis) / scale * 3.0);
        int priorSign = 0;
        int reversals = 0;
        long lastChange = -1L;
        for (int i = 1; i < count; i++) {
            double value = series[i];
            if (Math.abs(value) < threshold) continue;
            int sign = value > 0.0 ? 1 : -1;
            if (priorSign != 0 && sign != priorSign
                    && (lastChange < 0L || times[i] - lastChange >= 35_000_000L)) {
                reversals++;
                lastChange = times[i];
            }
            priorSign = sign;
        }
        return reversals;
    }

    private static Result rejected(String reason, int pulseCount, double signalToNoise) {
        return new Result(false, reason, pulseCount, signalToNoise);
    }

    private static final class ArrayListEnvelope {
        final double[] samples;
        double value;
        ArrayListEnvelope(int size) { samples = new double[size]; }
    }

    private static final class PulseSummary {
        final long[] starts = new long[16];
        final long[] durations = new long[16];
        final long[] gaps = new long[16];
        int count;
        long firstStartNanos;
        long lastEndNanos;
        long totalActiveNanos;
    }

    public static final class Result {
        private final boolean accepted;
        private final String reason;
        private final int pulseCount;
        private final double signalToNoise;

        private Result(boolean accepted, String reason, int pulseCount, double signalToNoise) {
            this.accepted = accepted;
            this.reason = reason;
            this.pulseCount = pulseCount;
            this.signalToNoise = signalToNoise;
        }
        public boolean accepted() { return accepted; }
        public String reason() { return reason; }
        public int pulseCount() { return pulseCount; }
        public double signalToNoise() { return signalToNoise; }
    }
}
