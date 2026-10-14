package cn.wordwatch.app;

import android.os.Build;
import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import cn.wordwatch.app.core.GitHubClient;
import cn.wordwatch.app.core.StudyEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs only on an emulator. The test APK is never included in the delivered app. */
@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public final class WatchFlowTest {
    private MainActivity activity;
    private AppStore store;
    private Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    @Before public void setUp() throws Exception {
        assertTrue("This test modifies emulator-only state", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
        activity = (MainActivity) getInstrumentation().startActivitySync(new android.content.Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        store = AppStore.get(activity);
        getInstrumentation().waitForIdleSync();
        waitForStudyIdle();
    }
    private void waitForStudyIdle() throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField("busy"); field.setAccessible(true);
        long deadline = System.nanoTime() + 30_000_000_000L;
        do {
            java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(true);
            getInstrumentation().runOnMainSync(() -> {
                try { busy.set(field.getBoolean(activity)); } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            if (!busy.get()) { getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Study worker did not finish");
    }
    private Button find(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button result = find(group.getChildAt(i), text); if (result != null) return result; }
        }
        return null;
    }
    private void click(String text) throws Exception {
        getInstrumentation().runOnMainSync(() -> {
            Button button = find(activity.getWindow().getDecorView(), text);
            assertNotNull("Button missing: " + text, button); button.performClick();
        });
        getInstrumentation().waitForIdleSync();
        waitForStudyIdle();
    }
    private void back() throws Exception { getInstrumentation().runOnMainSync(() -> activity.onBackPressed()); getInstrumentation().waitForIdleSync(); waitForStudyIdle(); }
    private JSONObject read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = input.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    @Test public void testOfflineChoiceUndoRecallAndLiveSync() throws Exception {
        JSONObject pack = store.pack(); assertNotNull("Build with an AI-authored bootstrap package", pack);
        StudyEngine.validatePackage(pack);
        assertTrue(activity.getResources().getConfiguration().isScreenRound());
        assertEquals("UI must expose the start action", "开始今日学习", find(activity.getWindow().getDecorView(), "开始今日学习").getText().toString());
        click("开始今日学习");
        JSONObject s = store.session(); JSONObject task = s.getJSONArray("queue").getJSONObject(s.getInt("position"));
        JSONObject word = s.getJSONObject("words").getJSONObject(task.getString("wordId"));
        JSONObject q = word.getJSONArray("questions").getJSONObject(s.getInt("questionIndex"));
        int initial = store.events().length();
        click(q.getJSONArray("options").getString((q.getInt("correctIndex") + 1) % q.getJSONArray("options").length()));
        assertEquals(initial + 1, store.events().length());
        assertEquals("wrong", store.session().getJSONObject("feedbackEvent").getString("result"));
        JSONObject disk = new LearningStorage(activity.getFilesDir()).read();
        assertEquals("Feedback and event must commit together", store.session().getJSONObject("feedbackEvent").getString("id"), disk.getJSONObject("session").getJSONObject("feedbackEvent").getString("id"));
        click("撤销这次作答"); assertFalse(store.session().has("feedbackEvent"));
        click("先看讲解"); click("返回");
        click(q.getJSONArray("options").getString(q.getInt("correctIndex")));
        assertTrue(store.session().getJSONObject("feedbackEvent").getBoolean("assisted"));
        click("下一题");
        JSONObject next = store.session();
        click("这个词已熟悉，略过");
        assertEquals("known", store.session().getJSONObject("feedbackEvent").getString("result"));
        int oldLength = next.getJSONArray("queue").length();
        click("下一题"); assertEquals("Skip should replenish from authored reserve", oldLength + 1, store.session().getJSONArray("queue").length());
        back(); store.saveSession(null);

        String reviewId = pack.getJSONArray("newWordIds").getString(20);
        JSONObject old = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", "emulator-review-fixture").put("seq", 1)
            .put("at", System.currentTimeMillis() - 3 * 86_400_000L).put("wordId", reviewId).put("packageId", pack.getString("packageId"))
            .put("type", "answer").put("mode", "forward").put("result", "correct").put("attempt", "primary").put("assisted", false).put("isReview", false);
        store.acceptSync(pack, new JSONArray().put(old));
        getInstrumentation().runOnMainSync(() -> { activity.recreate(); }); getInstrumentation().waitForIdleSync();
        // Recreate returns asynchronously; observe the resumed replacement instead of
        // starting an intent that Android can deliver to the existing task.
        java.util.concurrent.atomic.AtomicReference<MainActivity> resumed = new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < 50 && resumed.get() == null; i++) {
            getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity candidate : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (candidate instanceof MainActivity && candidate != activity) resumed.set((MainActivity) candidate);
            });
            if (resumed.get() == null) Thread.sleep(100);
        }
        assertNotNull("Recreated activity must resume", resumed.get()); activity = resumed.get();
        getInstrumentation().waitForIdleSync();
        waitForStudyIdle();
        click("开始今日学习");
        assertTrue(store.session().getJSONArray("queue").getJSONObject(0).getBoolean("review"));
        click("翻面看答案"); click("记得");
        assertEquals("recall", store.session().getJSONObject("feedbackEvent").getString("mode"));
        StudyEngine.Card card = StudyEngine.project(store.events(), System.currentTimeMillis()).get(reviewId);
        assertEquals(1, card.objectiveRight); assertEquals(1, card.selfRemember);
        CredentialVault vault = new CredentialVault(activity); vault.save("emulator-credential-encryption-check");
        assertEquals("emulator-credential-encryption-check", vault.read());
        assertFalse(activity.getSharedPreferences("vault", 0).getString("github", "").contains("emulator-credential-encryption-check"));

        File input = new File(getInstrumentation().getTargetContext().getFilesDir(), "live-sync-input.json");
        JSONObject report = new JSONObject().put("nativeOfflineFlow", "passed").put("roundScreen", true).put("apiLevel", Build.VERSION.SDK_INT)
            .put("liveSync", "not-run");
        if (input.exists()) {
            JSONObject config = read(input);
            if (config.has("token")) {
                String prefix = config.getString("prefix"); assertTrue(prefix.startsWith("watch-words-verification/"));
                GitHubClient client = new GitHubClient(prefix);
                try {
                    JSONArray local = store.events();
                    GitHubClient.SyncResult first = client.sync(config.getString("owner"), config.getString("repo"), "main", config.getString("token"), store.deviceId(), local);
                    assertTrue(first.uploadedCount > 0); store.acceptSync(first.pack, first.events);
                    GitHubClient.SyncResult second = client.sync(config.getString("owner"), config.getString("repo"), "main", config.getString("token"), store.deviceId(), store.events());
                    assertEquals(0, second.uploadedCount);
                    GitHubClient.SyncResult reader = client.sync(config.getString("owner"), config.getString("repo"), "main", config.getString("token"), "verification-reader", new JSONArray());
                    assertTrue(reader.events.length() >= local.length() - 1);
                    report.put("liveSync", "passed").put("prefix", prefix).put("uploaded", first.uploadedCount).put("repeatUploaded", second.uploadedCount)
                        .put("readerEvents", reader.events.length()).put("packageId", reader.pack.getString("packageId"));
                } finally {
                    try (FileOutputStream output = new FileOutputStream(input)) { output.write("{}".getBytes(StandardCharsets.UTF_8)); }
                }
            }
        }
        try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), "verification-report.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
