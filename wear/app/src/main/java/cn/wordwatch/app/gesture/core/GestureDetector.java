package cn.wordwatch.app.gesture.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming, bounded segmenter. A timestamp-aware 60 ms envelope gates candidate and calm state;
 * armed candidates also require 40 ms of continuous start evidence. Original sensor frames remain
 * untouched for classification. Defaults close after 400 ms without motion, require 750 ms of calm
 * before rearming, and cap candidates at 3.5 s / 4096 stored frames.
 * The 1 ms capture floor preserves all frames through 1 kHz. Results are emitted once, after the
 * quiet tail, or rejected at the cap.
 */
public final class GestureDetector {
    /** Covers a quick double pinch while keeping the quiet tail inside the 500 ms latency target. */
    public static final long SEGMENT_END_GAP_NANOS = 400_000_000L;
    public static final long CALM_REARM_NANOS = 750_000_000L;
    private static final long PERSONAL_TRIAL_REARM_NANOS = 250_000_000L;
    public static final long MAX_SENSOR_GAP_NANOS = 120_000_000L;
    public static final long MAX_SEGMENT_NANOS = 3_500_000_000L;
    public static final int MAX_SEGMENT_FRAMES = 4096;
    public static final long CAPTURE_SAMPLE_PERIOD_NANOS = 1_000_000L;
    public static final long MOTION_ENVELOPE_TAU_NANOS = 60_000_000L;
    public static final long ARM_NOISE_TOLERANCE_NANOS = 60_000_000L;
    public static final long START_CONFIRM_NANOS = 40_000_000L;
    private static final long START_CONFIRM_MAX_SAMPLE_GAP_NANOS = 30_000_000L;
    public static final long PRE_ROLL_NANOS = 160_000_000L;
    public static final int PRE_ROLL_MAX_FRAMES = 256;
    // Normalized sensor change per second; release hysteresis limits chatter around the arm point.
    private static final double MIN_START_CHANGE = 0.50;

    private final GestureModel model;
    private final GestureNoiseProfile noiseProfile;
    private final String source;
    private final boolean synthetic;
    private final boolean captureOnly;
    private final boolean personalTrial;
    private final SensorFrame[] capture = new SensorFrame[MAX_SEGMENT_FRAMES];
    private final SensorFrame[] history = new SensorFrame[PRE_ROLL_MAX_FRAMES];
    private final double[] envelope = new double[GestureFeatures.RAW_DIMENSIONS];
    private final double startChange;
    private final double releaseChange;
    private SensorFrame previous;
    private long envelopeTimestampNanos = -1L;
    private long calmHighSinceNanos = -1L;
    private long pendingStartNanos = -1L;
    private long calmSinceNanos = -1L;
    private long lastMovementNanos = -1L;
    private long startedAtNanos = -1L;
    private long lastCapturedNanos = -1L;
    private int captureCount;
    private int historyNext;
    private int historyCount;
    private boolean armed;
    private boolean capturing;
    private boolean overflow;

    /** Synthetic must be true for tests or diagnostics; production capture must pass false explicitly. */
    public GestureDetector(GestureModel model, String source, boolean synthetic) {
        this(model, source, synthetic, model == null ? GestureNoiseProfile.legacySynthetic() : model.noiseProfile(), false);
    }

    /** Enables the explicitly requested, local-only personal trial classifier. */
    public GestureDetector(GestureModel model, String source, boolean synthetic, boolean personalTrial) {
        this(model, source, synthetic, model == null ? GestureNoiseProfile.legacySynthetic() : model.noiseProfile(), personalTrial);
    }

    private GestureDetector(GestureModel model, String source, boolean synthetic,
                            GestureNoiseProfile noiseProfile) {
        this(model, source, synthetic, noiseProfile, false);
    }

    private GestureDetector(GestureModel model, String source, boolean synthetic,
                            GestureNoiseProfile noiseProfile, boolean personalTrial) {
        if (!validSource(source)) throw new IllegalArgumentException("source must be a safe 1-80 character tag");
        if (noiseProfile == null) throw new IllegalArgumentException("a still noise profile is required");
        this.model = model;
        this.noiseProfile = noiseProfile;
        this.source = source;
        this.synthetic = synthetic || noiseProfile.synthetic();
        this.captureOnly = model == null;
        this.personalTrial = personalTrial;
        double noiseRate = noiseProfile.normalizedNoiseRms() / (MOTION_ENVELOPE_TAU_NANOS / 1_000_000_000.0);
        this.startChange = Math.max(MIN_START_CHANGE, noiseRate * 5.0);
        this.releaseChange = Math.max(0.20, this.startChange * 0.60);
    }

    /** Segments calibration gestures before a personalized model has been trained. */
    public static GestureDetector captureOnly(String source, boolean synthetic) {
        return new GestureDetector(null, source, synthetic);
    }

    /** Segments calibration gestures with the exact still-noise profile used during training. */
    public static GestureDetector captureOnly(String source, boolean synthetic,
                                              GestureNoiseProfile noiseProfile) {
        return new GestureDetector(null, source, synthetic, noiseProfile);
    }

    /** Returns a completed candidate, including UNKNOWN candidates, or null while collecting. */
    public DetectionEvent accept(SensorFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame is required");
        if (previous != null && frame.timestampNanos() <= previous.timestampNanos()) {
            throw new IllegalArgumentException("sensor timestamps must increase");
        }
        if (previous != null && frame.timestampNanos() - previous.timestampNanos() > MAX_SENSOR_GAP_NANOS) {
            DetectionEvent aborted = null;
            if (capturing) {
                overflow = true;
                aborted = finish(frame.timestampNanos());
            }
            clearStreamForGap();
            seed(frame);
            return aborted;
        }
        if (previous == null) {
            seed(frame);
            return null;
        }

        double change = updateEnvelope(frame);
        double rawChange = rawChangeAboveStillNoise(previous, frame);
        remember(frame);
        if (capturing) {
            append(frame);
            // The smoothed envelope has a long decay tail. Anchor the physical end to raw motion
            // after removing an individualized per-axis still-noise deadband.
            if (rawChange >= releaseChange) lastMovementNanos = frame.timestampNanos();
            previous = frame;
            // A continuous-motion stream must not keep a candidate open indefinitely. Emit
            // this bounded, explicitly rejected candidate at the cap and wait for calm rearm.
            if (frame.timestampNanos() - startedAtNanos > MAX_SEGMENT_NANOS) {
                overflow = true;
                return finish(frame.timestampNanos());
            }
            if (frame.timestampNanos() - lastMovementNanos >= SEGMENT_END_GAP_NANOS) {
                return finish(frame.timestampNanos());
            }
            return null;
        }

        if (change < startChange) {
            pendingStartNanos = -1L;
            calmHighSinceNanos = -1L;
            if (calmSinceNanos < 0L) calmSinceNanos = previous.timestampNanos();
            if (frame.timestampNanos() - calmSinceNanos >=
                    (personalTrial ? PERSONAL_TRIAL_REARM_NANOS : CALM_REARM_NANOS)) armed = true;
        } else {
            if (armed) {
                long sampleGap = frame.timestampNanos() - previous.timestampNanos();
                if (pendingStartNanos < 0L || sampleGap > START_CONFIRM_MAX_SAMPLE_GAP_NANOS) {
                    pendingStartNanos = frame.timestampNanos();
                }
                if (frame.timestampNanos() - pendingStartNanos >= START_CONFIRM_NANOS) {
                    long onsetNanos = pendingStartNanos;
                    pendingStartNanos = -1L;
                    begin(onsetNanos, frame);
                }
            } else {
                pendingStartNanos = -1L;
                // A brief filtered spike should not restart the full calm timer. Sustained motion
                // still interrupts it; armed starts use the separate 40 ms confirmation above.
                if (calmHighSinceNanos < 0L) calmHighSinceNanos = frame.timestampNanos();
                if (frame.timestampNanos() - calmHighSinceNanos >= ARM_NOISE_TOLERANCE_NANOS) {
                    calmSinceNanos = -1L;
                    armed = false;
                }
            }
        }
        previous = frame;
        return null;
    }

    public void reset() {
        previous = null;
        java.util.Arrays.fill(envelope, 0.0);
        java.util.Arrays.fill(capture, null);
        java.util.Arrays.fill(history, null);
        envelopeTimestampNanos = -1L;
        calmHighSinceNanos = -1L;
        pendingStartNanos = -1L;
        calmSinceNanos = -1L;
        lastMovementNanos = -1L;
        startedAtNanos = -1L;
        lastCapturedNanos = -1L;
        captureCount = 0;
        historyNext = 0;
        historyCount = 0;
        armed = false;
        capturing = false;
        overflow = false;
    }

    private void begin(long onsetNanos, SensorFrame motion) {
        capturing = true;
        armed = false;
        overflow = false;
        captureCount = 0;
        pendingStartNanos = -1L;
        calmHighSinceNanos = -1L;
        startedAtNanos = onsetNanos;
        lastMovementNanos = motion.timestampNanos();
        lastCapturedNanos = -1L;
        long earliest = Math.max(0L, onsetNanos - PRE_ROLL_NANOS);
        int oldest = (historyNext - historyCount + history.length) % history.length;
        for (int i = 0; i < historyCount; i++) {
            SensorFrame candidate = history[(oldest + i) % history.length];
            if (candidate != null && candidate.timestampNanos() >= earliest) append(candidate);
        }
    }

    private void initializeEnvelope(SensorFrame frame) {
        for (int dim = 0; dim < envelope.length; dim++) envelope[dim] = GestureFeatures.channel(frame, dim);
        envelopeTimestampNanos = frame.timestampNanos();
    }

    private void seed(SensorFrame frame) {
        previous = frame;
        initializeEnvelope(frame);
        remember(frame);
        calmSinceNanos = frame.timestampNanos();
    }

    /** A stale sensor stream cannot bridge a candidate or contribute old pre-roll after a gap. */
    private void clearStreamForGap() {
        previous = null;
        java.util.Arrays.fill(envelope, 0.0);
        java.util.Arrays.fill(capture, null);
        java.util.Arrays.fill(history, null);
        envelopeTimestampNanos = -1L;
        calmHighSinceNanos = -1L;
        pendingStartNanos = -1L;
        calmSinceNanos = -1L;
        lastMovementNanos = -1L;
        startedAtNanos = -1L;
        lastCapturedNanos = -1L;
        captureCount = 0;
        historyNext = 0;
        historyCount = 0;
        armed = false;
        capturing = false;
        overflow = false;
    }

    private double updateEnvelope(SensorFrame frame) {
        long elapsedNanos = frame.timestampNanos() - envelopeTimestampNanos;
        if (elapsedNanos <= 0L || elapsedNanos > 250_000_000L) {
            initializeEnvelope(frame);
            return 0.0;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double alpha = -Math.expm1(-elapsedNanos / (double) MOTION_ENVELOPE_TAU_NANOS);
        double sum = 0.0;
        for (int dim = 0; dim < envelope.length; dim++) {
            double raw = GestureFeatures.channel(frame, dim);
            double previousSmooth = envelope[dim];
            double smoothed = previousSmooth + alpha * (raw - previousSmooth);
            envelope[dim] = smoothed;
            double scale = dim < 3 ? 4.0 : 5.0;
            double rate = (smoothed - previousSmooth) / scale / elapsedSeconds;
            sum += rate * rate;
        }
        envelopeTimestampNanos = frame.timestampNanos();
        return Math.sqrt(sum / envelope.length);
    }

    /** Removes a per-axis still-noise deadband before anchoring physical movement end. */
    private double rawChangeAboveStillNoise(SensorFrame before, SensorFrame after) {
        long elapsedNanos = after.timestampNanos() - before.timestampNanos();
        if (elapsedNanos <= 0L || elapsedNanos > MAX_SENSOR_GAP_NANOS) return 0.0;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double sum = 0.0;
        for (int axis = 0; axis < envelope.length; axis++) {
            double scale = axis < 3 ? 4.0 : 5.0;
            double delta = Math.abs(GestureFeatures.channel(after, axis)
                    - GestureFeatures.channel(before, axis));
            double deadband = noiseProfile.noiseSigma(axis) * 3.0;
            double change = Math.max(0.0, delta - deadband) / scale / elapsedSeconds;
            sum += change * change;
        }
        return Math.sqrt(sum / envelope.length);
    }

    private void remember(SensorFrame frame) {
        history[historyNext] = frame;
        historyNext = (historyNext + 1) % history.length;
        if (historyCount < history.length) historyCount++;
    }

    private void append(SensorFrame frame) {
        if (captureCount > 0 && frame.timestampNanos() - lastCapturedNanos < CAPTURE_SAMPLE_PERIOD_NANOS) return;
        if (captureCount < capture.length) {
            capture[captureCount++] = frame;
            lastCapturedNanos = frame.timestampNanos();
        } else {
            // The one millisecond floor preserves normal Wear OS sensor rates through 1 kHz
            // while keeping each segment bounded at 4096 stored frames.
            overflow = true;
        }
    }

    private static boolean validSource(String source) {
        if (source == null || source.trim().isEmpty() || source.length() > 80) return false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (!(Character.isLetterOrDigit(c) || " ._:/-+".indexOf(c) >= 0)) return false;
        }
        return true;
    }

    private DetectionEvent finish(long emittedAtNanos) {
        List<SensorFrame> frames = new ArrayList<>(captureCount);
        for (int i = 0; i < captureCount; i++) frames.add(capture[i]);
        GestureModel.Classification classification = captureOnly || overflow
                ? GestureModel.Classification.rejected(GestureKind.UNKNOWN, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
                : personalTrial ? model.classifyPersonalTrial(frames) : model.classify(frames);
        GestureSample sample = null;
        long duration = frames.size() < GestureSample.MIN_FRAMES ? 0L
                : frames.get(frames.size() - 1).timestampNanos() - frames.get(0).timestampNanos();
        if (!overflow && captureCount >= GestureSample.MIN_FRAMES && duration >= GestureSample.MIN_DURATION_NANOS
                && duration <= GestureSample.MAX_DURATION_NANOS) {
            sample = GestureSample.of(classification.kind(), frames, source, synthetic);
        }
        DetectionEvent event = new DetectionEvent(classification, startedAtNanos, lastMovementNanos,
                emittedAtNanos, captureCount, sample);
        capturing = false;
        armed = false;
        pendingStartNanos = -1L;
        calmHighSinceNanos = -1L;
        calmSinceNanos = lastMovementNanos;
        captureCount = 0;
        lastCapturedNanos = -1L;
        overflow = false;
        return event;
    }

    public static final class DetectionEvent {
        private final GestureModel.Classification classification;
        private final long startedAtNanos;
        private final long endedAtNanos;
        private final long emittedAtNanos;
        private final int frameCount;
        private final GestureSample sample;

        private DetectionEvent(GestureModel.Classification classification, long startedAtNanos,
                               long endedAtNanos, long emittedAtNanos, int frameCount, GestureSample sample) {
            this.classification = classification;
            this.startedAtNanos = startedAtNanos;
            this.endedAtNanos = endedAtNanos;
            this.emittedAtNanos = emittedAtNanos;
            this.frameCount = frameCount;
            this.sample = sample;
        }
        public GestureKind kind() { return classification.kind(); }
        public GestureModel.Classification classification() { return classification; }
        public long startedAtNanos() { return startedAtNanos; }
        /** Last observed motion timestamp; use this as the physical action-end latency anchor. */
        public long endedAtNanos() { return endedAtNanos; }
        /** Time the quiet tail completed and the result became available to the caller. */
        public long emittedAtNanos() { return emittedAtNanos; }
        public int frameCount() { return frameCount; }
        public GestureSample sample() { return sample; }
    }
}
