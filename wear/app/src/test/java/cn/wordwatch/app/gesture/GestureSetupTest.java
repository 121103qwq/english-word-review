package cn.wordwatch.app.gesture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.wordwatch.app.gesture.core.SensorFrame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GestureSetupTest {
    private static final long MAX_SKEW_NS = 20_000_000L;

    @Test public void trialExcludesButtonTouchAndSettlingEvenIfCandidateEndsAfterReady() {
        long start = 10_000_000_000L;
        long deadline = start + 8_000_000_000L;
        assertFalse(GestureSetupRules.acceptsTrialCandidate(start + 900_000_000L,
                start + 2_300_000_000L, start, deadline));
        assertFalse(GestureSetupRules.acceptsTrialCandidate(start + 1_999_999_999L,
                start + 2_500_000_000L, start, deadline));
        assertTrue(GestureSetupRules.acceptsTrialCandidate(start + 2_000_000_000L,
                start + 3_000_000_000L, start, deadline));
        assertFalse(GestureSetupRules.acceptsTrialCandidate(start + 7_000_000_000L,
                deadline + 1L, start, deadline));
        assertFalse(GestureSetupRules.acceptsTrialCandidate(start + 3_000_000_000L,
                start + 2_500_000_000L, start, deadline));
    }

    @Test public void normalMotionGateRequiresAccuracyLatencyAndRealEvidence() {
        assertTrue(GestureSetupRules.canStartNormalMotion(false, true,
                20, 19, 20, 19, 0, 20, 500, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, true,
                20, 18, 20, 20, 0, 40, 250, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, true,
                20, 19, 20, 19, 1, 40, 250, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, false,
                20, 20, 20, 20, 0, 40, 250, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, true,
                20, 20, 20, 20, 0, 19, 250, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, true,
                20, 20, 20, 20, 0, 40, 501, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(true, true,
                20, 20, 20, 20, 0, 40, 250, 0));
        assertFalse(GestureSetupRules.canStartNormalMotion(false, true,
                20, 20, 20, 20, 0, 40, 250, 1));
    }

    @Test public void normalMotionRequiresContinuousFreshSensorFrames() {
        assertTrue(GestureSetupRules.acceptsNormalMotionFrame(1_000_000_000L, 1_010_000_000L, 100, 10));
        assertTrue(GestureSetupRules.acceptsNormalMotionFrame(1_000_000_000L, 1_180_000_000L, 12, 180));
        assertFalse(GestureSetupRules.acceptsNormalMotionFrame(1_000_000_000L, 1_181_000_000L, 100, 10));
        assertFalse(GestureSetupRules.acceptsNormalMotionFrame(1_000_000_000L, 1_010_000_000L, 11.9, 10));
        assertFalse(GestureSetupRules.acceptsNormalMotionFrame(1_000_000_000L, 1_010_000_000L, 100, 181));
        assertFalse(GestureSetupRules.acceptsNormalMotionFrame(1_010_000_000L, 1_000_000_000L, 100, 10));
        assertTrue(GestureSetupRules.hasEnoughFrames(600_000, 6_480));
        assertFalse(GestureSetupRules.hasEnoughFrames(600_000, 6_479));
    }

    @Test public void nearSimultaneousGyroscopeCallbackPairsOnceOnAccelTimestamp() {
        long accelAt = 5_000_000_000L;
        long gyroBefore = 4_995_000_000L;
        long gyroAfter = 5_005_000_000L;
        SensorTimestampPairer pairer = new SensorTimestampPairer(MAX_SKEW_NS);
        assertTrue(pairer.addGyroscope(gyroBefore, 1, 0, 0).isEmpty());
        assertTrue(pairer.addAccelerometer(accelAt, 0, 0, 9.8f).isEmpty());
        List<SensorFrame> paired = pairer.addGyroscope(gyroAfter, 2, 0, 0);
        assertEquals(1, paired.size());
        assertEquals(accelAt, paired.get(0).timestampNanos());
        assertEquals(1.0, paired.get(0).gyroX(), 0.0);
        assertTrue(pairer.addGyroscope(gyroAfter + 10_000_000L, 3, 0, 0).isEmpty());
        assertTrue(pairer.addAccelerometer(accelAt, 9, 9, 9).isEmpty());
    }

    @Test public void timestampPairerHandlesRealAccelFirstSensorBatchesExactlyOnce() {
        SensorTimestampPairer pairer = new SensorTimestampPairer(60_000_000L);
        long batchGyroBefore = 59_858_653_250_228L;
        long[] accelBatch = {
                59_858_666_580_457L,
                59_858_675_933_457L,
                59_858_685_286_457L,
                59_858_694_639_457L
        };
        long[] gyroBatch = {
                59_858_662_605_228L,
                59_858_671_958_228L,
                59_858_681_311_228L,
                59_858_690_664_228L,
                59_858_700_036_228L
        };

        assertTrue(pairer.addGyroscope(batchGyroBefore, -1, 0, 0).isEmpty());
        for (long timestamp : accelBatch) {
            assertTrue(pairer.addAccelerometer(timestamp, 1, 2, 3).isEmpty());
        }

        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < gyroBatch.length; i++) {
            frames.addAll(pairer.addGyroscope(gyroBatch[i], i, 0, 0));
        }
        assertEquals(accelBatch.length, frames.size());
        for (int i = 0; i < accelBatch.length; i++) {
            assertEquals(accelBatch[i], frames.get(i).timestampNanos());
            assertEquals(1.0, frames.get(i).accelX(), 0.0);
            assertEquals((double) i, frames.get(i).gyroX(), 0.0);
            assertTrue(Math.abs(frames.get(i).timestampNanos() - gyroBatch[i]) <= 5_000_000L);
        }
        assertTrue(pairer.addAccelerometer(accelBatch[accelBatch.length - 1], 9, 9, 9).isEmpty());
        assertTrue(pairer.addGyroscope(gyroBatch[gyroBatch.length - 1], 9, 9, 9).isEmpty());
    }

    @Test public void timestampPairerDropsUnpairedAccelInsteadOfInventingGyroscopeData() {
        SensorTimestampPairer pairer = new SensorTimestampPairer(20_000_000L);
        assertTrue(pairer.addGyroscope(10_000_000L, 1, 2, 3).isEmpty());
        assertTrue(pairer.addAccelerometer(100_000_000L, 4, 5, 6).isEmpty());
        assertTrue(pairer.addGyroscope(200_000_000L, 7, 8, 9).isEmpty());
        assertTrue(pairer.addAccelerometer(210_000_000L, 10, 11, 12).isEmpty());
    }

    @Test public void stillNegativeNeedsBoundedQuietCaptureWindow() {
        long start = 10_000_000_000L;
        long end = start + 1_500_000_000L;
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (long timestamp = start + 50_000_000L; timestamp <= end - 50_000_000L; timestamp += 100_000_000L) {
            frames.add(new SensorFrame(timestamp, 0, 0, 9.8, 0, 0, 0));
        }
        assertTrue(GestureSetupRules.hasCompleteStillWindow(frames, start, end));
        assertFalse(GestureSetupRules.hasCompleteStillWindow(frames.subList(0, 5), start, end));

        frames.set(7, new SensorFrame(
                frames.get(6).timestampNanos() + 181_000_000L, 0, 0, 9.8, 0, 0, 0));
        assertFalse(GestureSetupRules.hasCompleteStillWindow(frames, start, end));
    }
}
