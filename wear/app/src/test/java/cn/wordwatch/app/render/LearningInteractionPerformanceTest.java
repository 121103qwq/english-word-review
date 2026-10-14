package cn.wordwatch.app.render;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import cn.wordwatch.app.AppStore;
import cn.wordwatch.app.MainActivity;
import cn.wordwatch.app.core.StudyEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Runs the real click handlers repeatedly, measuring callback and next-layout-ready time.
 * Synthetic 15-day content only; desktop timing is NOT watch timing. No emulator is used. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w192dp-h192dp-round-watch", shadows = ShadowAtomicFile.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LearningInteractionPerformanceTest {
    private static final int ROUNDS = 15;
    private static final int HISTORY = 300;

    @Test public void continuousFlipRememberNextWithArchivedPackages() throws Exception {
        seed();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        await(activity, "开始今日学习");
        click(activity, "开始今日学习", "翻面看答案");
        ShadowAtomicFile.mainWrites.set(0); ShadowAtomicFile.writtenBytes.set(0); ShadowAtomicFile.measure = true;
        JSONObject report = new JSONObject().put("environment", "Robolectric SDK35 on desktop; not watch latency")
                .put("cachedPackages", 15).put("wordsPerPackage", 120).put("historicalEvents", HISTORY)
                .put("rounds", ROUNDS);
        List<Double> flip = new ArrayList<>(), remember = new ArrayList<>(), next = new ArrayList<>();
        List<Double> callbacks = new ArrayList<>();
        try {
            for (int i = 0; i < ROUNDS; i++) {
                measure(activity, "翻面看答案", "记得", flip, callbacks);
                measure(activity, "记得", "下一题", remember, callbacks);
                measure(activity, "下一题", "翻面看答案", next, callbacks);
            }
        } finally { ShadowAtomicFile.measure = false; }
        report.put("flipMs", stats(flip)).put("rememberMs", stats(remember)).put("nextMs", stats(next))
                .put("clickCallbackMs", stats(callbacks)).put("mainThreadWrites", ShadowAtomicFile.mainWrites.get())
                .put("bytesWrittenDuringClicks", ShadowAtomicFile.writtenBytes.get());
        AppStore store = AppStore.get(activity);
        assertEquals("Each remember must create exactly one durable answer", HISTORY + ROUNDS, store.events().length());
        assertEquals(ROUNDS, store.session().getInt("position"));
        reset();
        AppStore restored = AppStore.get(activity);
        assertEquals("History must survive process restart", HISTORY + ROUNDS, restored.events().length());
        assertEquals("Next-question position must survive process restart", ROUNDS, restored.session().getInt("position"));
        File destination = new File(System.getProperty("wordwatch.renderDir"), "interaction-performance.json");
        destination.getParentFile().mkdirs();
        Files.write(destination.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("INTERACTION_PERFORMANCE " + report);
        assertEquals("Study clicks must never write on the main thread", 0, ShadowAtomicFile.mainWrites.get());
        assertTrue("45 clicks must not rewrite the 15-day content cache", ShadowAtomicFile.writtenBytes.get() < 12_000_000L);
        activity.finish();
    }

    private static void measure(Activity activity, String label, String next, List<Double> elapsed, List<Double> callbacks) throws Exception {
        Button button = await(activity, label);
        long start = System.nanoTime();
        assertTrue(button.performClick());
        callbacks.add((System.nanoTime() - start) / 1e6);
        await(activity, next);
        elapsed.add((System.nanoTime() - start) / 1e6);
    }
    private static JSONObject stats(List<Double> values) throws Exception {
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return new JSONObject().put("p50", sorted.get(sorted.size() / 2))
                .put("p95", sorted.get((int)Math.ceil(sorted.size() * .95) - 1)).put("max", sorted.get(sorted.size() - 1));
    }
    private static void click(Activity activity, String label, String next) throws Exception { await(activity, label).performClick(); await(activity, next); }
    public static Button await(Activity activity, String label) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View root = activity.getWindow().getDecorView();
            int spec = View.MeasureSpec.makeMeasureSpec(384, View.MeasureSpec.EXACTLY);
            root.measure(spec, spec); root.layout(0, 0, 384, 384);
            Button button = find(root, label);
            if (button != null && button.isEnabled()) return button;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + label);
    }
    private static Button find(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            Button found = find(((ViewGroup)view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    public static void reset() throws Exception { Field field = AppStore.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null, null); }
    public static void seed() throws Exception {
        reset();
        String today = LocalDate.now(StudyEngine.STUDY_ZONE).toString();
        JSONArray words = new JSONArray(), ids = new JSONArray(); JSONObject cached = new JSONObject();
        for (int i = 1; i <= 120; i++) {
            String id = String.format(java.util.Locale.ROOT, "csv:%04d", i);
            JSONObject word = new JSONObject().put("id", id).put("rank", i).put("word", "synthetic" + i)
                    .put("phonetic", "").put("meaning", "合成含义 " + i).put("generatedBy", "AI")
                    .put("examples", new JSONArray().put(new JSONObject().put("en", "A synthetic example.").put("zh", "合成例句。")))
                    .put("senses", new JSONArray().put("合成用法"))
                    .put("notes", new JSONObject().put("sections", new JSONArray().put(new JSONObject().put("title", "背景")
                            .put("body", "Synthetic archived learning content. ".repeat(32)))))
                    .put("questions", new JSONArray().put(new JSONObject().put("id", "recall-" + i).put("type", "recall")
                            .put("mode", "recall").put("prompt", "回忆 synthetic" + i).put("answer", "合成含义 " + i)
                            .put("explanation", "合成讲解。")));
            words.put(word); ids.put(id); cached.put(id, word);
        }
        JSONObject pack = new JSONObject().put("schemaVersion", 2).put("revision", 1).put("kind", "full")
                .put("packageId", "performance-" + today).put("date", today).put("timezone", "Asia/Shanghai")
                .put("contentMode", "ai-authored").put("catalogSha256", "a".repeat(64)).put("newWordLimit", 120)
                .put("requiredCapabilities", new JSONArray().put("plain-text").put("recall-v1").put("word-notes-v1"))
                .put("words", words).put("newWordIds", ids).put("reviewWordIds", new JSONArray()).put("withdrawnWordIds", new JSONArray());
        StudyEngine.validatePackage(pack);
        JSONObject packs = new JSONObject();
        for (int i = 0; i < 15; i++) {
            String day = LocalDate.parse(today).minusDays(i).toString();
            packs.put(day, new JSONObject(pack.toString()).put("date", day).put("packageId", "performance-" + day));
        }
        JSONArray events = new JSONArray();
        for (int i = 0; i < HISTORY; i++) events.put(new JSONObject().put("id", java.util.UUID.nameUUIDFromBytes(("history-" + i).getBytes(StandardCharsets.UTF_8)).toString()).put("deviceId", "old-device")
                .put("seq", i + 1).put("at", System.currentTimeMillis() - 100L * 86400000L)
                .put("wordId", "csv:" + (1000 + i)).put("packageId", "historical-pack").put("type", "answer")
                .put("mode", "forward").put("result", "correct").put("attempt", "primary").put("assisted", false).put("isReview", false));
        JSONObject state = new JSONObject().put("schemaVersion", 1).put("deviceId", "performance-device").put("seq", 0)
                .put("newLimitDefaultVersion", 2).put("events", events).put("words", cached).put("pack", pack).put("packages", packs)
                .put("settings", new JSONObject().put("owner", "synthetic").put("repo", "synthetic").put("branch", "main")
                        .put("limitMode", "package").put("newLimit", 30).put("practiceMode", "mixed"));
        assertEquals(120, StudyEngine.queue(pack, cached, events, System.currentTimeMillis(), -1).size());
        Files.write(new File(RuntimeEnvironment.getApplication().getFilesDir(), "learning-state.json").toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
    }
}
