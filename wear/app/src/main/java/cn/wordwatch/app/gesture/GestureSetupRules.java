package cn.wordwatch.app.gesture;

import cn.wordwatch.app.gesture.core.GestureVerificationProfile;
import cn.wordwatch.app.gesture.core.GestureSample;
import cn.wordwatch.app.gesture.core.SensorFrame;

import java.util.List;

/** Pure gates shared by setup orchestration and regression tests. */
final class GestureSetupRules {
    static final long TRIAL_SETTLE_NANOS = 2_000_000_000L;
    private static final long MAX_SENSOR_GAP_NANOS = 180_000_000L;
    private static final double MIN_OBSERVED_HZ = 12.0;
    private static final long MAX_REPORTED_GAP_MILLIS = 180L;

    private GestureSetupRules() { }

    static boolean acceptsTrialCandidate(long startedAtNs, long endedAtNs,
                                         long trialStartedAtNs, long trialDeadlineNs) {
        return trialStartedAtNs > 0 && trialDeadlineNs > trialStartedAtNs
                && startedAtNs >= trialStartedAtNs
                && startedAtNs - trialStartedAtNs >= TRIAL_SETTLE_NANOS
                && endedAtNs >= startedAtNs && endedAtNs <= trialDeadlineNs;
    }

    static boolean canStartNormalMotion(GestureVerificationProfile profile) {
        return profile != null && canStartNormalMotion(profile.failClosed(), profile.realHardwareEvidence(),
                profile.pinchTrials(), profile.pinchCorrect(), profile.rubTrials(), profile.rubCorrect(),
                profile.crossClassConfusions(), profile.latencySampleCount(), profile.latencyP95Millis(),
                profile.normalMotionFalsePositives());
    }

    static boolean canStartNormalMotion(boolean failClosed, boolean realHardware,
                                        int pinchTrials, int pinchCorrect, int rubTrials, int rubCorrect,
                                        int crossClassConfusions, int latencySamples, int latencyP95Millis,
                                        int falsePositives) {
        return !failClosed && realHardware && heldOutAccuracyPasses(pinchTrials, pinchCorrect, rubTrials,
                rubCorrect, crossClassConfusions)
                && latencySamples >= GestureVerificationProfile.REQUIRED_LATENCY_SAMPLES
                && latencyP95Millis <= GestureVerificationProfile.MAX_P95_LATENCY_MILLIS
                && falsePositives == 0;
    }

    static boolean heldOutAccuracyPasses(int pinchTrials, int pinchCorrect, int rubTrials, int rubCorrect,
                                         int crossClassConfusions) {
        return pinchTrials >= GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE
                && pinchCorrect >= GestureVerificationProfile.REQUIRED_CORRECT_PER_GESTURE
                && (long) pinchCorrect * 100L >= (long) pinchTrials * 95L
                && rubTrials >= GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE
                && rubCorrect >= GestureVerificationProfile.REQUIRED_CORRECT_PER_GESTURE
                && (long) rubCorrect * 100L >= (long) rubTrials * 95L
                && crossClassConfusions == 0;
    }

    static boolean hasAcceptableNormalMotionRate(double observedHz, long reportedMaxGapMillis) {
        return Double.isFinite(observedHz) && observedHz >= MIN_OBSERVED_HZ
                && reportedMaxGapMillis <= MAX_REPORTED_GAP_MILLIS;
    }

    static boolean acceptsNormalMotionFrame(long previousTimestampNs, long timestampNs,
                                            double observedHz, long reportedMaxGapMillis) {
        return previousTimestampNs > 0 && timestampNs > previousTimestampNs
                && timestampNs - previousTimestampNs <= MAX_SENSOR_GAP_NANOS
                && hasAcceptableNormalMotionRate(observedHz, reportedMaxGapMillis);
    }

    static boolean hasEnoughFrames(long elapsedMillis, long frameCount) {
        if (elapsedMillis < 0 || frameCount < 0) return false;
        long expectedAtMinimumRate = elapsedMillis * 12L / 1_000L;
        return frameCount >= expectedAtMinimumRate * 9L / 10L;
    }

    static boolean hasCompleteStillWindow(List<SensorFrame> frames, long windowStartNs, long windowEndNs) {
        if (frames == null || frames.size() < 12 || frames.size() > GestureSample.MAX_FRAMES
                || windowStartNs <= 0 || windowEndNs <= windowStartNs) return false;
        long first = frames.get(0).timestampNanos();
        long previous = first;
        if (first < windowStartNs || first > windowStartNs + MAX_SENSOR_GAP_NANOS) return false;
        for (int i = 1; i < frames.size(); i++) {
            long timestamp = frames.get(i).timestampNanos();
            if (timestamp <= previous || timestamp - previous > MAX_SENSOR_GAP_NANOS) return false;
            previous = timestamp;
        }
        long minimumSpan = Math.max(0, windowEndNs - windowStartNs - 2 * MAX_SENSOR_GAP_NANOS);
        return previous <= windowEndNs && previous >= windowEndNs - MAX_SENSOR_GAP_NANOS
                && previous - first >= minimumSpan;
    }
}
