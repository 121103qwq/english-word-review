package cn.wordwatch.app.gesture.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Accumulates held-out and real-device evidence. A single invalid or synthetic item fails the profile closed. */
public final class GestureProfileVerifier {
    public static final int MAX_HELD_OUT_TRIALS = 512;
    public static final int MAX_LATENCY_SAMPLES = 512;
    public static final int MAX_NORMAL_MOTION_ENTRIES = 512;
    public static final int MAX_PROGRESS_JSON_CHARS = 600_000;
    private static final int JSON_VERSION = 1;

    private final GestureModel model;
    private final String modelRevision;
    private final ArrayList<HeldOutRecord> heldOut = new ArrayList<>();
    private final ArrayList<NormalMotionRecord> normalMotion = new ArrayList<>();
    private final ArrayList<LatencyRecord> latencies = new ArrayList<>();
    private final Set<String> heldOutIds = new HashSet<>();
    private final Set<String> sources = new HashSet<>();
    private final ArrayList<String> failureReasons = new ArrayList<>();
    private boolean failClosed;
    private boolean realHardwareEvidence;

    public GestureProfileVerifier(GestureModel model) {
        if (model == null) throw new IllegalArgumentException("model is required");
        this.model = model;
        this.modelRevision = model.revision();
        if (model.syntheticTraining()) invalidate("synthetic training data cannot verify a production profile");
    }

    /** Scores a held-out gesture using the model; the sample label is deliberately ignored. */
    public GestureModel.Classification recordHeldOut(GestureKind expected, GestureSample sample,
                                                      boolean realHardware, String source) {
        requireExpected(expected);
        String safeSource = evidenceSource(source);
        boolean validEvidence = validateEvidence(realHardware, safeSource, sample == null ? null : sample.synthetic());
        GestureKind prediction = GestureKind.UNKNOWN;
        String sampleId = sample == null ? "" : sample.fingerprint();
        GestureModel.Classification classification = GestureModel.Classification.rejected(GestureKind.UNKNOWN,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        if (sample == null) {
            // The missing physical trial remains an explicit UNKNOWN miss; never silently repeat it.
        } else {
            if (!sample.source().equals(safeSource)) { invalidate("held-out source mismatch"); validEvidence = false; }
            if (sample.synthetic()) { invalidate("synthetic held-out sample"); validEvidence = false; }
            if (model.trainingSampleIds().contains(sampleId)) { invalidate("held-out sample overlaps training data"); validEvidence = false; }
            if (!heldOutIds.add(sampleId)) { invalidate("duplicate held-out sample"); validEvidence = false; }
            if (validEvidence) {
                classification = model.classify(sample.frames());
                prediction = classification.kind();
            }
        }
        appendHeldOut(new HeldOutRecord(expected, prediction, sampleId, safeSource,
                realHardware, sample != null && sample.synthetic()));
        return classification;
    }

    /** Records an attempted physical held-out trial that produced no usable candidate; it counts as UNKNOWN. */
    public void recordMissingHeldOut(GestureKind expected, boolean realHardware, String source) {
        requireExpected(expected);
        String safeSource = evidenceSource(source);
        validateEvidence(realHardware, safeSource, false);
        appendHeldOut(new HeldOutRecord(expected, GestureKind.UNKNOWN, "", safeSource, realHardware, false));
    }

    /** Adds observed normal-motion coverage and the number of recognized false positives in that interval. */
    public void recordNormalMotion(long durationMillis, int falsePositiveCount,
                                   boolean realHardware, String source) {
        String safeSource = evidenceSource(source);
        validateEvidence(realHardware, safeSource, false);
        if (durationMillis <= 0L || durationMillis > 604_800_000L || falsePositiveCount < 0 || falsePositiveCount > 100_000) {
            invalidate("normal-motion evidence out of bounds");
            return;
        }
        if (normalMotion.size() >= MAX_NORMAL_MOTION_ENTRIES) {
            invalidate("normal-motion evidence limit reached");
            return;
        }
        normalMotion.add(new NormalMotionRecord(durationMillis, falsePositiveCount, safeSource, realHardware));
    }

    /** Measures physical action-end to visible UI response, not classifier execution time. */
    public void recordPhysicalLatency(long latencyMillis, boolean realHardware, String source) {
        String safeSource = evidenceSource(source);
        validateEvidence(realHardware, safeSource, false);
        if (latencyMillis < 0L || latencyMillis > 60_000L) {
            invalidate("physical latency measurement out of bounds");
            return;
        }
        if (latencies.size() >= MAX_LATENCY_SAMPLES) {
            invalidate("latency evidence limit reached");
            return;
        }
        latencies.add(new LatencyRecord((int) latencyMillis, safeSource, realHardware));
    }

    public GestureVerificationProfile profile() {
        int pinchTrials = 0, pinchCorrect = 0, rubTrials = 0, rubCorrect = 0, cross = 0;
        for (HeldOutRecord record : heldOut) {
            if (record.expected == GestureKind.DOUBLE_PINCH_MIDDLE) {
                pinchTrials++;
                if (record.predicted == record.expected) pinchCorrect++;
            } else {
                rubTrials++;
                if (record.predicted == record.expected) rubCorrect++;
            }
            if (record.predicted != GestureKind.UNKNOWN && record.predicted != record.expected) cross++;
        }
        long motionMillis = 0L;
        int falsePositives = 0;
        for (NormalMotionRecord record : normalMotion) {
            motionMillis = Math.min(604_800_000L, motionMillis + record.durationMillis);
            falsePositives = Math.min(100_000, falsePositives + record.falsePositiveCount);
        }
        int p95 = latencyP95();
        ArrayList<String> sortedSources = new ArrayList<>(sources);
        Collections.sort(sortedSources);
        ArrayList<String> reasons = new ArrayList<>(failureReasons);
        return new GestureVerificationProfile(modelRevision, pinchTrials, pinchCorrect, rubTrials, rubCorrect,
                cross, motionMillis, falsePositives, latencies.size(), p95, failClosed,
                realHardwareEvidence, model.syntheticTraining(), sortedSources, reasons);
    }

    public boolean isProductionVerified() { return profile().isProductionVerifiedFor(model); }

    /** Exports evidence metadata and sample hashes; raw held-out motion is not copied into this progress file. */
    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", JSON_VERSION);
            object.put("modelRevision", modelRevision);
            object.put("failClosed", failClosed);
            object.put("realHardwareEvidence", realHardwareEvidence);
            object.put("failureReasons", strings(failureReasons));
            object.put("sources", strings(sorted(sources)));
            JSONArray trials = new JSONArray();
            for (HeldOutRecord record : heldOut) {
                JSONObject row = new JSONObject();
                row.put("expected", record.expected.name()); row.put("predicted", record.predicted.name());
                row.put("sampleId", record.sampleId); row.put("source", record.source);
                row.put("realHardware", record.realHardware); row.put("synthetic", record.synthetic);
                trials.put(row);
            }
            object.put("heldOut", trials);
            JSONArray motion = new JSONArray();
            for (NormalMotionRecord record : normalMotion) {
                JSONObject row = new JSONObject();
                row.put("durationMillis", record.durationMillis); row.put("falsePositives", record.falsePositiveCount);
                row.put("source", record.source); row.put("realHardware", record.realHardware);
                motion.put(row);
            }
            object.put("normalMotion", motion);
            JSONArray latency = new JSONArray();
            for (LatencyRecord record : latencies) {
                JSONObject row = new JSONObject();
                row.put("latencyMillis", record.latencyMillis); row.put("source", record.source);
                row.put("realHardware", record.realHardware);
                latency.put(row);
            }
            object.put("latencies", latency);
            object.put("checksum", checksum(object.toString()));
            String json = object.toString();
            if (json.length() > MAX_PROGRESS_JSON_CHARS) throw new IllegalStateException("verification progress exceeds size limit");
            return json;
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode verification progress", e);
        }
    }

    /** Imports bounded progress only for the same model revision; stale progress cannot verify a new model. */
    public static GestureProfileVerifier fromJson(GestureModel model, String json) {
        if (model == null) throw new IllegalArgumentException("model is required");
        if (json == null || json.length() > MAX_PROGRESS_JSON_CHARS) throw new IllegalArgumentException("verification progress is out of bounds");
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("version") != JSON_VERSION) throw new IllegalArgumentException("unsupported progress version");
            String checksum = object.getString("checksum");
            object.remove("checksum");
            if (!checksum.equals(checksum(object.toString()))) throw new IllegalArgumentException("verification progress checksum mismatch");
            if (!model.revision().equals(object.getString("modelRevision"))) throw new IllegalArgumentException("model revision changed; saved verification progress is invalid");
            GestureProfileVerifier verifier = new GestureProfileVerifier(model);
            verifier.failClosed = object.getBoolean("failClosed");
            verifier.realHardwareEvidence = object.getBoolean("realHardwareEvidence");
            verifier.failureReasons.addAll(readStrings(object.getJSONArray("failureReasons"), 32));
            Set<String> declaredSources = new HashSet<>(readStrings(object.getJSONArray("sources"), 32));

            JSONArray trials = object.getJSONArray("heldOut");
            if (trials.length() > MAX_HELD_OUT_TRIALS) throw new IllegalArgumentException("too many held-out trials");
            for (int i = 0; i < trials.length(); i++) {
                JSONObject row = trials.getJSONObject(i);
                GestureKind expected = GestureKind.valueOf(row.getString("expected"));
                GestureKind predicted = GestureKind.valueOf(row.getString("predicted"));
                requireExpected(expected);
                String id = row.getString("sampleId");
                if (!id.isEmpty() && !isHash(id)) throw new IllegalArgumentException("invalid held-out sample hash");
                String source = row.getString("source");
                boolean hardware = row.getBoolean("realHardware");
                boolean synthetic = row.getBoolean("synthetic");
                verifier.heldOut.add(new HeldOutRecord(expected, predicted, id, source, hardware, synthetic));
                if (!id.isEmpty() && !verifier.heldOutIds.add(id)) verifier.invalidate("duplicate held-out sample");
                if (!id.isEmpty() && model.trainingSampleIds().contains(id)) verifier.invalidate("held-out sample overlaps training data");
                verifier.validateEvidence(hardware, source, synthetic);
                if (predicted != GestureKind.UNKNOWN && predicted != expected) { /* counted in the profile */ }
            }

            JSONArray motion = object.getJSONArray("normalMotion");
            if (motion.length() > MAX_NORMAL_MOTION_ENTRIES) throw new IllegalArgumentException("too many normal-motion entries");
            for (int i = 0; i < motion.length(); i++) {
                JSONObject row = motion.getJSONObject(i);
                long duration = boundedLong(row, "durationMillis", 1L, 604_800_000L);
                int falsePositives = boundedInt(row, "falsePositives", 0, 100_000);
                String source = row.getString("source");
                boolean hardware = row.getBoolean("realHardware");
                verifier.normalMotion.add(new NormalMotionRecord(duration, falsePositives, source, hardware));
                verifier.validateEvidence(hardware, source, false);
            }

            JSONArray latency = object.getJSONArray("latencies");
            if (latency.length() > MAX_LATENCY_SAMPLES) throw new IllegalArgumentException("too many latency entries");
            for (int i = 0; i < latency.length(); i++) {
                JSONObject row = latency.getJSONObject(i);
                int millis = boundedInt(row, "latencyMillis", 0, 60_000);
                String source = row.getString("source");
                boolean hardware = row.getBoolean("realHardware");
                verifier.latencies.add(new LatencyRecord(millis, source, hardware));
                verifier.validateEvidence(hardware, source, false);
            }
            if (!declaredSources.equals(verifier.sources)) verifier.invalidate("saved evidence sources are inconsistent");
            if (verifier.realHardwareEvidence != object.getBoolean("realHardwareEvidence")) verifier.invalidate("saved hardware evidence flag is inconsistent");
            if (verifier.failClosed != object.getBoolean("failClosed")) verifier.invalidate("saved fail-closed flag is inconsistent");
            return verifier;
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid verification progress JSON", e);
        }
    }

    private boolean validateEvidence(boolean hardware, String source, Boolean synthetic) {
        boolean valid = hardware && isValidSource(source) && !Boolean.TRUE.equals(synthetic);
        if (hardware && isValidSource(source)) {
            realHardwareEvidence = true;
            if (!sources.contains(source) && sources.size() >= 32) { invalidate("too many evidence sources"); valid = false; }
            else sources.add(source);
        } else {
            invalidate(hardware ? "evidence source is invalid" : "evidence is not from real hardware");
        }
        if (Boolean.TRUE.equals(synthetic)) { invalidate("synthetic diagnostic evidence cannot verify a profile"); valid = false; }
        if (!valid && hardware && isValidSource(source) && !Boolean.TRUE.equals(synthetic)) return false;
        return valid;
    }

    private void appendHeldOut(HeldOutRecord record) {
        if (heldOut.size() >= MAX_HELD_OUT_TRIALS) { invalidate("held-out trial limit reached"); return; }
        heldOut.add(record);
    }

    private void invalidate(String reason) {
        failClosed = true;
        if (reason != null && !reason.isEmpty() && !failureReasons.contains(reason) && failureReasons.size() < 32) failureReasons.add(reason);
    }

    private int latencyP95() {
        if (latencies.isEmpty()) return 0;
        int[] values = new int[latencies.size()];
        for (int i = 0; i < values.length; i++) values[i] = latencies.get(i).latencyMillis;
        java.util.Arrays.sort(values);
        int index = Math.min(values.length - 1, (int) Math.ceil(values.length * 0.95) - 1);
        return values[index];
    }

    private static void requireExpected(GestureKind expected) {
        if (expected != GestureKind.DOUBLE_PINCH_MIDDLE && expected != GestureKind.RUB_INDEX) {
            throw new IllegalArgumentException("held-out expected kind must be one of the two gestures");
        }
    }

    private static String evidenceSource(String source) {
        if (!isValidSource(source)) return "";
        return source;
    }

    private static boolean isValidSource(String source) {
        if (source == null || source.trim().isEmpty() || source.length() > 80) return false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (!(Character.isLetterOrDigit(c) || " ._:/-+".indexOf(c) >= 0)) return false;
        }
        return true;
    }

    private static boolean isHash(String value) {
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) if (Character.digit(value.charAt(i), 16) < 0) return false;
        return true;
    }

    private static JSONArray strings(Iterable<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static List<String> sorted(Set<String> values) {
        ArrayList<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    private static List<String> readStrings(JSONArray array, int max) throws JSONException {
        if (array.length() > max) throw new IllegalArgumentException("too many verifier strings");
        ArrayList<String> values = new ArrayList<>(array.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.getString(i);
            if (value.length() > 120 || !seen.add(value)) throw new IllegalArgumentException("invalid verifier string");
            values.add(value);
        }
        return values;
    }

    private static long boundedLong(JSONObject object, String key, long min, long max) throws JSONException {
        long value = object.getLong(key);
        if (value < min || value > max) throw new IllegalArgumentException("progress value out of bounds: " + key);
        return value;
    }

    private static int boundedInt(JSONObject object, String key, int min, int max) throws JSONException {
        int value = object.getInt(key);
        if (value < min || value > max) throw new IllegalArgumentException("progress value out of bounds: " + key);
        return value;
    }

    private static String checksum(String data) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class HeldOutRecord {
        final GestureKind expected, predicted;
        final String sampleId, source;
        final boolean realHardware, synthetic;
        HeldOutRecord(GestureKind expected, GestureKind predicted, String sampleId, String source,
                      boolean realHardware, boolean synthetic) {
            this.expected = expected; this.predicted = predicted; this.sampleId = sampleId;
            this.source = source; this.realHardware = realHardware; this.synthetic = synthetic;
        }
    }
    private static final class NormalMotionRecord {
        final long durationMillis;
        final int falsePositiveCount;
        final String source;
        final boolean realHardware;
        NormalMotionRecord(long durationMillis, int falsePositiveCount, String source, boolean realHardware) {
            this.durationMillis = durationMillis; this.falsePositiveCount = falsePositiveCount;
            this.source = source; this.realHardware = realHardware;
        }
    }
    private static final class LatencyRecord {
        final int latencyMillis;
        final String source;
        final boolean realHardware;
        LatencyRecord(int latencyMillis, String source, boolean realHardware) {
            this.latencyMillis = latencyMillis; this.source = source; this.realHardware = realHardware;
        }
    }
}
