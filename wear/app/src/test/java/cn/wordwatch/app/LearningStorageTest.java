package cn.wordwatch.app;

import cn.wordwatch.app.render.ShadowAtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35, shadows = ShadowAtomicFile.class)
public class LearningStorageTest {
    private File directory() {
        File dir = new File(RuntimeEnvironment.getApplication().getFilesDir(), UUID.randomUUID().toString());
        assertTrue(dir.mkdirs()); return dir;
    }
    private JSONObject fixture() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("seq", 3).put("deviceId", "existing-device")
                .put("events", new JSONArray().put(new JSONObject().put("id", "existing-answer")))
                .put("settings", new JSONObject().put("repo", "existing-repo").put("limitMode", "package"))
                .put("words", new JSONObject().put("csv:0001", new JSONObject().put("word", "preserved")))
                .put("session", new JSONObject().put("sessionId", "frozen-session").put("position", 2)
                        .put("pack", new JSONObject().put("revision", 4)).put("words", new JSONObject().put("frozen", "content")))
                .put("lastSyncedSeq", 2).put("acknowledgedEventIds", new JSONArray().put("other-ack"));
    }
    @Test public void legacyStateAndMidQuestionProgressSurviveUpgradeAndRestart() throws Exception {
        File dir = directory(); JSONObject legacy = fixture();
        Files.write(new File(dir, "learning-state.json").toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        LearningStorage storage = new LearningStorage(dir);
        assertJsonEquals(legacy, storage.read());
        JSONObject base = storage.checkpoint(storage.read());
        JSONObject next = new JSONObject(base.toString()); next.getJSONObject("session").put("revealed", true).put("position", 3);
        next.getJSONArray("events").put(new JSONObject().put("id", "new-answer")); next.put("seq", 4);
        storage.progress(next);
        JSONObject restored = new LearningStorage(dir).read();
        assertJsonEquals(next, restored);
        assertEquals("existing-repo", restored.getJSONObject("settings").getString("repo"));
        assertEquals(2, restored.getLong("lastSyncedSeq"));
        assertEquals("content", restored.getJSONObject("session").getJSONObject("words").getString("frozen"));
        String progress = new String(Files.readAllBytes(new File(dir, "learning-progress.json").toPath()), StandardCharsets.UTF_8);
        assertFalse("Compact writes must omit the frozen content", progress.contains("preserved") || progress.contains("content"));
    }
    @Test public void checkpointSupersedesOldOverlayWithoutRevertingAnswersOrSyncAcknowledgements() throws Exception {
        File dir = directory(); LearningStorage storage = new LearningStorage(dir);
        JSONObject base = storage.checkpoint(fixture());
        JSONObject next = new JSONObject(base.toString()); next.put("seq", 4); storage.progress(next);
        next.getJSONArray("events").put(new JSONObject().put("id", "synced-answer")); next.put("lastSyncedSeq", 4);
        JSONObject committed = storage.checkpoint(next);
        assertJsonEquals(committed, new LearningStorage(dir).read());
        next = new JSONObject(committed.toString()); next.getJSONObject("session").put("revealed", true);
        storage.progress(next);
        assertJsonEquals(next, new LearningStorage(dir).read());
    }
    @Test public void failedCheckpointKeepsPreviousOverlayAndFailedProgressKeepsLastCommittedAnswer() throws Exception {
        File dir = directory(); LearningStorage storage = new LearningStorage(dir);
        JSONObject base = storage.checkpoint(fixture());
        JSONObject saved = new JSONObject(base.toString()).put("seq", 4); storage.progress(saved);
        for (String file : new String[]{"learning-state.json", "learning-progress.json"}) {
            ShadowAtomicFile.beforeRename = target -> { if (target.getName().equals(file)) throw new IllegalStateException("synthetic disk failure"); };
            try {
                JSONObject next = new JSONObject(saved.toString()).put("seq", 5);
                try { if (file.equals("learning-state.json")) storage.checkpoint(next); else storage.progress(next); fail("Expected write failure"); }
                catch (IllegalStateException expected) { assertEquals("synthetic disk failure", expected.getMessage()); }
            } finally { ShadowAtomicFile.beforeRename = null; }
            assertJsonEquals(saved, new LearningStorage(dir).read());
        }
    }
    @Test public void corruptMatchingOverlayIsNotSilentlyDiscarded() throws Exception {
        File dir = directory(); LearningStorage storage = new LearningStorage(dir);
        JSONObject base = storage.checkpoint(fixture());
        JSONObject invalid = new JSONObject().put("schemaVersion", 1).put("baseGeneration", base.getString("storageGeneration"))
                .put("data", new JSONObject().put("session", new JSONObject().put("sessionId", "wrong-session")));
        Files.write(new File(dir, "learning-progress.json").toPath(), invalid.toString().getBytes(StandardCharsets.UTF_8));
        try { storage.read(); fail("Must preserve and report an inconsistent progress file"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("frozen session")); }
        assertTrue(new File(dir, "learning-progress.json").isFile());
    }
    private static void assertJsonEquals(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject); JSONObject a = (JSONObject)expected, b = (JSONObject)actual;
            assertEquals(a.length(), b.length());
            for (java.util.Iterator<String> keys = a.keys(); keys.hasNext();) { String key = keys.next(); assertTrue(b.has(key)); assertJsonEquals(a.get(key), b.get(key)); }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray); JSONArray a = (JSONArray)expected, b = (JSONArray)actual;
            assertEquals(a.length(), b.length()); for (int i = 0; i < a.length(); i++) assertJsonEquals(a.get(i), b.get(i));
        } else assertEquals(String.valueOf(expected), String.valueOf(actual));
    }

}
