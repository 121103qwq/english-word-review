package cn.wordwatch.app.gesture;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import cn.wordwatch.app.gesture.core.GestureDetector;
import cn.wordwatch.app.gesture.core.GestureKind;
import cn.wordwatch.app.gesture.core.GestureModel;
import cn.wordwatch.app.gesture.core.GestureVerificationProfile;
import cn.wordwatch.app.gesture.core.GestureSignalQuality;
import cn.wordwatch.app.gesture.core.SensorFrame;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Foreground-only sensor runtime. It has no reference to AppStore or learning records. */
public final class GestureRuntime implements AutoCloseable {
    private static final long MAX_MAIN_DELIVERY_AGE_NS = 1_000_000_000L;
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "gesture-profile-status");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onStatus(Status status);
        void onGesture(Event event);
    }

    public static final class Status {
        public final boolean ready;
        public final String message;
        public final String modelRevision;
        public final String sensorIdentity;
        public final double observedHz;

        public Status(boolean ready, String message) { this(ready, message, "", "", 0); }
        public Status(boolean ready, String message, String modelRevision, String sensorIdentity, double observedHz) {
            this.ready = ready;
            this.message = message == null ? "" : message;
            this.modelRevision = modelRevision == null ? "" : modelRevision;
            this.sensorIdentity = sensorIdentity == null ? "" : sensorIdentity;
            this.observedHz = observedHz;
        }
    }

    public static final class Event {
        public final GestureKind kind;
        public final CardContext context;
        public final long endedAtNanos;
        public final long emittedAtNanos;

        Event(GestureKind kind, CardContext context, long endedAtNanos, long emittedAtNanos) {
            this.kind = kind;
            this.context = context;
            this.endedAtNanos = endedAtNanos;
            this.emittedAtNanos = emittedAtNanos;
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final PowerManager powerManager;
    private final HandlerThread workerThread = new HandlerThread("gesture-runtime");
    private final Handler worker;
    private final GestureInteractionGuard interactionGuard = new GestureInteractionGuard();
    private final AtomicBoolean detectorResetPosted = new AtomicBoolean();
    private final AtomicBoolean diagnosticWritePending = new AtomicBoolean();
    private volatile CardContext context;
    private volatile boolean active;
    private volatile boolean closed;
    private volatile long cardEpoch;
    private volatile long activationTimestampNs;
    private GestureSensorReader reader;
    private GestureDetector detector;
    private GestureModel model;
    private GestureProfileStore.Snapshot snapshot;
    private long profileCacheVersion = -1;
    private long workerCardEpoch;
    private boolean readerReady;
    private boolean readerStarted;
    private boolean samplingInteractive;
    private long nextPowerCheckNs;
    private boolean loadPending;
    private long nextDiagnosticNs;
    private long diagnosticFrames, diagnosticAllowedFrames;
    private volatile boolean screenReceiverRegistered;
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) pauseForScreenOff();
        }
    };

    public GestureRuntime(Context context, Listener listener) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (listener == null) throw new IllegalArgumentException("listener is required");
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(screenOffReceiver, filter);
            }
            screenReceiverRegistered = true;
        } catch (RuntimeException unavailable) {
            // Sensor and event paths still query PowerManager; a receiver is an early-stop aid.
            screenReceiverRegistered = false;
        }
    }

    /** Loads and validates the profile away from the UI thread; callback always runs on MAIN. */
    public static void queryStatus(Context context, Consumer<Status> callback) {
        if (context == null || callback == null) throw new IllegalArgumentException("context and callback are required");
        Context app = context.getApplicationContext();
        STATUS_EXECUTOR.execute(() -> {
            Status result = inspectStoredStatus(app);
            MAIN.post(() -> callback.accept(result));
        });
    }

    private static Status inspectStoredStatus(Context context) {
        GestureProfileStore.Snapshot profile = GestureProfileStore.read(context);
        if (profile == null) return new Status(false, "手势未校准，可在设置中开启。");
        if (profile.calibrationPending) return new Status(false, "手势正在重新校准。");
        if (!profile.enabled) return new Status(false, "手势功能已关闭。可在设置中重新启用。", profile.modelRevision, profile.sensorIdentity, profile.observedHz);
        GestureSensorReader.SensorInfo sensors = GestureSensorReader.inspect(context);
        if (!sensors.usable()) return new Status(false, "当前设备缺少加速度计或陀螺仪。", profile.modelRevision, sensors.identity, 0);
        if (!profile.sensorIdentity.equals(sensors.identity)) return new Status(false, "传感器或设备已变化，需要重新校准。", profile.modelRevision, sensors.identity, 0);
        try {
            GestureModel model = GestureModel.fromJson(profile.modelJson);
            GestureVerificationProfile verification = GestureVerificationProfile.fromJson(profile.verificationProfileJson);
            if (!profile.permitsLearning(model, verification)) {
                return new Status(false, "手势资料不完整，请重新校准。", profile.modelRevision, sensors.identity, profile.observedHz);
            }
            return new Status(true, profile.personalTrial ? "个人试用已开启 · 尚未完成独立验证。"
                    : "手势已在本设备完成验证。", model.revision(), sensors.identity, profile.observedHz);
        } catch (RuntimeException invalid) {
            return new Status(false, "手势资料已失效，请重新校准。", profile.modelRevision, sensors.identity, profile.observedHz);
        }
    }

    /** Repeating an exact context keeps capture; a changed card/generation resets it. */
    public void startLearning(CardContext nextContext) {
        if (nextContext == null) throw new IllegalArgumentException("context is required");
        if (!isDeviceInteractive()) {
            pauseForScreenOff();
            return;
        }
        final boolean changed;
        final long epoch;
        synchronized (this) {
            if (closed) return;
            changed = !nextContext.equals(context);
            if (!changed && active && profileCacheVersion == GestureProfileStore.cacheVersion()) return;
            if (changed) {
                cardEpoch++;
                activationTimestampNs = SystemClock.elapsedRealtimeNanos();
            }
            context = nextContext;
            active = true;
            epoch = cardEpoch;
        }
        worker.post(() -> installForCurrentContext(changed, epoch));
    }

    /** Clears a card during a save/flip while preserving this foreground sensor stream. */
    public void clearCard() {
        if (!isDeviceInteractive()) {
            pauseForScreenOff();
            return;
        }
        final long epoch;
        synchronized (this) {
            if (closed) return;
            context = null;
            cardEpoch++;
            activationTimestampNs = SystemClock.elapsedRealtimeNanos();
            active = true;
            epoch = cardEpoch;
        }
        worker.post(() -> {
            if (closed || epoch != cardEpoch) return;
            workerCardEpoch = epoch;
            if (detector != null) detector.reset();
        });
    }

    /** Records a key or rotary interaction and invalidates any in-flight sensor candidate. */
    public void onUserInteraction() {
        interactionGuard.recordInteraction(SystemClock.elapsedRealtimeNanos());
        resetDetectorForInteraction();
    }

    /** Records a touchscreen event; a held pointer blocks recognition until it is released. */
    public void onTouchInteraction(boolean touchStillDown) {
        interactionGuard.recordTouchInteraction(SystemClock.elapsedRealtimeNanos(), touchStillDown);
        resetDetectorForInteraction();
    }

    private void resetDetectorForInteraction() {
        if (closed || !detectorResetPosted.compareAndSet(false, true)) return;
        worker.post(() -> {
            detectorResetPosted.set(false);
            if (detector != null) detector.reset();
        });
    }

    private void installForCurrentContext(boolean changed, long epoch) {
        if (closed || !active || context == null || epoch != cardEpoch) return;
        if (changed && detector != null) detector.reset();
        long currentCacheVersion = GestureProfileStore.cacheVersion();
        if (loadPending) return;
        if (snapshot == null || currentCacheVersion != profileCacheVersion) {
            loadPending = true;
            GestureProfileStore.Snapshot loaded = GestureProfileStore.read(appContext);
            loadPending = false;
            snapshot = loaded;
            profileCacheVersion = GestureProfileStore.cacheVersion();
            model = null;
            detector = null;
            if (loaded != null) {
                try {
                    GestureModel parsed = GestureModel.fromJson(loaded.modelJson);
                    GestureVerificationProfile verification = GestureVerificationProfile.fromJson(loaded.verificationProfileJson);
                    if (loaded.permitsLearning(parsed, verification)) model = parsed;
                } catch (RuntimeException invalid) { model = null; }
            }
        }
        if (!active || context == null || epoch != cardEpoch) return;
        if (snapshot == null || snapshot.calibrationPending || !snapshot.enabled || model == null) {
            closeReader();
            postStatus(new Status(false, "手势未启用。"), epoch);
            return;
        }
        if (detector == null) detector = new GestureDetector(model, "watch-runtime", false, snapshot.personalTrial);
        if (reader == null) {
            reader = new GestureSensorReader(appContext, new GestureSensorReader.Listener() {
                @Override public void onFrame(SensorFrame frame, GestureSensorReader.RateSnapshot rates) {
                    long queuedEpoch = cardEpoch;
                    CardContext queuedContext = context;
                    long queuedActivation = activationTimestampNs;
                    worker.post(() -> processFrame(frame, rates, queuedContext, queuedEpoch, queuedActivation));
                }
                @Override public void onReaderStatus(String message, GestureSensorReader.SensorInfo ignored) {
                    long queuedEpoch = cardEpoch;
                    worker.post(() -> handleReaderStatus(message, queuedEpoch));
                }
            }, "gesture-sensors-runtime");
            readerReady = true;
            reader.start();
            readerStarted = true;
        } else if (!readerStarted) {
            readerReady = true;
            reader.start();
            readerStarted = true;
        }
        workerCardEpoch = epoch;
        postStatus(readerReady
                ? new Status(true, listeningMessage(), model.revision(), snapshot.sensorIdentity, snapshot.observedHz)
                : new Status(false, "传感器未启动，请重新进入学习。"), epoch);
    }

    private String listeningMessage() {
        return snapshot != null && snapshot.personalTrial
                ? "宽松试用 · 手势监听中（误操作可撤销）" : "手势已验证并正在监听当前学习卡。";
    }

    private void handleReaderStatus(String message, long epoch) {
        if (!isDeviceInteractive()) { pauseForScreenOff(); return; }
        if (!active || closed || reader == null || epoch != cardEpoch) return;
        if (message.startsWith("需要真实") || message.startsWith("系统未能")) {
            readerReady = false;
            postStatus(new Status(false, message), epoch);
        }
    }

    private void processFrame(SensorFrame frame, GestureSensorReader.RateSnapshot rates,
                              CardContext queuedContext, long epoch, long activationNs) {
        diagnosticFrames++;
        long diagnosticNow = SystemClock.elapsedRealtimeNanos();
        if (diagnosticNow >= nextDiagnosticNs) {
            nextDiagnosticNs = diagnosticNow + 10_000_000_000L;
            android.util.Log.i("WordWatchGesture", "runtime frames=" + diagnosticFrames + " eligible="
                    + diagnosticAllowedFrames + " active=" + active + " readerReady=" + readerReady
                    + " hz=" + Math.round(rates.observedHz) + " ageMs=" + (diagnosticNow - frame.timestampNanos()) / 1_000_000L);
        }
        if (!isSamplingInteractive()) { pauseForScreenOff(); return; }
        if (!active || closed || epoch != cardEpoch || epoch != workerCardEpoch
                || snapshot == null || model == null || frame.timestampNanos() < activationNs) return;
        if (!readerReady || queuedContext == null || context == null || !queuedContext.equals(context)) return;
        if (!interactionGuard.allows(frame.timestampNanos(), SystemClock.elapsedRealtimeNanos())) {
            detector.reset();
            return;
        }
        GestureDetector.DetectionEvent detection;
        diagnosticAllowedFrames++;
        try { detection = detector.accept(frame); }
        catch (IllegalArgumentException badTimestamp) { detector.reset(); return; }
        if (detection == null) return;
        GestureModel.Classification classification = detection.classification();
        String quality = "segment rejected";
        if (detection.sample() != null) {
            GestureSignalQuality.Result pinch = GestureSignalQuality.evaluate(
                    GestureKind.DOUBLE_PINCH_MIDDLE, detection.sample().frames(), model.noiseProfile());
            GestureSignalQuality.Result rub = GestureSignalQuality.evaluate(
                    GestureKind.RUB_INDEX, detection.sample().frames(), model.noiseProfile());
            quality = "pinch=" + pinch.reason() + " rub=" + rub.reason()
                    + " pulses=" + pinch.pulseCount() + " snr=" + pinch.signalToNoise();
        }
        android.util.Log.i("WordWatchGesture", "runtime candidate=" + detection.kind() + " nearest="
                + classification.nearestClass() + " distance=" + classification.bestDistance() + " other="
                + classification.otherDistance() + " negative=" + classification.negativeDistance()
                + " frames=" + detection.frameCount() + " quality=" + quality);
        if (snapshot.personalTrial && detection.sample() != null && diagnosticWritePending.compareAndSet(false, true)) {
            String phase = classification.accepted() ? "runtime-accepted" : "runtime-rejected";
            String detail = "prediction=" + classification.kind() + " nearest=" + classification.nearestClass()
                    + " distance=" + classification.bestDistance() + " negative=" + classification.negativeDistance()
                    + " quality=" + quality;
            STATUS_EXECUTOR.execute(() -> {
                try { GestureTrialDiagnostics.write(appContext, phase, "UNKNOWN", detail, detection.sample().frames()); }
                finally { diagnosticWritePending.set(false); }
            });
        }
        if (!classification.accepted() || detection.kind() == GestureKind.UNKNOWN) return;
        if (detection.frameCount() < 4 || detection.frameCount() > GestureDetector.MAX_SEGMENT_FRAMES
                || detection.emittedAtNanos() - detection.endedAtNanos() > 1_000_000_000L) return;
        CardContext capturedContext = queuedContext;
        long start = detection.startedAtNanos();
        long end = detection.endedAtNanos();
        long emitted = detection.emittedAtNanos();
        if (!interactionGuard.allows(start, SystemClock.elapsedRealtimeNanos())) return;
        Event event = new Event(detection.kind(), capturedContext, end, emitted);
        MAIN.post(() -> {
            if (!isDeviceInteractive()) { pauseForScreenOff(); return; }
            if (closed || !active || epoch != cardEpoch || capturedContext == null || !capturedContext.equals(context)) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (end <= 0 || now < end || now - end > MAX_MAIN_DELIVERY_AGE_NS) {
                android.util.Log.i("WordWatchGesture", "runtime drop=expired ageMs=" + (now - end) / 1_000_000L); return;
            }
            if (!interactionGuard.allows(start, now)) {
                android.util.Log.i("WordWatchGesture", "runtime drop=interaction"); return;
            }
            android.util.Log.i("WordWatchGesture", "runtime delivered=" + event.kind);
            listener.onGesture(event);
        });
    }

    private boolean isDeviceInteractive() {
        return powerManager != null && powerManager.isInteractive();
    }

    private boolean isSamplingInteractive() {
        // Avoid a power-service Binder call on every ~107 Hz sample. Screen-off broadcasts stop
        // immediately; the MAIN delivery path still checks the real state before any action.
        long now = SystemClock.elapsedRealtimeNanos();
        if (now >= nextPowerCheckNs) {
            samplingInteractive = isDeviceInteractive();
            nextPowerCheckNs = now + 250_000_000L;
        }
        return samplingInteractive;
    }

    /** Screen-off invalidates the captured card so returning requires a fresh parent signal. */
    private void pauseForScreenOff() {
        long epoch = deactivate();
        postStatus(new Status(false, "熄屏时暂停，亮屏后继续。"), epoch);
    }

    private long deactivate() {
        final long epoch;
        synchronized (this) {
            if (closed) return cardEpoch;
            active = false;
            context = null;
            cardEpoch++;
            activationTimestampNs = SystemClock.elapsedRealtimeNanos();
            epoch = cardEpoch;
        }
        worker.post(() -> {
            workerCardEpoch = epoch;
            if (detector != null) detector.reset();
            if (reader != null) reader.stop();
            readerStarted = false;
            nextPowerCheckNs = 0L;
            samplingInteractive = false;
            readerReady = false;
        });
        return epoch;
    }

    /** Stops sensors and clears the card. Call from Activity.onPause(). */
    public void pause() {
        deactivate();
    }

    private void closeReader() {
        if (reader != null) { reader.close(); reader = null; }
        readerStarted = false;
        readerReady = false;
    }

    private void postStatus(Status status, long epoch) {
        android.util.Log.i("WordWatchGesture", "runtime status ready=" + status.ready + " " + status.message);
        MAIN.post(() -> {
            if (!closed && epoch == cardEpoch) listener.onStatus(status);
        });
    }

    @Override public void close() {
        final long epoch;
        synchronized (this) { closed = true; active = false; context = null; cardEpoch++; epoch = cardEpoch; }
        if (screenReceiverRegistered) {
            try { appContext.unregisterReceiver(screenOffReceiver); }
            catch (IllegalArgumentException alreadyUnregistered) { /* Close remains idempotent. */ }
            screenReceiverRegistered = false;
        }
        worker.post(() -> {
            workerCardEpoch = epoch;
            closeReader();
            detector = null;
            model = null;
            snapshot = null;
            workerThread.quitSafely();
        });
    }
}

