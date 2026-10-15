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

/** Immutable summary. Verified status is derived from evidence and the exact model revision. */
public final class GestureVerificationProfile {
    public static final long REQUIRED_NORMAL_MOTION_MILLIS = 10 * 60 * 1000L;
    public static final int REQUIRED_HELD_OUT_PER_GESTURE = 20;
    public static final int REQUIRED_CORRECT_PER_GESTURE = 19;
    public static final int REQUIRED_LATENCY_SAMPLES = 20;
    public static final int MAX_P95_LATENCY_MILLIS = 500;
    private static final int JSON_VERSION = 2;

    private final String modelRevision;
    private final int pinchTrials;
    private final int pinchCorrect;
    private final int rubTrials;
    private final int rubCorrect;
    private final int crossClassConfusions;
    private final long normalMotionMillis;
    private final int normalMotionFalsePositives;
    private final int latencySampleCount;
    private final int latencyP95Millis;
    private final boolean failClosed;
    private final boolean realHardwareEvidence;
    private final boolean syntheticTraining;
    private final List<String> sources;
    private final List<String> failureReasons;

    GestureVerificationProfile(String modelRevision, int pinchTrials, int pinchCorrect,
                               int rubTrials, int rubCorrect, int crossClassConfusions,
                               long normalMotionMillis, int normalMotionFalsePositives,
                               int latencySampleCount, int latencyP95Millis, boolean failClosed,
                               boolean realHardwareEvidence, boolean syntheticTraining,
                               List<String> sources, List<String> failureReasons) {
        this.modelRevision = modelRevision;
        this.pinchTrials = pinchTrials;
        this.pinchCorrect = pinchCorrect;
        this.rubTrials = rubTrials;
        this.rubCorrect = rubCorrect;
        this.crossClassConfusions = crossClassConfusions;
        this.normalMotionMillis = normalMotionMillis;
        this.normalMotionFalsePositives = normalMotionFalsePositives;
        this.latencySampleCount = latencySampleCount;
        this.latencyP95Millis = latencyP95Millis;
        this.failClosed = failClosed;
        this.realHardwareEvidence = realHardwareEvidence;
        this.syntheticTraining = syntheticTraining;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        this.failureReasons = Collections.unmodifiableList(new ArrayList<>(failureReasons));
    }

    public String modelRevision() { return modelRevision; }
    public int heldOutTrials(GestureKind kind) { return kind == GestureKind.DOUBLE_PINCH_MIDDLE ? pinchTrials : kind == GestureKind.RUB_INDEX ? rubTrials : 0; }
    public int heldOutCorrect(GestureKind kind) { return kind == GestureKind.DOUBLE_PINCH_MIDDLE ? pinchCorrect : kind == GestureKind.RUB_INDEX ? rubCorrect : 0; }
    public int pinchTrials() { return pinchTrials; }
    public int pinchCorrect() { return pinchCorrect; }
    public int rubTrials() { return rubTrials; }
    public int rubCorrect() { return rubCorrect; }
    public int crossClassConfusions() { return crossClassConfusions; }
    public long normalMotionMillis() { return normalMotionMillis; }
    public int normalMotionFalsePositives() { return normalMotionFalsePositives; }
    public int latencySampleCount() { return latencySampleCount; }
    public int latencyP95Millis() { return latencyP95Millis; }
    public boolean failClosed() { return failClosed; }
    public boolean realHardwareEvidence() { return realHardwareEvidence; }
    public boolean syntheticTraining() { return syntheticTraining; }
    public List<String> sources() { return sources; }
    public List<String> failureReasons() { return failureReasons; }

    public boolean heldOutAccuracyPasses() {
        return pinchTrials >= REQUIRED_HELD_OUT_PER_GESTURE && pinchCorrect >= REQUIRED_CORRECT_PER_GESTURE
                && (long) pinchCorrect * 100L >= (long) pinchTrials * 95L
                && rubTrials >= REQUIRED_HELD_OUT_PER_GESTURE && rubCorrect >= REQUIRED_CORRECT_PER_GESTURE
                && (long) rubCorrect * 100L >= (long) rubTrials * 95L && crossClassConfusions == 0;
    }

    public boolean isProductionVerifiedFor(GestureModel model) {
        return model != null && modelRevision.equals(model.revision()) && !model.syntheticTraining()
                && !syntheticTraining && !failClosed && realHardwareEvidence
                && heldOutAccuracyPasses() && normalMotionMillis >= REQUIRED_NORMAL_MOTION_MILLIS
                && normalMotionFalsePositives == 0 && latencySampleCount >= REQUIRED_LATENCY_SAMPLES
                && latencyP95Millis <= MAX_P95_LATENCY_MILLIS && !sources.isEmpty();
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", JSON_VERSION);
            object.put("modelRevision", modelRevision);
            object.put("pinchTrials", pinchTrials); object.put("pinchCorrect", pinchCorrect);
            object.put("rubTrials", rubTrials); object.put("rubCorrect", rubCorrect);
            object.put("crossClassConfusions", crossClassConfusions);
            object.put("normalMotionMillis", normalMotionMillis);
            object.put("normalMotionFalsePositives", normalMotionFalsePositives);
            object.put("latencySampleCount", latencySampleCount);
            object.put("latencyP95Millis", latencyP95Millis);
            object.put("failClosed", failClosed);
            object.put("realHardwareEvidence", realHardwareEvidence);
            object.put("syntheticTraining", syntheticTraining);
            object.put("sources", strings(sources));
            object.put("failureReasons", strings(failureReasons));
            object.put("productionVerified", isProductionVerifiedForRevision());
            object.put("checksum", checksum(object.toString()));
            return object.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode verification profile", e);
        }
    }

    public static GestureVerificationProfile fromJson(String json) {
        if (json == null || json.length() > 64_000) throw new IllegalArgumentException("profile JSON is out of bounds");
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("version") != JSON_VERSION) throw new IllegalArgumentException("unsupported profile version");
            String checksum = object.getString("checksum");
            object.remove("checksum");
            if (!checksum.equals(checksum(object.toString()))) throw new IllegalArgumentException("profile checksum mismatch");
            GestureVerificationProfile profile = new GestureVerificationProfile(
                    object.getString("modelRevision"), boundedInt(object, "pinchTrials", 0, 512),
                    boundedInt(object, "pinchCorrect", 0, 512), boundedInt(object, "rubTrials", 0, 512),
                    boundedInt(object, "rubCorrect", 0, 512), boundedInt(object, "crossClassConfusions", 0, 512),
                    boundedLong(object, "normalMotionMillis", 0, 604_800_000L),
                    boundedInt(object, "normalMotionFalsePositives", 0, 100_000),
                    boundedInt(object, "latencySampleCount", 0, 512), boundedInt(object, "latencyP95Millis", 0, 60_000),
                    object.getBoolean("failClosed"), object.getBoolean("realHardwareEvidence"),
                    object.getBoolean("syntheticTraining"),
                    readStrings(object.getJSONArray("sources"), 32), readStrings(object.getJSONArray("failureReasons"), 32));
            if (object.getBoolean("productionVerified") != profile.isProductionVerifiedForRevision()) {
                throw new IllegalArgumentException("profile verification flag is inconsistent");
            }
            if (profile.pinchCorrect > profile.pinchTrials || profile.rubCorrect > profile.rubTrials
                    || profile.crossClassConfusions > (profile.pinchTrials + profile.rubTrials)) {
                throw new IllegalArgumentException("profile evidence counts are inconsistent");
            }
            return profile;
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid verification profile JSON", e);
        }
    }

    private boolean isProductionVerifiedForRevision() {
        return !syntheticTraining && !failClosed && realHardwareEvidence && heldOutAccuracyPasses()
                && normalMotionMillis >= REQUIRED_NORMAL_MOTION_MILLIS && normalMotionFalsePositives == 0
                && latencySampleCount >= REQUIRED_LATENCY_SAMPLES && latencyP95Millis <= MAX_P95_LATENCY_MILLIS
                && !sources.isEmpty();
    }

    private static JSONArray strings(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static List<String> readStrings(JSONArray array, int max) throws JSONException {
        if (array.length() > max) throw new IllegalArgumentException("too many profile strings");
        ArrayList<String> values = new ArrayList<>(array.length());
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.getString(i);
            if (value.length() > 120) throw new IllegalArgumentException("invalid profile string");
            // Repeated source/reason strings add no evidence; normalize them during import.
            if (unique.add(value)) values.add(value);
        }
        return values;
    }

    private static int boundedInt(JSONObject object, String key, int min, int max) throws JSONException {
        int value = object.getInt(key);
        if (value < min || value > max) throw new IllegalArgumentException("profile value out of bounds: " + key);
        return value;
    }

    private static long boundedLong(JSONObject object, String key, long min, long max) throws JSONException {
        long value = object.getLong(key);
        if (value < min || value > max) throw new IllegalArgumentException("profile value out of bounds: " + key);
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
}
