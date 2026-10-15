package cn.wordwatch.app;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.wordwatch.app.core.LearningSettings;
import cn.wordwatch.app.core.SessionPolicy;
import cn.wordwatch.app.core.StudyEngine;
import cn.wordwatch.app.gesture.CardContext;
import cn.wordwatch.app.gesture.GestureRuntime;
import cn.wordwatch.app.gesture.GestureSetupActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small view of the current card; all study computation and durable writes run off the UI thread. */
public final class GestureStudyActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 17), INK = Color.rgb(239, 247, 248);
    private static final int ACCENT = Color.rgb(133, 227, 207), MUTED = Color.rgb(157, 177, 184);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Button> buttons = new ArrayList<>();
    private final GestureActionGate gestureGate = new GestureActionGate();
    private AppStore store;
    private GestureRuntime runtime;
    private LinearLayout body;
    private ScrollView scroll;
    private boolean cardViewsReady;
    private String displayedCardKey;
    private TextView progressView, wordView, phoneticView, meaningView, instructionView, exampleEnView, exampleZhView;
    private Button revealButton, rememberButton, hardButton, undoButton;
    private ViewState current;
    private CardContext context;
    private TextView status;
    private boolean foreground, ready, profileEligible, busy, destroyed;
    private boolean windowFocused;
    private boolean pendingReveal;
    private long generation;
    private String lastError;
    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            // Some watch firmwares keep a visible Activity resumed across display-off.
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && foreground && profileEligible
                    && !busy && current != null && !current.finished()) renderCard();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Finger gestures do not count as Android touchscreen activity.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOnReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOnReceiver, screenFilter);
        layout(); text("正在准备手势学习", 20, INK);
        runtime = new GestureRuntime(this, new GestureRuntime.Listener() {
            @Override public void onStatus(GestureRuntime.Status value) {
                ready = value.ready;
                if (status != null && foreground && !busy) status.setText(value.message);
            }
            @Override public void onGesture(GestureRuntime.Event event) { acceptGesture(event); }
        });
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true;
        GestureRuntime.queryStatus(this, value -> {
            if (destroyed || !foreground) return;
            ready = value.ready;
            profileEligible = value.ready;
            if (!ready) showGate(value.message);
            else if (!busy) perform(() -> load(true));
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (runtime != null && event != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    runtime.onTouchInteraction(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_OUTSIDE:
                    runtime.onTouchInteraction(false);
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // The authorized ADB keepalive sends WAKEUP; it has no physical wrist interaction.
        if (runtime != null && event != null && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) runtime.onUserInteraction();
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (runtime != null && event != null && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            runtime.onUserInteraction();
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override protected void onPause() {
        foreground = false; generation++; context = null;
        if (runtime != null) {
            runtime.onTouchInteraction(false);
            runtime.pause();
        }
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        if (!hasFocus && foreground && runtime != null) {
            generation++; context = null; ready = false;
            runtime.onTouchInteraction(false);
            runtime.pause();
        } else if (hasFocus && foreground && profileEligible && !busy && current != null) renderCard();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterReceiver(screenOnReceiver);
        if (runtime != null) runtime.close();
        // An already accepted atomic write is allowed to finish; it must not be cancelled on exit.
        io.shutdown(); super.onDestroy();
    }

    private void showGate(String message) {
        if (runtime != null) runtime.pause();
        layout(); text("先完成手势校准", 22, INK);
        text(message, 13, MUTED);
        button("打开校准与验证", true, () -> startActivity(new Intent(this, GestureSetupActivity.class)));
        button("返回", false, this::finish);
    }

    private void perform(Callable<ViewState> work) {
        if (busy || destroyed) return;
        busy = true; generation++; context = null;
        if (runtime != null) runtime.clearCard();
        if (pendingReveal) renderCard();
        for (Button b : buttons) b.setEnabled(false);
        if (status != null) status.setText("正在保存…");
        io.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            ViewState value = null; Exception failure = null;
            try { if (store == null) store = AppStore.get(getApplicationContext()); value = work.call(); }
            catch (Exception e) { failure = e; }
            final ViewState result = value; final Exception error = failure;
            final long elapsed = SystemClock.elapsedRealtime() - started;
            runOnUiThread(() -> {
                if (destroyed) return;
                busy = false;
                pendingReveal = false;
                android.util.Log.i("WordWatchPerformance", "gesture transition=" + (error == null ? "saved" : "failed") + " workMs=" + elapsed);
                if (error == null) { current = result; lastError = null; }
                else lastError = "操作未保存或题目已更新，请重新载入；已保存的记录仍保留。";
                android.util.Log.i("WordWatchGesture", "study transaction=" + (error == null ? "saved" : "failed")
                        + " revealed=" + (current != null && current.revealed()));
                if (foreground) renderCard();
            });
        });
    }

    private ViewState load(boolean allowNewSession) throws Exception {
        JSONObject pack = store.pack();
        if (pack == null) return new ViewState(null, false, "尚未下载学习包，请返回首页同步。");
        JSONArray events = store.events(); JSONObject words = store.words();
        int limit = LearningSettings.newLimit(store.settings());
        JSONObject old = store.session();
        JSONObject session = SessionPolicy.reconcile(old, events, words, System.currentTimeMillis(), limit);
        if (session != null && session.has("feedbackEvent")) {
            // An answer already committed in touch mode only needs its normal advance, never another grade.
            session.put("position", session.optInt("position") + 1);
            session.remove("feedbackEvent"); session.remove("questionIndex");
            session.put("revealed", false).put("assisted", false).put("inputEnabled", false);
        }
        if (session != null && session.optInt("position") >= session.getJSONArray("queue").length()
                && allowNewSession && !StudyEngine.queue(pack, words, events, System.currentTimeMillis(), limit).isEmpty()) {
            session = null;
        }
        if (session == null && allowNewSession) {
            List<StudyEngine.Task> tasks = StudyEngine.queue(pack, words, events, System.currentTimeMillis(), limit);
            JSONObject frozen = new JSONObject(); JSONArray queue = new JSONArray();
            JSONArray authored = pack.getJSONArray("words");
            for (int i = 0; i < authored.length(); i++) {
                JSONObject word = authored.getJSONObject(i); frozen.put(word.getString("id"), word);
            }
            for (StudyEngine.Task task : tasks) {
                if (queue.length() >= SessionPolicy.MAX_SESSION_TASKS) break;
                queue.put(new JSONObject().put("wordId", task.wordId).put("review", task.review).put("attempt", task.attempt));
                if (words.has(task.wordId)) frozen.put(task.wordId, words.getJSONObject(task.wordId));
            }
            session = new JSONObject().put("sessionId", UUID.randomUUID().toString())
                    .put("date", LocalDate.now(StudyEngine.STUDY_ZONE).toString())
                    .put("packageId", pack.getString("packageId")).put("packageRevision", StudyEngine.revision(pack))
                    .put("pack", pack).put("words", frozen).put("queue", queue).put("position", 0)
                    .put("revealed", false).put("assisted", false).put("inputEnabled", false);
        }
        if (session != null) {
            // This screen always records word-level recall, including after switching from a choice/input card.
            session.put("inputEnabled", false);
            session.remove("questionIndex");
        }
        if (session != null && (old == null || !session.toString().equals(old.toString()))) store.saveSession(session);
        return new ViewState(session, store.hasGestureUndo(), "");
    }

    private ViewState afterTransaction(JSONObject session) {
        return new ViewState(session, store.hasGestureUndo(), "");
    }
    private void reveal() {
        CardContext expected = context;
        if (expected == null || current == null || current.revealed()) return;
        pendingReveal = true;
        perform(() -> afterTransaction(store.revealForGesture(expected.sessionId, expected.wordId, expected.position)));
    }
    private void rate(String result) {
        CardContext expected = context;
        if (expected == null || current == null || !current.revealed()) return;
        Button selected = "remember".equals(result) ? rememberButton : hardButton;
        if (selected != null) selected.setText("remember".equals(result) ? "知道 · 保存中…" : "不清楚 · 保存中…");
        perform(() -> afterTransaction(store.answerAndAdvanceGesture(expected.sessionId, expected.wordId, expected.position, result)));
    }
    private void undo() { perform(() -> afterTransaction(store.undoLastGestureAnswer())); }

    private void acceptGesture(GestureRuntime.Event event) {
        if (!foreground || !windowFocused || !ready || busy || context == null || event.context == null) {
            android.util.Log.i("WordWatchGesture", "study drop=state foreground=" + foreground + " focus="
                    + windowFocused + " ready=" + ready + " busy=" + busy); return;
        }
        GestureActionGate.Action action = gestureGate.accept(event.kind, event.context, context, event.endedAtNanos,
                event.emittedAtNanos, SystemClock.elapsedRealtimeNanos(), current.revealed());
        android.util.Log.i("WordWatchGesture", "study action=" + action + " gesture=" + event.kind);
        switch (action) {
            case REVEAL: reveal(); break;
            case REMEMBER: rate("remember"); break;
            case HARD: rate("hard"); break;
            case REVEAL_FIRST: Toast.makeText(this, "先翻面，再选择不清楚", Toast.LENGTH_SHORT).show(); break;
            default: break;
        }
    }

    private void renderCard() {
        if (destroyed || !foreground) return;
        if (lastError != null) {
            layout();
            text(lastError, 14, MUTED);
            button("重新载入", true, () -> perform(() -> load(true)));
            button("返回首页", false, this::finish);
            return;
        }
        if (current == null) { layout(); text("正在准备…", 18, INK); return; }
        if (!current.message.isEmpty()) {
            layout(); text(current.message, 18, INK); button("返回首页", false, this::finish); return;
        }
        if (current.finished()) {
            layout(); runtime.pause();
            text("本轮学习完成", 23, INK);
            text("已保存回忆自评记录", 13, MUTED);
            if (current.canUndo) button("撤销上一词", false, this::undo);
            button("返回首页", true, this::finish); return;
        }
        try {
            JSONObject session = current.session;
            JSONObject task = session.getJSONArray("queue").getJSONObject(session.getInt("position"));
            JSONObject word = session.getJSONObject("words").getJSONObject(task.getString("wordId"));
            long bindStarted = SystemClock.elapsedRealtime();
            ensureCardViews();
            boolean revealed = current.revealed() || pendingReveal;
            setText(rememberButton, "知道 · 下一词");
            setText(hardButton, "不清楚 · 下一词");
            setText(progressView, "手势回忆 · " + (session.getInt("position") + 1) + " / " + session.getJSONArray("queue").length());
            setText(wordView, word.getString("word"));
            setText(phoneticView, word.optString("phonetic"));
            setText(meaningView, word.getString("meaning"));
            phoneticView.setVisibility(revealed ? View.GONE : View.VISIBLE);
            meaningView.setVisibility(revealed ? View.VISIBLE : View.GONE);
            revealButton.setVisibility(revealed ? View.GONE : View.VISIBLE);
            rememberButton.setVisibility(revealed ? View.VISIBLE : View.GONE);
            hardButton.setVisibility(revealed ? View.VISIBLE : View.GONE);
            setText(instructionView, revealed ? "双捏＝知道；食指搓一下＝有印象但不确定" : "拇指与中指快速合拢两次");
            JSONArray examples = word.optJSONArray("examples");
            JSONObject example = examples == null ? null : examples.optJSONObject(0);
            setText(exampleEnView, example == null ? "" : example.optString("en"));
            setText(exampleZhView, example == null ? "" : example.optString("zh"));
            exampleEnView.setVisibility(revealed && example != null ? View.VISIBLE : View.GONE);
            exampleZhView.setVisibility(revealed && example != null ? View.VISIBLE : View.GONE);
            undoButton.setVisibility(current.canUndo ? View.VISIBLE : View.GONE);
            setText(status, "静坐或站定时识别；不确定的动作不会评分");
            for (Button button : buttons) button.setEnabled(!busy);
            String key = session.getString("sessionId") + ":" + session.getInt("position") + ":" + revealed;
            if (!key.equals(displayedCardKey)) { scroll.scrollTo(0, 0); displayedCardKey = key; }
            android.util.Log.i("WordWatchPerformance", "gesture bindMs=" + (SystemClock.elapsedRealtime() - bindStarted));
            context = busy ? null : new CardContext(session.getString("sessionId"), word.getString("id"), session.getInt("position"), ++generation);
            if (profileEligible && windowFocused && !busy) runtime.startLearning(context);
        } catch (Exception invalid) {
            layout();
            text("当前题目已变化，请返回首页重新开始。", 15, MUTED);
            button("返回首页", false, this::finish);
        }
    }

    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    /** Keep the window and the study controls attached across flips and advances. */
    private void ensureCardViews() {
        if (cardViewsReady) return;
        layout();
        progressView = text("", 12, MUTED);
        wordView = text("", 25, INK);
        phoneticView = text("", 13, MUTED);
        meaningView = text("", 16, INK);
        revealButton = button("翻面看答案", true, this::reveal);
        rememberButton = button("知道 · 下一词", true, () -> rate("remember"));
        hardButton = button("不清楚 · 下一词", false, () -> rate("hard"));
        instructionView = text("", 12, MUTED);
        exampleEnView = text("", 14, ACCENT);
        exampleZhView = text("", 12, MUTED);
        status = text("", 12, MUTED);
        undoButton = button("撤销上一词", false, this::undo);
        button("稍后继续", false, this::finish);
        cardViewsReady = true;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void layout() {
        buttons.clear(); context = null; status = null;
        cardViewsReady = false; displayedCardKey = null;
        if (scroll != null) {
            body.removeAllViews(); scroll.scrollTo(0, 0);
            return;
        }
        scroll = new ScrollView(this); scroll.setBackgroundColor(BG);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.max(dp(20), Math.round(width * .15f));
        body.setPadding(margin, dp(25), margin, dp(42));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); setContentView(scroll);
        scroll.setFocusableInTouchMode(true); scroll.requestFocus();
    }
    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(color); text.setTextSize(sp);
        text.setGravity(Gravity.CENTER); text.setPadding(0, dp(4), 0, dp(4));
        if (sp >= 20) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this); button.setAllCaps(false); button.setText(label); button.setTextSize(14);
        button.setStateListAnimator(null); button.setElevation(0);
        button.setTextColor(primary ? BG : INK); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(primary ? ACCENT : Color.rgb(25, 37, 44)); shape.setCornerRadius(dp(24)); button.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); body.addView(button, lp);
        button.setOnClickListener(v -> { if (!busy) action.run(); }); buttons.add(button); return button;
    }
    private static final class ViewState {
        final JSONObject session; final boolean canUndo; final String message;
        ViewState(JSONObject session, boolean canUndo, String message) { this.session = session; this.canUndo = canUndo; this.message = message; }
        boolean finished() { return session == null || session.optJSONArray("queue") == null
                || session.optInt("position") >= session.optJSONArray("queue").length(); }
        boolean revealed() { return session != null && session.optBoolean("revealed"); }
    }
}
