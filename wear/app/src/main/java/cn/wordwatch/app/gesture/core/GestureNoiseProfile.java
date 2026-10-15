package cn.wordwatch.app.gesture.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Personal stillness baseline and robust six-axis noise estimate shared by training and runtime. */
public final class GestureNoiseProfile {
    public static final int AXES = GestureFeatures.RAW_DIMENSIONS;
    public static final int MAX_STILL_FRAMES = 4096;
    public static final int MIN_STILL_FRAMES = 80;
    /** A nominal four-second capture spans slightly less than four seconds between callbacks. */
    public static final long MIN_STILL_DURATION_NANOS = 3_500_000_000L;
    public static final long MAX_STILL_GAP_NANOS = 150_000_000L;
    public static final int MAX_JSON_CHARS = 2048;
    private static final int JSON_VERSION = 1;
    private static final double[] MIN_NOISE = {0.01, 0.01, 0.01, 0.005, 0.005, 0.005};
    private static final double[] MAX_ROBUST_NOISE = {0.35, 0.35, 0.35, 0.25, 0.25, 0.25};
    private static final double[] MAX_P90_DEVIATION = {0.70, 0.70, 0.70, 0.50, 0.50, 0.50};

    private final double[] baseline;
    private final double[] noiseSigma;
    private final int frameCount;
    private final long durationNanos;
    private final boolean synthetic;
    private final String revision;

    private GestureNoiseProfile(double[] baseline, double[] noiseSigma, int frameCount,
                                long durationNanos, boolean synthetic, String expectedRevision) {
        if (baseline == null || noiseSigma == null || baseline.length != AXES || noiseSigma.length != AXES) {
            throw new IllegalArgumentException("noise profile needs six baseline and noise values");
        }
        if (frameCount < MIN_STILL_FRAMES || frameCount > MAX_STILL_FRAMES
                || durationNanos < MIN_STILL_DURATION_NANOS || durationNanos > 60_000_000_000L) {
            throw new IllegalArgumentException("noise profile duration or frame count is out of bounds");
        }
        this.baseline = baseline.clone();
        this.noiseSigma = noiseSigma.clone();
        for (int axis = 0; axis < AXES; axis++) {
            if (!Double.isFinite(this.baseline[axis]) || Math.abs(this.baseline[axis]) > 1_000_000.0) {
                throw new IllegalArgumentException("noise profile baseline must be finite and in range");
            }
            if (!Double.isFinite(this.noiseSigma[axis]) || this.noiseSigma[axis] < MIN_NOISE[axis]
                    || this.noiseSigma[axis] > MAX_ROBUST_NOISE[axis]) {
                throw new IllegalArgumentException("noise profile noise value is out of range");
            }
        }
        this.frameCount = frameCount;
        this.durationNanos = durationNanos;
        this.synthetic = synthetic;
        String computed = hash(canonicalState());
        if (expectedRevision != null && !computed.equals(expectedRevision)) {
            throw new IllegalArgumentException("noise profile revision mismatch");
        }
        this.revision = computed;
    }

    /**
     * Builds a profile from a continuous four-second still capture. Median/MAD estimates limit
     * the influence of isolated sensor spikes; sustained movement and large outliers are rejected.
     */
    public static GestureNoiseProfile fromStillFrames(List<SensorFrame> frames) {
        if (frames == null) throw new IllegalArgumentException("still frames are required");
        if (frames.size() < MIN_STILL_FRAMES) {
            throw new IllegalArgumentException("still calibration needs at least 80 sensor frames");
        }
        if (frames.size() > MAX_STILL_FRAMES) {
            throw new IllegalArgumentException("still calibration exceeds the 4096-frame limit");
        }
        SensorFrame first = frames.get(0);
        if (first == null) throw new IllegalArgumentException("still calibration contains a null frame");
        long previous = first.timestampNanos();
        double[][] values = new double[AXES][frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            SensorFrame frame = frames.get(i);
            if (frame == null) throw new IllegalArgumentException("still calibration contains a null frame");
            if (i > 0) {
                long gap = frame.timestampNanos() - previous;
                if (gap <= 0L) throw new IllegalArgumentException("still calibration timestamps must increase");
                if (gap > MAX_STILL_GAP_NANOS) {
                    throw new IllegalArgumentException("still calibration has a sensor gap; capture four continuous seconds");
                }
            }
            previous = frame.timestampNanos();
            for (int axis = 0; axis < AXES; axis++) values[axis][i] = GestureFeatures.channel(frame, axis);
        }
        long duration = previous - first.timestampNanos();
        if (duration < MIN_STILL_DURATION_NANOS) {
            throw new IllegalArgumentException("still calibration needs a nominal four-second continuous capture");
        }

        double[] center = new double[AXES];
        double[] sigma = new double[AXES];
        for (int axis = 0; axis < AXES; axis++) {
            double[] sorted = values[axis].clone();
            Arrays.sort(sorted);
            center[axis] = median(sorted);
            double[] deviations = new double[sorted.length];
            for (int i = 0; i < sorted.length; i++) deviations[i] = Math.abs(values[axis][i] - center[axis]);
            Arrays.sort(deviations);
            sigma[axis] = Math.max(MIN_NOISE[axis], 1.4826 * median(deviations));

            double p90 = deviations[Math.min(deviations.length - 1,
                    (int) Math.ceil(0.90 * deviations.length) - 1)];
            if (sigma[axis] > MAX_ROBUST_NOISE[axis] || p90 > MAX_P90_DEVIATION[axis]) {
                String label = axis < 3 ? "accelerometer" : "gyroscope";
                throw new IllegalArgumentException("still calibration contains movement on " + label + " axis " + (axis % 3 + 1));
            }
            double maxDeviation = axis < 3 ? 2.5 : 1.5;
            for (int i = 0; i < values[axis].length; i++) {
                if (Math.abs(values[axis][i] - center[axis]) > maxDeviation) {
                    throw new IllegalArgumentException("still calibration contains a motion spike");
                }
            }
        }
        return new GestureNoiseProfile(center, sigma, frames.size(), duration, false, null);
    }

    /** Baseline in native sensor units: m/s^2 for axes 0-2 and rad/s for axes 3-5. */
    public double baseline(int axis) { return baseline[checkedAxis(axis)]; }
    /** Robust one-sigma noise estimate in native sensor units. */
    public double noiseSigma(int axis) { return noiseSigma[checkedAxis(axis)]; }
    public int frameCount() { return frameCount; }
    public long durationNanos() { return durationNanos; }
    /** True for the compatibility fallback; such a model cannot be production-verified. */
    public boolean synthetic() { return synthetic; }
    public String revision() { return revision; }

    /** RMS noise in the same normalized six-axis units used by gesture signal quality. */
    public double normalizedNoiseRms() {
        double sum = 0.0;
        for (int axis = 0; axis < AXES; axis++) {
            double scale = axis < 3 ? 4.0 : 5.0;
            double normalized = noiseSigma[axis] / scale;
            sum += normalized * normalized;
        }
        return Math.sqrt(sum / AXES);
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", JSON_VERSION);
            object.put("revision", revision);
            object.put("frameCount", frameCount);
            object.put("durationNanos", durationNanos);
            object.put("synthetic", synthetic);
            object.put("baseline", doubles(baseline));
            object.put("noiseSigma", doubles(noiseSigma));
            String json = object.toString();
            if (json.length() > MAX_JSON_CHARS) throw new IllegalStateException("noise profile JSON exceeds size limit");
            return json;
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode noise profile", e);
        }
    }

    public static GestureNoiseProfile fromJson(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("noise profile JSON is out of bounds");
        }
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("version") != JSON_VERSION) throw new IllegalArgumentException("unsupported noise profile version");
            double[] baseline = readDoubles(object.getJSONArray("baseline"));
            double[] sigma = readDoubles(object.getJSONArray("noiseSigma"));
            return new GestureNoiseProfile(baseline, sigma, object.getInt("frameCount"),
                    object.getLong("durationNanos"), object.getBoolean("synthetic"), object.getString("revision"));
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid gesture noise profile JSON", e);
        }
    }

    /** Old API fallback: deliberately synthetic so it can never certify real-device accuracy. */
    static GestureNoiseProfile legacySynthetic() {
        return new GestureNoiseProfile(new double[AXES], MIN_NOISE.clone(), MIN_STILL_FRAMES,
                MIN_STILL_DURATION_NANOS, true, null);
    }

    private String canonicalState() {
        StringBuilder out = new StringBuilder("noise-profile-v1|median-mad14826|continuous-4s-gap150ms|")
                .append(frameCount).append('|').append(durationNanos).append('|').append(synthetic).append('|');
        for (int axis = 0; axis < AXES; axis++) {
            out.append(Double.toHexString(baseline[axis])).append('|')
                    .append(Double.toHexString(noiseSigma[axis])).append('|');
        }
        return out.toString();
    }

    private static int checkedAxis(int axis) {
        if (axis < 0 || axis >= AXES) throw new IllegalArgumentException("axis must be from 0 through 5");
        return axis;
    }

    private static double median(double[] sorted) {
        int mid = sorted.length / 2;
        return (sorted.length & 1) == 0 ? (sorted[mid - 1] + sorted[mid]) * 0.5 : sorted[mid];
    }

    private static JSONArray doubles(double[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (double value : values) array.put(value);
        return array;
    }

    private static double[] readDoubles(JSONArray values) throws JSONException {
        if (values.length() != AXES) throw new IllegalArgumentException("noise profile requires six axis values");
        double[] out = new double[AXES];
        for (int i = 0; i < AXES; i++) {
            Object raw = values.get(i);
            if (!(raw instanceof Number)) throw new IllegalArgumentException("noise profile values must be numbers");
            out[i] = ((Number) raw).doubleValue();
            if (!Double.isFinite(out[i])) throw new IllegalArgumentException("noise profile values must be finite");
        }
        return out;
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
