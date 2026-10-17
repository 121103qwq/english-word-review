package cn.wordwatch.app.gesture.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small personalized DTW model with training-derived positive, negative, and margin gates. */
public final class GestureModel {
    public static final int MIN_TEMPLATES_PER_GESTURE = 3;
    public static final int MIN_NEGATIVE_TEMPLATES = 3;
    public static final int MAX_TEMPLATES_PER_GESTURE = 32;
    public static final int MAX_NEGATIVE_TEMPLATES = 64;
    public static final int MAX_MODEL_JSON_CHARS = 1_000_000;
    private static final int JSON_VERSION = 10;
    private static final double MIN_STORED_DISTANCE_THRESHOLD = 1.0e-6;
    /** Nearest normal motion must be at least this much farther than the gesture (pinch, rub). */
    private static final double[] NEGATIVE_CLEARANCE = {1.0, 1.10};
    /** When both structural gates pass, the nearer class must lead by 5%. */
    private static final double CLASS_CLEARANCE = 1.05;
    private static final double MIN_CLASS_MARGIN = 0.015;
    /** Radius = median + 3 robust sigmas of the leave-one-out own-class distances, at least 1.25x median. */
    private static final double RADIUS_MAD_SIGMAS = 3.0;
    private static final double RADIUS_MIN_MEDIAN_RATIO = 1.25;
    private static final GestureKind[] CLASSES = {GestureKind.DOUBLE_PINCH_MIDDLE, GestureKind.RUB_INDEX};

    private final List<Template> templates;
    private final Set<String> trainingIds;
    private final double[] distanceThresholds;
    private final double largeMotionThreshold;
    private final boolean syntheticTraining;
    private final GestureNoiseProfile noiseProfile;
    private final TrainingDiagnostics trainingDiagnostics;
    private final String revision;

    private GestureModel(List<Template> templates, Set<String> trainingIds,
                         double[] distanceThresholds, double largeMotionThreshold,
                         boolean syntheticTraining, GestureNoiseProfile noiseProfile, String expectedRevision) {
        this.templates = Collections.unmodifiableList(new ArrayList<>(templates));
        this.trainingIds = Collections.unmodifiableSet(new HashSet<>(trainingIds));
        this.distanceThresholds = distanceThresholds.clone();
        this.largeMotionThreshold = largeMotionThreshold;
        this.syntheticTraining = syntheticTraining;
        if (noiseProfile == null) throw new IllegalArgumentException("a still noise profile is required");
        this.noiseProfile = noiseProfile;
        this.trainingDiagnostics = TrainingDiagnostics.analyze(this.templates);
        String computed = hash(canonicalState());
        if (expectedRevision != null && !computed.equals(expectedRevision)) throw new IllegalArgumentException("model revision mismatch");
        this.revision = computed;
    }

    /** Trains from labeled samples; UNKNOWN samples are normal-motion negative examples. */
    public static GestureModel train(List<GestureSample> samples) {
        // Compatibility overload is intentionally synthetic: it keeps old tests/readers usable,
        // while preventing a missing real still calibration from certifying a device model.
        return train(samples, GestureNoiseProfile.legacySynthetic());
    }

    /** Trains from labeled samples using the same personal still-noise profile as runtime. */
    public static GestureModel train(List<GestureSample> samples, GestureNoiseProfile noiseProfile) {
        if (samples == null) throw new IllegalArgumentException("samples are required");
        if (noiseProfile == null) throw new IllegalArgumentException("a still noise profile is required");
        ArrayList<GestureSample> ordered = new ArrayList<>(samples);
        if (ordered.contains(null)) throw new IllegalArgumentException("training sample is null");
        if (ordered.size() > MAX_TEMPLATES_PER_GESTURE * 2 + MAX_NEGATIVE_TEMPLATES) {
            throw new IllegalArgumentException("training sample count exceeds limit");
        }
        ordered.sort(Comparator.comparing(GestureSample::fingerprint));
        Set<String> ids = new HashSet<>();
        ArrayList<Template> positive = new ArrayList<>();
        ArrayList<Template> negative = new ArrayList<>();
        int[] classCounts = new int[CLASSES.length];
        boolean syntheticTraining = noiseProfile.synthetic();
        for (GestureSample sample : ordered) {
            if (sample == null) throw new IllegalArgumentException("training sample is null");
            if (!ids.add(sample.fingerprint())) throw new IllegalArgumentException("duplicate training sample");
            syntheticTraining |= sample.synthetic();
            double[] vector = GestureFeatures.extract(sample.frames());
            if (vector == null) throw new IllegalArgumentException("invalid training sample");
            int qualityMask = qualityMask(sample.frames(), noiseProfile);
            Template template = new Template(sample.kind(), sample.fingerprint(), vector,
                    GestureFeatures.peak(sample.frames()), qualityMask);
            if (sample.kind() == GestureKind.UNKNOWN) {
                if (negative.size() >= MAX_NEGATIVE_TEMPLATES) throw new IllegalArgumentException("too many negative templates");
                negative.add(template);
            } else {
                int index = classIndex(sample.kind());
                if (index < 0) throw new IllegalArgumentException("unsupported gesture class");
                if ((qualityMask & (1 << index)) == 0) {
                    GestureSignalQuality.Result quality = GestureSignalQuality.evaluate(sample.kind(), sample.frames(), noiseProfile);
                    throw new IllegalArgumentException("训练手势不符合信号质量要求：" + quality.reason());
                }
                if (++classCounts[index] > MAX_TEMPLATES_PER_GESTURE) throw new IllegalArgumentException("too many gesture templates");
                positive.add(template);
            }
        }
        for (int count : classCounts) if (count < MIN_TEMPLATES_PER_GESTURE) throw new IllegalArgumentException("each gesture needs at least three templates");
        if (negative.size() < MIN_NEGATIVE_TEMPLATES) throw new IllegalArgumentException("at least three normal-motion negatives are required");

        double[][] positiveDistances = new double[2][classCounts[0] + classCounts[1]];
        int[] classOffsets = new int[2];
        double[][] scratch = new double[4][GestureFeatures.POINTS + 1];
        int[][] lengths = new int[2][GestureFeatures.POINTS + 1];
        for (int c = 0; c < 2; c++) {
            ArrayList<Template> own = templatesFor(positive, CLASSES[c]);
            ArrayList<Template> other = templatesFor(positive, CLASSES[1 - c]);
            for (Template item : own) {
                double ownDistance = nearest(item, own, item.id, scratch, lengths);
                double otherDistance = nearest(item, other, null, scratch, lengths);
                if (!Double.isFinite(ownDistance) || !Double.isFinite(otherDistance)) {
                    throw new IllegalArgumentException("training data cannot define separable classes");
                }
                positiveDistances[c][classOffsets[c]] = ownDistance;
                classOffsets[c]++;
            }
        }

        double[] distanceThresholds = new double[2];
        double maxPeak = 0.0;
        for (Template item : positive) maxPeak = Math.max(maxPeak, item.peak);
        for (int c = 0; c < 2; c++) {
            distanceThresholds[c] = Math.max(MIN_STORED_DISTANCE_THRESHOLD,
                    robustRadius(positiveDistances[c], classCounts[c]));
        }
        double largeMotionThreshold = maxPeak * 1.8 + 0.10;
        ArrayList<Template> all = new ArrayList<>(positive.size() + negative.size());
        all.addAll(positive);
        all.addAll(negative);
        return new GestureModel(all, ids, distanceThresholds, largeMotionThreshold,
                syntheticTraining, noiseProfile, null);
    }

    /**
     * Structure first, then nearest neighbour inside that structural channel. The quality gates give
     * each class its channel (two separated pulses never pass the rub gate); a trace passing both is
     * resolved by distance with a 5% lead. The candidate is rejected whenever a normal-motion template
     * from the same channel is nearer than the gesture (RUB_INDEX confirms, so it needs 10% clearance).
     */
    public Classification classify(List<SensorFrame> frames) {
        return decide(frames);
    }

    /** Opt-in local trial path: the same channel gates and radius, plus a pulse presence check. */
    public Classification classifyPersonalTrial(List<SensorFrame> frames) {
        if (frames == null || frames.size() < GestureSample.MIN_FRAMES
                || frames.size() > GestureSample.MAX_FRAMES) return Classification.unknown();
        if (!hasPersonalTrialPulse(frames)) return Classification.unknown();
        return decide(frames);
    }

    private Classification decide(List<SensorFrame> frames) {
        double[] vector = GestureFeatures.extract(frames);
        if (vector == null) return Classification.unknown();
        long duration = frames.get(frames.size() - 1).timestampNanos() - frames.get(0).timestampNanos();
        if (duration > GestureSample.MAX_DURATION_NANOS) return Classification.unknown();
        double peak = GestureFeatures.peak(frames);
        if (peak > largeMotionThreshold) return Classification.rejected(GestureKind.UNKNOWN, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        int mask = qualityMask(frames, noiseProfile);
        if (mask == 0) return Classification.unknown();
        double[][] scratch = new double[4][GestureFeatures.POINTS + 1];
        int[][] lengths = new int[2][GestureFeatures.POINTS + 1];
        double[] classDistance = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] negativeDistance = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (Template template : templates) {
            double distance = GestureFeatures.distance(vector, template.vector,
                    scratch[0], scratch[1], lengths[0], lengths[1]);
            if (template.kind != GestureKind.UNKNOWN) {
                int index = classIndex(template.kind);
                classDistance[index] = Math.min(classDistance[index], distance);
            } else {
                for (int i = 0; i < CLASSES.length; i++) {
                    if ((template.qualityMask & (1 << i)) != 0) negativeDistance[i] = Math.min(negativeDistance[i], distance);
                }
            }
        }
        int best = mask == 1 ? 0 : mask == 2 ? 1 : classDistance[0] <= classDistance[1] ? 0 : 1;
        int other = 1 - best;
        double margin = classDistance[other] - classDistance[best];
        boolean accepted = classDistance[best] <= distanceThresholds[best]
                && negativeDistance[best] > classDistance[best] * NEGATIVE_CLEARANCE[best]
                && (mask != 3 || margin >= Math.max(MIN_CLASS_MARGIN, classDistance[best] * (CLASS_CLEARANCE - 1.0)));
        return new Classification(accepted ? CLASSES[best] : GestureKind.UNKNOWN, CLASSES[best],
                classDistance[best], classDistance[other], negativeDistance[best], margin, accepted);
    }

    private boolean hasPersonalTrialPulse(List<SensorFrame> frames) {
        SensorFrame first = frames.get(0);
        double[] smooth = new double[GestureNoiseProfile.AXES];
        for (int axis = 0; axis < smooth.length; axis++) smooth[axis] = GestureFeatures.channel(first, axis);
        double noise = Math.max(0.002, noiseProfile.normalizedNoiseRms());
        double on = Math.max(0.010, noise * 2.5);
        double off = Math.max(0.005, noise * 1.2);
        double envelope = 0.0;
        double maxEnvelope = 0.0;
        boolean active = false;
        boolean pulse = false;
        long previousNanos = first.timestampNanos();
        for (int i = 1; i < frames.size(); i++) {
            SensorFrame frame = frames.get(i);
            long time = frame.timestampNanos();
            long dtNanos = time - previousNanos;
            if (dtNanos <= 0L || dtNanos > GestureFeatures.MAX_FRAME_GAP_NANOS) return false;
            double alpha = -Math.expm1(-dtNanos / 30_000_000.0);
            double sumSquares = 0.0;
            for (int axis = 0; axis < smooth.length; axis++) {
                double scale = axis < 3 ? 4.0 : 5.0;
                double raw = GestureFeatures.channel(frame, axis);
                double filtered = smooth[axis] + alpha * (raw - smooth[axis]);
                double normalized = (raw - filtered) / scale;
                smooth[axis] = filtered;
                sumSquares += normalized * normalized;
            }
            double activity = Math.sqrt(sumSquares / smooth.length);
            double envelopeAlpha = -Math.expm1(-dtNanos / 20_000_000.0);
            envelope += envelopeAlpha * (activity - envelope);
            maxEnvelope = Math.max(maxEnvelope, envelope);
            if (envelope >= on) active = true;
            else if (active && envelope < off) {
                pulse = true;
                active = false;
            }
            previousNanos = time;
        }
        pulse |= active;
        return maxEnvelope / noise >= 2.5 && pulse;
    }

    public String revision() { return revision; }
    public Set<String> trainingSampleIds() { return trainingIds; }
    /** True if any positive or normal-motion training example was synthetic or diagnostic. */
    public boolean syntheticTraining() { return syntheticTraining; }
    /** Personal still baseline and robust noise estimate used by training and runtime gates. */
    public GestureNoiseProfile noiseProfile() { return noiseProfile; }
    /** LOO class-separation diagnostics, recomputed from the persisted six-axis templates. */
    public TrainingDiagnostics trainingDiagnostics() { return trainingDiagnostics; }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", JSON_VERSION);
            object.put("revision", revision);
            object.put("distanceThresholds", doubles(distanceThresholds));
            object.put("largeMotionThreshold", largeMotionThreshold);
            object.put("syntheticTraining", syntheticTraining);
            object.put("noiseProfile", noiseProfile.toJson());
            object.put("trainingDiagnostics", trainingDiagnostics.summary());
            JSONArray ids = new JSONArray();
            ArrayList<String> sortedIds = new ArrayList<>(trainingIds);
            Collections.sort(sortedIds);
            for (String id : sortedIds) ids.put(id);
            object.put("trainingIds", ids);
            JSONArray rows = new JSONArray();
            for (Template template : templates) {
                JSONObject row = new JSONObject();
                row.put("kind", template.kind.name());
                row.put("id", template.id);
                row.put("peak", template.peak);
                row.put("qualityMask", template.qualityMask);
                JSONArray vector = new JSONArray();
                for (double value : template.vector) vector.put(value);
                row.put("vector", vector);
                rows.put(row);
            }
            object.put("templates", rows);
            String json = object.toString();
            if (json.length() > MAX_MODEL_JSON_CHARS) throw new IllegalStateException("model JSON exceeds size limit");
            return json;
        } catch (JSONException e) {
            throw new IllegalStateException("could not encode gesture model", e);
        }
    }

    public static GestureModel fromJson(String json) {
        if (json == null || json.length() > MAX_MODEL_JSON_CHARS) throw new IllegalArgumentException("model JSON is out of bounds");
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("version") != JSON_VERSION) throw new IllegalArgumentException("unsupported model version");
            GestureNoiseProfile noiseProfile = GestureNoiseProfile.fromJson(object.getString("noiseProfile"));
            ArrayList<Template> templates = new ArrayList<>();
            JSONArray rows = object.getJSONArray("templates");
            if (rows.length() < MIN_TEMPLATES_PER_GESTURE * 2 + MIN_NEGATIVE_TEMPLATES
                    || rows.length() > MAX_TEMPLATES_PER_GESTURE * 2 + MAX_NEGATIVE_TEMPLATES) {
                throw new IllegalArgumentException("model template count is out of bounds");
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                GestureKind kind = GestureKind.valueOf(row.getString("kind"));
                if (kind == null) throw new IllegalArgumentException("invalid model class");
                JSONArray vectorJson = row.getJSONArray("vector");
                if (vectorJson.length() != GestureFeatures.VECTOR_SIZE) throw new IllegalArgumentException("invalid template vector size");
                double[] vector = new double[GestureFeatures.VECTOR_SIZE];
                for (int j = 0; j < vector.length; j++) vector[j] = finiteNumber(vectorJson.get(j));
                double peak = finiteNumber(row.get("peak"));
                if (peak < 0.0 || peak > 1_000_000.0) throw new IllegalArgumentException("invalid template peak");
                int qualityMask = row.getInt("qualityMask");
                if (qualityMask < 0 || qualityMask >= (1 << CLASSES.length)) throw new IllegalArgumentException("invalid template quality mask");
                int classIndex = classIndex(kind);
                if (classIndex >= 0 && (qualityMask & (1 << classIndex)) == 0) {
                    throw new IllegalArgumentException("gesture template does not pass its class quality gate");
                }
                String id = row.getString("id");
                if (!isHash(id)) throw new IllegalArgumentException("invalid template ID");
                templates.add(new Template(kind, id, vector, peak, qualityMask));
            }
            JSONArray idsJson = object.getJSONArray("trainingIds");
            if (idsJson.length() < templates.size() || idsJson.length() > MAX_TEMPLATES_PER_GESTURE * 2 + MAX_NEGATIVE_TEMPLATES) {
                throw new IllegalArgumentException("invalid model training ID count");
            }
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < idsJson.length(); i++) if (!ids.add(idsJson.getString(i))) throw new IllegalArgumentException("duplicate model training ID");
            for (Template template : templates) if (!ids.contains(template.id)) throw new IllegalArgumentException("template is missing its training ID");
            int pinchCount = 0, rubCount = 0, negativeCount = 0;
            for (Template template : templates) {
                if (template.kind == GestureKind.DOUBLE_PINCH_MIDDLE) pinchCount++;
                else if (template.kind == GestureKind.RUB_INDEX) rubCount++;
                else if (template.kind == GestureKind.UNKNOWN) negativeCount++;
            }
            if (pinchCount < MIN_TEMPLATES_PER_GESTURE || rubCount < MIN_TEMPLATES_PER_GESTURE
                    || negativeCount < MIN_NEGATIVE_TEMPLATES || pinchCount > MAX_TEMPLATES_PER_GESTURE
                    || rubCount > MAX_TEMPLATES_PER_GESTURE || negativeCount > MAX_NEGATIVE_TEMPLATES) {
                throw new IllegalArgumentException("model class template counts are out of bounds");
            }
            double[] distance = readDoubles(object.getJSONArray("distanceThresholds"));
            double largeMotion = finiteNumber(object.get("largeMotionThreshold"));
            for (double value : distance) if (value <= 0.0 || value > 1_000_000.0) throw new IllegalArgumentException("invalid distance threshold");
            if (largeMotion <= 0.0 || largeMotion > 1_000_000.0) throw new IllegalArgumentException("invalid motion threshold");
            GestureModel restored = new GestureModel(templates, ids, distance, largeMotion,
                    object.getBoolean("syntheticTraining"), noiseProfile, object.getString("revision"));
            if (!restored.trainingDiagnostics.summary().equals(object.getString("trainingDiagnostics"))) {
                throw new IllegalArgumentException("model training diagnostics mismatch");
            }
            return restored;
        } catch (JSONException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid gesture model JSON", e);
        }
    }

    private String canonicalState() {
        StringBuilder out = new StringBuilder("dtw-v10|active-env20pct-pad40ms|unit-rms-shape|logenergy-w005|quality-v3|")
                .append("quality-channel-mask|class-lead-1p05-min015|channel-1nn-negative-clearance-1p0-1p1|radius-median-3mad-min1p25|")
                .append(syntheticTraining).append('|').append(noiseProfile.revision()).append('|')
                .append(trainingDiagnostics.summary()).append('|');
        for (double distanceThreshold : distanceThresholds) out.append(Double.toHexString(distanceThreshold)).append('|');
        out.append(Double.toHexString(largeMotionThreshold)).append('|');
        ArrayList<String> ids = new ArrayList<>(trainingIds);
        Collections.sort(ids);
        for (String id : ids) out.append(id).append('|');
        for (Template template : templates) {
            out.append(template.kind.name()).append(':').append(template.id).append(':')
                    .append(template.qualityMask).append(':').append(Double.toHexString(template.peak)).append(':');
            for (double value : template.vector) out.append(Double.toHexString(value)).append(',');
            out.append('|');
        }
        return out.toString();
    }

    /** Compact, reproducible leave-one-out distance report for explaining conservative models. */
    public static final class TrainingDiagnostics {
        private final int[] templateCounts;
        private final int[] crossClassOverlaps;
        private final int[] negativeOverlaps;
        private final double[] withinClassP90;
        private final double[] otherClassP10;
        private final double[] classMarginP10;
        private final double[] negativeP10;
        private final int[] qualityNegativeCounts;

        private TrainingDiagnostics(int[] templateCounts, int[] crossClassOverlaps,
                                    int[] negativeOverlaps, double[] withinClassP90,
                                    double[] otherClassP10, double[] classMarginP10,
                                    double[] negativeP10, int[] qualityNegativeCounts) {
            this.templateCounts = templateCounts.clone();
            this.crossClassOverlaps = crossClassOverlaps.clone();
            this.negativeOverlaps = negativeOverlaps.clone();
            this.withinClassP90 = withinClassP90.clone();
            this.otherClassP10 = otherClassP10.clone();
            this.classMarginP10 = classMarginP10.clone();
            this.negativeP10 = negativeP10.clone();
            this.qualityNegativeCounts = qualityNegativeCounts.clone();
        }

        private static TrainingDiagnostics analyze(List<Template> templates) {
            int[] counts = new int[CLASSES.length];
            int[] qualityNegativeCounts = new int[CLASSES.length];
            int[] crossOverlaps = new int[CLASSES.length];
            int[] negativeOverlaps = new int[CLASSES.length];
            double[] withinP90 = new double[CLASSES.length];
            double[] otherP10 = new double[CLASSES.length];
            double[] marginP10 = new double[CLASSES.length];
            double[] negativeP10 = new double[CLASSES.length];
            double[][] scratch = new double[4][GestureFeatures.POINTS + 1];
            int[][] lengths = new int[2][GestureFeatures.POINTS + 1];
            for (int c = 0; c < CLASSES.length; c++) {
                ArrayList<Template> own = templatesFor(templates, CLASSES[c]);
                ArrayList<Template> other = templatesFor(templates, CLASSES[1 - c]);
                ArrayList<Template> negative = qualityNegativesForClass(templates, c);
                counts[c] = own.size();
                qualityNegativeCounts[c] = negative.size();
                double[] within = new double[own.size()];
                double[] cross = new double[own.size()];
                double[] margins = new double[own.size()];
                double[] againstNegative = new double[own.size()];
                for (int i = 0; i < own.size(); i++) {
                    Template item = own.get(i);
                    within[i] = nearest(item, own, item.id, scratch, lengths);
                    cross[i] = nearest(item, other, null, scratch, lengths);
                    againstNegative[i] = nearest(item, negative, null, scratch, lengths);
                    margins[i] = cross[i] - within[i];
                    if (within[i] >= cross[i]) crossOverlaps[c]++;
                    if (within[i] >= againstNegative[i]) negativeOverlaps[c]++;
                }
                withinP90[c] = percentile(within, within.length, 0.90);
                otherP10[c] = percentile(cross, cross.length, 0.10);
                marginP10[c] = percentile(margins, margins.length, 0.10);
                negativeP10[c] = againstNegative.length == 0 ? Double.POSITIVE_INFINITY
                        : percentile(againstNegative, againstNegative.length, 0.10);
            }
            return new TrainingDiagnostics(counts, crossOverlaps, negativeOverlaps,
                    withinP90, otherP10, marginP10, negativeP10, qualityNegativeCounts);
        }

        public boolean hasOverlap() {
            for (int i = 0; i < CLASSES.length; i++) {
                if (crossClassOverlaps[i] > 0 || negativeOverlaps[i] > 0) return true;
            }
            return false;
        }
        public int templateCount(GestureKind kind) { return templateCounts[diagnosticClassIndex(kind)]; }
        public int leaveOneOutCrossClassOverlaps(GestureKind kind) { return crossClassOverlaps[diagnosticClassIndex(kind)]; }
        public int leaveOneOutNegativeOverlaps(GestureKind kind) { return negativeOverlaps[diagnosticClassIndex(kind)]; }
        public double withinClassP90(GestureKind kind) { return withinClassP90[diagnosticClassIndex(kind)]; }
        public double nearestOtherClassP10(GestureKind kind) { return otherClassP10[diagnosticClassIndex(kind)]; }
        public double classMarginP10(GestureKind kind) { return classMarginP10[diagnosticClassIndex(kind)]; }
        public double nearestNegativeP10(GestureKind kind) { return negativeP10[diagnosticClassIndex(kind)]; }
        public int qualityMatchedNegativeCount(GestureKind kind) { return qualityNegativeCounts[diagnosticClassIndex(kind)]; }

        /** Training observations only; this never means the profile is verified or enabled. */
        public String failureReason() {
            int cross = 0, negative = 0;
            boolean missingQualityMatchedNegative = false;
            for (int i = 0; i < CLASSES.length; i++) {
                cross += crossClassOverlaps[i];
                negative += negativeOverlaps[i];
                missingQualityMatchedNegative |= qualityNegativeCounts[i] == 0;
            }
            if (missingQualityMatchedNegative) return "部分手势没有质量匹配的正常动作负样本；仍须完成独立真机验证。";
            if (cross > 0 && negative > 0) return "训练留一距离存在跨手势及正负样本重叠；分类将保守拒绝，仍须完成独立真机验证。";
            if (cross > 0) return "训练留一距离存在跨手势重叠；分类将保守拒绝，仍须完成独立真机验证。";
            if (negative > 0) return "部分训练正样本更接近正常动作；分类将保守拒绝，仍须完成独立真机验证。";
            return "训练留一距离未见明显重叠；这不是验证通过，仍须完成独立真机验证。";
        }

        public String summary() {
            return String.format(Locale.ROOT,
                    "训练距离：中指双捏 n=%d 类内P90=%.5f 跨类P10=%.5f 间隔P10=%.5f 质量匹配负样本=%d 负样本P10=%.5f 重叠=%d/%d；"
                            + "食指短擦 n=%d 类内P90=%.5f 跨类P10=%.5f 间隔P10=%.5f 质量匹配负样本=%d 负样本P10=%.5f 重叠=%d/%d",
                    templateCounts[0], withinClassP90[0], otherClassP10[0], classMarginP10[0], qualityNegativeCounts[0], negativeP10[0],
                    crossClassOverlaps[0], negativeOverlaps[0],
                    templateCounts[1], withinClassP90[1], otherClassP10[1], classMarginP10[1], qualityNegativeCounts[1], negativeP10[1],
                    crossClassOverlaps[1], negativeOverlaps[1]);
        }

        private static int diagnosticClassIndex(GestureKind kind) {
            int index = classIndex(kind);
            if (index < 0) throw new IllegalArgumentException("diagnostics require a supported gesture class");
            return index;
        }
    }

    private static ArrayList<Template> templatesFor(List<Template> templates, GestureKind kind) {
        ArrayList<Template> out = new ArrayList<>();
        for (Template template : templates) if (template.kind == kind) out.add(template);
        return out;
    }

    private static ArrayList<Template> qualityNegativesForClass(List<Template> templates, int classIndex) {
        ArrayList<Template> out = new ArrayList<>();
        int bit = 1 << classIndex;
        for (Template template : templates) {
            if (template.kind == GestureKind.UNKNOWN && (template.qualityMask & bit) != 0) out.add(template);
        }
        return out;
    }

    /** Structural channels: bit 0 when the pinch pulse gate passes, bit 1 when the rub texture gate passes. */
    private static int qualityMask(List<SensorFrame> frames, GestureNoiseProfile noiseProfile) {
        int mask = 0;
        for (int i = 0; i < CLASSES.length; i++) {
            if (GestureSignalQuality.evaluate(CLASSES[i], frames, noiseProfile).accepted()) mask |= 1 << i;
        }
        return mask;
    }

    private static double nearest(Template candidate, List<Template> choices, String excludedId,
                                  double[][] scratch, int[][] lengths) {
        double best = Double.POSITIVE_INFINITY;
        for (Template choice : choices) {
            if (excludedId != null && excludedId.equals(choice.id)) continue;
            best = Math.min(best, GestureFeatures.distance(candidate.vector, choice.vector,
                    scratch[0], scratch[1], lengths[0], lengths[1]));
        }
        return best;
    }

    /** Median plus MAD-scaled spread; a single unusual calibration sample cannot inflate the radius. */
    private static double robustRadius(double[] values, int count) {
        double median = percentile(values, count, 0.50);
        double[] deviations = new double[count];
        for (int i = 0; i < count; i++) deviations[i] = Math.abs(values[i] - median);
        double sigma = 1.4826 * percentile(deviations, count, 0.50);
        return Math.max(median * RADIUS_MIN_MEDIAN_RATIO, median + RADIUS_MAD_SIGMAS * sigma);
    }

    private static double percentile(double[] values, int count, double fraction) {
        double[] sorted = new double[count];
        System.arraycopy(values, 0, sorted, 0, count);
        java.util.Arrays.sort(sorted);
        int index = Math.min(count - 1, Math.max(0, (int) Math.ceil(fraction * count) - 1));
        return sorted[index];
    }

    private static int classIndex(GestureKind kind) {
        for (int i = 0; i < CLASSES.length; i++) if (CLASSES[i] == kind) return i;
        return -1;
    }

    private static boolean isHash(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) if (Character.digit(value.charAt(i), 16) < 0) return false;
        return true;
    }

    private static JSONArray doubles(double[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (double value : values) array.put(value);
        return array;
    }

    private static double[] readDoubles(JSONArray values) throws JSONException {
        if (values.length() != 2) throw new IllegalArgumentException("expected two thresholds");
        return new double[]{finiteNumber(values.get(0)), finiteNumber(values.get(1))};
    }

    private static double finiteNumber(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("model values must be numbers");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("model values must be finite");
        return number;
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

    private static final class Template {
        final GestureKind kind;
        final String id;
        final double[] vector;
        final double peak;
        final int qualityMask;
        Template(GestureKind kind, String id, double[] vector, double peak, int qualityMask) {
            this.kind = kind;
            this.id = id;
            this.vector = vector;
            this.peak = peak;
            this.qualityMask = qualityMask;
        }
    }

    public static final class Classification {
        private final GestureKind kind;
        private final GestureKind nearestClass;
        private final double bestDistance;
        private final double otherDistance;
        private final double negativeDistance;
        private final double margin;
        private final boolean accepted;

        private Classification(GestureKind kind, GestureKind nearestClass, double bestDistance,
                               double otherDistance, double negativeDistance, double margin, boolean accepted) {
            this.kind = kind;
            this.nearestClass = nearestClass;
            this.bestDistance = bestDistance;
            this.otherDistance = otherDistance;
            this.negativeDistance = negativeDistance;
            this.margin = margin;
            this.accepted = accepted;
        }
        private static Classification unknown() {
            return new Classification(GestureKind.UNKNOWN, GestureKind.UNKNOWN, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, false);
        }
        static Classification rejected(GestureKind kind, double best, double other, double negative, double margin) {
            return new Classification(kind, GestureKind.UNKNOWN, best, other, negative, margin, false);
        }
        public GestureKind kind() { return kind; }
        public GestureKind nearestClass() { return nearestClass; }
        public double bestDistance() { return bestDistance; }
        public double otherDistance() { return otherDistance; }
        public double negativeDistance() { return negativeDistance; }
        public double margin() { return margin; }
        public boolean accepted() { return accepted; }
    }
}
