package cn.wordwatch.app.gesture;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.wordwatch.app.gesture.core.GestureDetector;
import cn.wordwatch.app.gesture.core.GestureKind;
import cn.wordwatch.app.gesture.core.GestureModel;
import cn.wordwatch.app.gesture.core.GestureNoiseProfile;
import cn.wordwatch.app.gesture.core.GestureSignalQuality;
import cn.wordwatch.app.gesture.core.GestureProfileVerifier;
import cn.wordwatch.app.gesture.core.GestureSample;
import cn.wordwatch.app.gesture.core.GestureVerificationProfile;
import cn.wordwatch.app.gesture.core.SensorFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Touch-only, foreground calibration and physical verification for personalized hand gestures. */
public final class GestureSetupActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 17), SURFACE = Color.rgb(25, 37, 44);
    private static final int INK = Color.rgb(239, 247, 248), MUTED = Color.rgb(157, 177, 184);
    private static final int ACCENT = Color.rgb(133, 227, 207), BAD = Color.rgb(255, 160, 142);
    private static final int HOME = 0, DIAGNOSTICS = 1, TRAINING = 2, NEGATIVES = 3,
            HELD_OUT = 4, NORMAL_MOTION = 5, RESULTS = 6, BASELINE = 7;
    private static final int TRAINING_PER_GESTURE = 6, NEGATIVE_SAMPLES = 10;
    /** Each gesture is captured in two rounds of three, so one tense or forceful burst cannot define it. */
    private static final int TRAINING_PER_ROUND = 3;
    private static final int TRIAL_WINDOW_MS = 8_000, TRIAL_GRACE_MS = 700;
    private static final long STILL_SETTLE_NS = 2_000_000_000L, STILL_CAPTURE_NS = 1_500_000_000L;
    private static final int DIAGNOSTIC_READY_MS = 3_000, DIAGNOSTIC_TIMEOUT_MS = 9_000;
    private static final long NORMAL_CHECK_MS = GestureVerificationProfile.REQUIRED_NORMAL_MOTION_MILLIS;
    private static final long MAX_LIVE_FRAME_GAP_NS = 180_000_000L;
    private static final String SOURCE_TRAINING = "watch-training";
    private static final String SOURCE_HELD_OUT = "watch-heldout";
    private static final String SOURCE_NORMAL = "watch-normal-motion";
    private static final String SOURCE_LATENCY = "watch-heldout";

    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager powerManager;
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (destroyed || intent == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                foreground = false;
                postWorker(() -> suspendForegroundWork(true));
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && activityResumed
                    && readDeviceInteractive() && !foreground) {
                interactiveCache = true;
                interactiveCacheTimestampNs = SystemClock.elapsedRealtimeNanos();
                foreground = true;
                postWorker(GestureSetupActivity.this::resumeForegroundWork);
            }
        }
    };
    private GestureSensorReader reader;
    private GestureSensorReader.SensorInfo sensorInfo;
    private GestureSensorReader.RateSnapshot latestRates = GestureSensorReader.RateSnapshot.empty();
    private GestureSensorReader.RateSnapshot calibrationRates;
    private GestureDetector detector;
    private GestureModel model;
    private GestureNoiseProfile noiseProfile;
    private final GestureInteractionGuard interactionGuard = new GestureInteractionGuard();
    private final ArrayList<SensorFrame> baselineFrames = new ArrayList<>();
    private final ArrayList<SensorFrame> trialFrames = new ArrayList<>();
    private boolean baselineReading, baselineInterrupted, emptyControlPassed, emptyControlTrial;
    private boolean trialInterfered;
    private long baselineWindowStartNs, baselineWindowEndNs;
    private long baselineRun;
    private int qualifyingCandidates;
    private String trialQualityMessage = "";
    private GestureProfileVerifier verifier;
    private volatile GestureVerificationProfile uiProfile;
    private volatile boolean uiVerified;
    private GestureProfileStore.Snapshot storedSnapshot;
    private final ArrayList<GestureSample> trainingSamples = new ArrayList<>();
    private final ArrayList<SensorFrame> stillNegativeFrames = new ArrayList<>();
    private final int[] trainingCounts = new int[2];

    private volatile boolean destroyed;
    private volatile boolean foreground;
    private volatile boolean activityResumed;
    private volatile boolean windowFocused;
    private volatile boolean interactiveCache;
    private volatile long interactiveCacheTimestampNs;
    private volatile boolean ignoreSavedProgress;
    private int stage = HOME;
    private String hand;
    private String message = "先选择佩戴手腕。";
    private String ratesText = "尚未启动真实传感器";
    private String verificationText = "";
    private boolean diagnosticReady;
    private boolean diagnosticFailed;
    private boolean sensorsStartedByUser;
    private boolean calibrationInProgress;
    private long diagnosticStartNs = -1;
    private long diagnosticDeadlineNs = -1;
    private long latestFrameTimestampNs = -1;
    private long lastRateUiTimestampNs = -1;
    private boolean autoStartDiagnostic;

    private boolean trialOpen;
    private boolean trialCandidateReceived;
    private boolean latencyQueuedForTrial;
    private long pendingLatencyMs = -1;
    private GestureSample trialCandidate;
    private GestureKind trialExpected;
    private long trialStartedAtNs;
    private long trialDeadlineNs;
    private long trialId;
    private int negativeCount;
    private boolean stillNegativeTrial;
    private long stillWindowStartNs;
    private long stillWindowEndNs;
    private long lastStillFrameNs;

    private boolean normalRunning;
    private long normalStartedAtNs;
    private long normalLastFrameNs;
    private long normalFrameCount;
    private int normalFalsePositives;
    private boolean realHardware;

    private ScrollView scroll;
    private LinearLayout body;
    private TextView stateView;
    private TextView timerView;
    private TextView ratesView;
    private int renderedStage = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        workerThread = new HandlerThread("gesture-setup-work");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        sensorInfo = GestureSensorReader.inspect(this);
        realHardware = isRealHardware();
        autoStartDiagnostic = getIntent().getBooleanExtra("gesture-diagnostics", false)
                || getIntent().getBooleanExtra("diagnostics", false);
        render();
        loadSavedProgress();
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        registerScreenReceiver();
        interactiveCache = readDeviceInteractive();
        interactiveCacheTimestampNs = SystemClock.elapsedRealtimeNanos();
        foreground = interactiveCache && windowFocused;
        if (foreground && autoStartDiagnostic) {
            autoStartDiagnostic = false;
            startDiagnostics();
        } else if (foreground) postWorker(this::resumeForegroundWork);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            interactionGuard.recordTouchInteraction(SystemClock.elapsedRealtimeNanos(), true);
            recordSetupInteraction();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            interactionGuard.recordTouchInteraction(SystemClock.elapsedRealtimeNanos(), false);
            recordSetupInteraction();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // ADB's five-second WAKEUP is a keepalive, not a press of the watch's physical buttons.
        if (event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            interactionGuard.recordInteraction(SystemClock.elapsedRealtimeNanos());
            recordSetupInteraction();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            interactionGuard.recordInteraction(SystemClock.elapsedRealtimeNanos());
            recordSetupInteraction();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void recordSetupInteraction() {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        postWorker(() -> {
            if (detector != null) detector.reset();
            if (baselineReading && nowNs >= baselineWindowStartNs) baselineInterrupted = true;
            if (trialOpen && nowNs >= trialStartedAtNs + GestureSetupRules.TRIAL_SETTLE_NANOS) {
                trialInterfered = true;
                trialQualityMessage = "触屏、按键或表冠打断了本次动作";
            }
            if (normalRunning && nowNs > normalStartedAtNs) {
                normalRunning = false;
                normalStartedAtNs = normalLastFrameNs = normalFrameCount = 0;
                message = "触屏或按键打断了检查，请重新开始。";
                publish();
            }
        });
    }

    @Override protected void onPause() {
        activityResumed = false;
        foreground = false;
        interactionGuard.recordTouchInteraction(SystemClock.elapsedRealtimeNanos(), false);
        unregisterScreenReceiver();
        postWorker(() -> suspendForegroundWork(false));
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        if (!hasFocus) {
            interactionGuard.recordTouchInteraction(SystemClock.elapsedRealtimeNanos(), false);
            foreground = false;
            postWorker(() -> suspendForegroundWork(false));
        } else if (activityResumed && readDeviceInteractive()) {
            foreground = true;
            postWorker(this::resumeForegroundWork);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterScreenReceiver();
        if (worker != null) worker.post(() -> {
            if (reader != null) { reader.close(); reader = null; }
            detector = null;
            workerThread.quitSafely();
        });
        super.onDestroy();
    }

    private boolean readDeviceInteractive() {
        return powerManager != null && powerManager.isInteractive();
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenStateReceiver, filter);
            screenReceiverRegistered = true;
        } catch (RuntimeException unavailable) { screenReceiverRegistered = false; }
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return;
        try { unregisterReceiver(screenStateReceiver); }
        catch (IllegalArgumentException alreadyUnregistered) { /* Activity remains safe to pause twice. */ }
        screenReceiverRegistered = false;
    }

    /** Cached power-state check used by sensor callbacks and foreground timers. */
    private boolean ensureInteractive() {
        if (!activityResumed || !windowFocused || destroyed) return false;
        long now = SystemClock.elapsedRealtimeNanos();
        if (now - interactiveCacheTimestampNs >= 250_000_000L) {
            interactiveCache = readDeviceInteractive();
            interactiveCacheTimestampNs = now;
        }
        if (!interactiveCache) {
            if (foreground) {
                foreground = false;
                postWorker(() -> suspendForegroundWork(true));
            }
            return false;
        }
        if (!foreground) {
            foreground = true;
            postWorker(this::resumeForegroundWork);
        }
        return true;
    }

    private void resumeForegroundWork() {
        if (destroyed || !activityResumed || !windowFocused || !readDeviceInteractive()) {
            foreground = false;
            return;
        }
        foreground = true;
        interactiveCache = true;
        interactiveCacheTimestampNs = SystemClock.elapsedRealtimeNanos();
        if (autoStartDiagnostic) {
            autoStartDiagnostic = false;
            startDiagnostics();
        } else if (sensorsStartedByUser && reader != null) {
            if (stage == DIAGNOSTICS) beginDiagnosticWindow();
            reader.start();
        }
    }

    private void suspendForegroundWork(boolean screenOff) {
        if (baselineReading) {
            baselineReading = false;
            baselineRun++;
            baselineFrames.clear();
            message = "静止测量被打断，请回到此页重新开始。";
        }
        if (trialOpen) {
            trialOpen = false;
            trialCandidate = null;
            trialCandidateReceived = false;
            stillNegativeTrial = false;
            stillNegativeFrames.clear();
            lastStillFrameNs = -1;
            trialId++;
            message = screenOff
                    ? "熄屏打断了本次采集，请重新开始。"
                    : "本次采集被打断，请重新开始。";
        }
        if (normalRunning) {
            normalRunning = false;
            normalStartedAtNs = 0;
            normalLastFrameNs = 0;
            normalFrameCount = 0;
            normalFalsePositives = 0;
            if (detector != null) detector.reset();
            message = screenOff
                    ? "熄屏打断了 10 分钟检查，请重新开始。"
                    : "离开页面打断了 10 分钟检查，请重新开始。";
        }
        if (stage == DIAGNOSTICS) {
            diagnosticReady = false;
            diagnosticFailed = false;
            diagnosticStartNs = diagnosticDeadlineNs = -1;
            calibrationRates = null;
            message = screenOff
                    ? "熄屏打断了测量，亮屏后重测。"
                    : "测量被打断，回到此页后重测。";
        }
        if (detector != null) detector.reset();
        if (reader != null) { reader.stop(); }
        publish();
    }

    private void loadSavedProgress() {
        worker.post(() -> {
            GestureProfileStore.Snapshot saved = GestureProfileStore.read(getApplicationContext());
            if (saved == null) { restoreCalibrationDraft(); return; }
            if (saved.calibrationPending) {
                if (restoreCalibrationDraft()) return;
                main.post(() -> {
                    if (destroyed || ignoreSavedProgress) return;
                    storedSnapshot = saved;
                    hand = null;
                    calibrationInProgress = true;
                    stage = HOME;
                    message = "上次校准未完成，请重新校准。";
                    render();
                });
                return;
            }
            GestureModel savedModel = null;
            GestureProfileVerifier savedVerifier = null;
            boolean matchingDevice = saved.sensorIdentity.equals(sensorInfo.identity);
            try {
                savedModel = GestureModel.fromJson(saved.modelJson);
                savedVerifier = GestureProfileVerifier.fromJson(savedModel, saved.verificationJson);
                GestureVerificationProfile summary = GestureVerificationProfile.fromJson(saved.verificationProfileJson);
                if (!summary.modelRevision().equals(savedModel.revision())) throw new IllegalArgumentException("summary revision mismatch");
            } catch (RuntimeException invalid) {
                savedModel = null;
                savedVerifier = null;
            }
            final GestureModel loadedModel = savedModel;
            final GestureProfileVerifier loadedVerifier = savedVerifier;
            if (loadedModel == null && restoreCalibrationDraft()) return;
            main.post(() -> {
                if (destroyed || ignoreSavedProgress) return;
                if (loadedModel == null || loadedVerifier == null) {
                    if (!destroyed && saved != null) { message = "手势资料已失效，请重新校准。"; render(); }
                    return;
                }
                worker.post(() -> {
                    if (ignoreSavedProgress) return;
                    storedSnapshot = saved;
                    if (!matchingDevice || !sensorInfo.usable()) {
                        main.post(() -> { message = "设备已变化，请重新校准。"; stage = HOME; render(); });
                        return;
                    }
                    model = loadedModel;
                    noiseProfile = loadedModel.noiseProfile();
                    verifier = loadedVerifier;
                    uiProfile = verifier.profile();
                    uiVerified = verifier.isProductionVerified();
                    hand = saved.hand;
                    GestureVerificationProfile progress = uiProfile;
                    if (saved.personalTrial && saved.permitsLearning(model, uiProfile)) {
                        stage = RESULTS;
                        message = "个人试用已开启。";
                    } else if (verifier.isProductionVerified() && saved.enabled) {
                        stage = RESULTS;
                        message = "本设备已完成真实硬件验证。";
                    } else if (!progress.failClosed()
                            && (progress.pinchTrials() < GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE
                            || progress.rubTrials() < GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE)) {
                        stage = HELD_OUT;
                        message = "已恢复未完成的验证。";
                    } else if (GestureSetupRules.canStartNormalMotion(progress)
                            && progress.normalMotionMillis() < NORMAL_CHECK_MS) {
                        stage = NORMAL_MOTION;
                        message = "已恢复验证进度，10 分钟检查需重新计时。";
                    } else if (!progress.failClosed() && !progress.heldOutAccuracyPasses()) {
                        stage = RESULTS;
                        message = "识别准确率未达标，请重新校准。";
                    } else {
                        stage = RESULTS;
                        message = uiVerified && !saved.enabled
                                ? "验证已通过，点击启用即可使用。"
                                : progress.failClosed() ? "验证未通过，请重新校准。"
                                : "验证结果尚未通过；请按结果重新校准。";
                    }
                    verificationText = verificationSummary(progress, uiVerified);
                    if (stage == HELD_OUT || stage == NORMAL_MOTION) {
                        sensorsStartedByUser = true;
                        detector = stage == HELD_OUT
                                ? new GestureDetector(model, SOURCE_HELD_OUT, false)
                                : new GestureDetector(model, SOURCE_NORMAL, false);
                        ensureReader();
                        if (foreground && reader != null) reader.start();
                    }
                    main.post(this::render);
                });
            });
        });
    }

    /** Worker-only restore; raw calibration is never counted as independent verification. */
    private boolean restoreCalibrationDraft() {
        if (ignoreSavedProgress || destroyed || sensorInfo == null || !sensorInfo.usable()) return false;
        GestureCalibrationDraft.Snapshot draft = GestureCalibrationDraft.read(this, sensorInfo.identity);
        if (draft == null) return false;
        trainingSamples.clear(); trainingSamples.addAll(draft.samples);
        trainingCounts[0] = trainingCounts[1] = negativeCount = 0;
        for (GestureSample sample : trainingSamples) {
            if (sample.kind() == GestureKind.DOUBLE_PINCH_MIDDLE) trainingCounts[0]++;
            else if (sample.kind() == GestureKind.RUB_INDEX) trainingCounts[1]++;
            else negativeCount++;
        }
        hand = draft.hand; noiseProfile = draft.noise; emptyControlPassed = draft.emptyControlPassed;
        calibrationRates = draft.rates; calibrationInProgress = true; diagnosticReady = true;
        autoStartDiagnostic = false; model = null; verifier = null; uiVerified = false;
        stage = trainingCounts[0] >= TRAINING_PER_GESTURE && trainingCounts[1] >= TRAINING_PER_GESTURE
                ? NEGATIVES : TRAINING;
        detector = GestureDetector.captureOnly(SOURCE_TRAINING, false, noiseProfile);
        sensorsStartedByUser = true; ensureReader();
        if (foreground && reader != null) reader.start();
        message = "已恢复 " + trainingSamples.size() + " 份本机样本，无需重复采集。自动评分仍关闭。";
        android.util.Log.i("WordWatchGesture", "Restored calibration pinch=" + trainingCounts[0]
                + " rub=" + trainingCounts[1] + " negative=" + negativeCount);
        publish();
        return true;
    }

    private boolean saveCalibrationDraft() {
        if (noiseProfile == null || hand == null || sensorInfo == null) return true;
        GestureSensorReader.RateSnapshot rates = calibrationRates != null ? calibrationRates : latestRates;
        try {
            GestureCalibrationDraft.write(this, trainingSamples, noiseProfile, hand, emptyControlPassed, sensorInfo, rates);
            return true;
        } catch (Exception error) {
            android.util.Log.e("WordWatchGesture", "Calibration draft save failed", error);
            message = "样本暂未保存，请留在此页。";
            return false;
        }
    }

    private void render() {
        if (destroyed) return;
        int previousScroll = scroll == null || renderedStage != stage || trialOpen || baselineReading ? 0 : scroll.getScrollY();
        renderedStage = stage;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.max(dp(19), Math.round(width * (getResources().getConfiguration().isScreenRound() ? .15f : .08f)));
        body.setPadding(margin, dp(20), margin, dp(34));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        stateView = null;
        timerView = null;
        ratesView = null;
        setContentView(scroll);
        scroll.setFocusableInTouchMode(true);
        scroll.requestFocus();

        small("词序  /  手指手势");
        title(titleForStage());
        if (!message.isEmpty()) {
            stateView = text(message, 14, messageLooksBad() ? BAD : INK, false);
            gap(3);
        }
        gap(4);
        if (stage == HOME) renderHome();
        else if (stage == DIAGNOSTICS) renderDiagnostics();
        else if (stage == BASELINE) renderBaseline();
        else if (stage == TRAINING) renderTraining();
        else if (stage == NEGATIVES) renderNegatives();
        else if (stage == HELD_OUT) renderHeldOut();
        else if (stage == NORMAL_MOTION) renderNormalMotion();
        else renderResults();
        gap(8);
        button("返回", false, this::finish);
        if (previousScroll > 0) {
            int restoreScroll = previousScroll;
            main.post(() -> { if (scroll != null) scroll.scrollTo(0, restoreScroll); });
        }
    }

    private String titleForStage() {
        switch (stage) {
            case DIAGNOSTICS: return "传感器诊断";
            case BASELINE: return "先测静止噪声";
            case TRAINING: return "动作校准";
            case NEGATIVES: return "日常动作样本";
            case HELD_OUT: return "独立准确率验证";
            case NORMAL_MOTION: return "误触检查";
            case RESULTS: return "验证结果";
            default: return "开始设置";
        }
    }

    private void renderHome() {
        button("测量真实传感器", true, this::startDiagnostics);
        if (hand == null) {
            small("训练前请选择佩戴手腕");
            button("左手腕", false, () -> chooseHand("left"));
            button("右手腕", false, () -> chooseHand("right"));
        } else {
            text("当前选择：" + ("left".equals(hand) ? "左手腕" : "右手腕"), 14, ACCENT, true);
            button(calibrationInProgress ? "继续校准前先重新测传感器" : "重新测量传感器", false, this::startDiagnostics);
            if (storedSnapshot != null && storedSnapshot.enabled) button("停用手势", false, this::disableProfile);
        }
        gap(5);
        text("双捏：拇指与中指捏两下。搓动：拇指与食指搓一下。", 15, INK, false);
        gap(4);
        text("坐姿或站姿校准，手腕放松，留在此页。", 13, MUTED, false);
        gap(4);
        showSensorInfo();
        if (storedSnapshot != null) text("已有本机资料：" + (storedSnapshot.enabled ? "已启用" : "停用或验证未完成")
                + " · 手腕 " + ("left".equals(storedSnapshot.hand) ? "左" : "右"), 12, MUTED, false);
    }

    private void renderDiagnostics() {
        text("需要加速度计和陀螺仪。", 13, MUTED, false);
        showSensorInfo();
        ratesView = text(ratesText, 13, INK, true);
        if (diagnosticReady && hand == null) {
            small("传感器已测量；请选择训练手腕");
            button("左手腕", true, () -> chooseHand("left"));
            button("右手腕", false, () -> chooseHand("right"));
        } else if (diagnosticReady) button("开始采集真实动作样本", true, this::beginTraining);
        else if (diagnosticFailed) button("重新测量传感器", false, this::restartDiagnostics);
        else small("正在测量。请保持手臂静止约 3 秒。");
    }

    private void renderTraining() {
        text("校准手腕：" + handLabel(), 12, MUTED, false);
        text("双捏 " + trainingCounts[0] + "/" + TRAINING_PER_GESTURE + " · 搓动 "
                + trainingCounts[1] + "/" + TRAINING_PER_GESTURE, 13, ACCENT, true);
        if (trialOpen) timerView = text("本次动作试次进行中", 13, MUTED, true);
        if (!trialOpen) {
            if (!emptyControlPassed) {
                button("先做空白对照", true, this::startEmptyControl);
            } else {
                int roundLimit = trainingRoundLimit();
                if (trainingCounts[0] < roundLimit)
                    button("采集一次中指双捏", true, () -> startTrainingTrial(GestureKind.DOUBLE_PINCH_MIDDLE));
                if (trainingCounts[1] < roundLimit)
                    button("采集一次食指搓动", true, () -> startTrainingTrial(GestureKind.RUB_INDEX));
                if (trainingCounts[0] >= TRAINING_PER_GESTURE && trainingCounts[1] >= TRAINING_PER_GESTURE)
                    button("样本齐全，进入日常动作样本", true, this::beginNegativeSamples);
            }
            if (emptyControlPassed) button("再做一次空白对照", false, this::startEmptyControl);
            button("重新测静止噪声", false, this::beginTraining);
        }
        text("用平常使用的力度，不要刻意用力。静止 2 秒后做一次。", 13, MUTED, false);
        text(trainingRoundLimit() == TRAINING_PER_ROUND ? "第 1 轮：两种各 3 次。" : "第 2 轮：放下手臂休息一下再做，两种各 3 次。", 12, MUTED, false);
    }

    private void renderBaseline() {
        text("前臂轻放桌面，手指放松；开始后别碰屏幕，静止约 6 秒。", 14, INK, false);
        if (baselineReading) timerView = text("正在测量，请保持不动", 15, ACCENT, true);
        else button("开始测量静止噪声", true, this::startBaseline);
        small("移动或中断时不会保存。");
    }

    private void renderNegatives() {
        text("普通动作片段：" + negativeCount + " / " + NEGATIVE_SAMPLES, 15, ACCENT, true);
        timerView = text(trialOpen ? "本次动作试次进行中" : "", 13, MUTED, true);
        if (!trialOpen) {
            if (negativeCount < NEGATIVE_SAMPLES) {
                button(negativeCount == 0 ? "开始一次静止负样本" : "开始一次「" + negativeMotionInstruction() + "」试次",
                        true, this::startNegativeTrial);
            } else {
                button("训练并进入独立验证", true, this::trainAndBeginVerification);
            }
        }
        text(negativeCount == 0
                        ? "静止样本：开始后保持不动约 4 秒。"
                        : "按提示做相似的日常动作，不做目标手势。",
                13, MUTED, false);
    }

    private void renderHeldOut() {
        if (!trialOpen && canStartPersonalTrial()) {
            button("用现有样本开始学习", true, this::enablePersonalTrial);
            small("个人试用：直接启用双捏与搓动，误操作可撤销。");
        }
        GestureVerificationProfile progress = uiProfile;
        int pinch = progress == null ? 0 : progress.pinchTrials();
        int pinchCorrect = progress == null ? 0 : progress.pinchCorrect();
        int rub = progress == null ? 0 : progress.rubTrials();
        int rubCorrect = progress == null ? 0 : progress.rubCorrect();
        text("拇指＋中指双捏：" + pinchCorrect + " / " + pinch + "（需 19 / 20）", 14, INK, true);
        text("拇指＋食指摩擦：" + rubCorrect + " / " + rub + "（需 19 / 20）", 14, INK, true);
        text("传感器身份与佩戴手腕已绑定：" + handLabel(), 12, MUTED, false);
        timerView = text(trialOpen ? "本次固定窗口进行中" : "", 13, MUTED, true);
        if (!trialOpen) {
            GestureKind next = nextHeldOutKind();
            int count = progress == null ? 0 : progress.heldOutTrials(next);
            String gesture = label(next);
            button("开始第 " + (count + 1) + " / 20 次「" + gesture + "」试次", true, this::startHeldOutTrial);
        }
        text("每种手势做 20 次，需判对 19 次且不混淆。", 13, MUTED, false);
        if (GestureSetupRules.canStartNormalMotion(progress) && !trialOpen) {
            button("两组试次完成，进入 10 分钟检查", true, this::beginNormalMotion);
        }
    }

    private void renderNormalMotion() {
        text("像平常一样活动手部 10 分钟，不做目标手势，保持亮屏。", 13, MUTED, false);
        text("正常动作误触必须为 0。", 15, INK, true);
        if (uiProfile != null) { text(verificationSummary(uiProfile, uiVerified), 12, MUTED, false); }
        timerView = text(normalRunning ? "10 分钟检查正在计时" : "当前未计时", 15, ACCENT, true);
        if (!normalRunning) button("开始完整 10 分钟检查", true, this::startNormalMotionCheck);
        else button("取消本次检查", false, this::cancelNormalMotionCheck);
    }

    private void renderResults() {
        if (storedSnapshot != null && storedSnapshot.personalTrial && storedSnapshot.enabled) {
            text("个人试用已开启 · 尚未完成独立验证", 14, ACCENT, true);
            button("开始手势学习", true, this::openGestureLearning);
            button("停用手势", false, this::disableProfile);
            button("继续独立验证", false, () -> postWorker(() -> {
                try {
                    storedSnapshot = storedSnapshot.withEnabled(false);
                    GestureProfileStore.write(this, storedSnapshot);
                    stage = HELD_OUT;
                    detector = new GestureDetector(model, SOURCE_HELD_OUT, false);
                    sensorsStartedByUser = true; ensureReader();
                    if (foreground && reader != null) reader.start();
                    message = "已暂停个人试用，继续验证。";
                } catch (Exception error) { message = "未能暂停个人试用，请重试。"; }
                publish();
            }));
            return;
        }
        if (verifier != null) {
            verificationText = verificationSummary(uiProfile, uiVerified);
            text(verificationText, 14, INK, false);
        } else if (!verificationText.isEmpty()) text(verificationText, 14, INK, false);
        if (storedSnapshot != null && storedSnapshot.enabled && uiVerified) {
            text("手势已启用。换设备或换手腕需重新校准。", 13, ACCENT, false);
            button("停用手势", false, this::disableProfile);
        } else if (uiVerified) {
            text("验证全部通过，点击启用。", 13, ACCENT, false);
            button("启用已验证手势", true, this::enableProfile);
        } else {
            if (canStartPersonalTrial()) button("用现有样本开始学习", true, this::enablePersonalTrial);
            if (storedSnapshot != null && storedSnapshot.enabled) button("停用旧配置并重新校准", false, this::beginRecalibration);
        }
        button("重新校准", false, this::beginRecalibration);
    }

    private void chooseHand(String chosen) {
        postWorker(() -> {
            boolean keepDiagnostic = stage == DIAGNOSTICS && diagnosticReady;
            hand = chosen;
            stage = keepDiagnostic ? DIAGNOSTICS : HOME;
            message = keepDiagnostic
                    ? "已选择" + handLabel() + "。可以开始采集真实动作样本。"
                    : "已选择" + handLabel() + "。";
            publish();
        });
    }

    private void startDiagnostics() {
        ignoreSavedProgress = true;
        postWorker(() -> {
            if (storedSnapshot == null) storedSnapshot = GestureProfileStore.read(getApplicationContext());
            if (storedSnapshot != null) {
                try {
                    storedSnapshot = storedSnapshot.withCalibrationPending(true);
                    GestureProfileStore.write(getApplicationContext(), storedSnapshot);
                }
                catch (Exception error) { message = "无法安全停用旧配置，校准没有开始。"; publish(); return; }
            }
            stage = DIAGNOSTICS;
            diagnosticReady = diagnosticFailed = false;
            calibrationRates = null;
            calibrationInProgress = true;
            sensorsStartedByUser = true;
            message = "正在读取传感器……";
            ensureReader();
            if (sensorInfo == null || !sensorInfo.usable()) {
                diagnosticStartNs = diagnosticDeadlineNs = -1;
                diagnosticFailed = true;
                message = "缺少加速度计或陀螺仪，无法校准。";
            } else {
                beginDiagnosticWindow();
                reader.start();
            }
            publish();
        });
    }

    private void beginDiagnosticWindow() {
        diagnosticReady = diagnosticFailed = false;
        diagnosticStartNs = SystemClock.elapsedRealtimeNanos();
        diagnosticDeadlineNs = diagnosticStartNs + DIAGNOSTIC_TIMEOUT_MS * 1_000_000L;
        calibrationRates = null;
        final long expectedDeadline = diagnosticDeadlineNs;
        main.postDelayed(() -> postWorker(() -> {
            if (stage != DIAGNOSTICS || diagnosticReady || diagnosticDeadlineNs != expectedDeadline) return;
            diagnosticFailed = true;
            message = "传感器数据不稳定，请重测。";
            publish();
        }), DIAGNOSTIC_TIMEOUT_MS);
    }

    private void restartDiagnostics() {
        postWorker(() -> {
            message = "重新测量传感器实际回调。";
            beginDiagnosticWindow();
            if (reader != null) reader.start();
            publish();
        });
    }

    private void ensureReader() {
        if (reader != null) return;
        reader = new GestureSensorReader(this, new GestureSensorReader.Listener() {
            @Override public void onFrame(SensorFrame frame, GestureSensorReader.RateSnapshot rates) {
                worker.post(() -> processFrame(frame, rates));
            }
            @Override public void onRates(GestureSensorReader.RateSnapshot rates,
                                          GestureSensorReader.SensorInfo sensors) {
                worker.post(() -> {
                    if (destroyed) return;
                    sensorInfo = sensors;
                    updateRatesUi(rates, SystemClock.elapsedRealtimeNanos());
                });
            }
            @Override public void onReaderStatus(String status, GestureSensorReader.SensorInfo sensors) {
                worker.post(() -> {
                    sensorInfo = sensors;
                    if (status.startsWith("需要真实") || status.startsWith("系统未能")) {
                        diagnosticReady = false;
                        diagnosticFailed = true;
                        message = status;
                        if (normalRunning) {
                            normalRunning = false;
                            normalStartedAtNs = 0;
                            normalLastFrameNs = 0;
                            normalFrameCount = 0;
                            if (detector != null) { detector.reset(); }
                            stage = RESULTS;
                            message = "传感器中断，检查作废，请重新开始。";
                        }
                        publish();
                    }
                });
            }
        }, "gesture-sensors-setup");
        sensorInfo = reader.sensorInfo();
    }

    private void processFrame(SensorFrame frame, GestureSensorReader.RateSnapshot rates) {
        if (destroyed || !ensureInteractive()) return;
        latestFrameTimestampNs = frame.timestampNanos();
        updateRatesUi(rates, frame.timestampNanos());

        if (baselineReading) {
            if (frame.timestampNanos() >= baselineWindowStartNs && frame.timestampNanos() <= baselineWindowEndNs) {
                appendDiagnosticFrame(baselineFrames, frame);
            }
            return;
        }
        if (trialOpen && frame.timestampNanos() >= trialStartedAtNs && frame.timestampNanos() <= trialDeadlineNs) {
            appendDiagnosticFrame(trialFrames, frame);
        }

        if (normalRunning) {
            if (frame.timestampNanos() <= normalStartedAtNs) return;
            if (!GestureSetupRules.acceptsNormalMotionFrame(normalLastFrameNs, frame.timestampNanos(),
                    rates.observedHz, rates.maxGapMillis)) {
                normalRunning = false;
                normalStartedAtNs = 0;
                normalLastFrameNs = 0;
                normalFrameCount = 0;
                if (detector != null) detector.reset();
                message = "传感器采样不稳定，检查作废，请重新开始。";
                publish();
                return;
            }
            normalLastFrameNs = frame.timestampNanos();
            normalFrameCount++;
        }

        if (stage == NEGATIVES && trialOpen && stillNegativeTrial) {
            long timestamp = frame.timestampNanos();
            if (timestamp >= stillWindowStartNs && timestamp <= stillWindowEndNs
                    && (lastStillFrameNs < 0 || timestamp - lastStillFrameNs >= GestureDetector.CAPTURE_SAMPLE_PERIOD_NANOS)
                    && stillNegativeFrames.size() < GestureSample.MAX_FRAMES) {
                stillNegativeFrames.add(frame);
                lastStillFrameNs = timestamp;
            }
            return;
        }

        if (stage == DIAGNOSTICS) {
            if (diagnosticStartNs < 0) {
                beginDiagnosticWindow();
            }
            long elapsed = frame.timestampNanos() - diagnosticStartNs;
            if (!diagnosticReady && elapsed >= DIAGNOSTIC_READY_MS * 1_000_000L && ratesStable(rates)) {
                diagnosticReady = true;
                diagnosticFailed = false;
                calibrationRates = rates;
                message = "传感器正常，可以开始采集。";
                publish();
            } else if (!diagnosticReady && elapsed >= DIAGNOSTIC_TIMEOUT_MS * 1_000_000L) {
                diagnosticFailed = true;
                message = "采样率不达标，请重测。";
                publish();
            }
        }

        if (detector == null) return;
        if (!interactionGuard.allows(frame.timestampNanos(), SystemClock.elapsedRealtimeNanos())) {
            detector.reset();
            return;
        }
        GestureDetector.DetectionEvent detection;
        try { detection = detector.accept(frame); }
        catch (IllegalArgumentException invalidTimestamp) { detector.reset(); return; }
        if (detection == null) return;
        if (!interactionGuard.allows(detection.startedAtNanos(), SystemClock.elapsedRealtimeNanos())) return;

        if (stage == NORMAL_MOTION && normalRunning && detection.kind() != GestureKind.UNKNOWN) {
            normalFalsePositives++;
            normalRunning = false;
            normalStartedAtNs = normalLastFrameNs = 0;
            normalFrameCount = 0;
            if (detector != null) detector.reset();
            try {
                verifier.recordNormalMotion(1, normalFalsePositives, realHardware, SOURCE_NORMAL);
                saveProgress();
            } catch (RuntimeException error) { message = "误触证据无法保存，验证已停用。"; }
            if (message.isEmpty() || !message.contains("无法保存")) {
                message = "检查中出现误触，请重新校准。";
            }
            stage = RESULTS;
            uiProfile = verifier.profile();
            uiVerified = verifier.isProductionVerified();
            verificationText = verificationSummary(uiProfile, uiVerified);
            saveProgress();
            publish();
            return;
        }

        if (!trialOpen || trialInterfered || stage != TRAINING && stage != NEGATIVES && stage != HELD_OUT) return;
        if (!GestureSetupRules.acceptsTrialCandidate(detection.startedAtNanos(), detection.endedAtNanos(),
                trialStartedAtNs, trialDeadlineNs)) return;
        GestureSample sample = detection.sample();
        if (sample == null || detection.frameCount() < GestureSample.MIN_FRAMES || detection.frameCount() > GestureDetector.MAX_SEGMENT_FRAMES) return;
        if (stage == TRAINING) {
            GestureSignalQuality.Result quality = GestureSignalQuality.evaluate(emptyControlTrial
                    ? GestureKind.DOUBLE_PINCH_MIDDLE : trialExpected, sample.frames(), noiseProfile);
            if (emptyControlTrial && !quality.accepted()) {
                quality = GestureSignalQuality.evaluate(GestureKind.RUB_INDEX, sample.frames(), noiseProfile);
            }
            trialQualityMessage = quality.reason() + " · 脉冲 " + quality.pulseCount()
                    + " · 信噪比 " + fmt(quality.signalToNoise());
            if (!quality.accepted()) {
                message = "片段未通过质量检查：" + trialQualityMessage;
                publish();
                return;
            }
            qualifyingCandidates++;
            if (emptyControlTrial) return;
            if (qualifyingCandidates > 1) {
                trialCandidate = null;
                message = "出现多组动作，本次不计入。";
                publish();
                return;
            }
        } else if (trialCandidateReceived) return;
        if (stage == HELD_OUT) {
            trialCandidate = sample;
            trialCandidateReceived = true;
            queueLatencyFeedback(detection.endedAtNanos(), trialId);
        } else {
            trialCandidate = sample;
            trialCandidateReceived = true;
            message = stage == NEGATIVES ? "日常动作已捕获，等待窗口结束。" : "已捕获，等待窗口结束。";
            publish();
        }
    }

    private void updateRatesUi(GestureSensorReader.RateSnapshot rates, long timestampNs) {
        if (rates == null) return;
        latestRates = rates;
        if (lastRateUiTimestampNs >= 0 && timestampNs - lastRateUiTimestampNs < 1_000_000_000L) return;
        lastRateUiTimestampNs = timestampNs;
        ratesText = "实际回调：加速度计 " + fmt(rates.accelerometerHz) + " Hz · 陀螺仪 " + fmt(rates.gyroscopeHz)
                + " Hz · 融合 " + fmt(rates.observedHz) + " Hz · 最大间隔 " + rates.maxGapMillis + " ms";
        String display = ratesText;
        main.post(() -> { if (!destroyed && ratesView != null) ratesView.setText(display); });
    }

    private void queueLatencyFeedback(long endedAtNs, long expectedTrialId) {
        if (latencyQueuedForTrial) return;
        latencyQueuedForTrial = true;
        main.post(() -> {
            if (destroyed || !ensureInteractive() || stateView == null) return;
            TextView target = stateView;
            target.postOnAnimation(() -> {
                if (destroyed || !ensureInteractive() || target.getWindowToken() == null) return;
                ViewTreeObserver observer = target.getViewTreeObserver();
                observer.addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                    private boolean recorded;
                    @Override public void onDraw() {
                        if (recorded) return;
                        recorded = true;
                        long now = SystemClock.elapsedRealtimeNanos();
                        long latencyMs = now < endedAtNs ? 60_001
                                : Math.min(60_001L, (now - endedAtNs + 999_999L) / 1_000_000L);
                        target.post(() -> { if (observer.isAlive()) observer.removeOnDrawListener(this); });
                        postWorker(() -> {
                            if (stage == HELD_OUT && trialOpen && !trialInterfered
                                    && expectedTrialId == trialId && verifier != null) pendingLatencyMs = latencyMs;
                        });
                    }
                });
                target.setText("已捕获本次动作，等待窗口结束。");
                target.invalidate();
            });
        });
    }

    private void beginTraining() {
        postWorker(() -> {
            if (trialOpen || baselineReading) return;
            if (!diagnosticReady || hand == null || sensorInfo == null || !sensorInfo.usable() || !realHardware) {
                message = "请先完成传感器测量。"; publish(); return;
            }
            try { GestureCalibrationDraft.discard(this); }
            catch (Exception error) { message = "旧草稿未能停用，暂不开始新校准。"; publish(); return; }
            trainingSamples.clear();
            trainingCounts[0] = trainingCounts[1] = 0;
            negativeCount = 0;
            model = null; verifier = null;
            noiseProfile = null;
            emptyControlPassed = false;
            calibrationInProgress = true;
            stage = BASELINE;
            detector = null;
            message = "先测静止噪声，再做空白对照，最后采集两种手势。";
            publish();
        });
    }

    private void startBaseline() {
        postWorker(() -> {
            if (stage != BASELINE || baselineReading || !ensureInteractive()) return;
            baselineFrames.clear();
            baselineInterrupted = false;
            baselineReading = true;
            baselineWindowStartNs = SystemClock.elapsedRealtimeNanos() + 2_000_000_000L;
            baselineWindowEndNs = baselineWindowStartNs + 4_000_000_000L;
            long run = ++baselineRun;
            message = "请放松手指，保持静止，不再碰屏幕。";
            publish();
            main.post(() -> updateBaselineTimer(run));
            main.postDelayed(() -> postWorker(() -> finishBaseline(run)), 6_700L);
        });
    }

    private void finishBaseline(long run) {
        if (!baselineReading || run != baselineRun) return;
        baselineReading = false;
        try {
            if (baselineInterrupted || !GestureSetupRules.hasCompleteStillWindow(baselineFrames,
                    baselineWindowStartNs, baselineWindowEndNs)) {
                throw new IllegalArgumentException("测量被打断，请保持不动重测");
            }
            noiseProfile = GestureNoiseProfile.fromStillFrames(new ArrayList<>(baselineFrames));
            stage = TRAINING;
            emptyControlPassed = false;
            detector = GestureDetector.captureOnly(SOURCE_TRAINING, false, noiseProfile);
            message = "静止噪声已测好。下一步空白对照：全程不做手势。";
        } catch (RuntimeException invalid) {
            noiseProfile = null;
            String detail = String.valueOf(invalid.getMessage());
            message = "静止噪声未保存：" + (detail.contains("movement") || detail.contains("spike")
                    ? "检测到移动，放松后重测。"
                    : "采样被打断，请保持亮屏静止重测。");
        }
        GestureTrialDiagnostics.write(this, "baseline", "UNKNOWN", message, baselineFrames);
        baselineFrames.clear();
        saveCalibrationDraft();
        publish();
    }

    private void startEmptyControl() {
        postWorker(() -> {
            if (stage != TRAINING || trialOpen || noiseProfile == null) return;
            emptyControlPassed = false;
            openTrial(GestureKind.UNKNOWN, "空白对照：全程放松，不做手势。 ");
        });
    }

    private static void appendDiagnosticFrame(List<SensorFrame> target, SensorFrame frame) {
        // Diagnostic recording is bounded and written only after a trial. It is never a template.
        if (target.size() >= 4096) return;
        if (target.isEmpty() || frame.timestampNanos() - target.get(target.size() - 1).timestampNanos() >= 2_000_000L) {
            target.add(frame);
        }
    }

    private void startTrainingTrial(GestureKind expected) {
        postWorker(() -> {
            if (stage != TRAINING || trialOpen || !emptyControlPassed || noiseProfile == null) { return; }
            if (trainingCounts[classIndex(expected)] >= trainingRoundLimit()) { return; }
            openTrial(expected, "先保持不动，看到“现在做手势”再做一次。");
        });
    }

    private void beginNegativeSamples() {
        postWorker(() -> {
            if (stage != TRAINING || trainingCounts[0] < TRAINING_PER_GESTURE || trainingCounts[1] < TRAINING_PER_GESTURE) { return; }
            stage = NEGATIVES;
            detector = GestureDetector.captureOnly(SOURCE_TRAINING, false, noiseProfile);
            negativeCount = 0;
            message = "按提示做日常动作，不做目标手势。";
            publish();
        });
    }

    private void startNegativeTrial() {
        postWorker(() -> {
            if (stage != NEGATIVES || trialOpen || negativeCount >= NEGATIVE_SAMPLES) { return; }
            openTrial(GestureKind.UNKNOWN, negativeCount == 0
                    ? "静止样本开始：先静等 2 秒，再保持不动 1.5 秒。"
                    : "先保持不动，准备结束后" + negativeMotionInstruction() + "，不做目标手势。");
        });
    }

    private void trainAndBeginVerification() {
        postWorker(() -> {
            if (stage != NEGATIVES || negativeCount < NEGATIVE_SAMPLES
                    || trainingSamples.size() < TRAINING_PER_GESTURE * 2 + NEGATIVE_SAMPLES) { return; }
            if (!saveCalibrationDraft()) { publish(); return; }
            try {
                model = GestureModel.train(Collections.unmodifiableList(new ArrayList<>(trainingSamples)), noiseProfile);
                verifier = new GestureProfileVerifier(model);
                stage = HELD_OUT;
                detector = new GestureDetector(model, SOURCE_HELD_OUT, false);
                message = "样本已保存，模型已生成。"
                        + model.trainingDiagnostics().failureReason();
                android.util.Log.i("WordWatchGesture", model.trainingDiagnostics().summary());
                saveProgress();
                publish();
            } catch (RuntimeException trainingFailure) {
                model = null; verifier = null;
                android.util.Log.e("WordWatchGesture", "Training failed; raw calibration retained", trainingFailure);
                message = "样本已保留，训练未完成：" + String.valueOf(trainingFailure.getMessage())
                        + "。无需退出或重采，可用现有样本重试。";
                stage = NEGATIVES;
                publish();
            }
        });
    }

    private void startHeldOutTrial() {
        postWorker(() -> {
            if (stage != HELD_OUT || verifier == null || trialOpen || !foreground || !readDeviceInteractive()) { return; }
            GestureKind expected = nextHeldOutKind();
            GestureVerificationProfile p = verifier.profile();
            if (p.heldOutTrials(expected) >= GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE) { return; }
            trialExpected = expected;
            latencyQueuedForTrial = false;
            openTrial(expected, "独立验证：" + label(expected) + "。等准备结束再做一次，漏采计为 UNKNOWN。");
        });
    }

    private void openTrial(GestureKind expected, String prompt) {
        if (!ensureInteractive()) return;
        if (detector != null) detector.reset();
        trialId++;
        trialExpected = expected;
        emptyControlTrial = stage == TRAINING && expected == GestureKind.UNKNOWN;
        trialInterfered = false;
        qualifyingCandidates = 0;
        trialQualityMessage = "未发现达到门槛的动作";
        trialFrames.clear();
        trialStartedAtNs = SystemClock.elapsedRealtimeNanos();
        trialDeadlineNs = trialStartedAtNs + TRIAL_WINDOW_MS * 1_000_000L;
        stillNegativeTrial = stage == NEGATIVES && negativeCount == 0;
        stillNegativeFrames.clear();
        lastStillFrameNs = -1;
        stillWindowStartNs = trialStartedAtNs + STILL_SETTLE_NS;
        stillWindowEndNs = stillWindowStartNs + STILL_CAPTURE_NS;
        trialCandidate = null;
        trialCandidateReceived = false;
        pendingLatencyMs = -1;
        trialOpen = true;
        message = prompt;
        long thisId = trialId;
        main.post(() -> {
            if (!destroyed) { render(); updateTrialTimer(thisId, trialDeadlineNs); }
        });
        main.postDelayed(() -> postWorker(() -> finishTrial(thisId)), TRIAL_WINDOW_MS + TRIAL_GRACE_MS);
    }

    private void finishTrial(long thisId) {
        if (!trialOpen || thisId != trialId) return;
        trialOpen = false;
        GestureSample candidate = trialCandidate;
        if (stage == NEGATIVES && stillNegativeTrial) candidate = makeStillNegativeSample();
        if (trialInterfered || stage == TRAINING && qualifyingCandidates > 1) candidate = null;
        stillNegativeTrial = false;
        stillNegativeFrames.clear();
        lastStillFrameNs = -1;
        trialCandidate = null;
        trialCandidateReceived = false;
        if (stage == TRAINING && emptyControlTrial) {
            boolean complete = GestureSetupRules.hasCompleteStillWindow(trialFrames,
                    trialStartedAtNs, trialDeadlineNs);
            emptyControlPassed = complete && !trialInterfered && qualifyingCandidates == 0;
            message = emptyControlPassed
                    ? "空白对照通过，开始采集手势。"
                    : "空白对照未通过：" + (!complete ? "采样不连续" : trialInterfered
                    ? "触屏或按键干扰" : "静止时出现 " + qualifyingCandidates + " 组疑似手势")
                    + "。请放松手臂，重新测静止噪声。";
        } else if (stage == TRAINING) {
            if (candidate != null) {
                GestureKind expected = trialExpected;
                GestureSample labeled = candidate.withKind(expected);
                if (!labeled.synthetic() && (expected == GestureKind.DOUBLE_PINCH_MIDDLE || expected == GestureKind.RUB_INDEX)) {
                    trainingSamples.add(labeled);
                    trainingCounts[classIndex(expected)]++;
                    message = "已记 " + (expected == GestureKind.DOUBLE_PINCH_MIDDLE ? "双捏 " : "搓动 ")
                            + trainingCounts[classIndex(expected)] + "/" + TRAINING_PER_GESTURE + "。";
                }
            } else message = "本次未加入训练：" + (trialInterfered ? "触屏或按键干扰"
                    : qualifyingCandidates > 1 ? "出现多组动作" : trialQualityMessage) + "。";
        } else if (stage == NEGATIVES) {
            if (candidate != null) {
                trainingSamples.add(candidate.withKind(GestureKind.UNKNOWN));
                negativeCount++;
                message = "日常动作负样本已保存（" + negativeCount + " / " + NEGATIVE_SAMPLES + "）。";
            } else message = "没捕获到动作，本次不计入。";
        } else if (stage == HELD_OUT && verifier != null) {
            if (candidate != null) {
                verifier.recordHeldOut(trialExpected, candidate, realHardware, SOURCE_HELD_OUT);
                GestureKind prediction = model.classify(candidate.frames()).kind();
                message = "本次独立试次记录为 " + (prediction == trialExpected ? "判对" : prediction == GestureKind.UNKNOWN ? "UNKNOWN" : "另一类手势") + "；不会重做本次。";
                if (pendingLatencyMs >= 0) verifier.recordPhysicalLatency(pendingLatencyMs, realHardware, SOURCE_LATENCY);
            } else {
                verifier.recordMissingHeldOut(trialExpected, realHardware, SOURCE_HELD_OUT);
                message = "本次没识别到，记为未识别。";
            }
            GestureVerificationProfile p = verifier.profile();
            uiProfile = p;
            uiVerified = verifier.isProductionVerified();
            verificationText = verificationSummary(p, uiVerified);
            saveProgress();
            if (p.pinchTrials() >= GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE
                    && p.rubTrials() >= GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE) {
                if (GestureSetupRules.canStartNormalMotion(p)) {
                    stage = NORMAL_MOTION;
                    detector = new GestureDetector(model, SOURCE_NORMAL, false);
                    message += " 准确率和延迟达标，开始 10 分钟误触检查。";
                } else {
                    stage = RESULTS;
                    detector = null;
                    message += " 准确率或延迟未达标，请重新校准。";
                }
            }
        }
        GestureTrialDiagnostics.write(this, emptyControlTrial ? "empty-control" : "trial",
                trialExpected.name(), message, trialFrames);
        trialFrames.clear();
        emptyControlTrial = false;
        saveProgress();
        publish();
    }

    private GestureSample makeStillNegativeSample() {
        if (!GestureSetupRules.hasCompleteStillWindow(stillNegativeFrames, stillWindowStartNs, stillWindowEndNs)) return null;
        try {
            return GestureSample.of(GestureKind.UNKNOWN, new ArrayList<>(stillNegativeFrames), SOURCE_TRAINING, false);
        } catch (IllegalArgumentException invalidWindow) {
            return null;
        }
    }

    /** Round two opens only after both gestures finished round one. */
    private int trainingRoundLimit() {
        return Math.min(trainingCounts[0], trainingCounts[1]) >= TRAINING_PER_ROUND
                ? TRAINING_PER_GESTURE : TRAINING_PER_ROUND;
    }

    /** Near-miss motions; outward wrist rotation is avoided because the system turns the screen off. */
    private String negativeMotionInstruction() {
        if (negativeCount <= 0) return "保持静止";
        switch ((negativeCount - 1) % 6) {
            case 0: return "抬腕看表";
            case 1: return "手腕往里翻";
            case 2: return "手指轻敲桌面两下";
            case 3: return "像握笔般轻微活动";
            case 4: return "拇指与中指只捏一次";
            default: return "手指随意动一动";
        }
    }

    private GestureKind nextHeldOutKind() {
        if (verifier == null) return GestureKind.DOUBLE_PINCH_MIDDLE;
        return verifier.profile().pinchTrials() < GestureVerificationProfile.REQUIRED_HELD_OUT_PER_GESTURE
                ? GestureKind.DOUBLE_PINCH_MIDDLE : GestureKind.RUB_INDEX;
    }

    private void beginNormalMotion() {
        postWorker(() -> {
            if (stage != HELD_OUT || verifier == null) return;
            GestureVerificationProfile p = verifier.profile();
            if (!GestureSetupRules.canStartNormalMotion(p)) {
                stage = RESULTS;
                message = "准确率或延迟未达标，请重新校准。";
                publish();
                return;
            }
            stage = NORMAL_MOTION;
            detector = new GestureDetector(model, SOURCE_NORMAL, false);
            normalRunning = false;
            normalStartedAtNs = 0;
            message = "请只在坐姿或站姿进行完整 10 分钟检查。";
            saveProgress();
            publish();
        });
    }

    private void startNormalMotionCheck() {
        postWorker(() -> {
            if (stage != NORMAL_MOTION || normalRunning || verifier == null || !foreground || !readDeviceInteractive()) return;
            if (verifier.profile().failClosed()) {
                message = "验证未通过，请重新校准。";
                stage = RESULTS; publish(); return;
            }
            normalRunning = true;
            normalStartedAtNs = SystemClock.elapsedRealtimeNanos();
            normalLastFrameNs = normalStartedAtNs;
            normalFrameCount = 0;
            normalFalsePositives = 0;
            detector = new GestureDetector(model, SOURCE_NORMAL, false);
            message = "保持亮屏，不做目标手势。";
            publish();
            updateNormalTimer();
        });
    }

    private void updateNormalTimer() {
        if (destroyed || !normalRunning || !ensureInteractive()) return;
        long elapsed = SystemClock.elapsedRealtimeNanos() - normalStartedAtNs;
        long remaining = Math.max(0, NORMAL_CHECK_MS - elapsed / 1_000_000L);
        main.post(() -> {
            if (timerView != null && normalRunning) timerView.setText("剩余 " + formatClock(remaining) + " · 前台连续计时");
        });
        if (remaining <= 0) {
            postWorker(this::finishNormalMotionCheck);
        } else main.postDelayed(this::updateNormalTimer, 1000);
    }

    private void finishNormalMotionCheck() {
        if (!normalRunning || verifier == null || !ensureInteractive()) return;
        long elapsedMs = Math.min(NORMAL_CHECK_MS, (SystemClock.elapsedRealtimeNanos() - normalStartedAtNs) / 1_000_000L);
        if (elapsedMs < NORMAL_CHECK_MS) { updateNormalTimer(); return; }
        long nowNs = SystemClock.elapsedRealtimeNanos();
        GestureSensorReader.RateSnapshot rates = latestRates;
        boolean streamComplete = normalLastFrameNs > 0 && nowNs >= normalLastFrameNs
                && nowNs - normalLastFrameNs <= MAX_LIVE_FRAME_GAP_NS
                && rates != null && GestureSetupRules.hasAcceptableNormalMotionRate(
                rates.observedHz, rates.maxGapMillis)
                && GestureSetupRules.hasEnoughFrames(elapsedMs, normalFrameCount);
        normalRunning = false;
        normalStartedAtNs = 0;
        normalLastFrameNs = 0;
        if (!streamComplete) {
            normalFrameCount = 0;
            if (detector != null) detector.reset();
            stage = RESULTS;
            message = "传感器数据不足，本次检查作废，请重试。";
            publish();
            return;
        }
        normalFrameCount = 0;
        verifier.recordNormalMotion(elapsedMs, 0, realHardware, SOURCE_NORMAL);
        uiProfile = verifier.profile();
        boolean verified = verifier.isProductionVerified();
        uiVerified = verified;
        verificationText = verificationSummary(uiProfile, verified);
        message = verified ? "验证全部通过，点击启用。"
                : "10 分钟完成，但未达标，请查看结果。";
        stage = RESULTS;
        saveProgress();
        publish();
    }

    private void cancelNormalMotionCheck() {
        postWorker(() -> {
            if (!normalRunning) return;
            normalRunning = false;
            normalStartedAtNs = 0;
            normalLastFrameNs = 0;
            normalFrameCount = 0;
            if (detector != null) detector.reset();
            message = "检查已取消。";
            publish();
        });
    }

    private void saveProgress() {
        if (model == null) saveCalibrationDraft();
        if (model == null || verifier == null || hand == null || sensorInfo == null || !sensorInfo.usable()) return;
        GestureSensorReader.RateSnapshot measured = calibrationRates != null ? calibrationRates : latestRates;
        if (measured == null || measured.accelerometerHz <= 0 || measured.gyroscopeHz <= 0 || measured.observedHz <= 0) return;
        try {
            GestureVerificationProfile profile = verifier.profile();
            uiProfile = profile;
            uiVerified = verifier.isProductionVerified();
            GestureProfileStore.Snapshot saved = new GestureProfileStore.Snapshot(model.toJson(), verifier.toJson(),
                    profile.toJson(), model.revision(), sensorInfo.identity, sensorInfo.description,
                    measured.accelerometerHz, measured.gyroscopeHz, measured.observedHz,
                    measured.maxGapMillis, hand, profile.isProductionVerifiedFor(model) ? System.currentTimeMillis() : 0,
                    false);
            GestureProfileStore.write(getApplicationContext(), saved);
            storedSnapshot = saved;
        } catch (Exception failure) {
            message = "本机手势进度未能原子保存，验证未通过。";
            stage = RESULTS;
        }
    }

    private boolean canStartPersonalTrial() {
        return realHardware && model != null && storedSnapshot != null && uiProfile != null
                && sensorInfo != null && sensorInfo.identity.equals(storedSnapshot.sensorIdentity)
                && storedSnapshot.canTryPersonally(model, uiProfile);
    }

    private void openGestureLearning() {
        startActivity(new Intent(this, cn.wordwatch.app.GestureStudyActivity.class));
    }

    private void enablePersonalTrial() {
        postWorker(() -> {
            if (trialOpen || normalRunning || !canStartPersonalTrial()) {
                message = "需先完成校准样本采集。"; publish(); return;
            }
            try {
                GestureProfileStore.Snapshot enabled = storedSnapshot.withPersonalTrial();
                GestureProfileStore.write(this, enabled);
                storedSnapshot = enabled;
                stage = RESULTS;
                message = "个人试用已启用；双捏翻面或知道，搓动记录不清楚。";
                android.util.Log.i("WordWatchGesture", "Personal trial enabled; independent verification remains incomplete");
                main.post(() -> { if (!destroyed) { render(); openGestureLearning(); } });
            } catch (Exception error) { message = "个人试用状态未能保存，请重试。"; publish(); }
        });
    }

    private void enableProfile() {
        postWorker(() -> {
            if (storedSnapshot == null || storedSnapshot.calibrationPending || model == null || verifier == null
                    || !verifier.isProductionVerified()) {
                message = "验证未全部通过，暂不能启用。"; publish(); return;
            }
            try {
                GestureVerificationProfile profile = GestureVerificationProfile.fromJson(storedSnapshot.verificationProfileJson);
                GestureModel storedModel = GestureModel.fromJson(storedSnapshot.modelJson);
                if (!profile.isProductionVerifiedFor(storedModel) || !sensorInfo.identity.equals(storedSnapshot.sensorIdentity)) {
                    throw new IllegalStateException("verification no longer matches device");
                }
                GestureProfileStore.Snapshot enabled = storedSnapshot.withEnabled(true);
                GestureProfileStore.write(getApplicationContext(), enabled);
                storedSnapshot = enabled;
                uiVerified = true;
                message = "已启用。";
            } catch (Exception invalid) { message = "启用失败：验证资料与设备状态不匹配。"; }
            publish();
        });
    }

    private void disableProfile() {
        postWorker(() -> {
            if (storedSnapshot == null) return;
            try {
                storedSnapshot = storedSnapshot.withEnabled(false);
                GestureProfileStore.write(getApplicationContext(), storedSnapshot);
                message = "手势已停用；本机验证资料仍保留。";
            } catch (Exception error) { message = "停用状态未能保存。"; }
            publish();
        });
    }

    private void beginRecalibration() {
        ignoreSavedProgress = true;
        postWorker(() -> {
            try { GestureCalibrationDraft.discard(this); }
            catch (Exception error) { message = "旧草稿未能停用，重新校准未开始。"; publish(); return; }
            if (storedSnapshot == null) storedSnapshot = GestureProfileStore.read(getApplicationContext());
            if (storedSnapshot != null) {
                try {
                    storedSnapshot = storedSnapshot.withCalibrationPending(true);
                    GestureProfileStore.write(getApplicationContext(), storedSnapshot);
                } catch (Exception error) { message = "旧配置未能安全停用，重新校准未开始。"; publish(); return; }
            }
            stage = HOME;
            hand = null;
            model = null; verifier = null;
            noiseProfile = null;
            emptyControlPassed = false;
            baselineReading = false;
            baselineRun++;
            baselineFrames.clear();
            trialFrames.clear();
            calibrationInProgress = false;
            diagnosticReady = diagnosticFailed = false;
            trainingSamples.clear(); trainingCounts[0] = trainingCounts[1] = 0; negativeCount = 0;
            message = "旧配置已停用，请重新校准。";
            publish();
        });
    }

    private void publish() {
        main.post(() -> { if (!destroyed) render(); });
    }

    private void postWorker(Runnable action) {
        Handler target = worker;
        if (target != null && !destroyed) target.post(action);
    }

    private void updateTrialTimer(long thisId, long deadlineNs) {
        if (destroyed || !trialOpen || thisId != trialId || timerView == null || !ensureInteractive()) return;
        long nowNs = SystemClock.elapsedRealtimeNanos();
        long remain = Math.max(0, deadlineNs - nowNs);
        long settleRemain = GestureSetupRules.TRIAL_SETTLE_NANOS - (nowNs - trialStartedAtNs);
        if (remain == 0) timerView.setText("窗口结束，正在收尾。" );
        else if (settleRemain > 0) timerView.setText("准备：保持不动 "
                + (settleRemain + 999_999_999L) / 1_000_000_000L + " 秒");
        else if (stillNegativeTrial || emptyControlTrial) timerView.setText("继续保持不动 · 剩余 "
                + (remain + 999_999_999L) / 1_000_000_000L + " 秒");
        else if (trialCandidateReceived) timerView.setText("片段已记录，请保持不动");
        else timerView.setText((stage == NEGATIVES ? "现在做日常动作" : "现在做手势")
                + " · 剩余 " + (remain + 999_999_999L) / 1_000_000_000L + " 秒");
        main.postDelayed(() -> updateTrialTimer(thisId, deadlineNs), 500);
    }

    private void updateBaselineTimer(long run) {
        if (destroyed || !baselineReading || run != baselineRun || timerView == null || !ensureInteractive()) return;
        long now = SystemClock.elapsedRealtimeNanos();
        long prepare = baselineWindowStartNs - now;
        long remain = Math.max(0L, baselineWindowEndNs - now);
        timerView.setText(prepare > 0 ? "准备静止 " + (prepare + 999_999_999L) / 1_000_000_000L + " 秒"
                : remain > 0 ? "保持不动 " + (remain + 999_999_999L) / 1_000_000_000L + " 秒" : "测量完成，正在检查");
        main.postDelayed(() -> updateBaselineTimer(run), 500);
    }

    private void showSensorInfo() {
        GestureSensorReader.SensorInfo info = sensorInfo;
        if (info == null || !info.usable()) {
            text("未检测到完整的加速度计与陀螺仪组合。", 12, BAD, false);
            return;
        }
        text("加速度计：" + info.accelerometer.getName() + " · " + info.accelerometer.getVendor(), 12, MUTED, false);
        text("陀螺仪：" + info.gyroscope.getName() + " · " + info.gyroscope.getVendor(), 12, MUTED, false);
    }

    private boolean ratesStable(GestureSensorReader.RateSnapshot rates) {
        return realHardware && sensorInfo != null && sensorInfo.usable()
                && rates.accelerometerHz >= 12.0 && rates.gyroscopeHz >= 12.0 && rates.observedHz >= 12.0
                && rates.maxGapMillis <= 180;
    }

    private boolean messageLooksBad() {
        return message.contains("未") || message.contains("无法") || message.contains("失败") || message.contains("漏采")
                || message.contains("误触") || message.contains("打断") || message.contains("不通过") || message.contains("缺少");
    }

    private void title(String value) { text(value, 23, INK, true); }
    private void small(String value) { text(value, 12, MUTED, false); }
    private void gap(int d) { View spacer = new View(this); body.addView(spacer, new LinearLayout.LayoutParams(1, dp(d))); }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(color); view.setTextSize(size);
        view.setGravity(Gravity.CENTER); view.setLineSpacing(dp(2), 1.06f); view.setPadding(0, dp(3), 0, dp(3));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(view, new LinearLayout.LayoutParams(-1, -2));
        if (value.equals(message) && value.length() > 0) stateView = view;
        if (value.startsWith("本次剩余") || value.equals("10 分钟检查正在计时")) timerView = view;
        return view;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this); view.setAllCaps(false); view.setText(label); view.setTextSize(14);
        view.setStateListAnimator(null); view.setElevation(0f);
        view.setTextColor(primary ? BG : INK);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(primary ? ACCENT : SURFACE); shape.setCornerRadius(dp(23));
        view.setBackground(shape); view.setPadding(dp(9), dp(7), dp(9), dp(7));
        view.setMinHeight(dp(50)); view.setMinimumHeight(dp(50));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); body.addView(view, lp);
        view.setOnClickListener(v -> { if (ensureInteractive()) action.run(); });
        return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String handLabel() { return "left".equals(hand) ? "左手腕" : "right".equals(hand) ? "右手腕" : "未选择"; }
    private static String label(GestureKind kind) { return kind == GestureKind.DOUBLE_PINCH_MIDDLE ? "拇指＋中指双捏" : "拇指＋食指搓动"; }
    private static int classIndex(GestureKind kind) { return kind == GestureKind.DOUBLE_PINCH_MIDDLE ? 0 : 1; }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String formatClock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private static String verificationSummary(GestureVerificationProfile profile, boolean verified) {
        if (profile == null) return "尚无验证证据。";
        return "双捏 " + profile.pinchCorrect() + "/" + profile.pinchTrials()
                + " · 食指摩擦 " + profile.rubCorrect() + "/" + profile.rubTrials()
                + " · 交叉误判 " + profile.crossClassConfusions()
                + " · 日常动作 " + (profile.normalMotionMillis() / 60_000) + "/10 分钟，误触 " + profile.normalMotionFalsePositives()
                + " · 实测反馈 p95 " + profile.latencyP95Millis() + " ms（" + profile.latencySampleCount() + " 次）"
                + (verified ? " · 门槛满足" : " · 尚未满足全部门槛");
    }

    private boolean isRealHardware() {
        String fingerprint = android.os.Build.FINGERPRINT == null ? "" : android.os.Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        String modelName = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.toLowerCase(Locale.ROOT);
        String hardware = android.os.Build.HARDWARE == null ? "" : android.os.Build.HARDWARE.toLowerCase(Locale.ROOT);
        String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND.toLowerCase(Locale.ROOT);
        return !fingerprint.contains("generic") && !modelName.contains("emulator") && !hardware.contains("ranchu")
                && !hardware.contains("goldfish") && !brand.startsWith("generic");
    }
}
