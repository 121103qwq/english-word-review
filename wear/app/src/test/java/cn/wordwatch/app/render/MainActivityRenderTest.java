package cn.wordwatch.app.render;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.wordwatch.app.AppStore;
import cn.wordwatch.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Draws MainActivity with synthetic data in Robolectric; no emulator or private seed is used. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MainActivityRenderTest {
    private static final String DEVICE_ID = "render-test-device";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private AppStore store;
    private JSONObject pack;

    @Before
    public void resetStore() throws Exception {
        RuntimeEnvironment.setFontScale(1.0f);
        resetStoreInstance();
    }

    private static void resetStoreInstance() throws Exception {
        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192RendersQuestionFeedbackDetailAndScrollableLists() throws Exception {
        prepareFixture(16, false, "forward");
        MainActivity activity = launch();
        assertRound(activity, 192);

        capture(activity, "round-192-home", false);
        assertPrimaryActionFitsRoundScreen(activity, "开始今日学习");
        click(activity, "开始今日学习");
        JSONObject session = store.session();
        JSONObject word = session.getJSONObject("words").getJSONObject(
                session.getJSONArray("queue").getJSONObject(0).getString("wordId"));
        JSONObject question = word.getJSONArray("questions").getJSONObject(session.getInt("questionIndex"));
        assertEquals("forward", question.getString("mode"));
        capture(activity, "round-192-question-long", false);
        assertNoEllipsis(activity.getWindow().getDecorView());
        assertLongOptionsWrap(activity);
        assertAllButtonsMeetTapTarget(activity);

        click(activity, "先看讲解");
        assertTextWraps(activity, "含义开头");
        assertNoEllipsis(activity.getWindow().getDecorView());
        capture(activity, "round-192-detail-top", false);
        ScrollView scroll = findScrollView(activity);
        assertTrue("Long detail must extend beyond the viewport", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scrollToBottom(scroll);
        assertVisible(activity, "返回");
        capture(activity, "round-192-detail-bottom", false);

        click(activity, "返回");
        click(activity, question.getJSONArray("options").getString(question.getInt("correctIndex")));
        capture(activity, "round-192-feedback", false);
        click(activity, "撤销这次作答");
        assertFalse("Undo must return to the same unanswered card", store.session().has("feedbackEvent"));
        assertNoEllipsis(activity.getWindow().getDecorView());

        click(activity, "稍后继续");
        String sessionId = store.session().getString("sessionId");
        JSONObject stateBeforeFinish = readSavedState();
        assertEquals("The session must already be committed before process recreation", sessionId,
                stateBeforeFinish.getJSONObject("session").optString("sessionId"));
        activity.finish();
        JSONObject stateAfterFinish = readSavedState();
        assertEquals("Finishing the Activity must preserve the committed session", sessionId,
                stateAfterFinish.getJSONObject("session").optString("sessionId"));
        resetStoreInstance();
        JSONObject stateBeforeLaunch = readSavedState();
        assertEquals("Resetting the in-memory store must preserve the committed session", sessionId,
                stateBeforeLaunch.getJSONObject("session").optString("sessionId"));
        activity = launch();
        JSONObject persisted = readSavedState();
        JSONObject restoredSession = persisted.optJSONObject("session");
        store = AppStore.get(activity);
        capture(activity, "round-192-session-restored-home", false);
        assertFalse("Session restoration must not land on the learning-state error page",
                findTextViewContaining(activity.getWindow().getDecorView(), "学习记录无法读取") != null
                        || findButton(activity, "检查修复更新") != null);
        assertNotNull("The persisted state must contain the active session; after-finish="
                + sessionSummary(stateAfterFinish) + "; before-launch=" + sessionSummary(stateBeforeLaunch)
                + "; after-launch=" + sessionSummary(persisted) + "; files=" + atomicFileSummary(), restoredSession);
        assertEquals("The persisted session date must remain today", pack.getString("date"),
                restoredSession.optString("date"));
        assertEquals("The persisted session must retain its package id", pack.getString("packageId"),
                restoredSession.optString("packageId"));
        assertEquals("The frozen session pack must retain its package id", pack.getString("packageId"),
                restoredSession.getJSONObject("pack").optString("packageId"));
        assertEquals("The frozen session revision must be retained", 0,
                restoredSession.optInt("packageRevision"));
        JSONArray persistedEvents = persisted.optJSONArray("events");
        assertNotNull("The persisted state must retain its event array", persistedEvents);
        assertEquals("The answer and its undo must both survive process recreation", 2, persistedEvents.length());
        assertEquals("The persisted sequence must match the undo event", 2, persisted.optLong("seq"));
        assertEquals("The first event should be the synthetic answer", "answer",
                persistedEvents.getJSONObject(0).optString("type"));
        assertEquals("The second event should undo that synthetic answer", "undo",
                persistedEvents.getJSONObject(1).optString("type"));
        assertEquals("Undo must target the persisted answer", persistedEvents.getJSONObject(0).optString("id"),
                persistedEvents.getJSONObject(1).optString("targetEventId"));
        assertEquals("The reconstructed store must read the same saved session", sessionId,
                store.session().optString("sessionId"));
        assertNotNull("A saved current-day session must be resumable", findButton(activity, "继续学习"));
        click(activity, "继续学习");
        assertEquals("Process recreation must preserve the active session", sessionId,
                store.session().getString("sessionId"));
        assertQuestionMode(activity, "forward");
        click(activity, "稍后继续");
        click(activity, "设置");
        capture(activity, "round-192-settings", false);
        assertNotNull("Settings token field must have a spoken label", findEditText(activity, "Access Token"));
        click(activity, "英→中");
        capture(activity, "round-192-modes", false);
        click(activity, "熟词生义");
        click(activity, "检查应用更新");
        assertTextExists(activity, "当前版本 1.1.0");
        capture(activity, "round-192-updates", false);
        click(activity, "返回");
        click(activity, "个人每日上限");
        assertNotNull(findButton(activity, "少 5 个"));
        assertNotNull(findButton(activity, "多 5 个"));
        assertNotNull(findButton(activity, "填写其他数量"));
        capture(activity, "round-192-settings-personal-limit", false);
        int personalLimit = store.settings().optInt("newLimit");
        click(activity, "多 5 个");
        assertEquals("Personal limit adjustment must preserve its five-word step", personalLimit + 5,
                store.settings().optInt("newLimit"));
        capture(activity, "round-192-settings-personal-limit-plus", false);
        click(activity, "填写其他数量");
        assertNotNull("Custom limit input must stay opt-in", findEditText(activity, "每日新词上限"));
        capture(activity, "round-192-settings-custom-limit-input", false);
        click(activity, "返回首页");
        click(activity, "单词与记录");
        capture(activity, "round-192-words-top", false);
        scrollToBottom(findScrollView(activity));
        assertVisible(activity, "返回首页");
        capture(activity, "round-192-words-bottom", false);

        click(activity, "返回首页");
        JSONObject staleSession = store.session();
        staleSession.put("date", Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1).toString());
        store.saveSession(staleSession);
        activity.finish();
        resetStoreInstance();
        activity = launch();
        assertNotNull("A prior-day session must not block today's queue", findButton(activity, "开始今日学习"));
        assertEquals("A prior-day session must not be offered as resumable", null, findButton(activity, "继续学习"));
        capture(activity, "round-192-session-expired-next-day-home", false);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w227dp-h227dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round227LargeFontRendersReverseAndRareModes() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        prepareFixture(8, false, "reverse");
        MainActivity activity = launch();
        assertRound(activity, 227);
        assertEquals(1.5f, activity.getResources().getConfiguration().fontScale, 0.01f);
        capture(activity, "round-227-font150-home", false);
        assertPrimaryActionFitsRoundScreen(activity, "开始今日学习");

        click(activity, "开始今日学习");
        assertQuestionMode(activity, "reverse");
        capture(activity, "round-227-font150-reverse", false);
        assertNoEllipsis(activity.getWindow().getDecorView());
        assertLongOptionsWrap(activity);
        click(activity, "稍后继续");

        JSONObject settings = store.settings();
        settings.put("practiceMode", "rare");
        store.saveSettings(settings);
        store.saveSession(null);
        activity.finish();
        activity = launch();
        click(activity, "开始今日学习");
        assertQuestionMode(activity, "rare");
        capture(activity, "round-227-font150-rare", false);

        click(activity, "先看讲解");
        assertTextWraps(activity, "含义开头");
        assertNoEllipsis(activity.getWindow().getDecorView());
        ScrollView scroll = findScrollView(activity);
        assertTrue("Large-font detail must remain scrollable", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scrollToBottom(scroll);
        assertVisible(activity, "返回");
        capture(activity, "round-227-font150-detail-bottom", false);
        assertAllButtonsMeetTapTarget(activity);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192RendersDueReviewFlipFeedbackAndEmptyPackage() throws Exception {
        prepareFixture(0, true, "mixed");
        MainActivity activity = launch();
        assertRound(activity, 192);
        click(activity, "开始今日学习");
        assertTextExists(activity, "到期复习");
        capture(activity, "round-192-review-front", false);
        click(activity, "翻面看答案");
        assertTextExists(activity, "记得");
        capture(activity, "round-192-review-revealed", false);
        click(activity, "记得");
        capture(activity, "round-192-review-feedback", false);
        click(activity, "下一题");
        capture(activity, "round-192-done", false);

        prepareFixture(0, false, "mixed");
        activity.finish();
        activity = launch();
        assertEquals("An empty package must not create a session", null, store.session());
        Button emptyQueue = findButton(activity, "当前没有待学任务");
        assertNotNull("An empty package should show its disabled empty-state action", emptyQueue);
        assertFalse("The empty-state action must not start an empty session", emptyQueue.isEnabled());
        assertEquals("There must be no start action for an empty package", null,
                findButton(activity, "开始今日学习"));
        capture(activity, "round-192-empty-package", false);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192V2InputDefaultsToKeyboardFreeFlipSelfAssessment() throws Exception {
        prepareV2Fixture("input");
        MainActivity activity = launch();
        click(activity, "开始今日学习");
        assertEquals("input", currentQuestion(activity).getString("type"));
        assertTextExists(activity, "输入合成答案");
        assertNotNull("Input questions should default to the no-keyboard recall path", findButton(activity, "翻面看答案"));
        assertNotNull("Typing must remain an explicit choice", findButton(activity, "改为输入作答"));
        assertEquals("No answer field should be present before the user chooses to type", null,
                findEditText(activity, "你的答案"));
        capture(activity, "round-192-v2-input-default-no-keyboard", false);
        assertPrimaryActionFitsRoundScreen(activity, "翻面看答案");
        click(activity, "翻面看答案");
        assertTextExists(activity, "合成回忆答案");
        assertNotNull("Self-assessment should be available after revealing the answer", findButton(activity, "记得"));
        capture(activity, "round-192-v2-input-default-revealed", false);
        click(activity, "记得");
        assertTextExists(activity, "已记录：记得");
        capture(activity, "round-192-v2-input-default-feedback", false);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192V2InputCanExpandToTextEntryAndLongSectionNotes() throws Exception {
        prepareV2Fixture("input");
        MainActivity activity = launch();
        click(activity, "开始今日学习");
        assertEquals("input", currentQuestion(activity).getString("type"));
        capture(activity, "round-192-v2-input-before-expand", false);
        click(activity, "改为输入作答");
        EditText answer = findEditText(activity, "你的答案");
        assertNotNull("Input question must expose an accessible answer field", answer);
        assertTrue("Answer field must meet the 48dp tap target", answer.getHeight()
                >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        capture(activity, "round-192-v2-input-expanded", false);
        answer.setText("合成回忆答案");
        capture(activity, "round-192-v2-input-question", false);
        assertNoEllipsis(activity.getWindow().getDecorView());
        click(activity, "提交答案");
        assertTextExists(activity, "答对了");
        capture(activity, "round-192-v2-input-feedback", false);

        click(activity, "查看例句与用法");
        assertTextExists(activity, "扩展讲解 1");
        assertTextWraps(activity, "长段合成说明");
        assertNoEllipsis(activity.getWindow().getDecorView());
        capture(activity, "round-192-v2-notes-top", false);
        scrollToBottom(findScrollView(activity));
        assertVisible(activity, "返回");
        assertTextExists(activity, "扩展讲解 6");
        capture(activity, "round-192-v2-notes-bottom", false);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192RendersV2RecallQuestionFlipAndFeedback() throws Exception {
        prepareV2Fixture("recall");
        MainActivity activity = launch();
        click(activity, "开始今日学习");
        assertEquals("recall", currentQuestion(activity).getString("type"));
        assertTextExists(activity, "回忆合成答案");
        capture(activity, "round-192-v2-recall-front", false);
        click(activity, "翻面看答案");
        assertTextExists(activity, "合成回忆答案");
        capture(activity, "round-192-v2-recall-revealed", false);
        click(activity, "记得");
        assertTextExists(activity, "已记录：记得");
        capture(activity, "round-192-v2-recall-feedback", false);
    }

    @Test
    @Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
    public void round192RendersFuturePackageGateWithoutStartingNewWords() throws Exception {
        prepareFutureFixture();
        MainActivity activity = launch();
        assertTextExists(activity, "到当天开放新词");
        assertEquals("Future packages must not create a session early", null, store.session());
        assertEquals("There must be no start action before the package date", null,
                findButton(activity, "开始今日学习"));
        capture(activity, "round-192-future-pack-home", false);
        Button waitForDate = findButton(activity, "等待学习包日期");
        assertNotNull("A future package should show a disabled date gate", waitForDate);
        assertFalse("The date gate must stay disabled until the package date", waitForDate.isEnabled());
        ScrollView scroll = findScrollView(activity);
        scroll.requestChildRectangleOnScreen(waitForDate,
                new Rect(0, 0, waitForDate.getWidth(), waitForDate.getHeight()), true);
        measureActivity(activity);
        assertVisible(activity, "等待学习包日期");
        capture(activity, "round-192-future-pack-date-gate", false);
    }

    private void prepareFixture(int newWordCount, boolean includeDueReview, String mode) throws Exception {
        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);

        long now = System.currentTimeMillis();
        String date = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        String prefix = "render-" + UUID.randomUUID().toString().substring(0, 8);
        pack = new JSONObject()
                .put("schemaVersion", 1)
                .put("packageId", prefix + "-" + date)
                .put("date", date)
                .put("timezone", "Asia/Shanghai")
                .put("contentMode", "ai-authored")
                .put("catalogSha256", repeat("a", 64))
                .put("newWordLimit", 10000);
        JSONArray words = new JSONArray();
        JSONArray newIds = new JSONArray();
        JSONArray reviewIds = new JSONArray();
        JSONObject wordCache = new JSONObject();
        for (int i = 0; i < newWordCount; i++) {
            JSONObject word = syntheticWord(prefix, i, i == 0);
            words.put(word);
            newIds.put(word.getString("id"));
            wordCache.put(word.getString("id"), word);
        }
        JSONArray events = new JSONArray();
        if (includeDueReview) {
            JSONObject review = syntheticWord(prefix, 900, true);
            String reviewId = review.getString("id");
            words.put(review);
            reviewIds.put(reviewId);
            wordCache.put(reviewId, review);
            events.put(new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("deviceId", DEVICE_ID)
                    .put("seq", 1)
                    .put("at", now - 4 * DAY_MS)
                    .put("wordId", reviewId)
                    .put("packageId", pack.getString("packageId"))
                    .put("type", "answer")
                    .put("mode", "forward")
                    .put("result", "correct")
                    .put("attempt", "primary")
                    .put("assisted", false)
                    .put("isReview", false));
        }
        pack.put("words", words).put("newWordIds", newIds).put("reviewWordIds", reviewIds);

        JSONObject settings = new JSONObject()
                .put("owner", "render-fixture")
                .put("repo", "synthetic-fixture")
                .put("branch", "main")
                .put("newLimit", 137)
                .put("newLimitDefaultVersion", 2)
                .put("limitMode", "package")
                .put("practiceMode", mode);
        JSONObject state = new JSONObject()
                .put("schemaVersion", 1)
                .put("deviceId", DEVICE_ID)
                .put("seq", includeDueReview ? 1 : 0)
                .put("events", events)
                .put("words", wordCache)
                .put("settings", settings)
                .put("pack", pack)
                .put("lastSyncedSeq", 0)
                .put("lastSync", 0)
                .put("lastAttempt", 0)
                .put("syncError", "");

        File stateFile = new File(RuntimeEnvironment.getApplication().getFilesDir(), "learning-state.json");
        try (FileOutputStream output = new FileOutputStream(stateFile)) {
            output.write(state.toString().getBytes(StandardCharsets.UTF_8));
        }
        store = AppStore.get(RuntimeEnvironment.getApplication());
    }

    private void prepareV2Fixture(String questionType) throws Exception {
        prepareFixture(1, false, "forward");
        JSONObject state = store.snapshot();
        JSONObject basePack = state.getJSONObject("pack");
        JSONObject word = basePack.getJSONArray("words").getJSONObject(0);
        String id = word.getString("id");
        JSONObject question;
        if ("input".equals(questionType)) {
            question = new JSONObject()
                    .put("id", id + "-input")
                    .put("type", "input")
                    .put("mode", "forward")
                    .put("prompt", "输入合成答案")
                    .put("acceptedAnswers", new JSONArray().put("合成回忆答案"))
                    .put("explanation", "输入题合成解释。");
        } else if ("recall".equals(questionType)) {
            question = new JSONObject()
                    .put("id", id + "-recall")
                    .put("type", "recall")
                    .put("mode", "forward")
                    .put("prompt", "回忆合成答案")
                    .put("answer", "合成回忆答案")
                    .put("explanation", "回忆题合成解释。");
        } else {
            throw new IllegalArgumentException("unknown synthetic question type: " + questionType);
        }
        JSONArray questions = new JSONArray().put(question);
        JSONArray noteSections = new JSONArray();
        for (int i = 1; i <= 6; i++) {
            noteSections.put(new JSONObject()
                    .put("title", "扩展讲解 " + i)
                    .put("body", "长段合成说明 " + i + "。" + repeat("这是纯合成的扩展讲解，用于验证 Wear 小屏长文本自动换行和垂直滚动。", 28)));
        }
        JSONObject notes = new JSONObject().put("sections", noteSections);
        word.put("questions", questions).put("notes", notes)
                .put("futureOptionalField", new JSONObject().put("ignored", true));

        JSONObject pack = new JSONObject(basePack.toString())
                .put("schemaVersion", 2)
                .put("revision", 1)
                .put("kind", "full")
                .put("newWordLimit", -1)
                .put("withdrawnWordIds", new JSONArray())
                .put("requiredCapabilities", new JSONArray()
                        .put("plain-text").put("word-notes-v1")
                        .put("input-v1").put("recall-v1"))
                .put("optionalCapabilities", new JSONArray().put("future-render-v99"))
                .put("words", new JSONArray().put(word));
        state.getJSONObject("words").put(id, word);
        state.put("pack", pack);
        state.put("packages", new JSONObject().put(pack.getString("date"), pack));
        writeFixtureState(state);
    }

    private void prepareFutureFixture() throws Exception {
        prepareFixture(1, false, "forward");
        JSONObject state = store.snapshot();
        JSONObject futurePack = state.getJSONObject("pack");
        String futureDate = Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(2).toString();
        futurePack.put("date", futureDate).put("packageId", "render-future-" + futureDate);
        state.put("pack", futurePack).put("packages", new JSONObject().put(futureDate, futurePack));
        writeFixtureState(state);
    }

    private void writeFixtureState(JSONObject state) throws Exception {
        File stateFile = new File(RuntimeEnvironment.getApplication().getFilesDir(), "learning-state.json");
        try (FileOutputStream output = new FileOutputStream(stateFile)) {
            output.write(state.toString().getBytes(StandardCharsets.UTF_8));
        }
        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        store = AppStore.get(RuntimeEnvironment.getApplication());
    }

    private static JSONObject readSavedState() throws Exception {
        File stateFile = new File(RuntimeEnvironment.getApplication().getFilesDir(), "learning-state.json");
        byte[] bytes = java.nio.file.Files.readAllBytes(stateFile.toPath());
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static String sessionSummary(JSONObject state) {
        JSONObject session = state.optJSONObject("session");
        JSONArray events = state.optJSONArray("events");
        return "date=" + (session == null ? "<none>" : session.optString("date"))
                + ", packageId=" + (session == null ? "<none>" : session.optString("packageId"))
                + ", sessionId=" + (session == null ? "<none>" : session.optString("sessionId"))
                + ", seq=" + state.optLong("seq")
                + ", events=" + (events == null ? -1 : events.length());
    }

    private static String atomicFileSummary() {
        File dir = RuntimeEnvironment.getApplication().getFilesDir();
        StringBuilder result = new StringBuilder(dir.getAbsolutePath());
        for (String name : new String[]{"learning-state.json", "learning-state.json.new", "learning-state.json.bak"}) {
            File file = new File(dir, name);
            result.append("; ").append(name).append('=').append(file.exists() ? file.length() : "absent");
            if (file.exists()) result.append('@').append(file.lastModified());
        }
        return result.toString();
    }

    private static JSONObject syntheticWord(String prefix, int index, boolean longText) throws Exception {
        String id = "dyn:" + prefix + ":" + index;
        String word = "watchword" + index;
        String meaning = longText ? "含义开头。" + repeat("手表离线学习的合成解释文本，用于观察字号缩放、自动换行和垂直滚动。", 18)
                : "合成词义 " + index;
        if (meaning.length() > 1200) meaning = meaning.substring(0, 1200);
        JSONObject result = new JSONObject()
                .put("id", id)
                .put("word", word)
                .put("rank", index + 1)
                .put("phonetic", "")
                .put("meaning", meaning)
                .put("generatedBy", "AI");
        if (longText) result.put("roots", repeat("构词与记忆提示。", 125).substring(0, 1000));

        JSONArray examples = new JSONArray();
        for (int i = 0; i < (longText ? 3 : 1); i++) {
            String en = longText ? repeat("A synthetic English example sentence for a round watch display. ", 7) : "A short example " + index + ".";
            String zh = longText ? repeat("合成例句用于验证小屏幕长文本换行和滚动。", 14) : "合成例句 " + index + "。";
            examples.put(new JSONObject().put("en", en.substring(0, Math.min(en.length(), 500)))
                    .put("zh", zh.substring(0, Math.min(zh.length(), 700))));
        }
        result.put("examples", examples);

        JSONArray senses = new JSONArray();
        int senseCount = longText ? 16 : 1;
        for (int i = 0; i < senseCount; i++) {
            String sense = longText ? "用法 " + i + "：" + repeat("合成释义文本，测试滚动容器是否能到达末尾。", 7) : "合成用法 " + index;
            senses.put(sense.substring(0, Math.min(sense.length(), 500)));
        }
        result.put("senses", senses);

        JSONArray questions = new JSONArray();
        String prompt = longText ? repeat("请结合句子上下文判断词语含义，以下内容仅为合成渲染数据。", 7) : "选择合成词义：" + index;
        String explanation = longText ? repeat("答题解释：这是一段合成解释，用于检查反馈页面的换行、字号和滚动行为。", 12) : "合成解释。";
        for (String mode : new String[]{"forward", "reverse", "rare"}) {
            if ("rare".equals(mode) && !longText) continue;
            JSONArray options = new JSONArray();
            if (longText) {
                options.put(repeat("合成选项 A，观察长选项在圆形屏幕中的换行和触控区域。", 10).substring(0, 220));
                options.put(repeat("合成选项 B，观察长选项在圆形屏幕中的换行和触控区域。", 10).substring(0, 220));
                options.put(repeat("合成选项 C，观察长选项在圆形屏幕中的换行和触控区域。", 10).substring(0, 220));
            } else {
                options.put("合成选项 A " + index).put("合成选项 B " + index).put("合成选项 C " + index);
            }
            questions.put(new JSONObject()
                    .put("id", id + "-" + mode)
                    .put("mode", mode)
                    .put("prompt", prompt.substring(0, Math.min(prompt.length(), 500)))
                    .put("options", options)
                    .put("correctIndex", 1)
                    .put("explanation", explanation.substring(0, Math.min(explanation.length(), 1200))));
        }
        result.put("questions", questions);
        if (longText) {
            JSONArray collocations = new JSONArray();
            for (int i = 0; i < 6; i++) collocations.put("synthetic collocation " + i + " · " + repeat("合成搭配片段 ", 8));
            result.put("collocations", collocations);
        }
        return result;
    }

    private MainActivity launch() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class);
        return Robolectric.buildActivity(MainActivity.class, intent).setup().get();
    }

    private static void assertRound(Activity activity, int expectedDp) {
        Configuration config = activity.getResources().getConfiguration();
        assertTrue("Robolectric must expose a round watch configuration", config.isScreenRound());
        assertEquals(Configuration.UI_MODE_TYPE_WATCH, config.uiMode & Configuration.UI_MODE_TYPE_MASK);
        int actualDp = Math.round(activity.getResources().getDisplayMetrics().widthPixels
                / activity.getResources().getDisplayMetrics().density);
        assertEquals(expectedDp, actualDp);
    }

    private static void assertQuestionMode(MainActivity activity, String expected) throws Exception {
        JSONObject session = AppStore.get(activity).session();
        JSONObject word = session.getJSONObject("words").getJSONObject(
                session.getJSONArray("queue").getJSONObject(session.getInt("position")).getString("wordId"));
        JSONObject question = word.getJSONArray("questions").getJSONObject(session.getInt("questionIndex"));
        assertEquals(expected, question.getString("mode"));
    }

    private static JSONObject currentQuestion(MainActivity activity) throws Exception {
        JSONObject session = AppStore.get(activity).session();
        JSONObject word = session.getJSONObject("words").getJSONObject(
                session.getJSONArray("queue").getJSONObject(session.getInt("position")).getString("wordId"));
        return word.getJSONArray("questions").getJSONObject(session.getInt("questionIndex"));
    }

    private static void assertPrimaryActionFitsRoundScreen(Activity activity, String label) {
        int[] size = measureActivity(activity);
        Button button = findButton(activity, label);
        assertNotNull("Missing first-screen action: " + label, button);
        assertEquals("Primary action must be visible without scrolling", 0, findScrollView(activity).getScrollY());
        int[] location = new int[2]; button.getLocationOnScreen(location);
        double radius = Math.min(size[0], size[1]) / 2.0;
        for (int x : new int[]{location[0], location[0] + button.getWidth()}) {
            for (int y : new int[]{location[1], location[1] + button.getHeight()}) {
                double dx = x - size[0] / 2.0, dy = y - size[1] / 2.0;
                assertTrue("Primary action must fit inside the round first screen: " + label,
                        dx * dx + dy * dy <= radius * radius + 1);
            }
        }
    }

    private static void click(Activity activity, String label) {
        measureActivity(activity);
        Button button = findButton(activity, label);
        assertNotNull("Missing visible action: " + label, button);
        ScrollView scroll = findScrollView(activity);
        scroll.requestChildRectangleOnScreen(button, new Rect(0, 0, button.getWidth(), button.getHeight()), true);
        measureActivity(activity);
        Rect visible = new Rect();
        assertTrue("Action must be reachable by scrolling: " + label, button.getGlobalVisibleRect(visible));
        assertTrue("Action must have a visible touch area: " + label, visible.height() > 0 && visible.width() > 0);
        button.performClick();
        measureActivity(activity);
    }

    private static Button findButton(Activity activity, String label) {
        return findButton(activity.getWindow().getDecorView(), label);
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText findEditText(Activity activity, String contentDescription) {
        return findEditText(activity.getWindow().getDecorView(), contentDescription);
    }

    private static EditText findEditText(View view, String contentDescription) {
        if (view instanceof EditText && contentDescription.contentEquals(view.getContentDescription())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i), contentDescription);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ScrollView findScrollView(Activity activity) {
        return findScrollView(activity.getWindow().getDecorView());
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScrollView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        throw new AssertionError("MainActivity must keep its vertical ScrollView");
    }

    private static void assertTextWraps(Activity activity, String marker) {
        TextView view = findTextViewContaining(activity.getWindow().getDecorView(), marker);
        assertNotNull("Expected long fixture text: " + marker, view);
        assertNotNull("Long text must have a measured layout", view.getLayout());
        assertTrue("Long text should wrap over multiple lines", view.getLayout().getLineCount() > 1);
    }

    private static void assertTextExists(Activity activity, String marker) {
        assertNotNull("Missing expected rendered text: " + marker,
                findTextViewContaining(activity.getWindow().getDecorView(), marker));
    }

    private static TextView findTextViewContaining(View view, String marker) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(marker)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextViewContaining(group.getChildAt(i), marker);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertNoEllipsis(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) {
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertEquals("Text must remain scrollable/wrapped instead of ellipsized: " + text.getText(),
                            0, text.getLayout().getEllipsisCount(line));
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNoEllipsis(group.getChildAt(i));
        }
    }

    private static void assertLongOptionsWrap(Activity activity) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(activity.getWindow().getDecorView(), buttons);
        int checked = 0;
        for (Button button : buttons) {
            if (button.getText().toString().startsWith("合成选项")) {
                assertNotNull("Long option must have a native text layout", button.getLayout());
                assertTrue("Long choice options must wrap inside the round-screen column",
                        button.getLayout().getLineCount() > 1);
                checked++;
            }
        }
        assertTrue("Long synthetic choices must be present", checked >= 3);
    }

    private static void assertAllButtonsMeetTapTarget(Activity activity) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(activity.getWindow().getDecorView(), buttons);
        float density = activity.getResources().getDisplayMetrics().density;
        int minimum = Math.round(48 * density);
        assertFalse("Screen should expose at least one action", buttons.isEmpty());
        for (Button button : buttons) {
            assertTrue("Button target is smaller than 48dp: " + button.getText(), button.getHeight() >= minimum);
        }
    }

    private static void collectButtons(View view, List<Button> buttons) {
        if (view instanceof Button) buttons.add((Button) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectButtons(group.getChildAt(i), buttons);
        }
    }

    private static void scrollToBottom(ScrollView scroll) {
        int contentHeight = scroll.getChildAt(0).getHeight();
        scroll.scrollTo(0, Math.max(0, contentHeight - scroll.getHeight()));
        assertTrue("Expected scroll position to advance", scroll.getScrollY() > 0);
    }

    private static void assertVisible(Activity activity, String label) {
        View view = findButton(activity, label);
        assertNotNull("Missing bottom action: " + label, view);
        Rect visible = new Rect();
        assertTrue("Bottom action must scroll fully into view: " + label, view.getGlobalVisibleRect(visible));
        assertTrue("Bottom action must have a non-empty visible area", visible.height() > 0 && visible.width() > 0);
    }

    private static void capture(Activity activity, String name, boolean atBottom) throws Exception {
        int[] size = measureActivity(activity);
        int width = size[0];
        int height = size[1];
        View decor = activity.getWindow().getDecorView();
        ScrollView scroll = findScrollView(activity);
        if (atBottom) scrollToBottom(scroll);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        if (activity.getResources().getConfiguration().isScreenRound()) {
            Path circle = new Path();
            circle.addCircle(width / 2f, height / 2f, Math.min(width, height) / 2f, Path.Direction.CW);
            canvas.clipPath(circle);
        }
        decor.draw(canvas);
        canvas.restore();

        File outputDir = new File(System.getProperty("wordwatch.renderDir", "build/reports/wordwatch-render"));
        if (!outputDir.exists() && !outputDir.mkdirs()) throw new IllegalStateException("Could not create render output directory");
        File output = new File(outputDir, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("Screenshot encoder must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
        assertTrue("Expected non-empty screenshot: " + output, output.length() > 1000);
    }

    private static int[] measureActivity(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (width <= 0) width = Math.round(192 * density);
        if (height <= 0) height = Math.round(192 * density);
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
        return new int[]{width, height};
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }
}
