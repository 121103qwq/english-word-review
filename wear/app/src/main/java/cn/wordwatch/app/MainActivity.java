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
    private AppStore store;
    private LinearLayout body;
    private ScrollView scroll;
    private String screen = "home";
    private String detailId;
    private String detailBack = "home";
    private boolean savingAnswer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setNavigationBarColor(BG);
        try {
            store = AppStore.get(this);
            if (state != null) { screen = state.getString("screen", "home"); detailId = state.getString("detailId"); detailBack = state.getString("detailBack", "home"); }
            if ("settings".equals(getIntent().getStringExtra("screen"))) screen = "settings";
            SyncJob.schedule(this); render();
        } catch (Exception e) { fatal("学习记录无法读取", "原始数据已保留，请勿卸载应用。请安装修复后的更新版。"); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (store != null) SyncCoordinator.start(this, false, (message, success) -> { if ("home".equals(screen)) render(); });
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (store != null && "settings".equals(intent.getStringExtra("screen"))) navigate("settings");
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("screen", screen); out.putString("detailId", detailId); out.putString("detailBack", detailBack);
    }
    @Override public void onBackPressed() {
        if ("home".equals(screen)) { super.onBackPressed(); return; }
        screen = "detail".equals(screen) ? detailBack : "home"; render();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void layout() {
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
        view.setTextColor(primary ? BG : INK); view.setBackground(shape(primary ? ACCENT : SURFACE));
        view.setPadding(dp(10), dp(10), dp(10), dp(10)); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(7); body.addView(view, lp);
        view.setOnClickListener(v -> { try { action.run(); } catch (Exception e) { error("操作未完成，记录已保留"); } }); return view;
    }
    private void error(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String day(long time) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(time)); }
    private String time(long millis) { if (millis == 0) return "尚未同步"; SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(millis)); }
    private void navigate(String target) { screen = target; render(); }
    private void render() {
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
                default: screen = "home"; home(); break;
            }
        } catch (Exception e) {
            layout(); title("暂时无法打开"); small("本机记录已保留。请返回首页或同步有效学习包。");
            button("返回首页", true, () -> navigate("home"));
        }
    }
    private void home() throws Exception {
        small("词 序  /  WORDWATCH");
        JSONObject pack = store.pack();
        if (pack == null) {
            title("从这里开始"); gap(4);
            button("配置 GitHub", true, () -> navigate("settings"));
            small("AI 为你准备内容，下载后可离线学习。"); return;
        }
        int[] counts = StudyEngine.todayCounts(store.events(), System.currentTimeMillis());
        int limit = store.settings().optInt("newLimit", 15);
        title(counts[0] + " / " + limit);
        List<StudyEngine.Task> queue = StudyEngine.queue(pack, store.words(), store.events(), System.currentTimeMillis(), limit);
        int reviews = 0; for (StudyEngine.Task task : queue) if (task.review) reviews++;
        small("今日新词 · " + reviews + " 个待复习");
        JSONObject session = activeSession();
        boolean unfinished = session != null && session.optInt("position") < session.getJSONArray("queue").length();
        button(unfinished ? "继续学习" : "开始今日学习", true, this::startSession);
        small("复习完成 " + counts[1] + " · 熟词略过 " + counts[2]);
        gap(5);
        String packageDay = pack.optString("date");
        small("学习包 " + packageDay + (packageDay.compareTo(day(System.currentTimeMillis())) < 0 ? " · 旧包仍可用" : ""));
        JSONObject state = store.snapshot(); small("上次同步 " + time(state.optLong("lastSync")));
        long pending = state.optLong("seq") - state.optLong("lastSyncedSeq");
        if (pending > 0) small("本机待上传 " + pending + " 条记录");
        if (state.optLong("lastSync") == 0 && store.events().length() > 0) small("学习记录保存在本机，配置后会上传");
        if (!state.optString("syncError").isEmpty()) text(state.optString("syncError"), 12, BAD, false);
        button(SyncCoordinator.running() ? "同步中…" : "立即同步", false, this::sync);
        button("单词与记录", false, () -> navigate("words"));
        button("设置", false, () -> navigate("settings"));
    }
    private JSONObject activeSession() {
        JSONObject s = store.session(); return s != null && day(System.currentTimeMillis()).equals(s.optString("date")) ? s : null;
    }
    private void startSession() {
        try {
            JSONObject session = activeSession();
            if (session == null || session.optInt("position") >= session.getJSONArray("queue").length()) {
                JSONObject pack = store.pack(); if (pack == null) { navigate("settings"); return; }
                List<StudyEngine.Task> tasks = StudyEngine.queue(pack, store.words(), store.events(), System.currentTimeMillis(), store.settings().optInt("newLimit", 15));
                JSONArray queue = new JSONArray(); for (StudyEngine.Task task : tasks) queue.put(new JSONObject().put("wordId", task.wordId).put("review", task.review).put("attempt", task.attempt));
                session = new JSONObject().put("queue", queue).put("position", 0).put("date", day(System.currentTimeMillis()))
                    .put("packageId", pack.getString("packageId")).put("words", store.words()).put("assisted", false).put("revealed", false);
                store.saveSession(session);
            }
            navigate(session.getJSONArray("queue").length() == 0 ? "done" : "question");
        } catch (Exception e) { error("学习队列未能保存，请重试"); }
    }
    private JSONObject task(JSONObject session) throws Exception { return session.getJSONArray("queue").getJSONObject(session.getInt("position")); }
    private JSONObject currentWord(JSONObject session) throws Exception { return session.getJSONObject("words").getJSONObject(task(session).getString("wordId")); }
    private JSONObject selectedQuestion(JSONObject session, JSONObject word) throws Exception {
        JSONArray all = word.getJSONArray("questions");
        int index = session.optInt("questionIndex", -1);
        if (index >= 0 && index < all.length()) return all.getJSONObject(index);
        String requested = store.settings().optString("practiceMode", "mixed");
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) if (requested.equals("mixed") || requested.equals(all.getJSONObject(i).getString("mode"))) candidates.add(i);
        if (candidates.isEmpty()) candidates.add(0);
        int deterministic = Math.floorMod((word.getString("id") + session.optString("date")).hashCode(), candidates.size());
        index = candidates.get(deterministic); session.put("questionIndex", index); store.saveSession(session); return all.getJSONObject(index);
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
        if (review) {
            TextView heading = title(word.getString("word")); heading.setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            small(word.optString("phonetic"));
            if (!session.optBoolean("revealed")) {
                gap(9); text("先回忆意思和用法", 15, INK, false);
                button("翻面看答案", true, () -> {
                    try { JSONObject s = store.session(); s.put("revealed", true); store.saveSession(s); render(); } catch (Exception e) { error("未能保存，请重试"); }
                });
            } else {
                text(word.getString("meaning"), 17, INK, true); example(word);
                small("按翻面前的回忆情况选择");
                button("记得", true, () -> answer("recall", "remember"));
                button("模糊", false, () -> answer("recall", "hard"));
                button("忘了", false, () -> answer("recall", "forgot"));
            }
        } else {
            JSONObject question = selectedQuestion(session, word);
            text(question.getString("prompt"), 17, INK, true);
            JSONArray options = question.getJSONArray("options");
            for (int i = 0; i < options.length(); i++) {
                final int index = i;
                button(options.getString(i), false, () -> answer(question.optString("mode"), index == question.optInt("correctIndex") ? "correct" : "wrong"));
            }
            button("先看讲解", false, () -> {
                try { JSONObject s = store.session(); s.put("assisted", true); store.saveSession(s); detailId = word.optString("id"); detailBack = "question"; navigate("detail"); }
                catch (Exception e) { error("未能保存，请重试"); }
            });
            if (!"retry".equals(attempt)) button("这个词已熟悉，略过", false, () -> answer("skip", "known"));
        }
        gap(5); button("稍后继续", false, () -> navigate("home"));
    }
    private void answer(String mode, String result) {
        if (savingAnswer) return; savingAnswer = true;
        try {
            JSONObject session = activeSession(); if (session == null || session.has("feedbackEvent")) return;
            JSONObject task = task(session);
            store.answer(task.getString("wordId"), session.getString("packageId"), mode, result, task.optString("attempt", "primary"), session.optBoolean("assisted"), task.optBoolean("review"));
            render();
        } catch (Exception e) { error("答案未保存，请重试；当前题保留"); }
        finally { savingAnswer = false; }
    }
    private void feedback(JSONObject session, JSONObject word) throws Exception {
        JSONObject event = session.getJSONObject("feedbackEvent"); String result = event.getString("result");
        boolean good = result.equals("correct") || result.equals("remember") || result.equals("known");
        String label = result.equals("correct") ? "答对了" : result.equals("wrong") ? "再记一下" : result.equals("known") ? "已标记熟悉"
            : result.equals("remember") ? "已记录：记得" : result.equals("hard") ? "已记录：模糊" : "已记录：忘了";
        text(label, 20, good ? ACCENT : BAD, true); title(word.getString("word"));
        text(word.getString("meaning"), 16, INK, false);
        if (!event.optString("mode").equals("recall") && !result.equals("known")) {
            JSONObject q = selectedQuestion(session, word); small(q.optString("explanation"));
            if (event.optBoolean("assisted")) small("本题看过讲解，已单独标记");
        } else if (result.equals("known")) small("跳过不算答题正确，也不增加新词计数");
        else small("这是回忆自评，单独记录");
        button("下一题", true, this::advance);
        button("查看例句与用法", false, () -> { detailId = word.optString("id"); detailBack = "question"; navigate("detail"); });
        button("撤销这次作答", false, () -> { try { store.undo(event.getString("id")); render(); } catch (Exception e) { error("撤销未保存，请重试"); } });
    }
    private void advance() {
        try {
            JSONObject s = store.session(); JSONObject event = s.getJSONObject("feedbackEvent");
            JSONArray queue = s.getJSONArray("queue"); int pos = s.getInt("position"); String result = event.optString("result");
            // Insert one retry only when at least five intervening questions exist.
            if ((result.equals("wrong") || result.equals("forgot") || result.equals("hard")) && !event.optString("attempt").equals("retry") && queue.length() - pos - 1 >= 5) {
                JSONObject retry = new JSONObject(queue.getJSONObject(pos).toString()).put("attempt", "retry");
                JSONArray updated = new JSONArray();
                for (int i = 0; i < queue.length(); i++) { if (i == pos + 6) updated.put(retry); updated.put(queue.get(i)); }
                if (pos + 6 == queue.length()) updated.put(retry);
                queue = updated; s.put("queue", queue);
            }
            s.put("position", pos + 1); s.remove("feedbackEvent"); s.remove("questionIndex"); s.put("revealed", false).put("assisted", false);
            // A known-word skip frees a new-word place. Extend from the prepared reserve, without inventing content.
            if (result.equals("known")) {
                List<StudyEngine.Task> fresh = StudyEngine.queue(store.pack(), store.words(), store.events(), System.currentTimeMillis(), store.settings().optInt("newLimit", 15));
                for (StudyEngine.Task candidate : fresh) {
                    boolean exists = false; for (int i = pos + 1; i < queue.length(); i++) if (candidate.wordId.equals(queue.getJSONObject(i).optString("wordId"))) exists = true;
                    if (!exists) { queue.put(new JSONObject().put("wordId", candidate.wordId).put("review", candidate.review).put("attempt", "primary"));
                        JSONObject definition = store.words().optJSONObject(candidate.wordId); if (definition != null) s.getJSONObject("words").put(candidate.wordId, definition); break; }
                }
            }
            store.saveSession(s); navigate(pos + 1 < queue.length() ? "question" : "done");
        } catch (Exception e) { error("进度未保存，请重试"); }
    }
    private void example(JSONObject word) throws Exception {
        JSONArray examples = word.optJSONArray("examples");
        if (examples != null && examples.length() > 0) { JSONObject ex = examples.getJSONObject(0); text(ex.getString("en"), 15, ACCENT, false); small(ex.getString("zh")); }
    }
    private void detail() throws Exception {
        JSONObject session = store.session(); JSONObject word = null;
        if ("question".equals(detailBack) && session != null) word = session.getJSONObject("words").optJSONObject(detailId);
        if (word == null) word = store.words().optJSONObject(detailId);
        if (word == null) { small("这个词尚未下载"); button("返回", true, () -> navigate(detailBack)); return; }
        small("AI 学习卡"); title(word.getString("word")); small(word.optString("phonetic"));
        text(word.getString("meaning"), 17, INK, true); gap(4); example(word);
        JSONArray collocations = word.optJSONArray("collocations");
        if (collocations != null && collocations.length() > 0) {
            gap(5); small("常用搭配");
            for (int i = 0; i < collocations.length(); i++) text(collocations.getString(i), 14, INK, false);
        }
        if (!word.optString("roots").isEmpty()) { gap(5); small("构词与记忆"); text(word.getString("roots"), 14, INK, false); }
        JSONArray senses = word.optJSONArray("senses");
        if (senses != null && senses.length() > 0) { gap(5); small("搭配与其他用法"); for (int i = 0; i < senses.length(); i++) text(senses.getString(i), 14, INK, false); }
        small("底库排名 " + word.optInt("rank") + " · AI 生成内容");
        button("返回", true, () -> navigate(detailBack));
    }
    private void done() throws Exception {
        small("这一轮已结束"); title("留一点给明天");
        int[] counts = StudyEngine.todayCounts(store.events(), System.currentTimeMillis());
        text("新词 " + counts[0] + "  ·  复习 " + counts[1], 17, ACCENT, true); small("熟词略过 " + counts[2]);
        small("完成的是实际作答与回忆记录，不自动等同于完全掌握。");
        if (counts[0] < store.settings().optInt("newLimit", 15)) small("本包可用候选已完成；晚间对话时可让 AI 按记录接着准备。");
        button("回到首页", true, () -> navigate("home")); button("同步今天的记录", false, this::sync);
        SyncCoordinator.start(this, false, (message, success) -> {});
    }
    private void words() throws Exception {
        title("单词与记录"); JSONObject all = store.words(); Map<String, StudyEngine.Card> cards = StudyEngine.project(store.events(), System.currentTimeMillis());
        List<JSONObject> entries = new ArrayList<>(); Iterator<String> ids = all.keys(); while (ids.hasNext()) entries.add(all.getJSONObject(ids.next()));
        entries.sort((a, b) -> Integer.compare(a.optInt("rank", Integer.MAX_VALUE), b.optInt("rank", Integer.MAX_VALUE)));
        small("已下载 " + entries.size() + " 个词");
        for (JSONObject word : entries) {
            StudyEngine.Card card = cards.get(word.getString("id"));
            String status = card == null ? "未学" : card.known ? "熟悉 · 已略过" : card.dueAt <= System.currentTimeMillis() ? "待复习" : "复习 " + day(card.dueAt);
            button(word.getString("word") + "\n" + status, false, () -> { detailId = word.optString("id"); detailBack = "words"; navigate("detail"); });
        }
        button("返回首页", false, () -> navigate("home"));
    }
    private String modeLabel(String mode) { switch (mode) { case "forward": return "英→中"; case "reverse": return "中→英"; case "rare": return "熟词生义"; default: return "混合题型"; } }
    private void modes() {
        title("点选题型");
        for (String mode : new String[]{"mixed", "forward", "reverse", "rare"}) button(modeLabel(mode), false, () -> {
            try { JSONObject settings = store.settings(); settings.put("practiceMode", mode); store.saveSettings(settings); navigate("settings"); }
            catch (Exception e) { error("设置未保存"); }
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
        title("学习与同步"); JSONObject config = store.settings(); int limit = config.optInt("newLimit", 15);
        text("每日新词 " + limit, 17, ACCENT, true);
        small("调整后在下一轮学习生效");
        button("少 5 个", false, () -> changeLimit(-5)); button("多 5 个", false, () -> changeLimit(5));
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
                String secret = token.getText().toString().trim();
                if (!secret.isEmpty()) vault.save(secret); else if (!vault.exists()) { error("请填写 Token"); return; }
                JSONObject next = store.settings(); next.put("owner", o).put("repo", r).put("branch", b); store.saveSettings(next);
                token.setText(""); SyncJob.schedule(this); navigate("home"); sync();
            } catch (Exception e) { error("配置未能安全保存，请重试"); }
        });
        button("返回首页", false, () -> navigate("home")); small("词序 1.0.0 · 学习数据格式 v1");
    }
    private void changeLimit(int amount) {
        try { JSONObject settings = store.settings(); settings.put("newLimit", Math.max(0, Math.min(50, settings.optInt("newLimit", 15) + amount))); store.saveSettings(settings); render(); }
        catch (Exception e) { error("设置未保存"); }
    }
    private void sync() {
        if (!new CredentialVault(this).exists()) { navigate("settings"); return; }
        boolean started = SyncCoordinator.start(this, true, (message, success) -> { if ("home".equals(screen)) render(); error(message); });
        if (started) { if ("home".equals(screen)) render(); }
        else error("已有同步正在进行");
    }
    private void fatal(String title, String message) { layout(); title(title); small(message); }
}
