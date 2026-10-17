package cn.wordwatch.app.gesture;

import android.content.Context;
import android.util.AtomicFile;
import cn.wordwatch.app.gesture.core.GestureKind;
import cn.wordwatch.app.gesture.core.GestureModel;
import cn.wordwatch.app.gesture.core.GestureVerificationProfile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Private, atomic profile persistence, kept entirely outside AppStore and learning records. */
public final class GestureProfileStore {
    private static final int SCHEMA = 3;
    private static final int MAX_PROFILE_BYTES = 2_000_000;
    private static final Object LOCK = new Object();
    private static volatile Snapshot cached;
    private static volatile boolean cacheLoaded;
    private static volatile long cacheVersion;

    private GestureProfileStore() { }

    public static final class Snapshot {
        public final String modelJson;
        public final String verificationJson;
        public final String verificationProfileJson;
        public final String modelRevision;
        public final String sensorIdentity;
        public final String sensorDescription;
        public final double accelerometerHz;
        public final double gyroscopeHz;
        public final double observedHz;
        public final long maxGapMillis;
        public final String hand;
        public final long verifiedAtEpochMillis;
        public final boolean enabled;
        public final boolean calibrationPending;
        public final boolean personalTrial;

        public Snapshot(String modelJson, String verificationJson, String verificationProfileJson, String modelRevision,
                        String sensorIdentity, String sensorDescription, double accelerometerHz,
                        double gyroscopeHz, double observedHz, long maxGapMillis, String hand,
                        long verifiedAtEpochMillis, boolean enabled) {
            this(modelJson, verificationJson, verificationProfileJson, modelRevision, sensorIdentity, sensorDescription,
                    accelerometerHz, gyroscopeHz, observedHz, maxGapMillis, hand, verifiedAtEpochMillis, enabled, false);
        }

        public Snapshot(String modelJson, String verificationJson, String verificationProfileJson, String modelRevision,
                        String sensorIdentity, String sensorDescription, double accelerometerHz,
                        double gyroscopeHz, double observedHz, long maxGapMillis, String hand,
                        long verifiedAtEpochMillis, boolean enabled, boolean calibrationPending) {
            this(modelJson, verificationJson, verificationProfileJson, modelRevision, sensorIdentity, sensorDescription,
                    accelerometerHz, gyroscopeHz, observedHz, maxGapMillis, hand, verifiedAtEpochMillis,
                    enabled, calibrationPending, false);
        }

        private Snapshot(String modelJson, String verificationJson, String verificationProfileJson, String modelRevision,
                        String sensorIdentity, String sensorDescription, double accelerometerHz,
                        double gyroscopeHz, double observedHz, long maxGapMillis, String hand,
                        long verifiedAtEpochMillis, boolean enabled, boolean calibrationPending, boolean personalTrial) {
            this.modelJson = bounded(modelJson, 1_000_000, "modelJson");
            this.verificationJson = bounded(verificationJson, 750_000, "verificationJson");
            this.verificationProfileJson = bounded(verificationProfileJson, 64_000, "verificationProfileJson");
            this.modelRevision = bounded(modelRevision, 128, "modelRevision");
            this.sensorIdentity = bounded(sensorIdentity, 128, "sensorIdentity");
            this.sensorDescription = bounded(sensorDescription, 1_000, "sensorDescription");
            if (!(accelerometerHz > 0) || !(gyroscopeHz > 0) || !(observedHz > 0)
                    || !Double.isFinite(accelerometerHz) || !Double.isFinite(gyroscopeHz) || !Double.isFinite(observedHz)) {
                throw new IllegalArgumentException("observed sensor rates must be finite and positive");
            }
            if (maxGapMillis < 0 || verifiedAtEpochMillis < 0) throw new IllegalArgumentException("invalid verification metadata");
            if (!("left".equals(hand) || "right".equals(hand))) throw new IllegalArgumentException("hand must be left or right");
            this.accelerometerHz = accelerometerHz;
            this.gyroscopeHz = gyroscopeHz;
            this.observedHz = observedHz;
            this.maxGapMillis = maxGapMillis;
            this.hand = hand;
            this.verifiedAtEpochMillis = verifiedAtEpochMillis;
            this.enabled = enabled;
            this.calibrationPending = calibrationPending;
            if (personalTrial && (!enabled || calibrationPending)) throw new IllegalArgumentException("invalid trial state");
            this.personalTrial = personalTrial;
        }

        JSONObject toJson() throws JSONException {
            JSONObject out = new JSONObject();
            out.put("schema", SCHEMA);
            out.put("model", new JSONObject(modelJson));
            out.put("verification", new JSONObject(verificationJson));
            out.put("verificationProfile", new JSONObject(verificationProfileJson));
            out.put("modelRevision", modelRevision);
            out.put("sensorIdentity", sensorIdentity);
            out.put("sensorDescription", sensorDescription);
            out.put("accelerometerHz", accelerometerHz);
            out.put("gyroscopeHz", gyroscopeHz);
            out.put("observedHz", observedHz);
            out.put("maxGapMillis", maxGapMillis);
            out.put("hand", hand);
            out.put("verifiedAtEpochMillis", verifiedAtEpochMillis);
            out.put("enabled", enabled);
            out.put("calibrationPending", calibrationPending);
            out.put("personalTrial", personalTrial);
            return out;
        }

        static Snapshot fromJson(JSONObject object) throws JSONException {
            int schema = object.getInt("schema");
            if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("unsupported gesture profile schema");
            return new Snapshot(object.getJSONObject("model").toString(), object.getJSONObject("verification").toString(),
                    object.getJSONObject("verificationProfile").toString(),
                    object.getString("modelRevision"), object.getString("sensorIdentity"), object.getString("sensorDescription"),
                    object.getDouble("accelerometerHz"), object.getDouble("gyroscopeHz"), object.getDouble("observedHz"),
                    object.getLong("maxGapMillis"), object.getString("hand"), object.getLong("verifiedAtEpochMillis"),
                    object.getBoolean("enabled"), schema >= 2 && object.getBoolean("calibrationPending"),
                    schema >= 3 && object.getBoolean("personalTrial"));
        }

        public Snapshot withEnabled(boolean value) {
            return new Snapshot(modelJson, verificationJson, verificationProfileJson, modelRevision, sensorIdentity, sensorDescription,
                    accelerometerHz, gyroscopeHz, observedHz, maxGapMillis, hand, verifiedAtEpochMillis, value, calibrationPending);
        }

        public Snapshot withCalibrationPending(boolean value) {
            return new Snapshot(modelJson, verificationJson, verificationProfileJson, modelRevision, sensorIdentity, sensorDescription,
                    accelerometerHz, gyroscopeHz, observedHz, maxGapMillis, hand, value ? 0 : verifiedAtEpochMillis,
                    value ? false : enabled, value, !value && personalTrial);
        }

        /** Explicit opt-in, without manufacturing independent validation or a verified timestamp. */
        public Snapshot withPersonalTrial() {
            if (calibrationPending) throw new IllegalStateException("Complete calibration before personal use");
            return new Snapshot(modelJson, verificationJson, verificationProfileJson, modelRevision, sensorIdentity, sensorDescription,
                    accelerometerHz, gyroscopeHz, observedHz, maxGapMillis, hand, 0, true, false, true);
        }

        public boolean canTryPersonally(GestureModel model, GestureVerificationProfile evidence) {
            if (model == null || evidence == null || calibrationPending || model.syntheticTraining()
                    || evidence.syntheticTraining() || evidence.failClosed() || !modelRevision.equals(model.revision())
                    || !model.revision().equals(evidence.modelRevision())) return false;
            int pinch = model.trainingDiagnostics().templateCount(GestureKind.DOUBLE_PINCH_MIDDLE);
            int rub = model.trainingDiagnostics().templateCount(GestureKind.RUB_INDEX);
            return pinch >= 6 && rub >= 6 && model.trainingSampleIds().size() - pinch - rub >= 10;
        }

        public boolean permitsLearning(GestureModel model, GestureVerificationProfile evidence) {
            return enabled && !calibrationPending && model != null && evidence != null
                    && modelRevision.equals(model.revision())
                    && (evidence.isProductionVerifiedFor(model) || (personalTrial && canTryPersonally(model, evidence)));
        }

        private static String bounded(String value, int max, String name) {
            if (value == null || value.trim().isEmpty() || value.length() > max) throw new IllegalArgumentException(name + " is out of bounds");
            return value;
        }
    }

    /** Performs file I/O; call from a worker thread. A corrupt or absent file fails closed. */
    public static Snapshot read(Context context) {
        synchronized (LOCK) {
            if (cacheLoaded) return cached;
            Snapshot result = null;
            AtomicFile file = atomicFile(context);
            if (file.getBaseFile().exists()) {
                try (FileInputStream in = file.openRead()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] block = new byte[8192];
                    int count;
                    while ((count = in.read(block)) >= 0) {
                        if (bytes.size() + count > MAX_PROFILE_BYTES) throw new IllegalArgumentException("gesture profile exceeds size limit");
                        bytes.write(block, 0, count);
                    }
                    result = Snapshot.fromJson(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
                } catch (Exception invalid) {
                    result = null;
                }
            }
            cached = result;
            cacheLoaded = true;
            cacheVersion++;
            return cached;
        }
    }

    /** Atomic phase-transition write. The model and evidence remain local to this installation. */
    public static void write(Context context, Snapshot snapshot) throws Exception {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        byte[] data = snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PROFILE_BYTES) throw new IllegalArgumentException("gesture profile exceeds size limit");
        synchronized (LOCK) {
            AtomicFile file = atomicFile(context);
            FileOutputStream out = file.startWrite();
            try {
                out.write(data);
                out.flush();
                file.finishWrite(out);
                cached = snapshot;
                cacheLoaded = true;
                cacheVersion++;
            } catch (Exception error) {
                file.failWrite(out);
                throw error;
            }
        }
    }

    public static long cacheVersion() { return cacheVersion; }

    /** Returns the in-process cached value only; it never triggers file I/O. */
    public static Snapshot cached() { return cacheLoaded ? cached : null; }

    /** Allows live changes to take effect without retaining any old accepted state. */
    public static void invalidateCache() {
        synchronized (LOCK) {
            cached = null;
            cacheLoaded = false;
            cacheVersion++;
        }
    }

    private static AtomicFile atomicFile(Context context) {
        File base = new File(context.getApplicationContext().getFilesDir(), "gesture-profile.json");
        return new AtomicFile(base);
    }
}
