package cn.wordwatch.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.wordwatch.app.core.StudyEngine;
import cn.wordwatch.app.core.SessionPolicy;
import cn.wordwatch.app.core.LearningSettings;
import cn.wordwatch.app.gesture.GestureRuntime;
import cn.wordwatch.app.gesture.GestureSetupActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A native, scrollable round-screen UI. All content is rendered as plain text. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 17), SURFACE = Color.rgb(25, 37, 44);
    private static final int INK = Color.rgb(239, 247, 248), MUTED = Color.rgb(157, 177, 184);
    private static final int ACCENT = Color.rgb(133, 227, 207), BAD = Color.rgb(255, 160, 142);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private AppStore store;
    private LinearLayout body;
    private ScrollView scroll;
    private final List<Button> buttons = new ArrayList<>();
    private PreparedState prepared;
    private boolean busy;
    private boolean destroyed;
    private String screen = "home";
    private String detailId;
    private String detailBack = "home";
    private int wordPage;
    private boolean customLimitEditor;
    private boolean gestureReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setNavigationBarColor(BG);
        if ("gesture-setup".equals(getIntent().getStringExtra("screen"))) {
            startActivity(new Intent(this, GestureSetupActivity.class)
                    .putExtra("diagnostics", getIntent().getBooleanExtra("gesture-diagnostics", false))); finish(); return;
        }
        try {
            store = AppStore.get(this);
            if (state != null) { screen = state.getString("screen", "home"); detailId = state.getString("detailId"); detailBack = state.getString("detailBack", "home"); }
            if ("settings".equals(getIntent().getStringExtra("screen"))) screen = "settings";
            SyncJob.schedule(this); render();
        } catch (Exception e) { fatal("学习记录无法读取", "原始数据已保留，请勿卸载应用。请安装修复后的更新版。"); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (!isFinishing()) GestureRuntime.queryStatus(this, value -> { if (!isFinishing()) gestureReady = value.ready; });
        if (store != null) {
            refreshPreparedState();
            SyncCoordinator.start(this, false, (message, success) -> refreshPreparedState());
        }
        if ("updates".equals(screen)) render();
    }
    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if ("gesture-setup".equals(intent.getStringExtra("screen"))) startActivity(new Intent(this, GestureSetupActivity.class)
                .putExtra("diagnostics", intent.getBooleanExtra("gesture-diagnostics", false)));
        if (store != null && "settings".equals(intent.getStringExtra("screen"))) navigate("settings");
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("screen", screen); out.putString("detailId", detailId); out.putString("detailBack", detailBack);
    }
    @Override public void onBackPressed() {
        if (busy) return;
        if (store == null) { finish(); return; }
        if ("home".equals(screen)) { super.onBackPressed(); return; }
        screen = "detail".equals(screen) ? detailBack : "home"; render();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void layout() {
        buttons.clear();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        scroll = new ScrollView(this); scroll.setBackgroundColor(BG); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.max(dp(20), Math.round(width * (getResources().getConfiguration().isScreenRound() ? .15f : .08f)));
        body.setPadding(margin, dp(28), margin, dp(42));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); setContentView(scroll);
        scroll.setFocusableInTouchMode(true); scroll.requestFocus();
        scroll.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                scroll.scrollBy(0, Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(38))); return true;
            }
            return false;
        });
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size);
        view.setGravity(Gravity.CENTER); view.setLineSpacing(dp(2), 1.05f); view.setPadding(0, dp(3), 0, dp(3));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private TextView title(String s) { return text(s, 23, INK, true); }
    private void small(String s) { text(s, 12, MUTED, false); }
    private void gap(int d) { View spacer = new View(this); body.addView(spacer, new LinearLayout.LayoutParams(1, dp(d))); }
    private GradientDrawable shape(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(23)); return d; }
    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this); view.setAllCaps(false); view.setText(label); view.setTextSize(14);
        view.setStateListAnimator(null); view.setElevation(0);
        view.setTextColor(primary ? BG : INK); view.setBackground(shape(primary ? ACCENT : SURFACE));
        view.setPadding(dp(10), dp(10), dp(10), dp(10)); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); body.addView(view, lp);
        view.setEnabled(!busy);
        view.setOnClickListener(v -> { if (busy || !view.isEnabled()) return; try { action.run(); } catch (Exception e) { error("操作未完成，记录已保留"); } });
        buttons.add(view); return view;
    }
    private void error(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String day(long time) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(time)); }
    private String time(long millis) { if (millis == 0) return "尚未同步"; SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(millis)); }
    private void navigate(String target) { screen = target; render(); }
    private void render() {
        if (isFinishing()) return;
        try {
            layout();
            if (prepared == null) { title("正在准备学习进度…"); return; }
            switch (screen) {
                case "question": question(); break;
                case "settings": settings(); break;
                case "detail": detail(); break;
                case "words": words(); break;
                case "modes": modes(); break;
                case "done": done(); break;
                case "updates": updates(); break;
                default: screen = "home"; home(); break;
            }
        } catch (Exception e) {
            layout(); title("暂时无法打开"); small("本机记录已保留。请返回首页或同步有效学习包。");
            button("返回首页", true, () -> navigate("home"));
        }
    }
    private void home() throws Exception {
        small("词 序  /  WORDWATCH");
        JSONObject pack = prepared.pack;
        if (pack == null) {
            title("从这里开始"); gap(4);
            button("配置 GitHub", true, () -> navigate("settings"));
            button("手势校准与诊断", false, () -> startActivity(new Intent(this, GestureSetupActivity.class)));
            small("AI 为你准备内容，下载后可离线学习。"); return;
        }
        int[] counts = prepared.todayCounts;
        title("今日新词 " + counts[0]);
        int reviews = prepared.reviewCount;
        JSONObject session = activeSession();
        boolean unfinished = session != null && session.optInt("position") < session.getJSONArray("queue").length();
        if (!unfinished && prepared.homeQueue.isEmpty()) {
            button(pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0 ? "等待学习包日期" : "当前没有待学任务", false, () -> {}).setEnabled(false);
        } else button(unfinished ? "继续学习" : "开始今日学习", true, this::startSession);
        small(LearningSettings.label(prepared.settings, pack));
        if (pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0) small("已备好 " + pack.optString("date") + " 的内容，到当天开放新词");
        small("待复习 " + reviews + " 个");
        small("复习完成 " + counts[1] + " · 熟词略过 " + counts[2]);
        gap(5);
        String packageDay = pack.optString("date");
        small("学习包 " + packageDay + (packageDay.compareTo(day(System.currentTimeMillis())) < 0 ? " · 旧包仍可用" : ""));
        small("上次同步 " + time(prepared.lastSync));
        long pending = prepared.pending;
        if (pending > 0) small("本机待上传 " + pending + " 条记录");
        if (prepared.lastSync == 0 && prepared.events.length() > 0) small("学习记录保存在本机，配置后会上传");
        if (!prepared.syncError.isEmpty()) text(prepared.syncError, 12, BAD, false);
        if (!prepared.packError.isEmpty()) text(prepared.packError, 12, BAD, false);
        button(SyncCoordinator.running() ? "同步中…" : "立即同步", false, this::sync);
        button("单词与记录", false, () -> navigate("words"));
        button("设置", false, () -> navigate("settings"));
    }
    private void refreshPreparedState() {
        if (store == null || destroyed) return;
        IO.execute(() -> {
            PreparedState value = null;
            Exception failure = null;
            try { value = prepareState(false); }
            catch (Exception e) { failure = e; }
            final PreparedState result = value;
            final Exception error = failure;
            runOnUiThread(() -> {
                if (destroyed || busy) return;
                if (error != null) {
                    android.util.Log.w("WordWatch", "Could not prepare main view", error);
                    if (prepared == null) {
                        layout(); title("学习进度暂未载入"); small("记录仍保留在本机，请重试。");
                        button("重新载入", true, this::refreshPreparedState);
                    }
                    return;
                }
                boolean firstLoad = prepared == null;
                prepared = result;
                // Background synchronization must not replace edits in the configuration form.
                if (firstLoad || !"settings".equals(screen)) render();
            });
        });
    }
    private PreparedState prepareState(boolean createSession) throws Exception {
        store.pack(); // Activate the appropriate date's downloaded package on the worker thread.
        JSONObject root = store.snapshot();
        JSONObject pack = root.optJSONObject("pack");
        JSONArray events = root.optJSONArray("events");
        if (events == null) events = new JSONArray();
        JSONObject words = root.optJSONObject("words");
        if (words == null) words = new JSONObject();
        JSONObject withdrawn = root.optJSONObject("withdrawn");
        if (withdrawn != null) withdrawn.keys().forEachRemaining(words::remove);
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();
        JSONObject old = root.optJSONObject("session");
        long now = System.currentTimeMillis();
        JSONObject session;
        try { session = SessionPolicy.reconcile(old, events, words, now, LearningSettings.newLimit(settings)); }
        catch (Exception invalidSession) { session = null; }
        if (createSession && (session == null || session.optInt("position") >= session.getJSONArray("queue").length()) && pack != null) {
            List<StudyEngine.Task> tasks = StudyEngine.queue(pack, words, events, now, LearningSettings.newLimit(settings));
            JSONArray queue = new JSONArray();
            JSONObject frozen = new JSONObject();
            JSONArray authored = pack.getJSONArray("words");
            for (int i = 0; i < authored.length(); i++) {
                JSONObject word = authored.getJSONObject(i); frozen.put(word.getString("id"), word);
            }
            for (StudyEngine.Task task : tasks) {
                if (queue.length() >= SessionPolicy.MAX_SESSION_TASKS) break;
                queue.put(new JSONObject().put("wordId", task.wordId).put("review", task.review).put("attempt", task.attempt));
                if (words.has(task.wordId)) frozen.put(task.wordId, words.getJSONObject(task.wordId));
            }
            session = new JSONObject().put("queue", queue).put("position", 0).put("date", day(now))
                    .put("sessionId", java.util.UUID.randomUUID().toString())
                    .put("packageRevision", StudyEngine.revision(pack)).put("pack", pack)
                    .put("packageId", pack.getString("packageId")).put("words", frozen)
                    .put("assisted", false).put("revealed", false);
        }
        if (session != null && session.optInt("position") < session.getJSONArray("queue").length()) {
            ensureQuestionIndex(session, settings);
        }
        if (session != null && (old == null || !session.toString().equals(old.toString()))) store.saveSession(session);

        int[] counts = StudyEngine.todayCounts(events, now);
        List<StudyEngine.Task> queue = pack == null ? new ArrayList<>()
                : StudyEngine.queue(pack, words, events, now, LearningSettings.newLimit(settings));
        int reviews = 0;
        for (StudyEngine.Task task : queue) if (task.review) reviews++;
        return new PreparedState(pack, events, words, settings, session, counts, queue, reviews,
                root.optLong("lastSync"), store.pendingCount(), root.optString("syncError"), root.optString("packError"));
    }
    private void ensureQuestionIndex(JSONObject session, JSONObject settings) throws Exception {
        JSONObject word = currentWord(session);
        JSONArray all = word.optJSONArray("questions");
        if (all == null || all.length() == 0) return;
        int index = session.optInt("questionIndex", -1);
        if (index >= 0 && index < all.length()) return;
        String requested = settings.optString("practiceMode", "mixed");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            if (requested.equals("mixed") || requested.equals(all.getJSONObject(i).optString("mode"))) candidates.add(i);
        }
        if (candidates.isEmpty()) candidates.add(0);
        int deterministic = Math.floorMod((word.getString("id") + session.optString("date")).hashCode(), candidates.size());
        session.put("questionIndex", candidates.get(deterministic));
    }
    private void performStudy(Callable<StudyResult> operation, String failureMessage) {
        performStudy(operation, failureMessage, null);
    }
    private void performStudy(Callable<StudyResult> operation, String failureMessage, Runnable afterSuccess) {
        if (busy || destroyed) return;
        busy = true;
        for (Button button : buttons) button.setEnabled(false);
        IO.execute(() -> {
            StudyResult value = null;
            Exception failure = null;
            try { value = operation.call(); }
            catch (Exception e) { failure = e; }
            final StudyResult result = value;
            final Exception error = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                busy = false;
                if (error != null) error(failureMessage);
                else {
                    prepared = result.state;
                    if (result.screen != null) screen = result.screen;
                    if (result.detailId != null) detailId = result.detailId;
                    if (result.detailBack != null) detailBack = result.detailBack;
                    if (afterSuccess != null) {
                        try { afterSuccess.run(); }
                        catch (RuntimeException postActionFailure) {
                            android.util.Log.w("WordWatch", "Post-study action failed", postActionFailure);
                            error("配置已保存，但同步启动失败");
                        }
                    }
                }
                render();
            });
        });
    }
    private void editSession(JSONObject visible, SessionEdit edit, String targetScreen, String targetDetailId, String targetDetailBack) {
        try {
            JSONObject visibleTask = task(visible);
            String sessionId = visible.getString("sessionId");
            int position = visible.getInt("position");
            String wordId = visibleTask.getString("wordId");
            performStudy(() -> {
                PreparedState current = prepareState(false);
                JSONObject session = current.session;
                requireCurrentCard(session, sessionId, wordId, position);
                edit.apply(session);
                store.saveSession(session);
                return new StudyResult(prepareState(false), targetScreen, targetDetailId, targetDetailBack);
            }, "学习进度未保存，请重试");
        } catch (Exception e) { error("当前题目已变化，请重新打开"); }
    }
    private static void requireCurrentCard(JSONObject session, String sessionId, String wordId, int position) throws Exception {
        if (session == null || !sessionId.equals(session.optString("sessionId")) || session.optInt("position", -1) != position
                || !wordId.equals(session.getJSONArray("queue").getJSONObject(position).optString("wordId"))) {
            throw new IllegalStateException("学习进度已更新");
        }
    }
    private static final class PreparedState {
        final JSONObject pack, words, settings, session;
        final JSONArray events;
        final int[] todayCounts;
        final List<StudyEngine.Task> homeQueue;
        final int reviewCount;
        final long lastSync, pending;
        final String syncError, packError;
        PreparedState(JSONObject pack, JSONArray events, JSONObject words, JSONObject settings, JSONObject session,
                      int[] todayCounts, List<StudyEngine.Task> homeQueue, int reviewCount,
                      long lastSync, long pending, String syncError, String packError) {
            this.pack = pack; this.events = events; this.words = words; this.settings = settings; this.session = session;
            this.todayCounts = todayCounts; this.homeQueue = homeQueue; this.reviewCount = reviewCount;
            this.lastSync = lastSync; this.pending = pending; this.syncError = syncError; this.packError = packError;
        }
    }
    private static final class StudyResult {
        final PreparedState state; final String screen, detailId, detailBack;
        StudyResult(PreparedState state, String screen, String detailId, String detailBack) {
            this.state = state; this.screen = screen; this.detailId = detailId; this.detailBack = detailBack;
        }
    }
    @FunctionalInterface private interface SessionEdit { void apply(JSONObject session) throws Exception; }
    private JSONObject activeSession() { return prepared == null ? null : prepared.session; }
    private void startSession() {
        if (gestureReady) { startActivity(new Intent(this, GestureStudyActivity.class)); return; }
        performStudy(() -> {
            PreparedState next = prepareState(true);
            String target = next.pack == null ? "settings" : next.session != null
                    && next.session.optInt("position") < next.session.getJSONArray("queue").length() ? "question" : "done";
            return new StudyResult(next, target, null, null);
        }, "学习队列未能保存，请重试");
    }
    private JSONObject task(JSONObject session) throws Exception { return session.getJSONArray("queue").getJSONObject(session.getInt("position")); }
    private JSONObject currentWord(JSONObject session) throws Exception { return session.getJSONObject("words").getJSONObject(task(session).getString("wordId")); }
    private JSONObject selectedQuestion(JSONObject session, JSONObject word) throws Exception {
        JSONArray all = word.getJSONArray("questions");
        int index = session.optInt("questionIndex", -1);
        if (index >= 0 && index < all.length()) return all.getJSONObject(index);
        String requested = prepared.settings.optString("practiceMode", "mixed");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) if (requested.equals("mixed") || requested.equals(all.getJSONObject(i).getString("mode"))) candidates.add(i);
        if (candidates.isEmpty()) candidates.add(0);
        int deterministic = Math.floorMod((word.getString("id") + session.optString("date")).hashCode(), candidates.size());
        return all.getJSONObject(candidates.get(deterministic));
    }
    private void question() throws Exception {
        JSONObject session = activeSession();
        if (session == null || session.optInt("position") >= session.getJSONArray("queue").length()) { screen = "done"; done(); return; }
        JSONObject task = task(session), word = currentWord(session);
        boolean review = task.optBoolean("review");
        String attempt = task.optString("attempt", "primary");
        small(("retry".equals(attempt) ? "再试一次" : review ? "到期复习" : "新词")
            + " · " + (session.optInt("position") + 1) + " / " + session.getJSONArray("queue").length());
        if (session.has("feedbackEvent")) { feedback(session, word); return; }
        JSONObject sessionPack = session.optJSONObject("pack");
        boolean selfReview = review && (sessionPack == null || sessionPack.optInt("schemaVersion", 1) == 1 || "self-recall".equals(sessionPack.optString("reviewMode")));
        if (selfReview) {
            TextView heading = title(word.getString("word")); heading.setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            small(word.optString("phonetic"));
            if (!session.optBoolean("revealed")) {
                gap(9); text("先回忆意思和用法", 15, INK, false);
                button("翻面看答案", true, () -> editSession(session, s -> s.put("revealed", true), null, null, null));
            } else {
                text(word.getString("meaning"), 17, INK, true); example(word);
                small("按翻面前的回忆情况选择");
                button("记得", true, () -> answer(word.optString("id"), "recall", "remember"));
                button("模糊", false, () -> answer(word.optString("id"), "recall", "hard"));
                button("忘了", false, () -> answer(word.optString("id"), "recall", "forgot"));
            }
        } else {
            JSONObject question = selectedQuestion(session, word);
            text(question.getString("prompt"), 17, INK, true);
            String type = question.optString("type", "choice");
            if (type.equals("input") && !session.optBoolean("inputEnabled")) {
                if (!session.optBoolean("revealed")) button("翻面看答案", true,
                        () -> editSession(session, s -> s.put("revealed", true), null, null, null));
                else {
                    text(question.getJSONArray("acceptedAnswers").getString(0), 17, INK, true); small("按翻面前的回忆情况自评");
                    button("记得", true, () -> answer(word.optString("id"), question.optString("mode"), "remember"));
                    button("模糊", false, () -> answer(word.optString("id"), question.optString("mode"), "hard"));
                    button("忘了", false, () -> answer(word.optString("id"), question.optString("mode"), "forgot"));
                }
                if (!session.optBoolean("revealed")) small("先在心里回忆，或说出答案");
                button("改为输入作答", false, () -> editSession(session, s -> {
                    s.put("inputEnabled", true);
                    if (s.optBoolean("revealed")) s.put("assisted", true);
                }, null, null, null));
            } else if (type.equals("input")) {
                EditText response = field("你的答案", "", false);
                response.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                button("提交答案", true, () -> {
                    String value = response.getText().toString();
                    if (value.trim().isEmpty()) { error("请先填写答案"); return; }
                    answer(word.optString("id"), question.optString("mode"), StudyEngine.matchesAnswer(question, value) ? "correct" : "wrong");
                });
                button("不用输入，改为回忆", false,
                        () -> editSession(session, s -> s.put("inputEnabled", false), null, null, null));
            } else if (type.equals("recall")) {
                if (!session.optBoolean("revealed")) button("翻面看答案", true,
                        () -> editSession(session, s -> s.put("revealed", true), null, null, null));
                else {
                    text(question.getString("answer"), 17, INK, true); small("按翻面前的回忆情况选择");
                    button("记得", true, () -> answer(word.optString("id"), question.optString("mode"), "remember"));
                    button("模糊", false, () -> answer(word.optString("id"), question.optString("mode"), "hard"));
                    button("忘了", false, () -> answer(word.optString("id"), question.optString("mode"), "forgot"));
                }
            } else {
                JSONArray options = question.getJSONArray("options");
                for (int i = 0; i < options.length(); i++) {
                    final int index = i;
                    button(options.getString(i), false, () -> answer(word.optString("id"), question.optString("mode"), index == question.optInt("correctIndex") ? "correct" : "wrong"));
                }
            }
            if (!type.equals("recall") && (!type.equals("input") || session.optBoolean("inputEnabled")))
                button("先看讲解", false, () -> editSession(session, s -> s.put("assisted", true),
                        "detail", word.optString("id"), "question"));
            if (!"retry".equals(attempt)) button("这个词已熟悉，略过", false, () -> answer(word.optString("id"), "skip", "known"));
        }
        gap(5); button("稍后继续", false, () -> navigate("home"));
    }
    private void answer(String expectedId, String mode, String result) {
        try {
            JSONObject visible = activeSession();
            JSONObject visibleTask = visible == null ? null : task(visible);
            if (visible == null || visible.has("feedbackEvent") || visibleTask == null
                    || !expectedId.equals(visibleTask.getString("wordId"))) {
                error("当前题或进度已变化，请重新作答"); return;
            }
            String sessionId = visible.getString("sessionId");
            int position = visible.getInt("position");
            performStudy(() -> {
                PreparedState current = prepareState(false);
                JSONObject session = current.session;
                requireCurrentCard(session, sessionId, expectedId, position);
                if (session.has("feedbackEvent")) throw new IllegalStateException("本题已经提交");
                JSONObject currentTask = task(session);
                store.answer(expectedId, session.getString("packageId"), mode, result,
                        currentTask.optString("attempt", "primary"), session.optBoolean("assisted"), currentTask.optBoolean("review"));
                return new StudyResult(prepareState(false), null, null, null);
            }, "当前题或进度已变化，请重新作答");
        } catch (Exception e) { error("当前题或进度已变化，请重新作答"); }
    }
    private void feedback(JSONObject session, JSONObject word) throws Exception {
        JSONObject event = session.getJSONObject("feedbackEvent"); String result = event.getString("result");
        boolean good = result.equals("correct") || result.equals("remember") || result.equals("known");
        String label = result.equals("correct") ? "答对了" : result.equals("wrong") ? "再记一下" : result.equals("known") ? "已标记熟悉"
            : result.equals("remember") ? "已记录：记得" : result.equals("hard") ? "已记录：模糊" : "已记录：忘了";
        text(label, 20, good ? ACCENT : BAD, true); title(word.getString("word"));
        text(word.getString("meaning"), 16, INK, false);
        boolean selfReport = event.optString("evidence").equals("self-report")
            || (!event.has("evidence") && event.optString("mode").equals("recall"))
            || result.equals("remember") || result.equals("hard") || result.equals("forgot");
        if (!selfReport && !result.equals("known")) {
            JSONObject q = selectedQuestion(session, word); small(q.optString("explanation"));
            if (q.optString("type").equals("input")) text("参考答案：" + q.getJSONArray("acceptedAnswers").getString(0), 15, ACCENT, false);
            if (event.optBoolean("assisted")) small("本题看过讲解，已单独标记");
        } else if (result.equals("known")) small("跳过不算答题正确，也不增加新词计数");
        else small("这是回忆自评，单独记录");
        button("下一题", true, this::advance);
        button("查看例句与用法", false, () -> { detailId = word.optString("id"); detailBack = "question"; navigate("detail"); });
        button("撤销这次作答", false, () -> undoAnswer(session, event));
    }
    private void undoAnswer(JSONObject visible, JSONObject event) {
        try {
            String sessionId = visible.getString("sessionId");
            int position = visible.getInt("position");
            String wordId = task(visible).getString("wordId");
            String eventId = event.getString("id");
            performStudy(() -> {
                PreparedState current = prepareState(false);
                requireCurrentCard(current.session, sessionId, wordId, position);
                JSONObject currentEvent = current.session == null ? null : current.session.optJSONObject("feedbackEvent");
                if (currentEvent == null || !eventId.equals(currentEvent.optString("id")))
                    throw new IllegalStateException("作答已经变化");
                store.undo(eventId);
                return new StudyResult(prepareState(false), null, null, null);
            }, "撤销未保存，请重试");
        } catch (Exception e) { error("撤销未保存，请重试"); }
    }
    private void advance() {
        try {
            JSONObject visible = activeSession();
            if (visible == null || !visible.has("feedbackEvent")) return;
            String sessionId = visible.getString("sessionId");
            int position = visible.getInt("position");
            String wordId = task(visible).getString("wordId");
            performStudy(() -> {
                PreparedState current = prepareState(false);
                JSONObject session = current.session;
                requireCurrentCard(session, sessionId, wordId, position);
                if (!session.has("feedbackEvent")) throw new IllegalStateException("作答已经变化");
                int nextPosition = position + 1;
                session.put("position", nextPosition); session.remove("feedbackEvent"); session.remove("questionIndex");
                session.put("revealed", false).put("assisted", false).put("inputEnabled", false);
                if (nextPosition >= session.getJSONArray("queue").length()) session.put("completedAt", System.currentTimeMillis());
                store.saveSession(session);
                PreparedState next = prepareState(false);
                String target = next.session != null && next.session.optInt("position") < next.session.getJSONArray("queue").length()
                        ? "question" : "done";
                return new StudyResult(next, target, null, null);
            }, "进度未保存，请重试", () -> {
                if ("done".equals(screen)) SyncCoordinator.start(this, false,
                        (message, success) -> refreshPreparedState());
            });
        } catch (Exception e) { error("进度未保存，请重试"); }
    }
    private void example(JSONObject word) throws Exception {
        JSONArray examples = word.optJSONArray("examples");
        if (examples != null && examples.length() > 0) { JSONObject ex = examples.getJSONObject(0); text(ex.getString("en"), 15, ACCENT, false); small(ex.getString("zh")); }
    }
    private void detail() throws Exception {
        JSONObject session = activeSession(); JSONObject word = null;
        if ("question".equals(detailBack) && session != null) word = session.getJSONObject("words").optJSONObject(detailId);
        if (word == null) word = prepared.words.optJSONObject(detailId);
        if (word == null) { small("这个词尚未下载"); button("返回", true, () -> navigate(detailBack)); return; }
        small("AI 学习卡"); title(word.getString("word")); small(word.optString("phonetic"));
        text(word.getString("meaning"), 17, INK, true); gap(4);
        JSONArray examples = word.optJSONArray("examples");
        if (examples != null) for (int i = 0; i < examples.length(); i++) {
            JSONObject ex = examples.getJSONObject(i); text(ex.getString("en"), 15, ACCENT, false); small(ex.getString("zh")); gap(5);
        }
        JSONArray collocations = word.optJSONArray("collocations");
        if (collocations != null && collocations.length() > 0) {
            gap(5); small("常用搭配");
            for (int i = 0; i < collocations.length(); i++) text(collocations.getString(i), 14, INK, false);
        }
        Object roots = word.opt("roots");
        if (roots instanceof JSONArray) { gap(5); small("构词与记忆"); for (int i = 0; i < ((JSONArray) roots).length(); i++) text(((JSONArray) roots).getString(i), 14, INK, false); }
        else if (roots instanceof String && !((String) roots).isEmpty()) { gap(5); small("构词与记忆"); text((String) roots, 14, INK, false); }
        JSONObject notes = word.optJSONObject("notes");
        String[][] sections = {{"partOfSpeech", "词性"}, {"forms", "词形变化"}, {"chunks", "词块与固定搭配"}, {"frames", "句法框架"}, {"pitfalls", "易错点"}, {"retrievalTips", "主动回忆"}};
        if (notes != null) for (String[] section : sections) {
            JSONArray values = notes.optJSONArray(section[0]);
            if (values != null && values.length() > 0) { gap(5); small(section[1]); for (int i = 0; i < values.length(); i++) text(values.getString(i), 14, INK, false); }
        }
        JSONArray senses = word.optJSONArray("senses");
        if (senses != null && senses.length() > 0) { gap(5); small("搭配与其他用法"); for (int i = 0; i < senses.length(); i++) text(senses.getString(i), 14, INK, false); }
        JSONArray sectionsExtra = notes == null ? null : notes.optJSONArray("sections");
        if (sectionsExtra != null) for (int i = 0; i < sectionsExtra.length(); i++) {
            JSONObject section = sectionsExtra.getJSONObject(i); gap(5); small(section.getString("title"));
            if (section.has("body")) text(section.getString("body"), 14, INK, false);
            JSONArray items = section.optJSONArray("items");
            if (items != null) for (int j = 0; j < items.length(); j++) text(items.getString(j), 14, INK, false);
        }
        small("底库排名 " + word.optInt("rank") + " · AI 生成内容");
        button("返回", true, () -> navigate(detailBack));
    }
    private void done() throws Exception {
        small("这一轮已结束"); title("留一点给明天");
        int[] counts = prepared.todayCounts;
        text("新词 " + counts[0] + "  ·  复习 " + counts[1], 17, ACCENT, true); small("熟词略过 " + counts[2]);
        small("完成的是实际作答与回忆记录，不自动等同于完全掌握。");
        JSONObject pack = prepared.pack;
        if (pack != null && pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0) small("新词内容会在 " + pack.optString("date") + " 开放。");
        else small("可用候选与剩余额度由词包和个人设置共同决定。");
        button("回到首页", true, () -> navigate("home")); button("同步今天的记录", false, this::sync);
    }
    private void words() throws Exception {
        title("单词与记录"); JSONObject all = prepared.words; Map<String, StudyEngine.Card> cards = StudyEngine.project(prepared.events, System.currentTimeMillis());
        List<JSONObject> entries = new ArrayList<>(); Iterator<String> ids = all.keys(); while (ids.hasNext()) entries.add(all.getJSONObject(ids.next()));
        entries.sort((a, b) -> Integer.compare(a.optInt("rank", Integer.MAX_VALUE), b.optInt("rank", Integer.MAX_VALUE)));
        small("已下载 " + entries.size() + " 个词");
        int pages = Math.max(1, (entries.size() + 19) / 20);
        wordPage = Math.max(0, Math.min(wordPage, pages - 1)); small("第 " + (wordPage + 1) + " / " + pages + " 页");
        for (JSONObject word : entries.subList(wordPage * 20, Math.min(entries.size(), (wordPage + 1) * 20))) {
            StudyEngine.Card card = cards.get(word.getString("id"));
            String status = card == null ? "未学" : card.known ? "熟悉 · 已略过" : card.dueAt <= System.currentTimeMillis() ? "待复习" : "复习 " + day(card.dueAt);
            button(word.getString("word") + "\n" + status, false, () -> { detailId = word.optString("id"); detailBack = "words"; navigate("detail"); });
        }
        if (wordPage > 0) button("上一页", false, () -> { wordPage--; render(); });
        if (wordPage + 1 < pages) button("下一页", true, () -> { wordPage++; render(); });
        button("返回首页", false, () -> navigate("home"));
    }
    private String modeLabel(String mode) { switch (mode) { case "forward": return "英→中"; case "reverse": return "中→英"; case "rare": return "熟词生义"; case "mixed": return "混合题型"; default: return mode; } }
    private void modes() {
        title("点选题型");
        java.util.LinkedHashSet<String> availableModes = new java.util.LinkedHashSet<>(java.util.Arrays.asList("mixed", "forward", "reverse", "rare"));
        JSONObject pack = prepared.pack; JSONArray words = pack == null ? null : pack.optJSONArray("words");
        if (words != null) for (int i = 0; i < words.length(); i++) {
            JSONArray questions = words.optJSONObject(i).optJSONArray("questions");
            if (questions != null) for (int j = 0; j < questions.length(); j++) availableModes.add(questions.optJSONObject(j).optString("mode"));
        }
        for (String mode : availableModes) button(mode.equals("mixed") || mode.equals("forward") || mode.equals("reverse") || mode.equals("rare") ? modeLabel(mode) : mode, false,
                () -> updateSettings(s -> s.put("practiceMode", mode), "settings", "设置未保存，请重试"));
        small("没有合适的生义题时，使用该词的基础题。");
    }
    // This is an explicitly chosen repository PAT, not an account password.
    // Desktop ADB entry is supported; the field must remain masked on the watch.
    @android.annotation.SuppressLint("WearPasswordInput")
    private EditText field(String label, String value, boolean secret) {
        small(label); EditText input = new EditText(this); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setTextSize(13);
        input.setSingleLine(true); input.setSelectAllOnFocus(!secret); input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        input.setContentDescription(label); input.setMinHeight(dp(48)); input.setPadding(dp(9), dp(8), dp(9), dp(8));
        input.setBackground(shape(SURFACE)); body.addView(input, new LinearLayout.LayoutParams(-1, -2)); gap(6); return input;
    }
    private void settings() throws Exception {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        title("学习与同步"); JSONObject config = prepared.settings; int limit = config.optInt("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT);
        text("学习量", 17, ACCENT, true);
        small(LearningSettings.label(config, prepared.pack));
        button("跟随词包", config.optString("limitMode", "package").equals("package"), () -> changeLimitMode("package"));
        button("个人每日上限", config.optString("limitMode").equals("custom"), () -> changeLimitMode("custom"));
        button("只复习", config.optString("limitMode").equals("review"), () -> changeLimitMode("review"));
        if ("custom".equals(config.optString("limitMode"))) {
            button("少 5 个", false, () -> adjustPersonalLimit(-5));
            button("多 5 个", false, () -> adjustPersonalLimit(5));
            button(customLimitEditor ? "收起输入" : "填写其他数量", false, () -> { customLimitEditor = !customLimitEditor; render(); });
            if (customLimitEditor) {
            EditText target = field("每日新词上限", String.valueOf(config.optInt("newLimit", 30)), false);
            target.setInputType(InputType.TYPE_CLASS_NUMBER);
            button("保存学习量", true, () -> {
                try { int value = Integer.parseInt(target.getText().toString().trim());
                    if (value < 0 || value > 10000) { error("请填写 0 到 10000"); return; }
                    updateSettings(s -> s.put("newLimit", value), null, "学习量未保存，请重试");
                } catch (Exception invalid) { error("请填写有效数字"); }
            });
            }
        }
        small("按上海日期累计。跟随词包时，AI 可直接调整每日学习量。");
        button(modeLabel(config.optString("practiceMode", "mixed")), false, () -> navigate("modes"));
        button("手势学习与校准", false, () -> startActivity(new Intent(this, GestureSetupActivity.class)));
        gap(12); small("GitHub 私有仓库");
        EditText owner = field("账号", config.optString("owner"), false), repo = field("仓库", config.optString("repo"), false), branch = field("分支", config.optString("branch"), false);
        CredentialVault vault = new CredentialVault(this);
        EditText token = field("Access Token", "", true); token.setHint(vault.exists() ? "已保存，留空保持不变" : "仅授权本仓库 Contents 读写");
        small("Token 只保存在本机加密空间。Wi-Fi 下自动同步，手动同步可以使用移动网络。");
        button("保存配置", true, () -> {
            try {
                String o = owner.getText().toString().trim(), r = repo.getText().toString().trim(), b = branch.getText().toString().trim();
                if (!o.matches("[A-Za-z0-9-]{1,39}") || !r.matches("[A-Za-z0-9_.-]{1,100}") || b.isEmpty() || b.contains("..") || b.length() > 150) { error("请检查账号、仓库和分支"); return; }
                String secret = token.getText().toString().trim();
                token.setText("");
                performStudy(() -> {
                    if (SyncCoordinator.running()) throw new IllegalStateException("同步中，请稍后保存配置");
                    CredentialVault secureVault = new CredentialVault(getApplicationContext());
                    if (secret.isEmpty() && !secureVault.exists()) throw new IllegalArgumentException("请填写 Token");
                    SyncCoordinator.withSettingsWriteLock(() -> {
                        if (!secret.isEmpty()) secureVault.save(secret);
                        JSONObject next = store.settings(); next.put("owner", o).put("repo", r).put("branch", b); store.saveSettings(next);
                        return null;
                    });
                    return new StudyResult(prepareState(false), "home", null, null);
                }, "配置未能安全保存，请重试", () -> { SyncJob.schedule(this); sync(); });
            } catch (Exception e) { error("配置未能安全保存，请重试"); }
        });
        button("检查应用更新", false, () -> navigate("updates"));
        button("返回首页", false, () -> navigate("home")); small("词序 1.1.0 · 支持学习包 v1 / v2");
    }
    private void changeLimitMode(String mode) {
        updateSettings(s -> s.put("limitMode", mode), null, "设置未保存，请重试");
    }
    private void adjustPersonalLimit(int amount) {
        updateSettings(s -> s.put("newLimit", Math.max(0, Math.min(10000,
                s.optInt("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT) + amount))), null, "设置未保存，请重试");
    }
    private void updateSettings(SessionEdit edit, String targetScreen, String failureMessage) {
        performStudy(() -> {
            JSONObject settings = store.settings();
            edit.apply(settings);
            store.saveSettings(settings);
            return new StudyResult(prepareState(false), targetScreen, null, null);
        }, failureMessage);
    }
    private void sync() {
        if (!new CredentialVault(this).exists()) { navigate("settings"); return; }
        boolean started = SyncCoordinator.start(this, true, (message, success) -> { refreshPreparedState(); error(message); });
        if (started) { if ("home".equals(screen)) render(); }
        else error("已有同步正在进行");
    }
    private void fatal(String title, String message) { layout(); title(title); small(message); button("检查修复更新", true, () -> { screen = "updates"; layout(); updates(); }); }
    private void updates() {
        title("应用更新");
        try { small("当前版本 " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); }
        catch (Exception unavailable) { small("当前已安装版本"); }
        small(UpdateCoordinator.message(this));
        if (UpdateCoordinator.hasConfirmation(this)) button("继续安装或查看进度", true, () -> UpdateCoordinator.confirm(this));
        button(UpdateCoordinator.running() ? "正在检查或下载…" : "检查 GitHub 更新", true, () -> UpdateCoordinator.check(this, () -> { if ("updates".equals(screen)) render(); }));
        if (UpdateCoordinator.available(this)) button("下载并安装更新", true, () -> UpdateCoordinator.install(this, () -> { if ("updates".equals(screen)) render(); }));
        small("校验签名后交给系统确认安装。更新会保留本机学习记录。");
        button("返回", false, () -> { if (store != null) navigate("settings"); else finish(); });
    }
}
