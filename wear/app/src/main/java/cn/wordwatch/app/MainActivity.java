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
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
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

/** A native, scrollable round-screen UI. All content is rendered as plain text. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 17), SURFACE = Color.rgb(25, 37, 44);
    private static final int INK = Color.rgb(239, 247, 248), MUTED = Color.rgb(157, 177, 184);
    private static final int ACCENT = Color.rgb(133, 227, 207), BAD = Color.rgb(255, 160, 142);
    private volatile AppStore store;
    private StudyScreen model;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy, refreshAfterWork;
    private int generation;
    private LinearLayout body;
    private ScrollView scroll;
    private String screen = "home";
    private String detailId;
    private String detailBack = "home";
    private int wordPage;
    private boolean customLimitEditor;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setNavigationBarColor(BG);
        if (state != null) { screen = state.getString("screen", "home"); detailId = state.getString("detailId"); detailBack = state.getString("detailBack", "home"); }
        if ("settings".equals(getIntent().getStringExtra("screen"))) screen = "settings";
        layout(); title("正在读取学习进度…"); SyncJob.schedule(this); render();
    }
    @Override protected void onResume() {
        super.onResume();
        StudyWork.execute(() -> { if (store != null) SyncCoordinator.start(getApplicationContext(), false, (message, success) -> refreshAfterSync()); });
        if ("updates".equals(screen) && !busy) render();
    }
    @Override protected void onDestroy() { generation++; super.onDestroy(); }
    private void refreshAfterSync() {
        if (!"home".equals(screen) || isDestroyed()) return;
        if (busy) refreshAfterWork = true; else render();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (store != null && "settings".equals(intent.getStringExtra("screen"))) navigate("settings");
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("screen", screen); out.putString("detailId", detailId); out.putString("detailBack", detailBack);
    }
    @Override public void onBackPressed() {
        if (store == null) { finish(); return; }
        if ("home".equals(screen)) { super.onBackPressed(); return; }
        screen = "detail".equals(screen) ? detailBack : "home"; render();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void layout() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (scroll != null) { body.removeAllViews(); scroll.scrollTo(0, 0); return; }
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
        view.setTextColor(primary ? BG : INK);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(primary ? Color.argb(50, 0, 0, 0) : Color.argb(50, 255, 255, 255)),
                shape(primary ? ACCENT : SURFACE), shape(Color.WHITE)));
        view.setPadding(dp(10), dp(10), dp(10), dp(10)); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); body.addView(view, lp);
        final int renderedGeneration = generation;
        view.setOnClickListener(v -> {
            if (busy || renderedGeneration != generation) return;
            try { action.run(); } catch (Exception e) { error("操作未完成，记录已保留"); }
        }); return view;
    }
    private void error(String message) { main.post(() -> { if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }); }
    private String day(long time) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(time)); }
    private String time(long millis) { if (millis == 0) return "尚未同步"; SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(millis)); }
    private void navigate(String target) { screen = target; render(); }
    private interface Work { String run() throws Exception; }
    private void setActionsEnabled(View view, boolean enabled) {
        if (view instanceof Button || view instanceof EditText) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : .65f); }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) setActionsEnabled(((ViewGroup)view).getChildAt(i), enabled);
    }
    private void execute(Work work) {
        if (isFinishing() || isDestroyed()) return;
        final String returnScreen = screen;
        final int request = ++generation; busy = true;
        if (body != null) setActionsEnabled(body, false);
        StudyWork.execute(() -> {
            try {
                if (store == null) store = AppStore.get(getApplicationContext());
                StudyScreen prepared = StudyScreen.prepare(store, work.run());
                main.post(() -> {
                    if (request != generation || isFinishing() || isDestroyed()) return;
                    busy = false; model = prepared; screen = prepared.screen; draw();
                    if (refreshAfterWork) { refreshAfterWork = false; if (screen.equals("home")) render(); }
                });
                if (prepared.screen.equals("done")) SyncCoordinator.start(getApplicationContext(), false, (message, success) -> {});
            } catch (Exception failure) {
                StudyScreen recovered = null;
                try { if (store != null) recovered = StudyScreen.prepare(store, returnScreen); }
                catch (Exception unreadable) { /* Keep the last visible, committed view for retry. */ }
                final StudyScreen recovery = recovered;
                main.post(() -> {
                    if (request != generation || isFinishing() || isDestroyed()) return;
                    busy = false;
                    if (model == null) fatal("学习记录无法读取", "原始数据已保留，请勿卸载应用。请安装修复后的更新版。");
                    else {
                        if (recovery != null) model = recovery;
                        screen = model.screen; draw(); error("操作未完成或题目已更新，请查看当前题；已保存记录仍保留");
                    }
                });
            }
        });
    }
    private void render() {
        if (store == null && screen.equals("updates")) { layout(); updates(); return; }
        final String target = screen; execute(() -> target);
    }
    private void draw() {
        if (isFinishing()) return;
        try {
            layout();
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
        JSONObject pack = model.pack;
        if (pack == null) {
            title("从这里开始"); gap(4);
            button("配置 GitHub", true, () -> navigate("settings"));
            small("AI 为你准备内容，下载后可离线学习。"); return;
        }
        int[] counts = model.counts;
        title("今日新词 " + counts[0]);
        List<StudyEngine.Task> queue = model.queue;
        int reviews = model.reviews;
        JSONObject session = activeSession();
        boolean unfinished = session != null && session.optInt("position") < session.getJSONArray("queue").length();
        if (!unfinished && queue.isEmpty()) {
            button(pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0 ? "等待学习包日期" : "当前没有待学任务", false, () -> {}).setEnabled(false);
        } else button(unfinished ? "继续学习" : "开始今日学习", true, this::startSession);
        small(LearningSettings.label(model.settings, pack));
        if (pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0) small("已备好 " + pack.optString("date") + " 的内容，到当天开放新词");
        small("待复习 " + reviews + " 个");
        small("复习完成 " + counts[1] + " · 熟词略过 " + counts[2]);
        gap(5);
        String packageDay = pack.optString("date");
        small("学习包 " + packageDay + (packageDay.compareTo(day(System.currentTimeMillis())) < 0 ? " · 旧包仍可用" : ""));
        JSONObject state = model.state; small("上次同步 " + time(state.optLong("lastSync")));
        long pending = model.pending;
        if (pending > 0) small("本机待上传 " + pending + " 条记录");
        if (state.optLong("lastSync") == 0 && model.state.getJSONArray("events").length() > 0) small("学习记录保存在本机，配置后会上传");
        if (!state.optString("syncError").isEmpty()) text(state.optString("syncError"), 12, BAD, false);
        if (!state.optString("packError").isEmpty()) text(state.optString("packError"), 12, BAD, false);
        button(SyncCoordinator.running() ? "同步中…" : "立即同步", false, this::sync);
        button("单词与记录", false, () -> navigate("words"));
        button("设置", false, () -> navigate("settings"));
    }
    private JSONObject activeSession() { return model.session; }
    private void startSession() { execute(() -> StudyScreen.start(store)); }
    private void editSession(String key, boolean value, String target) {
        try {
            String sessionId = model.session.getString("sessionId"), wordId = task(model.session).getString("wordId");
            execute(() -> { store.editSession(sessionId, wordId, key, value); return target; });
        } catch (Exception invalid) { error("题目已变化，请从首页继续"); }
    }
    private JSONObject task(JSONObject session) throws Exception { return session.getJSONArray("queue").getJSONObject(session.getInt("position")); }
    private JSONObject currentWord(JSONObject session) throws Exception { return session.getJSONObject("words").getJSONObject(task(session).getString("wordId")); }
    private JSONObject selectedQuestion(JSONObject session, JSONObject word) throws Exception {
        return word.getJSONArray("questions").getJSONObject(session.getInt("questionIndex"));
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
                button("翻面看答案", true, () -> {
                    editSession("revealed", true, "question");
                });
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
                if (!session.optBoolean("revealed")) button("翻面看答案", true, () -> {
                    editSession("revealed", true, "question");
                });
                else {
                    text(question.getJSONArray("acceptedAnswers").getString(0), 17, INK, true); small("按翻面前的回忆情况自评");
                    button("记得", true, () -> answer(word.optString("id"), question.optString("mode"), "remember"));
                    button("模糊", false, () -> answer(word.optString("id"), question.optString("mode"), "hard"));
                    button("忘了", false, () -> answer(word.optString("id"), question.optString("mode"), "forgot"));
                }
                if (!session.optBoolean("revealed")) small("先在心里回忆，或说出答案");
                button("改为输入作答", false, () -> {
                    editSession("inputEnabled", true, "question");
                });
            } else if (type.equals("input")) {
                EditText response = field("你的答案", "", false);
                response.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                button("提交答案", true, () -> {
                    String value = response.getText().toString();
                    if (value.trim().isEmpty()) { error("请先填写答案"); return; }
                    execute(() -> saveAnswer(word.optString("id"), question.optString("mode"), StudyEngine.matchesAnswer(question, value) ? "correct" : "wrong"));
                });
                button("不用输入，改为回忆", false, () -> {
                    editSession("inputEnabled", false, "question");
                });
            } else if (type.equals("recall")) {
                if (!session.optBoolean("revealed")) button("翻面看答案", true, () -> {
                    editSession("revealed", true, "question");
                });
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
            if (!type.equals("recall") && (!type.equals("input") || session.optBoolean("inputEnabled"))) button("先看讲解", false, () -> {
                detailId = word.optString("id"); detailBack = "question"; editSession("assisted", true, "detail");
            });
            if (!"retry".equals(attempt)) button("这个词已熟悉，略过", false, () -> answer(word.optString("id"), "skip", "known"));
        }
        gap(5); button("稍后继续", false, () -> navigate("home"));
    }
    private void answer(String expectedId, String mode, String result) {
        execute(() -> saveAnswer(expectedId, mode, result));
    }
    private String saveAnswer(String expectedId, String mode, String result) throws Exception {
        JSONObject session = store.prepareSession();
        if (session == null) throw new IllegalStateException("Session expired");
        JSONObject task = task(session);
        if (!expectedId.equals(task.getString("wordId"))) throw new IllegalStateException("Question changed");
        store.answer(expectedId, session.getString("packageId"), mode, result, task.optString("attempt", "primary"), session.optBoolean("assisted"), task.optBoolean("review"));
        return "question";
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
        button("撤销这次作答", false, () -> execute(() -> { store.undo(event.getString("id")); return "question"; }));
    }
    private void advance() {
        try {
            String eventId = model.session.getJSONObject("feedbackEvent").getString("id");
            execute(() -> { store.advance(eventId); return "question"; });
        } catch (Exception e) { error("题目已变化，请从首页继续"); }
    }
    private void example(JSONObject word) throws Exception {
        JSONArray examples = word.optJSONArray("examples");
        if (examples != null && examples.length() > 0) { JSONObject ex = examples.getJSONObject(0); text(ex.getString("en"), 15, ACCENT, false); small(ex.getString("zh")); }
    }
    private void detail() throws Exception {
        JSONObject session = model.session; JSONObject word = null;
        if ("question".equals(detailBack) && session != null) word = session.getJSONObject("words").optJSONObject(detailId);
        if (word == null) word = model.words.optJSONObject(detailId);
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
        int[] counts = model.counts;
        text("新词 " + counts[0] + "  ·  复习 " + counts[1], 17, ACCENT, true); small("熟词略过 " + counts[2]);
        small("完成的是实际作答与回忆记录，不自动等同于完全掌握。");
        JSONObject pack = model.pack;
        if (pack != null && pack.optString("date").compareTo(day(System.currentTimeMillis())) > 0) small("新词内容会在 " + pack.optString("date") + " 开放。");
        else small("可用候选与剩余额度由词包和个人设置共同决定。");
        button("回到首页", true, () -> navigate("home")); button("同步今天的记录", false, this::sync);
    }
    private void words() throws Exception {
        title("单词与记录"); Map<String, StudyEngine.Card> cards = model.cards;
        List<JSONObject> entries = model.entries;
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
        JSONObject pack = model.pack; JSONArray words = pack == null ? null : pack.optJSONArray("words");
        if (words != null) for (int i = 0; i < words.length(); i++) {
            JSONArray questions = words.optJSONObject(i).optJSONArray("questions");
            if (questions != null) for (int j = 0; j < questions.length(); j++) availableModes.add(questions.optJSONObject(j).optString("mode"));
        }
        for (String mode : availableModes) button(mode.equals("mixed") || mode.equals("forward") || mode.equals("reverse") || mode.equals("rare") ? modeLabel(mode) : mode, false, () -> {
            execute(() -> { JSONObject settings = store.settings(); settings.put("practiceMode", mode); store.saveSettings(settings); return "settings"; });
        });
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
        title("学习与同步"); JSONObject config = model.settings;
        text("学习量", 17, ACCENT, true);
        small(LearningSettings.label(config, model.pack));
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
                    execute(() -> { JSONObject next = store.settings(); next.put("newLimit", value); store.saveSettings(next); return "settings"; });
                } catch (Exception invalid) { error("请填写有效数字"); }
            });
            }
        }
        small("按上海日期累计。跟随词包时，AI 可直接调整每日学习量。");
        button(modeLabel(config.optString("practiceMode", "mixed")), false, () -> navigate("modes"));
        gap(12); small("GitHub 私有仓库");
        EditText owner = field("账号", config.optString("owner"), false), repo = field("仓库", config.optString("repo"), false), branch = field("分支", config.optString("branch"), false);
        CredentialVault vault = new CredentialVault(this);
        EditText token = field("Access Token", "", true); token.setHint(vault.exists() ? "已保存，留空保持不变" : "仅授权本仓库 Contents 读写");
        small("Token 只保存在本机加密空间。Wi-Fi 下自动同步，手动同步可以使用移动网络。");
        button("保存配置", true, () -> {
            try {
                String o = owner.getText().toString().trim(), r = repo.getText().toString().trim(), b = branch.getText().toString().trim();
                if (!o.matches("[A-Za-z0-9-]{1,39}") || !r.matches("[A-Za-z0-9_.-]{1,100}") || b.isEmpty() || b.contains("..") || b.length() > 150) { error("请检查账号、仓库和分支"); return; }
                if (SyncCoordinator.running()) { error("同步中，请稍后保存配置"); return; }
                String secret = token.getText().toString().trim();
                if (secret.isEmpty() && !vault.exists()) { error("请填写 Token"); return; }
                execute(() -> {
                    SyncCoordinator.withSettingsWriteLock(() -> {
                        if (!secret.isEmpty()) vault.save(secret);
                        JSONObject next = store.settings(); next.put("owner", o).put("repo", r).put("branch", b); store.saveSettings(next); return null;
                    });
                    SyncJob.schedule(getApplicationContext());
                    SyncCoordinator.start(getApplicationContext(), true, (message, success) -> { refreshAfterSync(); error(message); });
                    return "home";
                });
            } catch (Exception e) { error("配置未能安全保存，请重试"); }
        });
        button("检查应用更新", false, () -> navigate("updates"));
        button("返回首页", false, () -> navigate("home")); small("词序 1.1.1 · 支持学习包 v1 / v2");
    }
    private void changeLimitMode(String mode) {
        execute(() -> { JSONObject settings = store.settings(); settings.put("limitMode", mode); store.saveSettings(settings); return "settings"; });
    }
    private void adjustPersonalLimit(int amount) {
        execute(() -> { JSONObject settings = store.settings(); settings.put("newLimit", Math.max(0, Math.min(10000, settings.optInt("newLimit", 30) + amount))); store.saveSettings(settings); return "settings"; });
    }
    private void sync() {
        final String target = screen;
        execute(() -> {
            if (!new CredentialVault(getApplicationContext()).exists()) return "settings";
            boolean started = SyncCoordinator.start(getApplicationContext(), true, (message, success) -> { refreshAfterSync(); error(message); });
            if (!started) error("已有同步正在进行");
            return target;
        });
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
