package cn.wordwatch.app;

import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import cn.wordwatch.app.render.LearningInteractionPerformanceTest;
import cn.wordwatch.app.render.MainActivityRenderTest;
import cn.wordwatch.app.render.ShadowAtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StudyInteractionSafetyTest {
    private MainActivity activity;
    @After public void cleanup() { ShadowAtomicFile.beforeRename = null; if (activity != null) activity.finish(); }
    private void launch() throws Exception {
        LearningInteractionPerformanceTest.seed();
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        button("开始今日学习").performClick(); button("翻面看答案");
    }
    private Button button(String text) throws Exception { return LearningInteractionPerformanceTest.await(activity, text); }
    @Test public void slowDiskDoesNotBlockMainLoopAndRapidOrStaleClicksCannotDuplicateAnswers() throws Exception {
        launch(); button("翻面看答案").performClick(); Button remember = button("记得");
        int before = AppStore.get(activity).events().length();
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        ShadowAtomicFile.beforeRename = file -> {
            if (file.getName().equals("learning-progress.json")) {
                writing.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test write timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        };
        try {
            remember.performClick();
            assertFalse("Second tap must be disabled immediately", remember.isEnabled());
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            AtomicBoolean uiHeartbeat = new AtomicBoolean();
            new Handler(Looper.getMainLooper()).post(() -> uiHeartbeat.set(true));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Main loop must keep processing while storage is blocked", uiHeartbeat.get());
            remember.performClick(); remember.performClick();
        } finally { release.countDown(); ShadowAtomicFile.beforeRename = null; }
        button("下一题"); remember.performClick();
        MainActivityRenderTest.awaitPrepared(activity);
        assertEquals(before + 1, AppStore.get(activity).events().length());
        LearningInteractionPerformanceTest.reset();
        assertEquals(before + 1, AppStore.get(activity).events().length());
    }
    @Test public void failedAnswerWriteDoesNotClaimSuccessAndCanRetryOnce() throws Exception {
        launch(); button("翻面看答案").performClick(); Button remember = button("记得");
        AppStore store = AppStore.get(activity); int before = store.events().length();
        ShadowAtomicFile.beforeRename = file -> { if (file.getName().equals("learning-progress.json")) throw new IllegalStateException("synthetic write failure"); };
        try { remember.performClick(); button("记得"); }
        finally { ShadowAtomicFile.beforeRename = null; }
        assertEquals(before, store.events().length()); assertFalse(store.session().has("feedbackEvent"));
        button("记得").performClick(); button("下一题");
        assertEquals(before + 1, store.events().length());
        LearningInteractionPerformanceTest.reset();
        assertEquals(before + 1, AppStore.get(activity).events().length());
    }
    @Test public void syncMergeAndAcknowledgementPreserveLocalAnswerAndFrozenSession() throws Exception {
        launch(); button("翻面看答案").performClick(); button("记得").performClick(); button("下一题");
        AppStore store = AppStore.get(activity); int before = store.events().length();
        String frozenId = store.session().getString("sessionId");
        JSONObject remote = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", "another-device")
                .put("seq", 1).put("at", System.currentTimeMillis() - 86400000L).put("wordId", "csv:2000")
                .put("packageId", "remote-history").put("type", "answer").put("mode", "forward").put("result", "correct")
                .put("attempt", "primary").put("assisted", false).put("isReview", false);
        store.acceptSync(null, new JSONArray().put(remote));
        assertEquals(before + 1, store.events().length()); assertEquals(1, store.pendingCount());
        assertEquals(frozenId, store.session().getString("sessionId"));
        store.acceptSync(null, store.events()); assertEquals(0, store.pendingCount());
        button("下一题").performClick(); button("翻面看答案");
        LearningInteractionPerformanceTest.reset();
        AppStore restored = AppStore.get(activity);
        assertEquals(before + 1, restored.events().length()); assertEquals(0, restored.pendingCount());
        assertEquals(1, restored.session().getInt("position"));
        assertEquals(frozenId, restored.session().getString("sessionId"));
    }
    @Test public void syncedWithdrawalRejectsStaleAnswerAndRefreshesVisibleQuestion() throws Exception {
        launch(); button("翻面看答案").performClick(); Button remember = button("记得");
        AppStore store = AppStore.get(activity); int before = store.events().length();
        String first = store.session().getJSONArray("queue").getJSONObject(0).getString("wordId");
        JSONObject pack = store.pack(); pack.put("revision", 2);
        pack.getJSONArray("requiredCapabilities").put("word-withdrawal-v1");
        pack.put("withdrawnWordIds", new JSONArray().put(first));
        JSONArray words = new JSONArray(), ids = new JSONArray();
        for (int i = 0; i < pack.getJSONArray("words").length(); i++) {
            JSONObject word = pack.getJSONArray("words").getJSONObject(i);
            if (!first.equals(word.getString("id"))) { words.put(word); ids.put(word.getString("id")); }
        }
        pack.put("words", words).put("newWordIds", ids); store.acceptSync(pack, new JSONArray());
        remember.performClick(); button("翻面看答案");
        assertEquals(before, store.events().length());
        assertNotEquals(first, store.session().getJSONArray("queue").getJSONObject(0).getString("wordId"));
    }
    @Test public void cachedQueueExpiresWhenFutureDatedSyncedEvidenceBecomesCurrent() throws Exception {
        launch(); AppStore store = AppStore.get(activity);
        String first = store.session().getJSONArray("queue").getJSONObject(0).getString("wordId");
        long at = System.currentTimeMillis() + 1200;
        JSONObject future = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", "clock-ahead-device")
                .put("seq", 1).put("at", at).put("wordId", first).put("packageId", "future-clock-evidence")
                .put("type", "answer").put("mode", "forward").put("result", "correct")
                .put("attempt", "primary").put("assisted", false).put("isReview", false);
        store.acceptSync(null, new JSONArray().put(future));
        assertEquals(first, store.prepareSession().getJSONArray("queue").getJSONObject(0).getString("wordId"));
        long remaining = at - System.currentTimeMillis(); if (remaining >= 0) Thread.sleep(remaining + 20);
        assertNotEquals(first, store.prepareSession().getJSONArray("queue").getJSONObject(0).getString("wordId"));
    }
}
