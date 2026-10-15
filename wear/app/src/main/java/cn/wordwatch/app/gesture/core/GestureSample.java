package cn.wordwatch.app.gesture.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded sensor segment suitable for local template training or held-out evaluation. */
public final class GestureSample {
    public static final int MAX_FRAMES = 4096;
    public static final int MIN_FRAMES = 4;
    public static final int MAX_JSON_CHARS = 1_500_000;
    public static final long MIN_DURATION_NANOS = 30_000_000L;
    public static final long MAX_DURATION_NANOS = 5_000_000_000L;
    private static final int JSON_VERSION = 1;

    private final GestureKind kind;
    private final List<SensorFrame> frames;
    private final String source;
    private final boolean synthetic;
    private final String fingerprint;

    private GestureSample(GestureKind kind, List<SensorFrame> frames, String source, boolean synthetic) {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (frames == null || frames.size() < MIN_FRAMES || frames.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("sample frame count is out of bounds");
        }
        this.source = validateSource(source);
        ArrayList<SensorFrame> copy = new ArrayList<>(frames.size());
        long previous = -1;
        for (SensorFrame frame : frames) {
            if (frame == null) throw new IllegalArgumentException("sample frame is null");
            if (frame.timestampNanos() <= previous) throw new IllegalArgumentException("timestamps must increase");
            previous = frame.timestampNanos();
            copy.add(frame);
        }
        long span = previous - copy.get(0).timestampNanos();
        if (span < MIN_DURATION_NANOS || span > MAX_DURATION_NANOS) throw new IllegalArgumentException("sample duration is out of bounds");
        this.kind = kind;
        this.frames = Collections.unmodifiableList(copy);
        this.synthetic = synthetic;
        this.fingerprint = fingerprint(copy);
    }

    public static GestureSample of(GestureKind kind, List<SensorFrame> frames, String source, boolean synthetic) {
        return new GestureSample(kind, frames, source, synthetic);
    }

    public GestureKind kind() { return kind; }
    public List<SensorFrame> frames() { return frames; }
    public String source() { return source; }
    public boolean synthetic() { return synthetic; }
    /** Hashes only relative timestamps and sensor values, so relabeling a sample does not evade leak checks. */
    public String fingerprint() { return fingerprint; }

    /** Returns the same captured sensor segment with a prompted training label. */
    public GestureSample withKind(GestureKind newKind) {
        return new GestureSample(newKind, frames, source, synthetic);
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", JSON_VERSION);
            object.put("kind", kind.name());
            object.put("source", source);
            object.put("synthetic", synthetic);
            object.put("fingerprint", fingerprint);
            object.put("integrity", integrity(kind, source, synthetic, fingerprint));
            JSONArray rows = new JSONArray();
            long origin = frames.get(0).timestampNanos();
            for (SensorFrame frame : frames) {
                JSONArray row = new JSONArray();
                row.put(frame.timestampNanos() - origin);
                row.put(frame.accelX()); row.put(frame.accelY()); row.put(frame.accelZ());
                row.put(frame.gyroX()); row.put(frame.gyroY()); row.put(frame.gyroZ());
                rows.put(row);
            }
            object.put("frames", rows);
            String encoded = object.toString();
            if (encoded.length() > MAX_JSON_CHARS) throw new IllegalStateException("encoded sample exceeds size limit");
            return encoded;
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode gesture sample", e);
        }
    }

    public static GestureSample fromJson(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("sample JSON is out of bounds");
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("version") != JSON_VERSION) throw new IllegalArgumentException("unsupported sample version");
            GestureKind kind = GestureKind.valueOf(object.getString("kind"));
            String source = object.getString("source");
            boolean synthetic = object.getBoolean("synthetic");
            JSONArray rows = object.getJSONArray("frames");
            if (rows.length() < MIN_FRAMES || rows.length() > MAX_FRAMES) throw new IllegalArgumentException("sample frame count is out of bounds");
            ArrayList<SensorFrame> frames = new ArrayList<>(rows.length());
            long origin = 1_000_000_000L;
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i);
                if (row.length() != 7) throw new IllegalArgumentException("invalid sample frame row");
                Number relativeNumber = number(row.get(0));
                long relativeNanos = relativeNumber.longValue();
                if (relativeNumber.doubleValue() != relativeNanos) throw new IllegalArgumentException("relative timestamp must be an integer");
                if (relativeNanos < 0 || relativeNanos > MAX_DURATION_NANOS) throw new IllegalArgumentException("invalid relative timestamp");
                frames.add(new SensorFrame(origin + relativeNanos,
                        number(row.get(1)).doubleValue(), number(row.get(2)).doubleValue(), number(row.get(3)).doubleValue(),
                        number(row.get(4)).doubleValue(), number(row.get(5)).doubleValue(), number(row.get(6)).doubleValue()));
            }
            GestureSample sample = new GestureSample(kind, frames, source, synthetic);
            if (!sample.fingerprint.equals(object.getString("fingerprint"))) throw new IllegalArgumentException("sample fingerprint mismatch");
            if (!integrity(kind, source, synthetic, sample.fingerprint).equals(object.getString("integrity"))) {
                throw new IllegalArgumentException("sample metadata integrity mismatch");
            }
            return sample;
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid gesture sample JSON", e);
        }
    }

    private static Number number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("sample values must be numbers");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("sample values must be finite");
        return (Number) value;
    }

    private static String validateSource(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 80) throw new IllegalArgumentException("source must be 1-80 characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || " ._:/-+".indexOf(c) >= 0)) throw new IllegalArgumentException("source contains unsupported characters");
        }
        return value;
    }

    private static String fingerprint(List<SensorFrame> frames) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer bytes = ByteBuffer.allocate(56).order(ByteOrder.BIG_ENDIAN);
            long origin = frames.get(0).timestampNanos();
            for (SensorFrame frame : frames) {
                bytes.clear();
                bytes.putLong(frame.timestampNanos() - origin);
                bytes.putLong(Double.doubleToLongBits(frame.accelX()));
                bytes.putLong(Double.doubleToLongBits(frame.accelY()));
                bytes.putLong(Double.doubleToLongBits(frame.accelZ()));
                bytes.putLong(Double.doubleToLongBits(frame.gyroX()));
                bytes.putLong(Double.doubleToLongBits(frame.gyroY()));
                bytes.putLong(Double.doubleToLongBits(frame.gyroZ()));
                digest.update(bytes.array());
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String integrity(GestureKind kind, String source, boolean synthetic, String fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((JSON_VERSION + "|" + kind.name() + "|" + source + "|" + synthetic + "|" + fingerprint)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
