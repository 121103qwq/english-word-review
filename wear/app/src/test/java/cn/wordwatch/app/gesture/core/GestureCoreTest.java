package cn.wordwatch.app.gesture.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class GestureCoreTest {
    private static final String SOURCE = "synthetic-test";

    @Test public void personalizedDtwAcceptsBothPatternsAndRejectsNormalAndLargeMotion() {
        GestureModel model = model(0);
        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE, model.classify(pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 6, 1.035, 1_000_000_000L)).kind());
        assertEquals(GestureKind.RUB_INDEX, model.classify(pattern(GestureKind.RUB_INDEX, 6, 1.035, 1_000_000_000L)).kind());
        assertEquals(GestureKind.UNKNOWN, model.classify(pattern(GestureKind.UNKNOWN, 6, 1.0, 1_000_000_000L)).kind());
        assertEquals(GestureKind.UNKNOWN, model.classify(pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 6, 9.0, 1_000_000_000L)).kind());

        GestureModel restored = GestureModel.fromJson(model.toJson());
        assertEquals(model.revision(), restored.revision());
        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE,
                restored.classify(pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 6, 1.035, 1_000_000_000L)).kind());
    }

    @Test public void qualityRevisionRejectsModelsCalibratedWithOldGate() {
        String current = model(0).toJson();
        assertTrue(current.contains("\"version\":10"));
        try {
            GestureModel.fromJson(current.replace("\"version\":10", "\"version\":9"));
            fail("old detector-gate calibration must be invalidated");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported model version"));
        }
    }

    @Test public void overlappingNormalMotionKeepsTrainingAndDiagnosticsButClassifiesUnknown() {
        ArrayList<GestureSample> samples = new ArrayList<>(modelSamplesForTest());
        GestureSample pinch = samples.get(0);
        GestureSample nearNegative = GestureSample.of(GestureKind.UNKNOWN,
                scaledFrames(pinch.frames(), 1.0001), SOURCE, true);
        samples.add(nearNegative);

        GestureModel model = GestureModel.train(samples);
        GestureModel.TrainingDiagnostics diagnostics = model.trainingDiagnostics();
        assertTrue(diagnostics.summary(), diagnostics.hasOverlap());
        assertTrue("the report should expose the nearest-normal-motion overlap",
                diagnostics.leaveOneOutNegativeOverlaps(GestureKind.DOUBLE_PINCH_MIDDLE) > 0);
        assertTrue(diagnostics.summary().contains("类内P90"));
        assertTrue(diagnostics.failureReason().contains("正常动作"));

        GestureModel restored = GestureModel.fromJson(model.toJson());
        assertEquals(diagnostics.summary(), restored.trainingDiagnostics().summary());
        assertEquals("a candidate matching a normal-motion negative must stay UNKNOWN",
                GestureKind.UNKNOWN, restored.classify(nearNegative.frames()).kind());
        assertEquals("stillness cannot become a trained gesture",
                GestureKind.UNKNOWN, restored.classify(stillFrames(100_000_000_000L, 50_000_000L, 81, false)).kind());
    }

    @Test public void qualityMatchedNegativesDoNotClampRadiusAndMaskTamperingIsRejected() throws Exception {
        ArrayList<GestureSample> samples = new ArrayList<>(modelSamplesForTest());
        GestureModel baseline = GestureModel.train(samples);
        GestureSample singlePinch = GestureSample.of(GestureKind.UNKNOWN,
                singlePinchFrames(15_000_000_000L), SOURCE, true);
        samples.add(singlePinch);
        GestureModel withUnmatchedNegative = GestureModel.train(samples);

        assertEquals("a one-pulse negative must not enter the double-pinch negative-distance set",
                0, qualityMask(withUnmatchedNegative, singlePinch.fingerprint()) & 1);
        assertEquals("unmatched negatives must not shrink the gesture radius",
                distanceThreshold(baseline, GestureKind.DOUBLE_PINCH_MIDDLE),
                distanceThreshold(withUnmatchedNegative, GestureKind.DOUBLE_PINCH_MIDDLE), 0.0);
        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE,
                withUnmatchedNegative.classify(patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE,
                        1.0, 16_000_000_000L)).kind());
        assertEquals("single pinch remains rejected by its structural quality gate", GestureKind.UNKNOWN,
                withUnmatchedNegative.classify(singlePinch.frames()).kind());
        GestureModel.Classification trialAgainstUnmatchedNegative =
                withUnmatchedNegative.classifyPersonalTrial(singlePinch.frames());
        assertFalse("trial mode applies the same structural gate, so a single pinch is rejected",
                trialAgainstUnmatchedNegative.accepted());
        assertEquals(GestureKind.UNKNOWN, trialAgainstUnmatchedNegative.kind());
        assertEquals("stillness remains rejected by its structural quality gate", GestureKind.UNKNOWN,
                withUnmatchedNegative.classify(stillFrames(17_000_000_000L, 50_000_000L, 81, false)).kind());

        GestureSample pinch = samples.get(0);
        GestureSample qualifiedNegative = GestureSample.of(GestureKind.UNKNOWN,
                scaledFrames(pinch.frames(), 1.0001), SOURCE, true);
        samples.add(qualifiedNegative);
        GestureModel withQualifiedNegative = GestureModel.train(samples);
        assertTrue("near-pinch negative must be quality matched for the pinch class",
                (qualityMask(withQualifiedNegative, qualifiedNegative.fingerprint()) & 1) != 0);
        assertEquals("a quality-matched negative must still reject its own motion", GestureKind.UNKNOWN,
                withQualifiedNegative.classify(qualifiedNegative.frames()).kind());

        JSONObject tampered = new JSONObject(withQualifiedNegative.toJson());
        JSONArray rows = tampered.getJSONArray("templates");
        boolean changed = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (GestureKind.UNKNOWN.name().equals(row.getString("kind"))) {
                row.put("qualityMask", row.getInt("qualityMask") ^ 1);
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        try {
            GestureModel.fromJson(tampered.toString());
            fail("quality-mask tampering must invalidate the model revision");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("revision mismatch"));
        }
    }

    @Test public void personalTrialClassifiesSinglePulseWithoutChangingStrictPath() {
        GestureModel model = model(0);
        List<SensorFrame> single = singlePinchFrames(18_000_000_000L);
        assertFalse(GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE, single,
                model.noiseProfile()).accepted());
        assertEquals(GestureKind.UNKNOWN, model.classify(single).kind());
        assertEquals("one pulse is not a double pinch in trial mode either",
                GestureKind.UNKNOWN, model.classifyPersonalTrial(single).kind());

        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE,
                model.classifyPersonalTrial(patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE,
                        1.0, 19_000_000_000L)).kind());
        assertEquals("personal trial still rejects static input", GestureKind.UNKNOWN,
                model.classifyPersonalTrial(stillFrames(20_000_000_000L, 50_000_000L, 81, false)).kind());
        assertEquals("personal trial keeps the trained large-motion ceiling", GestureKind.UNKNOWN,
                model.classifyPersonalTrial(pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 6,
                        9.0, 21_000_000_000L)).kind());

        GestureDetector detector = new GestureDetector(model, SOURCE, true, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 22_000_000_000L;
        for (int i = 0; i <= 40; i++) push(detector, events,
                frame(start + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        long pulseStart = start + 41 * 20_000_000L;
        for (SensorFrame sample : singlePinchFrames(pulseStart)) push(detector, events,
                frame(sample.timestampNanos(), sample.accelX(), sample.accelY(), sample.accelZ(),
                        sample.gyroX(), sample.gyroY(), sample.gyroZ()));
        long quietStart = pulseStart + 60 * 20_000_000L;
        for (int i = 0; i < 40; i++) push(detector, events,
                frame(quietStart + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals("trial classification still emits one completed event", 1, events.size());
        assertEquals(model.classifyPersonalTrial(events.get(0).sample().frames()).kind(),
                events.get(0).classification().kind());
    }

    @Test public void personalTrialRearmsAfterCardResetWithoutSplittingDoublePinch() {
        GestureDetector detector = new GestureDetector(detectorModel(), SOURCE, true, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 23_000_000_000L;
        for (int round = 0; round < 2; round++) {
            detector.reset();
            // A changed card can listen again after 300 ms, without the old 750 ms dead time.
            for (int i = 0; i <= 15; i++) push(detector, events,
                    frame(start + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
            for (SensorFrame sample : doublePinchFrames(start + 320_000_000L, 3, 28, 64))
                push(detector, events, sample);
            assertEquals("the complete two-pulse gesture emits only once", round + 1, events.size());
            assertNotNull(events.get(round).sample());
            assertEquals(2, GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                    events.get(round).sample().frames(), detectorModel().noiseProfile()).pulseCount());
            start += 4_000_000_000L;
        }
    }

    @Test public void indistinguishableGestureClassesTrainDiagnosticallyAndRejectAmbiguousCandidates() {
        ArrayList<GestureSample> samples = new ArrayList<>();
        double[] scales = {0.55, 0.80, 1.00};
        for (int i = 0; i < scales.length; i++) {
            long start = 110_000_000_000L + i * 2_000_000_000L;
            List<SensorFrame> pinchTrace = residualTailDoublePinchFrames(start, scales[i]);
            List<SensorFrame> rubTrace = residualTailDoublePinchFrames(start + 1_000_000_000L, scales[i] + 0.0001);
            GestureNoiseProfile profile = GestureNoiseProfile.legacySynthetic();
            assertTrue(GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE, pinchTrace, profile).accepted());
            assertTrue(GestureSignalQuality.evaluate(GestureKind.RUB_INDEX, rubTrace, profile).accepted());
            samples.add(GestureSample.of(GestureKind.DOUBLE_PINCH_MIDDLE, pinchTrace, SOURCE, true));
            samples.add(GestureSample.of(GestureKind.RUB_INDEX, rubTrace, SOURCE, true));
            samples.add(GestureSample.of(GestureKind.UNKNOWN,
                    pattern(GestureKind.UNKNOWN, i + 20, 1.0, start + 1_500_000_000L), SOURCE, true));
        }

        GestureModel model = GestureModel.train(samples);
        GestureModel.TrainingDiagnostics diagnostics = model.trainingDiagnostics();
        assertTrue(diagnostics.summary(), diagnostics.leaveOneOutCrossClassOverlaps(GestureKind.DOUBLE_PINCH_MIDDLE) > 0);
        assertTrue(diagnostics.summary(), diagnostics.leaveOneOutCrossClassOverlaps(GestureKind.RUB_INDEX) > 0);
        List<SensorFrame> ambiguous = residualTailDoublePinchFrames(120_000_000_000L, 0.80);
        GestureNoiseProfile noise = GestureNoiseProfile.legacySynthetic();
        assertTrue(GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE, ambiguous, noise).accepted());
        assertTrue(GestureSignalQuality.evaluate(GestureKind.RUB_INDEX, ambiguous, noise).accepted());
        GestureModel.Classification classification = model.classify(ambiguous);
        assertFalse("both quality-qualified classes still need the cross-class margin",
                classification.accepted());
        assertTrue(classification.margin() < 0.015);
        assertEquals("two class templates with near-identical features must not be named as either class",
                GestureKind.UNKNOWN, classification.kind());
    }

    @Test public void sampleRoundTripPreservesDataAndRelabelingCannotChangeFingerprint() {
        GestureSample original = GestureSample.of(GestureKind.DOUBLE_PINCH_MIDDLE,
                pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 1, 1.01, 0L), SOURCE, true);
        GestureSample restored = GestureSample.fromJson(original.toJson());
        assertEquals(original.fingerprint(), restored.fingerprint());
        assertEquals(original.fingerprint(), restored.withKind(GestureKind.RUB_INDEX).fingerprint());
        assertTrue(restored.synthetic());
        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE, restored.kind());

        String tamperedMetadata = original.toJson().replace("\"synthetic\":true", "\"synthetic\":false");
        assertNotEquals(original.toJson(), tamperedMetadata);
        try {
            GestureSample.fromJson(tamperedMetadata);
            fail("sample metadata tampering must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("integrity"));
        }
    }

    @Test public void heldOutLeakAndSyntheticEvidenceCannotProduceVerifiedProfile() {
        GestureModel model = model(0);
        assertTrue("synthetic training provenance is part of the model", model.syntheticTraining());
        assertTrue("a model trained on diagnostics is permanently ineligible", new GestureProfileVerifier(model).profile().failClosed());
        assertTrue(GestureModel.fromJson(model.toJson()).syntheticTraining());
        GestureProfileVerifier verifier = new GestureProfileVerifier(model);
        GestureSample leaked = trainingSample(model, GestureKind.DOUBLE_PINCH_MIDDLE);
        GestureModel.Classification leakedResult = verifier.recordHeldOut(
                GestureKind.DOUBLE_PINCH_MIDDLE, leaked, true, SOURCE);
        assertEquals(GestureKind.UNKNOWN, leakedResult.kind());
        assertEquals(1, verifier.profile().pinchTrials());
        assertEquals(0, verifier.profile().pinchCorrect());
        assertTrue(verifier.profile().failClosed());

        GestureProfileVerifier syntheticVerifier = new GestureProfileVerifier(model);
        for (int i = 0; i < 20; i++) {
            GestureSample pinch = GestureSample.of(GestureKind.DOUBLE_PINCH_MIDDLE,
                    pattern(GestureKind.DOUBLE_PINCH_MIDDLE, 0, 1.08 + i * 0.001, 0L), SOURCE, true);
            GestureSample rub = GestureSample.of(GestureKind.RUB_INDEX,
                    pattern(GestureKind.RUB_INDEX, 0, 1.08 + i * 0.001, 0L), SOURCE, true);
            syntheticVerifier.recordHeldOut(GestureKind.DOUBLE_PINCH_MIDDLE, pinch, false, SOURCE);
            syntheticVerifier.recordHeldOut(GestureKind.RUB_INDEX, rub, false, SOURCE);
        }
        syntheticVerifier.recordNormalMotion(600_000L, 0, false, SOURCE);
        for (int i = 0; i < 20; i++) syntheticVerifier.recordPhysicalLatency(100, false, SOURCE);
        assertFalse(syntheticVerifier.isProductionVerified());
        assertTrue(syntheticVerifier.profile().failClosed());
    }

    @Test public void missingHeldOutTrialCountsAsUnknownAndProgressRoundTripsFailClosed() {
        GestureModel model = model(0);
        GestureProfileVerifier verifier = new GestureProfileVerifier(model);
        verifier.recordMissingHeldOut(GestureKind.RUB_INDEX, true, "OWW251");
        assertEquals(1, verifier.profile().rubTrials());
        assertEquals(0, verifier.profile().rubCorrect());

        GestureSample synthetic = GestureSample.of(GestureKind.RUB_INDEX,
                pattern(GestureKind.RUB_INDEX, 1, 1.01, 0L), SOURCE, true);
        verifier.recordHeldOut(GestureKind.RUB_INDEX, synthetic, false, SOURCE);
        GestureProfileVerifier restored = GestureProfileVerifier.fromJson(model, verifier.toJson());
        assertEquals(verifier.profile().rubTrials(), restored.profile().rubTrials());
        assertEquals(verifier.profile().modelRevision(), restored.profile().modelRevision());
        assertTrue(restored.profile().failClosed());

        GestureVerificationProfile saved = GestureVerificationProfile.fromJson(restored.profile().toJson());
        assertEquals(model.revision(), saved.modelRevision());
        assertFalse(saved.isProductionVerifiedFor(model));
        assertFalse(saved.isProductionVerifiedFor(model(1)));
        try {
            GestureProfileVerifier.fromJson(model(1), verifier.toJson());
            fail("stale model progress must not be imported");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("model revision changed"));
        }
    }

    @Test public void heldOutAccuracyRequiresAtLeastNinetyFivePercent() {
        GestureVerificationProfile exactlyNinetyFive = new GestureVerificationProfile("rev", 20, 19, 20, 19,
                0, 600_000L, 0, 20, 100, true, false, true,
                Collections.singletonList(SOURCE), Collections.singletonList("synthetic evidence"));
        GestureVerificationProfile lowOverallRate = new GestureVerificationProfile("rev", 100, 19, 20, 20,
                0, 600_000L, 0, 20, 100, true, false, true,
                Collections.singletonList(SOURCE), Collections.singletonList("synthetic evidence"));
        assertTrue(exactlyNinetyFive.heldOutAccuracyPasses());
        assertFalse(lowOverallRate.heldOutAccuracyPasses());
        assertFalse(exactlyNinetyFive.isProductionVerifiedFor(model(0)));

        GestureVerificationProfile otherwisePassingDiagnostic = new GestureVerificationProfile(model(0).revision(),
                20, 20, 20, 20, 0, 600_000L, 0, 20, 100, false, true, true,
                Collections.singletonList("OWW251"), Collections.<String>emptyList());
        assertFalse("a profile tied to a synthetic model cannot verify, even if counts look good",
                otherwisePassingDiagnostic.isProductionVerifiedFor(model(0)));
    }

    @Test public void stillNoiseProfileRequiresContinuousStillCaptureAndRoundTrips() {
        GestureNoiseProfile profile = GestureNoiseProfile.fromStillFrames(stillFrames(20_000_000_000L, 50_000_000L, 81, false));
        GestureNoiseProfile restored = GestureNoiseProfile.fromJson(profile.toJson());
        assertFalse(profile.synthetic());
        assertEquals(profile.revision(), restored.revision());
        assertEquals(81, restored.frameCount());
        assertEquals(4_000_000_000L, restored.durationNanos());
        assertEquals(profile.baseline(2), restored.baseline(2), 0.0);
        assertEquals(profile.noiseSigma(4), restored.noiseSigma(4), 0.0);

        assertIllegalNoiseProfile(stillFrames(20_000_000_000L, 50_000_000L, 61, false), "incomplete capture");
        assertIllegalNoiseProfile(stillFrames(20_000_000_000L, 50_000_000L, 81, true), "moving watch");
        assertIllegalNoiseProfile(stillFramesWithGap(20_000_000_000L), "sensor gap");
    }

    @Test public void signalQualitySeparatesRestRingingDoublePinchRubAndNegativeMotion() {
        GestureNoiseProfile profile = GestureNoiseProfile.fromStillFrames(
                stillFrames(30_000_000_000L, 50_000_000L, 81, false));
        GestureSignalQuality.Result rest = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                stillFrames(40_000_000_000L, 50_000_000L, 81, false), profile);
        assertFalse("stillness cannot satisfy a gesture activity gate", rest.accepted());

        GestureSignalQuality.Result single = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                singlePinchFrames(50_000_000_000L), profile);
        assertFalse("one impact must not satisfy a double pinch", single.accepted());
        assertEquals(1, single.pulseCount());

        GestureSignalQuality.Result ringing = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                ringingFrames(60_000_000_000L), profile);
        assertFalse("short ringing after one impact is still one pinch pulse", ringing.accepted());
        assertEquals(1, ringing.pulseCount());

        GestureSignalQuality.Result doublePinch = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, 70_000_000_000L), profile);
        assertTrue(doublePinch.reason() + " (pulse count=" + doublePinch.pulseCount() + ")", doublePinch.accepted());
        assertEquals(2, doublePinch.pulseCount());

        GestureSignalQuality.Result residualTailDouble = GestureSignalQuality.evaluate(
                GestureKind.DOUBLE_PINCH_MIDDLE, residualTailDoublePinchFrames(75_000_000_000L), profile);
        assertTrue("two prominent peaks separated by a deep relative valley can qualify when the absolute floor stays high: "
                + residualTailDouble.reason(), residualTailDouble.accepted());
        assertEquals(2, residualTailDouble.pulseCount());

        GestureSignalQuality.Result unequalDouble = GestureSignalQuality.evaluate(
                GestureKind.DOUBLE_PINCH_MIDDLE, residualTailDoublePinchFrames(76_000_000_000L, 0.55), profile);
        assertTrue("a weaker second pinch remains eligible when it rises clearly from the valley: "
                + unequalDouble.reason(), unequalDouble.accepted());

        GestureSignalQuality.Result rub = GestureSignalQuality.evaluate(GestureKind.RUB_INDEX,
                briefRubFrames(80_000_000_000L), profile);
        assertTrue(rub.reason(), rub.accepted());

        List<SensorFrame> broadNormalMotion = patternFrames(GestureKind.UNKNOWN, 1.0, 90_000_000_000L);
        GestureModel model = model(0);
        assertEquals("normal motion remains UNKNOWN after signal gates and personalized DTW",
                GestureKind.UNKNOWN, model.classify(broadNormalMotion).kind());
    }

    @Test public void signalQualityRejectsGapsAndSaturatesPulseStorageSafely() {
        GestureNoiseProfile profile = GestureNoiseProfile.fromStillFrames(
                stillFrames(100_000_000_000L, 50_000_000L, 81, false));
        List<SensorFrame> regular = patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, 110_000_000_000L);
        ArrayList<SensorFrame> withGap = new ArrayList<>();
        for (int i = 0; i < regular.size(); i++) {
            SensorFrame frame = regular.get(i);
            long timestamp = frame.timestampNanos() + (i >= 12 ? 150_000_000L : 0L);
            withGap.add(frame(timestamp, frame.accelX(), frame.accelY(), frame.accelZ(),
                    frame.gyroX(), frame.gyroY(), frame.gyroZ()));
        }
        GestureSignalQuality.Result gap = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                withGap, profile);
        assertFalse(gap.accepted());
        assertTrue(gap.reason(), gap.reason().contains("采样间断"));

        GestureSignalQuality.Result manyPulses = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                pulseTrain(120_000_000_000L, 18), profile);
        assertFalse("more than two distinct pulses cannot be a double pinch", manyPulses.accepted());
        assertEquals("pulse count is safely saturated at its bounded storage size", 16, manyPulses.pulseCount());
    }

    @Test public void detectorEmitsOneCandidateForDoublePinchAndWaitsForCalmToRearm() {
        GestureDetector detector = new GestureDetector(detectorModel(), SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long now = 1_000_000_000L;
        for (int i = 0; i <= 40; i++) push(detector, events, frame(now + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        now += 41 * 20_000_000L;
        for (SensorFrame frame : patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, now)) push(detector, events, frame);
        long firstEnd = events.get(0).endedAtNanos();
        assertEquals(1, events.size());
        assertEquals(GestureKind.DOUBLE_PINCH_MIDDLE, events.get(0).kind());
        assertTrue(events.get(0).emittedAtNanos() > events.get(0).endedAtNanos());
        assertTrue(events.get(0).frameCount() <= GestureDetector.MAX_SEGMENT_FRAMES);

        long suppressedStart = firstEnd + 500_000_000L;
        for (SensorFrame frame : patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, suppressedStart)) push(detector, events, frame);
        assertEquals("a second motion before the rearm calm interval must be ignored", 1, events.size());

        long rearmedStart = suppressedStart + 44 * 20_000_000L + 800_000_000L;
        for (long t = suppressedStart + 44 * 20_000_000L + 20_000_000L; t < rearmedStart; t += 20_000_000L) {
            push(detector, events, frame(t, 0, 0, 0, 0, 0, 0));
        }
        for (SensorFrame frame : patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, rearmedStart)) push(detector, events, frame);
        assertEquals(2, events.size());
    }

    @Test public void detectorKeepsAQuickDoublePinchTogetherAndClosesWithinLatencyBudget() {
        GestureNoiseProfile profile = GestureNoiseProfile.fromStillFrames(
                stillFrames(130_000_000_000L, 50_000_000L, 81, false));
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, false, profile);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 140_000_000_000L;
        for (int i = 0; i <= 40; i++) push(detector, events, frame(start + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        long actionStart = start + 41 * 20_000_000L;
        List<SensorFrame> gesture = doublePinchFrames(actionStart, 3, 28, 64);
        for (SensorFrame sample : gesture) push(detector, events, sample);
        assertEquals("a 500 ms onset gap between pulses must stay inside one segment", 1, events.size());

        GestureDetector.DetectionEvent event = events.get(0);
        assertNotNull(event.sample());
        GestureSignalQuality.Result quality = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                event.sample().frames(), profile);
        assertTrue(quality.reason(), quality.accepted());
        assertEquals(2, quality.pulseCount());
        long physicalEnd = gesture.get(33).timestampNanos();
        assertTrue("a closed candidate must be available within 500 ms of the final physical motion; delay="
                        + (event.emittedAtNanos() - physicalEnd) / 1_000_000L + " ms",
                event.emittedAtNanos() - physicalEnd <= 500_000_000L);
    }

    @Test public void detectorClosesAfterARealisticNoisyStillTailWithoutOverflow() {
        long start = 150_000_000_000L;
        long step = 9_360_000L;
        List<SensorFrame> calibration = new ArrayList<>();
        for (int i = 0; i < 430; i++) calibration.add(restNoiseFrame(start + i * step));
        GestureNoiseProfile profile = GestureNoiseProfile.fromStillFrames(calibration);
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, false, profile);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        for (SensorFrame sample : calibration) push(detector, events, sample);

        long actionStart = start + calibration.size() * step;
        for (int i = 0; i < 96; i++) {
            long timestamp = actionStart + i * step;
            double pinch = pulse(i, 8, 14) + pulse(i, 38, 44);
            SensorFrame noise = restNoiseFrame(timestamp);
            push(detector, events, frame(timestamp, noise.accelX() + 4.0 * pinch,
                    noise.accelY(), noise.accelZ(), noise.gyroX(),
                    noise.gyroY() + 5.0 * pinch, noise.gyroZ()));
        }
        long tailStart = actionStart + 96 * step;
        for (int i = 0; i < 90; i++) {
            long timestamp = tailStart + i * step;
            push(detector, events, restNoiseFrame(timestamp));
        }

        assertEquals("one noisy double pinch should close as one bounded candidate", 1, events.size());
        GestureDetector.DetectionEvent event = events.get(0);
        assertNotNull(event.sample());
        GestureSignalQuality.Result quality = GestureSignalQuality.evaluate(GestureKind.DOUBLE_PINCH_MIDDLE,
                event.sample().frames(), profile);
        assertTrue(quality.reason(), quality.accepted());
        assertEquals(2, quality.pulseCount());
        assertTrue(event.frameCount() < GestureDetector.MAX_SEGMENT_FRAMES);
    }

    @Test public void detectorRejectsOneSampleSpikeThenCapturesDoublePinchAfter40MsConfirmation() {
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 8_000_000_000L;
        long restStep = 10_000_000L;
        for (int i = 0; i <= 80; i++) {
            push(detector, events, frame(start + i * restStep, 0, 0, 0, 0, 0, 0));
        }

        long spikeAt = start + 81 * restStep;
        push(detector, events, frame(spikeAt, 0.6, 0, 0, 0, 0, 0));
        for (int i = 1; i <= 30; i++) {
            push(detector, events, frame(spikeAt + i * restStep, 0, 0, 0, 0, 0, 0));
        }
        assertTrue("a one-frame envelope crossing must be canceled before opening a candidate", events.isEmpty());

        long actionStart = spikeAt + 31 * restStep;
        for (SensorFrame sample : patternFrames(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0, actionStart)) {
            push(detector, events, sample);
        }
        assertEquals("the synthetic two-pulse pattern remains capturable", 1, events.size());
        GestureDetector.DetectionEvent event = events.get(0);
        long firstPinchCrossing = actionStart + 3 * 20_000_000L;
        assertEquals("startedAt stays anchored to the first threshold crossing",
                firstPinchCrossing, event.startedAtNanos());
        assertNotNull(event.sample());
        assertEquals("pre-roll is cut from the first crossing, not the confirmation frame",
                event.startedAtNanos() - GestureDetector.PRE_ROLL_NANOS,
                event.sample().frames().get(0).timestampNanos());
    }

    @Test public void detectorArmsThroughRestNoiseAndKeepsRawPrerollForCandidate() {
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 9_000_000_000L;
        long step = 9_360_000L;
        SensorFrame prior = null;
        int noisyRawIntervals = 0;
        for (int i = 0; i <= 110; i++) {
            SensorFrame sample = restNoiseFrame(start + i * step);
            if (prior != null && GestureFeatures.change(prior, sample) >= 0.50) noisyRawIntervals++;
            push(detector, events, sample);
            prior = sample;
        }
        assertTrue("synthetic rest jitter should include raw threshold crossings", noisyRawIntervals >= 10);
        assertEquals("rest jitter must not create a candidate", 0, events.size());

        long actionStart = start + 111 * step;
        int actionFrames = 85;
        for (int i = 0; i < actionFrames; i++) {
            double pinch = pulse(i, 8, 14) + pulse(i, 24, 30);
            SensorFrame noise = restNoiseFrame(actionStart + i * step);
            push(detector, events, frame(actionStart + i * step,
                    noise.accelX() + 4.0 * pinch, noise.accelY(), noise.accelZ(),
                    noise.gyroX(), noise.gyroY() + 5.0 * pinch, noise.gyroZ()));
        }
        long quietStart = actionStart + actionFrames * step;
        for (int i = 0; i < 60; i++) push(detector, events, frame(quietStart + i * step, 0, 0, 9.81, 0, 0, 0));

        assertEquals("the first true double pinch must be collected after noisy stillness", 1, events.size());
        GestureDetector.DetectionEvent event = events.get(0);
        assertEquals(GestureKind.UNKNOWN, event.kind());
        assertNotNull(event.sample());
        assertTrue("bounded pre-roll must retain the original frames before the filtered trigger",
                event.sample().frames().get(0).timestampNanos() < event.startedAtNanos());
        assertTrue(event.sample().frames().get(0).timestampNanos() >= event.startedAtNanos() - GestureDetector.PRE_ROLL_NANOS);
        long quietTail = event.emittedAtNanos() - event.endedAtNanos();
        assertTrue("candidate closes after the 400 ms quiet gap", quietTail >= GestureDetector.SEGMENT_END_GAP_NANOS
                && quietTail < GestureDetector.SEGMENT_END_GAP_NANOS + 20_000_000L);
    }

    @Test public void captureOnlyCreatesUnknownSampleThatCanBePromptRelabeled() {
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long now = 1_000_000_000L;
        for (int i = 0; i <= 40; i++) push(detector, events, frame(now + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        now += 41 * 20_000_000L;
        for (SensorFrame frame : patternFrames(GestureKind.RUB_INDEX, 1.0, now)) push(detector, events, frame);
        now += 64 * 20_000_000L;
        for (int i = 0; i < 24; i++) push(detector, events,
                frame(now + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals(1, events.size());
        assertEquals(GestureKind.UNKNOWN, events.get(0).kind());
        assertNotNull(events.get(0).sample());
        assertTrue(events.get(0).sample().synthetic());
        assertEquals(GestureKind.RUB_INDEX, events.get(0).sample().withKind(GestureKind.RUB_INDEX).kind());
    }

    @Test public void detectorPreservesFastSensorsWithinBoundedStorage() {
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long now = 3_000_000_000L;
        long step = 1_250_000L; // 800 Hz input
        for (int i = 0; i <= 640; i++) push(detector, events, frame(now + i * step, 0, 0, 0, 0, 0, 0));
        now += 641 * step;
        int activeFrames = 2_400; // three seconds at 800 Hz
        for (int i = 0; i < activeFrames; i++) {
            double wave = 8.0 * Math.sin(i * Math.PI / 40.0);
            push(detector, events, frame(now + i * step, wave, 0, 0, 0, wave, 0));
        }
        now += activeFrames * step;
        // Allow the 60 ms envelope to settle, followed by the 250 ms quiet tail.
        for (int i = 0; i <= 400; i++) push(detector, events, frame(now + i * step, 0, 0, 0, 0, 0, 0));
        assertEquals(1, events.size());
        assertTrue("even this large-amplitude signal closes within 500 ms of physical stop",
                events.get(0).emittedAtNanos() - now <= 500_000_000L);
        assertTrue(events.get(0).frameCount() > 220);
        assertTrue(events.get(0).frameCount() <= GestureDetector.MAX_SEGMENT_FRAMES);
        assertNotNull(events.get(0).sample());
        assertTrue(events.get(0).sample().frames().size() > 220);
    }

    @Test public void detectorBoundsContinuousMotionAndRejectsOverlongCandidate() {
        GestureDetector detector = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 5_000_000_000L;
        for (int i = 0; i <= 40; i++) push(detector, events, frame(start + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        long actionStart = start + 41 * 20_000_000L;
        for (int i = 0; i < 190; i++) {
            double alternating = i % 2 == 0 ? 1.0 : -1.0;
            push(detector, events, frame(actionStart + i * 20_000_000L, alternating, 0, 0, 0, 0, 0));
        }
        assertEquals("continuous motion is capped without waiting for a calm tail", 1, events.size());
        assertEquals(GestureKind.UNKNOWN, events.get(0).kind());
        assertNull("overlong candidates cannot be relabeled into calibration data", events.get(0).sample());
        assertTrue(events.get(0).emittedAtNanos() - events.get(0).startedAtNanos()
                <= GestureDetector.MAX_SEGMENT_NANOS + 20_000_000L);
    }

    @Test public void timeBasedFeaturesAreStableWhenSamplingRateChangesWithinASegment() {
        double[] regular = GestureFeatures.extract(waveFrames(false));
        double[] irregular = GestureFeatures.extract(waveFrames(true));
        double[] costs = new double[GestureFeatures.POINTS + 1];
        double[] nextCosts = new double[GestureFeatures.POINTS + 1];
        int[] lengths = new int[GestureFeatures.POINTS + 1];
        int[] nextLengths = new int[GestureFeatures.POINTS + 1];
        assertTrue(GestureFeatures.distance(regular, irregular, costs, nextCosts, lengths, nextLengths) < 0.05);
    }

    private static GestureModel model(int variant) {
        ArrayList<GestureSample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(GestureSample.of(GestureKind.DOUBLE_PINCH_MIDDLE,
                    pattern(GestureKind.DOUBLE_PINCH_MIDDLE, i + variant, 1.0 + 0.01 * i, 0L), SOURCE, true));
            samples.add(GestureSample.of(GestureKind.RUB_INDEX,
                    pattern(GestureKind.RUB_INDEX, i + variant, 1.0 + 0.01 * i, 0L), SOURCE, true));
            if (i < 3) samples.add(GestureSample.of(GestureKind.UNKNOWN,
                    pattern(GestureKind.UNKNOWN, i + variant, 1.0 + 0.02 * i, 0L), SOURCE, true));
        }
        return GestureModel.train(samples);
    }

    private static double distanceThreshold(GestureModel model, GestureKind kind) throws Exception {
        int index = kind == GestureKind.DOUBLE_PINCH_MIDDLE ? 0 : 1;
        return new JSONObject(model.toJson()).getJSONArray("distanceThresholds").getDouble(index);
    }

    private static int qualityMask(GestureModel model, String sampleId) throws Exception {
        JSONArray rows = new JSONObject(model.toJson()).getJSONArray("templates");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (sampleId.equals(row.getString("id"))) return row.getInt("qualityMask");
        }
        throw new AssertionError("template was not serialized");
    }

    private static GestureModel detectorModel() {
        ArrayList<GestureSample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(capturedSample(GestureKind.DOUBLE_PINCH_MIDDLE, 1.0 + 0.01 * i));
            samples.add(capturedSample(GestureKind.RUB_INDEX, 1.0 + 0.01 * i));
            if (i < 3) samples.add(GestureSample.of(GestureKind.UNKNOWN,
                    pattern(GestureKind.UNKNOWN, i, 1.0 + 0.02 * i, 0L), SOURCE, true));
        }
        return GestureModel.train(samples);
    }

    private static GestureSample capturedSample(GestureKind kind, double scale) {
        GestureDetector captureOnly = GestureDetector.captureOnly(SOURCE, true);
        ArrayList<GestureDetector.DetectionEvent> events = new ArrayList<>();
        long start = 2_000_000_000L;
        for (int i = 0; i <= 40; i++) push(captureOnly, events, frame(start + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        long actionStart = start + 41 * 20_000_000L;
        for (SensorFrame frame : patternFrames(kind, scale, actionStart)) push(captureOnly, events, frame);
        long tailStart = actionStart + (kind == GestureKind.DOUBLE_PINCH_MIDDLE ? 44 : 64) * 20_000_000L;
        for (int i = 0; i < 24; i++) push(captureOnly, events,
                frame(tailStart + i * 20_000_000L, 0, 0, 0, 0, 0, 0));
        if (events.size() != 1 || events.get(0).sample() == null) throw new AssertionError("could not capture synthetic template");
        return events.get(0).sample().withKind(kind);
    }

    private static GestureSample trainingSample(GestureModel model, GestureKind kind) {
        for (GestureSample sample : modelSamplesForTest()) {
            if (sample.kind() == kind && model.trainingSampleIds().contains(sample.fingerprint())) return sample;
        }
        throw new AssertionError("training sample not found");
    }

    private static List<GestureSample> modelSamplesForTest() {
        ArrayList<GestureSample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(GestureSample.of(GestureKind.DOUBLE_PINCH_MIDDLE,
                    pattern(GestureKind.DOUBLE_PINCH_MIDDLE, i, 1.0 + 0.01 * i, 0L), SOURCE, true));
            samples.add(GestureSample.of(GestureKind.RUB_INDEX,
                    pattern(GestureKind.RUB_INDEX, i, 1.0 + 0.01 * i, 0L), SOURCE, true));
            if (i < 3) samples.add(GestureSample.of(GestureKind.UNKNOWN,
                    pattern(GestureKind.UNKNOWN, i, 1.0 + 0.02 * i, 0L), SOURCE, true));
        }
        return samples;
    }

    private static List<SensorFrame> scaledFrames(List<SensorFrame> source, double scale) {
        SensorFrame base = source.get(0);
        ArrayList<SensorFrame> scaled = new ArrayList<>(source.size());
        for (SensorFrame sample : source) {
            scaled.add(frame(sample.timestampNanos(),
                    base.accelX() + (sample.accelX() - base.accelX()) * scale,
                    base.accelY() + (sample.accelY() - base.accelY()) * scale,
                    base.accelZ() + (sample.accelZ() - base.accelZ()) * scale,
                    base.gyroX() + (sample.gyroX() - base.gyroX()) * scale,
                    base.gyroY() + (sample.gyroY() - base.gyroY()) * scale,
                    base.gyroZ() + (sample.gyroZ() - base.gyroZ()) * scale));
        }
        return scaled;
    }

    private static List<SensorFrame> pattern(GestureKind kind, int variant, double scale, long startNanos) {
        return patternFrames(kind, scale + variant * 0.001, startNanos);
    }

    private static List<SensorFrame> patternFrames(GestureKind kind, double scale, long startNanos) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        int count = kind == GestureKind.DOUBLE_PINCH_MIDDLE ? 44 : 64;
        for (int i = 0; i < count; i++) {
            double ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
            if (kind == GestureKind.DOUBLE_PINCH_MIDDLE) {
                double pulse = pulse(i, 3, 7) + pulse(i, 17, 21);
                ax = 4.0 * scale * pulse;
                gy = 5.0 * scale * pulse;
            } else if (kind == GestureKind.RUB_INDEX) {
                double window = i < 8 || i > 55 ? 0 : 1;
                double wave = Math.sin((i - 8) * Math.PI / 6.0) * window;
                ay = 2.5 * scale * wave;
                gz = 4.0 * scale * wave;
            } else {
                double broad = Math.sin(i * Math.PI / 18.0);
                az = 2.0 * scale * broad;
                gx = 1.5 * scale * Math.sin(i * Math.PI / 11.0);
            }
            frames.add(frame(startNanos + i * 20_000_000L, ax, ay, az, gx, gy, gz));
        }
        return frames;
    }

    private static List<SensorFrame> doublePinchFrames(long startNanos, int firstPulseStart,
                                                        int secondPulseStart, int count) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double pulse = pulse(i, firstPulseStart, firstPulseStart + 4)
                    + pulse(i, secondPulseStart, secondPulseStart + 4);
            frames.add(frame(startNanos + i * 20_000_000L, 4.0 * pulse, 0, 0,
                    0, 5.0 * pulse, 0));
        }
        return frames;
    }

    private static List<SensorFrame> singlePinchFrames(long startNanos) {
        return doublePinchFrames(startNanos, 8, 10_000, 60);
    }

    private static List<SensorFrame> ringingFrames(long startNanos) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double seconds = i * 0.01;
            double elapsed = seconds - 0.20;
            double ring = elapsed < 0.0 || elapsed > 0.65 ? 0.0
                    : 1.3 * Math.exp(-elapsed / 0.16) * Math.sin(2.0 * Math.PI * 18.0 * elapsed);
            frames.add(frame(startNanos + i * 10_000_000L, 4.0 * ring, 0, 0,
                    0, 5.0 * ring, 0));
        }
        return frames;
    }

    private static List<SensorFrame> residualTailDoublePinchFrames(long startNanos) {
        return residualTailDoublePinchFrames(startNanos, 1.0);
    }

    private static List<SensorFrame> residualTailDoublePinchFrames(long startNanos, double secondScale) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            double seconds = i * 0.01;
            double residual = 0.22 * Math.sin(2.0 * Math.PI * 28.0 * seconds);
            double first = pulse(i, 30, 34);
            double second = secondScale * pulse(i, 56, 60);
            frames.add(frame(startNanos + i * 10_000_000L,
                    4.0 * (residual + first + second), 0, 0,
                    0, 5.0 * (residual + first + second), 0));
        }
        return frames;
    }

    private static List<SensorFrame> briefRubFrames(long startNanos) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            double seconds = i * 0.01;
            double elapsed = seconds - 0.15;
            double window = elapsed < 0.0 || elapsed > 0.45 ? 0.0 : 1.0;
            double wave = Math.sin(2.0 * Math.PI * 8.0 * elapsed) * window;
            frames.add(frame(startNanos + i * 10_000_000L, 0, 2.5 * wave, 0,
                    0, 0, 4.0 * wave));
        }
        return frames;
    }

    private static List<SensorFrame> pulseTrain(long startNanos, int pulseCount) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        int total = 1_000;
        for (int i = 0; i < total; i++) {
            double value = 0.0;
            for (int pulse = 0; pulse < pulseCount; pulse++) {
                int pulseStart = 20 + pulse * 50;
                value += pulse(i, pulseStart, pulseStart + 8);
            }
            frames.add(frame(startNanos + i * 5_000_000L, 4.0 * value, 0, 0,
                    0, 5.0 * value, 0));
        }
        return frames;
    }

    private static List<SensorFrame> stillFrames(long startNanos, long stepNanos, int count, boolean moving) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double seconds = i * stepNanos / 1_000_000_000.0;
            double gyroX = moving ? 0.9 * Math.sin(2.0 * Math.PI * 2.0 * seconds)
                    : 0.002 * Math.sin(2.0 * Math.PI * 1.1 * seconds);
            frames.add(frame(startNanos + i * stepNanos,
                    0.003 * Math.sin(2.0 * Math.PI * 0.7 * seconds),
                    0.002 * Math.sin(2.0 * Math.PI * 0.9 * seconds),
                    9.81 + 0.004 * Math.sin(2.0 * Math.PI * 0.6 * seconds),
                    gyroX,
                    0.002 * Math.sin(2.0 * Math.PI * 0.8 * seconds),
                    0.001 * Math.sin(2.0 * Math.PI * 1.0 * seconds)));
        }
        return frames;
    }

    private static List<SensorFrame> stillFramesWithGap(long startNanos) {
        List<SensorFrame> regular = stillFrames(startNanos, 50_000_000L, 81, false);
        ArrayList<SensorFrame> frames = new ArrayList<>();
        for (int i = 0; i < regular.size(); i++) {
            SensorFrame sample = regular.get(i);
            long timestamp = sample.timestampNanos() + (i >= 40 ? 200_000_000L : 0L);
            frames.add(frame(timestamp, sample.accelX(), sample.accelY(), sample.accelZ(),
                    sample.gyroX(), sample.gyroY(), sample.gyroZ()));
        }
        return frames;
    }

    private static void assertIllegalNoiseProfile(List<SensorFrame> frames, String context) {
        try {
            GestureNoiseProfile.fromStillFrames(frames);
            fail(context + " must not produce a still-noise profile");
        } catch (IllegalArgumentException expected) {
            // Invalid calibration is a deliberate fail-closed outcome.
        }
    }

    private static List<SensorFrame> waveFrames(boolean irregular) {
        ArrayList<SensorFrame> frames = new ArrayList<>();
        long end = 1_200_000_000L;
        if (!irregular) {
            for (long t = 0; t <= end; t += 10_000_000L) frames.add(waveFrame(t));
        } else {
            long t = 0L;
            while (t <= end) {
                frames.add(waveFrame(t));
                t += t < 600_000_000L ? 5_000_000L : 20_000_000L;
            }
            if (frames.get(frames.size() - 1).timestampNanos() < end) frames.add(waveFrame(end));
        }
        return frames;
    }

    private static SensorFrame waveFrame(long t) {
        double seconds = t / 1_000_000_000.0;
        double wave = 1.2 * Math.sin(2.0 * Math.PI * 5.0 * seconds);
        return frame(t, wave, 0, 0, 0, 0.6 * wave, 0);
    }

    private static double pulse(int frame, int start, int end) {
        if (frame < start || frame > end) return 0.0;
        int center = (start + end) / 2;
        return frame <= center ? (frame - start + 1.0) / (center - start + 1.0)
                : (end - frame + 1.0) / (end - center + 1.0);
    }

    private static void push(GestureDetector detector, List<GestureDetector.DetectionEvent> events, SensorFrame frame) {
        GestureDetector.DetectionEvent event = detector.accept(frame);
        if (event != null) events.add(event);
    }

    private static SensorFrame frame(long t, double ax, double ay, double az, double gx, double gy, double gz) {
        return new SensorFrame(t, ax, ay, az, gx, gy, gz);
    }

    private static SensorFrame restNoiseFrame(long t) {
        double seconds = t / 1_000_000_000.0;
        return frame(t,
                0.070 * Math.sin(2.0 * Math.PI * 18.0 * seconds)
                        + 0.035 * Math.sin(2.0 * Math.PI * 29.0 * seconds + 0.3),
                0.060 * Math.sin(2.0 * Math.PI * 13.0 * seconds + 0.6),
                9.81 + 0.070 * Math.sin(2.0 * Math.PI * 22.0 * seconds + 0.8),
                0.018 * Math.sin(2.0 * Math.PI * 15.0 * seconds),
                0.022 * Math.sin(2.0 * Math.PI * 19.0 * seconds + 0.5),
                0.015 * Math.sin(2.0 * Math.PI * 25.0 * seconds + 0.2));
    }
}
